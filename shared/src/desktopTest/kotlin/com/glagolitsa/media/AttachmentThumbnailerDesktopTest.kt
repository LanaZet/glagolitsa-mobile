// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentThumbnailerDesktopTest {
    @Test
    fun createThumbnailReturnsImageBytesForSupportedImage() {
        val thumbnail = AttachmentThumbnailer.createThumbnail(
            bytes = tinyPngBytes(),
            mimeType = "image/png",
            maxEdgePx = 64,
        )

        assertNotNull(thumbnail)
        assertTrue(thumbnail.isNotEmpty())
    }

    @Test
    fun createThumbnailReturnsNullForNonImageMime() {
        val thumbnail = AttachmentThumbnailer.createThumbnail(
            bytes = byteArrayOf(1, 2, 3, 4),
            mimeType = "application/octet-stream",
            maxEdgePx = 64,
        )

        assertNull(thumbnail)
    }

    @Test
    fun thumbnailTargetSizeKeepsAspectRatio() {
        assertEquals(64 to 32, thumbnailTargetSize(width = 200, height = 100, maxEdgePx = 64))
        assertEquals(32 to 64, thumbnailTargetSize(width = 100, height = 200, maxEdgePx = 64))
        assertEquals(20 to 10, thumbnailTargetSize(width = 20, height = 10, maxEdgePx = 64))
    }

    private fun tinyPngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
        0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78,
        0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00,
        0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00, 0x00, 0x00, 0x00,
        0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )
}
