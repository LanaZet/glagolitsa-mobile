// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val KEY_BYTES = 32
private const val NONCE_BYTES = 12
private const val GCM_TAG_BITS = 128

actual object FileAttachmentCrypto {
    private val secureRandom = SecureRandom()

    actual fun encrypt(plaintext: ByteArray): EncryptedFile {
        val fileKey = ByteArray(KEY_BYTES).also(secureRandom::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val encrypted = cipher.doFinal(plaintext)
        val core = nonce + encrypted
        return EncryptedFile(
            fileKey = fileKey,
            ciphertext = applyAttachmentPadding(
                core,
                nextInt = { bound -> secureRandom.nextInt(bound) },
                nextBytes = secureRandom::nextBytes,
            ),
        )
    }

    actual fun decrypt(fileKey: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (ciphertext.startsWith(STREAMING_ATTACHMENT_MAGIC)) {
            return decryptStreamingEnvelope(fileKey, ciphertext)
        }
        val stripped = stripAttachmentPadding(ciphertext) ?: return null
        if (stripped.size <= NONCE_BYTES) return null
        return runCatching {
            val nonce = stripped.copyOfRange(0, NONCE_BYTES)
            val payload = stripped.copyOfRange(NONCE_BYTES, stripped.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(payload)
        }.getOrNull()
    }

    private fun decryptStreamingEnvelope(fileKey: ByteArray, ciphertext: ByteArray): ByteArray? {
        val nonceStart = STREAMING_ATTACHMENT_MAGIC.size
        val payloadStart = nonceStart + NONCE_BYTES
        if (ciphertext.size <= payloadStart) return null
        return runCatching {
            val nonce = ciphertext.copyOfRange(nonceStart, payloadStart)
            val payload = ciphertext.copyOfRange(payloadStart, ciphertext.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(payload)
        }.getOrNull()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { index -> this[index] == prefix[index] }
    }
}
