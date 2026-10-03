// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class FileAttachmentStreamingCryptoTest {
    @Test
    fun decrypt_readsStreamingEnvelope() {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val plaintext = ByteArray(4096) { (it % 251).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = STREAMING_ATTACHMENT_MAGIC + nonce + cipher.doFinal(plaintext)

        val decrypted = FileAttachmentCrypto.decrypt(key, ciphertext)

        assertNotNull(decrypted)
        assertContentEquals(plaintext, decrypted)
    }
}
