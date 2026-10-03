// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

data class EncryptedFile(
    val fileKey: ByteArray,
    val ciphertext: ByteArray,
)

internal val STREAMING_ATTACHMENT_MAGIC = byteArrayOf(0x47, 0x4C, 0x46, 0x53, 0x31) // GLFS1

expect object FileAttachmentCrypto {
    fun encrypt(plaintext: ByteArray): EncryptedFile
    fun decrypt(fileKey: ByteArray, ciphertext: ByteArray): ByteArray?
}
