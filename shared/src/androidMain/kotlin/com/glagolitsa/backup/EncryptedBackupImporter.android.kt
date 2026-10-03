// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.backup

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.glagolitsa.db.AndroidDatabasePassphraseStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val DB_NAME = "glagolitsa.db"
private const val MEDIA_CACHE_DIR = "media-cache"
private const val SIGNAL_PREFS = "glagolitsa_crypto_signal_store"
private const val BACKUP_MAGIC = "GLBK1"
private const val PBKDF2_ITERATIONS = 120_000
private const val SALT_BYTES = 16
private const val IV_BYTES = 12
private const val KEY_BYTES = 32
private const val GCM_TAG_BITS = 128

actual object EncryptedBackupImporter {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    actual suspend fun import(bytes: ByteArray, passphrase: CharArray, recoveryKey: String) =
        withContext(Dispatchers.IO) {
            check(::appContext.isInitialized) { "EncryptedBackupImporter.init(context) required" }
            val archive = decryptBackup(bytes, passphrase, recoveryKey)
            restoreArchive(archive)
        }

    private fun decryptBackup(bytes: ByteArray, passphrase: CharArray, recoveryKey: String): ByteArray {
        val magic = bytes.copyOfRange(0, BACKUP_MAGIC.length).decodeToString()
        require(magic == BACKUP_MAGIC) { "Неверный формат резервной копии" }
        var offset = BACKUP_MAGIC.length
        val salt = bytes.copyOfRange(offset, offset + SALT_BYTES).also { offset += SALT_BYTES }
        val iv = bytes.copyOfRange(offset, offset + IV_BYTES).also { offset += IV_BYTES }
        val wrappedSize = ByteBuffer.wrap(bytes, offset, 4).int.also { offset += 4 }
        val recoveryWrapped = bytes.copyOfRange(offset, offset + wrappedSize).also { offset += wrappedSize }
        val ciphertext = bytes.copyOfRange(offset, bytes.size)
        val unwrappedRecoveryKey = unwrapWithPassphrase(recoveryWrapped, passphrase).decodeToString()
        require(unwrappedRecoveryKey == recoveryKey) { "Recovery key не совпадает с паролем" }
        val key = deriveKey(recoveryKey.toCharArray(), salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun restoreArchive(archiveBytes: ByteArray) {
        var dbBytes: ByteArray? = null
        var passphraseBytes: ByteArray? = null
        var signalXml: String? = null
        val mediaEntries = mutableListOf<MediaEntry>()
        ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val content = zip.readBytes()
                when (entry.name) {
                    "database/$DB_NAME" -> dbBytes = content
                    "database/sqlcipher_passphrase.bin" -> passphraseBytes = content
                    "signal_store/prefs.xml" -> signalXml = content.decodeToString()
                    else -> {
                        if (!entry.isDirectory && entry.name.startsWith("media_cache/")) {
                            val relative = entry.name.removePrefix("media_cache/")
                            if (isSafeRelativePath(relative)) {
                                mediaEntries += MediaEntry(relative, content)
                            }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        require(dbBytes != null) { "В копии нет локальной базы" }
        passphraseBytes?.let { AndroidDatabasePassphraseStore.restorePassphrase(appContext, it) }
        appContext.deleteDatabase(DB_NAME)
        appContext.getDatabasePath(DB_NAME).outputStream().use { output ->
            output.write(dbBytes!!)
        }
        restoreMediaCache(mediaEntries)
        signalXml?.let { restoreSignalPrefs(it) }
    }

    private fun restoreMediaCache(entries: List<MediaEntry>) {
        val root = File(appContext.filesDir, MEDIA_CACHE_DIR)
        if (root.exists()) {
            root.deleteRecursively()
        }
        root.mkdirs()
        val rootPath = root.canonicalPath
        entries.forEach { entry ->
            val target = File(root, entry.relativePath)
            val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return@forEach
            if (!targetPath.startsWith(rootPath + File.separator)) return@forEach
            target.parentFile?.mkdirs()
            target.outputStream().use { output -> output.write(entry.bytes) }
        }
    }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith("/") &&
            !path.startsWith("\\") &&
            path.split('/', '\\').none { it == ".." || it.isBlank() }

    private data class MediaEntry(
        val relativePath: String,
        val bytes: ByteArray,
    )

    private fun restoreSignalPrefs(xml: String) {
        val prefs = EncryptedSharedPreferences.create(
            appContext,
            SIGNAL_PREFS,
            MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        val editor = prefs.edit()
        prefs.all.keys.forEach(editor::remove)
        parseXmlStrings(xml).forEach { (key, value) ->
            editor.putString(key, value)
        }
        editor.apply()
    }

    private fun parseXmlStrings(xml: String): Map<String, String> {
        val pattern = Regex("""<string name="([^"]*)">([\s\S]*?)</string>""")
        return pattern.findAll(xml).associate { match ->
            unescapeXml(match.groupValues[1]) to unescapeXml(match.groupValues[2])
        }
    }

    private fun unwrapWithPassphrase(wrapped: ByteArray, passphrase: CharArray): ByteArray {
        val salt = wrapped.copyOfRange(0, SALT_BYTES)
        val iv = wrapped.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val ciphertext = wrapped.copyOfRange(SALT_BYTES + IV_BYTES, wrapped.size)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BYTES * 8)
        return factory.generateSecret(spec).encoded
    }

    private fun unescapeXml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&amp;", "&")
}
