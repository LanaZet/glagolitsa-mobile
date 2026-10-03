// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

const val ATTACHMENT_PADDING_MIN_BYTES = 64
const val ATTACHMENT_PADDING_MAX_BYTES = 512

/** Appends random padding + 4-byte big-endian length suffix to obscure ciphertext size. */
internal fun applyAttachmentPadding(
    ciphertext: ByteArray,
    nextInt: (Int) -> Int,
    nextBytes: (ByteArray) -> Unit,
): ByteArray {
    val padLen = ATTACHMENT_PADDING_MIN_BYTES +
        nextInt(ATTACHMENT_PADDING_MAX_BYTES - ATTACHMENT_PADDING_MIN_BYTES + 1)
    val padding = ByteArray(padLen).also(nextBytes)
    val lengthSuffix = byteArrayOf(
        (padLen ushr 24).toByte(),
        (padLen ushr 16).toByte(),
        (padLen ushr 8).toByte(),
        padLen.toByte(),
    )
    return ciphertext + padding + lengthSuffix
}

internal fun stripAttachmentPadding(padded: ByteArray): ByteArray? {
    if (padded.size < 4) return null
    val padLen = ((padded[padded.size - 4].toInt() and 0xFF) shl 24) or
        ((padded[padded.size - 3].toInt() and 0xFF) shl 16) or
        ((padded[padded.size - 2].toInt() and 0xFF) shl 8) or
        (padded[padded.size - 1].toInt() and 0xFF)
    val stripBytes = padLen + 4
    if (padLen < 0 || stripBytes > padded.size) return null
    return padded.copyOfRange(0, padded.size - stripBytes)
}