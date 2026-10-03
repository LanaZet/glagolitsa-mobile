// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.history

import com.glagolitsa.crypto.iosPbkdf2HmacSha256
import com.glagolitsa.crypto.iosRandomBytes
import com.glagolitsa.crypto.requireAesGcm

private const val MAGIC = "GLSBR1"
private const val SALT_BYTES = 16
private const val IV_BYTES = 12
private const val KEY_BYTES = 32
private const val PBKDF2_ITERATIONS = 210_000

actual object SecureHistoryCrypto {
    actual fun generateRecoveryKey(): String {
        val raw = iosRandomBytes(32)
        return raw.joinToString("") { b -> ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }

    actual fun isSecureBackupBlob(sealed: ByteArray): Boolean =
        sealed.size >= MAGIC.length &&
            sealed.copyOfRange(0, MAGIC.length).contentEquals(MAGIC.encodeToByteArray())

    actual fun seal(recoveryKey: String, plaintext: ByteArray): ByteArray {
        val normalized = normalizeRecoveryKey(recoveryKey)
        require(normalized.length == 64) { "recovery key must be 64 hex chars" }
        val salt = iosRandomBytes(SALT_BYTES)
        val iv = iosRandomBytes(IV_BYTES)
        val key = deriveKey(normalized, salt)
        val ciphertext = requireAesGcm().encrypt(key, iv, plaintext)
            ?: error("iOS AES-GCM encryption failed")
        return MAGIC.encodeToByteArray() + salt + iv + ciphertext
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
            requireAesGcm().decrypt(key, iv, ciphertext)
        }.getOrNull()
    }

    private fun normalizeRecoveryKey(raw: String): String =
        raw.trim().lowercase().replace(" ", "").replace("-", "")

    private fun deriveKey(recoveryKeyHex: String, salt: ByteArray): ByteArray =
        iosPbkdf2HmacSha256(
            password = recoveryKeyHex.encodeToByteArray(),
            salt = salt,
            iterations = PBKDF2_ITERATIONS,
            keyBytes = KEY_BYTES,
        )
}
