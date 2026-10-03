// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal object DesktopCredentialCipher {
    private const val KEY_FILE = ".session_key"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    @OptIn(ExperimentalEncodingApi::class)
    fun encrypt(plain: String, baseDir: File): String {
        val key = loadOrCreateKey(baseDir)
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plain.encodeToByteArray())
        return Base64.encode(iv + encrypted)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decrypt(encoded: String, baseDir: File): String {
        val key = loadOrCreateKey(baseDir)
        val bytes = Base64.decode(encoded)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val payload = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(payload))
    }

    private fun loadOrCreateKey(baseDir: File): SecretKey {
        val keyFile = baseDir.resolve(KEY_FILE)
        if (keyFile.exists()) {
            val raw = keyFile.readBytes()
            if (raw.size == 32) return SecretKeySpec(raw, "AES")
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        baseDir.mkdirs()
        keyFile.outputStream().use { it.write(raw) }
        keyFile.setReadable(true, true)
        keyFile.setWritable(true, true)
        return SecretKeySpec(raw, "AES")
    }
}