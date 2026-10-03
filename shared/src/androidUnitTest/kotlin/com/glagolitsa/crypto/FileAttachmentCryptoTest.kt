// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class FileAttachmentCryptoTest {
    @Test
    fun encryptDecrypt_roundTrip() {
        val plaintext = "secret attachment payload".encodeToByteArray()
        val encrypted = FileAttachmentCrypto.encrypt(plaintext)
        val decrypted = FileAttachmentCrypto.decrypt(encrypted.fileKey, encrypted.ciphertext)
        assertNotNull(decrypted)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun encrypt_addsRandomPadding() {
        val plaintext = ByteArray(128) { it.toByte() }
        val first = FileAttachmentCrypto.encrypt(plaintext)
        val second = FileAttachmentCrypto.encrypt(plaintext)
        assertNotNull(FileAttachmentCrypto.decrypt(first.fileKey, first.ciphertext))
        assertNotNull(FileAttachmentCrypto.decrypt(second.fileKey, second.ciphertext))
    }
}