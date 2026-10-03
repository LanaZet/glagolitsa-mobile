// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatMediaOpenPolicyTest {
    @Test
    fun tapOpensViewerForImageAndVideoOnly() {
        assertTrue(ChatMediaOpenPolicy.opensInViewer(isImage = true, isVideo = false))
        assertTrue(ChatMediaOpenPolicy.opensInViewer(isImage = false, isVideo = true))
        assertFalse(ChatMediaOpenPolicy.opensInViewer(isImage = false, isVideo = false))
    }

    @Test
    fun gifDetectedFromMimeAndHeader() {
        assertTrue(ChatMediaOpenPolicy.isAnimatedGif("image/gif", ByteArray(0)))
        assertTrue(ChatMediaOpenPolicy.isAnimatedGif("image/gif; charset=binary", ByteArray(0)))
        val gif89a = "GIF89a".encodeToByteArray()
        assertTrue(ChatMediaOpenPolicy.isAnimatedGif("application/octet-stream", gif89a))
        val gif87a = "GIF87a".encodeToByteArray()
        assertTrue(ChatMediaOpenPolicy.isAnimatedGif(null, gif87a))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00)
        assertFalse(ChatMediaOpenPolicy.isAnimatedGif("image/jpeg", jpeg))
    }
}
