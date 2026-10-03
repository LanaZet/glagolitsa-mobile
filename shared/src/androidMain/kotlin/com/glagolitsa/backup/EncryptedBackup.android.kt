// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.backup

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.glagolitsa.currentIsoTimestamp
import com.glagolitsa.crypto.encodeHex
import com.glagolitsa.db.AndroidDatabasePassphraseStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

actual object EncryptedBackupExporter {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    actual suspend fun export(passphrase: CharArray): EncryptedBackupResult = withContext(Dispatchers.IO) {
        check(::appContext.isInitialized) { "EncryptedBackupExporter.init(context) required" }
        val recoveryKey = generateRecoveryKey()
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(recoveryKey.toCharArray(), salt)
        val payload = buildBackupArchive()
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(payload)
        val recoveryWrapped = wrapWithPassphrase(recoveryKey.toByteArray(), passphrase)
        val bytes = ByteArrayOutputStream().use { out ->
            out.write(BACKUP_MAGIC.encodeToByteArray())
            out.write(salt)
            out.write(iv)
            out.write(ByteBuffer.allocate(4).putInt(recoveryWrapped.size).array())
            out.write(recoveryWrapped)
            out.write(ciphertext)
            out.toByteArray()
        }
        EncryptedBackupResult(
            bytes = bytes,
            recoveryKey = recoveryKey,
            createdAt = currentIsoTimestamp(),
        )
    }

    private fun buildBackupArchive(): ByteArray {
        val dbFile = appContext.getDatabasePath(DB_NAME)
        val signalPrefs = EncryptedSharedPreferences.create(
            appContext,
            SIGNAL_PREFS,
            MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        val sqlCipherPassphrase = AndroidDatabasePassphraseStore.exportPassphrase(appContext)
        return ByteArrayOutputStream().use { raw ->
            ZipOutputStream(raw).use { zip ->
                zip.putNextEntry(ZipEntry("database/sqlcipher_passphrase.bin"))
                zip.write(sqlCipherPassphrase)
                zip.closeEntry()
                if (dbFile.exists()) {
                    zip.putNextEntry(ZipEntry("database/$DB_NAME"))
                    dbFile.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
                appendMediaCache(zip, File(appContext.filesDir, MEDIA_CACHE_DIR))
                zip.putNextEntry(ZipEntry("signal_store/prefs.xml"))
                val xml = buildString {
                    append("<map>")
                    signalPrefs.all.forEach { (key, value) ->
                        if (value is String) {
                            append("<string name=\"")
                            append(escapeXml(key))
                            append("\">")
                            append(escapeXml(value))
                            append("</string>")
                        }
                    }
                    append("</map>")
                }
                zip.write(xml.encodeToByteArray())
                zip.closeEntry()
            }
            raw.toByteArray()
        }
    }

    private fun appendMediaCache(zip: ZipOutputStream, root: File) {
        if (!root.isDirectory) return
        root.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative.isBlank() || relative.contains("..")) return@forEach
                zip.putNextEntry(ZipEntry("media_cache/$relative"))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
    }

    private fun wrapWithPassphrase(secret: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(secret)
        return salt + iv + ciphertext
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BYTES * 8)
        return factory.generateSecret(spec).encoded
    }

    private fun generateRecoveryKey(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return encodeHex(bytes).chunked(6).joinToString("-")
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
