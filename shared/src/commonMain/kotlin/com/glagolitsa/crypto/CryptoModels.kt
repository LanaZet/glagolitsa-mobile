// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

data class EncryptedPayload(
    val envelopeType: Int,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as EncryptedPayload
        return envelopeType == other.envelopeType && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = envelopeType
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}

data class SafetyNumberInfo(
    val displayText: String,
    val qrPayload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as SafetyNumberInfo
        return displayText == other.displayText && qrPayload.contentEquals(other.qrPayload)
    }

    override fun hashCode(): Int {
        var result = displayText.hashCode()
        result = 31 * result + qrPayload.contentHashCode()
        return result
    }
}