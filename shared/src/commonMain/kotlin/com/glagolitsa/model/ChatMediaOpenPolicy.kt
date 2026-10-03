// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Tap vs menu for chat attachments.
 * Photo / GIF / video open the viewer immediately; long-press keeps the message menu.
 */
object ChatMediaOpenPolicy {
    fun opensInViewer(isImage: Boolean, isVideo: Boolean): Boolean = isImage || isVideo

    fun isAnimatedGif(
        mimeType: String?,
        bytes: ByteArray,
        thumbnailBytes: ByteArray? = null,
    ): Boolean {
        val mime = mimeType?.trim()?.lowercase()?.substringBefore(';')
        if (mime == "image/gif") return true
        val sniff = when {
            bytes.size >= GIF_SIGNATURE_SIZE -> bytes
            thumbnailBytes != null && thumbnailBytes.size >= GIF_SIGNATURE_SIZE -> thumbnailBytes
            else -> return false
        }
        return hasGifHeader(sniff)
    }

    private const val GIF_SIGNATURE_SIZE = 6

    private fun hasGifHeader(bytes: ByteArray): Boolean {
        if (bytes.size < GIF_SIGNATURE_SIZE) return false
        if (bytes[0] != 'G'.code.toByte() ||
            bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() ||
            bytes[3] != '8'.code.toByte() ||
            bytes[5] != 'a'.code.toByte()
        ) {
            return false
        }
        val version = bytes[4]
        return version == '7'.code.toByte() || version == '9'.code.toByte()
    }
}
