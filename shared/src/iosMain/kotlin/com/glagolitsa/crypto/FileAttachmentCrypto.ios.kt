// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

private const val KEY_BYTES = 32
private const val NONCE_BYTES = 12
private const val GCM_TAG_BYTES = 16

actual object FileAttachmentCrypto {
    actual fun encrypt(plaintext: ByteArray): EncryptedFile {
        val fileKey = iosRandomBytes(KEY_BYTES)
        val nonce = iosRandomBytes(NONCE_BYTES)
        val encrypted = requireAesGcm().encrypt(fileKey, nonce, plaintext)
            ?: error("iOS AES-GCM encryption failed")
        val core = nonce + encrypted
        return EncryptedFile(
            fileKey = fileKey,
            ciphertext = applyAttachmentPadding(
                core,
                nextInt = ::iosRandomInt,
                nextBytes = { dest -> iosRandomBytes(dest.size).copyInto(dest) },
            ),
        )
    }

    actual fun decrypt(fileKey: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (ciphertext.startsWithPrefix(STREAMING_ATTACHMENT_MAGIC)) {
            return decryptStreamingEnvelope(fileKey, ciphertext)
        }
        val stripped = stripAttachmentPadding(ciphertext) ?: return null
        if (stripped.size <= NONCE_BYTES + GCM_TAG_BYTES) return null
        return runCatching {
            val nonce = stripped.copyOfRange(0, NONCE_BYTES)
            val payload = stripped.copyOfRange(NONCE_BYTES, stripped.size)
            requireAesGcm().decrypt(fileKey, nonce, payload)
        }.getOrNull()
    }

    private fun decryptStreamingEnvelope(fileKey: ByteArray, ciphertext: ByteArray): ByteArray? {
        val nonceStart = STREAMING_ATTACHMENT_MAGIC.size
        val payloadStart = nonceStart + NONCE_BYTES
        if (ciphertext.size <= payloadStart + GCM_TAG_BYTES) return null
        return runCatching {
            val nonce = ciphertext.copyOfRange(nonceStart, payloadStart)
            val payload = ciphertext.copyOfRange(payloadStart, ciphertext.size)
            requireAesGcm().decrypt(fileKey, nonce, payload)
        }.getOrNull()
    }
}

internal fun encryptAttachmentStreaming(plaintext: ByteArray): EncryptedFile {
    val fileKey = iosRandomBytes(KEY_BYTES)
    val nonce = iosRandomBytes(NONCE_BYTES)
    val encrypted = requireAesGcm().encrypt(fileKey, nonce, plaintext)
        ?: error("iOS AES-GCM encryption failed")
    return EncryptedFile(
        fileKey = fileKey,
        ciphertext = STREAMING_ATTACHMENT_MAGIC + nonce + encrypted,
    )
}

private fun ByteArray.startsWithPrefix(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    return prefix.indices.all { index -> this[index] == prefix[index] }
}
