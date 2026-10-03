// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.history

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val MAGIC = "GLSBR1"
private const val SALT_BYTES = 16
private const val IV_BYTES = 12
private const val KEY_BYTES = 32
private const val GCM_TAG_BITS = 128
private const val PBKDF2_ITERATIONS = 210_000

actual object SecureHistoryCrypto {
    private val random = SecureRandom()

    actual fun generateRecoveryKey(): String {
        val raw = ByteArray(32).also(random::nextBytes)
        return raw.joinToString("") { b -> "%02x".format(b) }
    }

    actual fun isSecureBackupBlob(sealed: ByteArray): Boolean =
        sealed.size >= MAGIC.length &&
            sealed.copyOfRange(0, MAGIC.length).contentEquals(MAGIC.encodeToByteArray())

    actual fun seal(recoveryKey: String, plaintext: ByteArray): ByteArray {
        val normalized = normalizeRecoveryKey(recoveryKey)
        require(normalized.length == 64) { "recovery key must be 64 hex chars" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(normalized, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(MAGIC.length + SALT_BYTES + IV_BYTES + ciphertext.size).apply {
            put(MAGIC.encodeToByteArray())
            put(salt)
            put(iv)
            put(ciphertext)
        }.array()
    }

    actual fun open(recoveryKey: String, sealed: ByteArray): ByteArray? {
        if (!isSecureBackupBlob(sealed)) return null
        if (sealed.size < MAGIC.length + SALT_BYTES + IV_BYTES + 16) return null
        var offset = MAGIC.length
        val salt = sealed.copyOfRange(offset, offset + SALT_BYTES).also { offset += SALT_BYTES }
        val iv = sealed.copyOfRange(offset, offset + IV_BYTES).also { offset += IV_BYTES }
        val ciphertext = sealed.copyOfRange(offset, sealed.size)
        val normalized = normalizeRecoveryKey(recoveryKey)
        if (normalized.length != 64) return null
        return runCatching {
            val key = deriveKey(normalized, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }

    private fun normalizeRecoveryKey(raw: String): String =
        raw.trim().lowercase().replace(" ", "").replace("-", "")

    private fun deriveKey(recoveryKeyHex: String, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(recoveryKeyHex.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BYTES * 8)
        return factory.generateSecret(spec).encoded
    }
}
