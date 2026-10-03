// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal fun iosRandomBytes(size: Int): ByteArray {
    if (size <= 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pin ->
        val status = SecRandomCopyBytes(kSecRandomDefault, size.convert(), pin.addressOf(0))
        check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosPbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
    require(iterations > 0) { "iterations must be positive" }
    require(keyBytes > 0) { "keyBytes must be positive" }
    val derived = ByteArray(keyBytes)
    val passwordString = password.decodeToString()
    val status = salt.usePinned { saltPin ->
        derived.usePinned { derivedPin ->
            CCKeyDerivationPBKDF(
                kCCPBKDF2,
                passwordString,
                password.size.convert(),
                saltPin.addressOf(0).reinterpret(),
                salt.size.convert(),
                kCCPRFHmacAlgSHA256,
                iterations.toUInt(),
                derivedPin.addressOf(0).reinterpret(),
                keyBytes.convert(),
            )
        }
    }
    check(status == kCCSuccess) { "PBKDF2 failed: $status" }
    return derived
}

internal fun iosRandomInt(bound: Int): Int {
    require(bound > 0)
    val sampleRange = 1L shl 32
    val unbiasedLimit = sampleRange - sampleRange % bound
    while (true) {
        val raw = iosRandomBytes(4)
        val value = ((raw[0].toLong() and 0xFF) shl 24) or
            ((raw[1].toLong() and 0xFF) shl 16) or
            ((raw[2].toLong() and 0xFF) shl 8) or
            (raw[3].toLong() and 0xFF)
        if (value < unbiasedLimit) return (value % bound).toInt()
    }
}
