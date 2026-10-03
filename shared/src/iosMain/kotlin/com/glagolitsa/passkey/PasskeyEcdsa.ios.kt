// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.passkey

import com.glagolitsa.platform.toByteArray
import com.glagolitsa.platform.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFErrorRefVar
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSCopyingProtocol
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyCreateWithData
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecRandomDefault
import platform.Security.SecRandomCopyBytes

@OptIn(ExperimentalForeignApi::class)
internal actual object PasskeyEcdsa {
    actual fun generate(): PasskeyEcdsaKey {
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val attributes = secAttrs(
                kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeySizeInBits to 256,
            )
            val privateKey = SecKeyCreateRandomKey(attributes, error.ptr)
                ?: error("could not generate passkey")
            val exported = SecKeyCopyExternalRepresentation(privateKey, error.ptr)
                ?: error("could not export passkey")
            val data = CFBridgingRelease(exported) as NSData
            val bytes = data.toByteArray()
            require(bytes.size >= 97 && bytes[0] == 0x04.toByte()) { "unexpected EC key encoding" }
            return PasskeyEcdsaKey(
                x = bytes.copyOfRange(1, 33),
                y = bytes.copyOfRange(33, 65),
                d = bytes.copyOfRange(65, 97),
            )
        }
    }

    actual fun sign(key: PasskeyEcdsaKey, data: ByteArray): ByteArray {
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val raw = byteArrayOf(0x04) + pad32(key.x) + pad32(key.y) + pad32(key.d)
            val attributes = secAttrs(
                kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeyClass to kSecAttrKeyClassPrivate,
                kSecAttrKeySizeInBits to 256,
            )
            val privateKey = SecKeyCreateWithData(
                raw.toNSData() as CFDataRef,
                attributes,
                error.ptr,
            ) ?: error("could not import passkey")
            val signature = SecKeyCreateSignature(
                privateKey,
                kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                data.toNSData() as CFDataRef,
                error.ptr,
            ) ?: error("could not sign passkey assertion")
            return (CFBridgingRelease(signature) as NSData).toByteArray()
        }
    }

    actual fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        if (bytes.isEmpty()) return bytes
        bytes.usePinned { pinned ->
            val status = SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0))
            require(status == 0) { "SecRandomCopyBytes failed: $status" }
        }
        return bytes
    }

    private fun pad32(raw: ByteArray): ByteArray {
        if (raw.size == 32) return raw
        if (raw.size > 32) return raw.copyOfRange(raw.size - 32, raw.size)
        return ByteArray(32 - raw.size) + raw
    }

    private fun secAttrs(vararg pairs: Pair<Any?, Any?>): CFDictionaryRef? {
        val dictionary = NSMutableDictionary()
        for ((key, value) in pairs) {
            if (key != null && value != null) {
                dictionary.setObject(value, forKey = key as NSCopyingProtocol)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return CFBridgingRetain(dictionary) as CFDictionaryRef?
    }
}
