// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.glagolitsa.crypto.decodeHex
import com.glagolitsa.crypto.encodeHex
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Пароль SQLCipher: случайные байты, зашифрованные ключом Android Keystore (AES-GCM).
 */
internal object AndroidDatabasePassphraseStore {
    private const val KEY_ALIAS = "glagolitsa_sqlcipher_passphrase"
    private const val PREFS_NAME = "glagolitsa_db_key_meta"
    private const val PREF_ENCRYPTED_PASSPHRASE = "encrypted_passphrase"
    private const val PREF_IV = "iv"
    private const val KEY_SQLCIPHER_ENABLED = "sqlcipher_enabled"
    private const val GCM_TAG_BITS = 128

    fun exportPassphrase(context: Context): ByteArray = getOrCreate(context)

    fun restorePassphrase(context: Context, passphrase: ByteArray) {
        val sealed = encryptPassphrase(passphrase)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_ENCRYPTED_PASSPHRASE, encodeHex(sealed.ciphertext))
            .putString(PREF_IV, encodeHex(sealed.iv))
            .putBoolean(KEY_SQLCIPHER_ENABLED, true)
            .apply()
    }

    fun getOrCreate(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(PREF_ENCRYPTED_PASSPHRASE, null)
        val iv = prefs.getString(PREF_IV, null)
        if (encrypted != null && iv != null) {
            return decryptPassphrase(
                ciphertext = decodeHex(encrypted),
                iv = decodeHex(iv),
            )
        }

        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sealed = encryptPassphrase(passphrase)
        prefs.edit()
            .putString(PREF_ENCRYPTED_PASSPHRASE, encodeHex(sealed.ciphertext))
            .putString(PREF_IV, encodeHex(sealed.iv))
            .apply()
        return passphrase
    }

    private fun encryptPassphrase(passphrase: ByteArray): SealedBlob {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        val ciphertext = cipher.doFinal(passphrase)
        return SealedBlob(ciphertext = ciphertext, iv = cipher.iv)
    }

    private fun decryptPassphrase(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private data class SealedBlob(
        val ciphertext: ByteArray,
        val iv: ByteArray,
    )

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
}