// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.media.AttachmentKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentPreviewStoreTest {
    @Test
    fun cacheRejectsSpoofedImageBytes() {
        val store = AttachmentPreviewStore()

        store.cache(
            messageId = "msg-1",
            bytes = "not a jpeg".encodeToByteArray(),
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
        )

        assertNull(store.state.value["msg-1"])
    }

    @Test
    fun cacheAcceptsImageWithMatchingSignature() {
        val store = AttachmentPreviewStore()
        val png = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
            0x00,
        )

        store.cache(
            messageId = "msg-2",
            bytes = png,
            fileName = "photo.png",
            mimeType = "image/png",
        )

        assertEquals("image/png", store.state.value["msg-2"]?.mimeType)
    }

    @Test
    fun cacheInfersImageMimeFromBytesWhenMetadataIsLegacy() {
        val store = AttachmentPreviewStore()
        val png = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
            0x00,
        )

        store.cache(
            messageId = "msg-legacy",
            bytes = png,
            fileName = null,
            mimeType = "application/octet-stream",
        )

        assertEquals("image/png", store.state.value["msg-legacy"]?.mimeType)
    }

    @Test
    fun cacheKeepsVideoAsTypedPreviewWithPoster() {
        val store = AttachmentPreviewStore()
        val poster = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
            0x00,
        )

        store.cache(
            messageId = "msg-video",
            bytes = ByteArray(0),
            fileName = "clip.mp4",
            mimeType = "video/mp4",
            kind = AttachmentKind.VIDEO,
            thumbnailBytes = poster,
            durationMs = 12_000,
        )

        val preview = store.state.value["msg-video"]
        assertEquals(AttachmentKind.VIDEO, preview?.kind)
        assertEquals("video/mp4", preview?.mimeType)
        assertEquals(12_000, preview?.durationMs)
        assertEquals(poster.contentHashCode(), preview?.displayBytes?.contentHashCode())
        assertTrue(preview?.bytes?.isEmpty() == true)
    }

    @Test
    fun cacheKeepsVideoBytesForPlayback() {
        val store = AttachmentPreviewStore()
        val video = byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70)
        val poster = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
            0x00,
        )

        store.cache(
            messageId = "msg-video-bytes",
            bytes = video,
            fileName = "clip.mp4",
            mimeType = "video/mp4",
            kind = AttachmentKind.VIDEO,
            thumbnailBytes = poster,
            durationMs = 4_000,
        )

        val preview = store.state.value["msg-video-bytes"]
        assertEquals(AttachmentKind.VIDEO, preview?.kind)
        assertContentEquals(video, preview?.bytes)
        assertContentEquals(poster, preview?.thumbnailBytes)
        assertFalse(preview?.isAnimatedGif == true)
    }

    @Test
    fun cacheMarksGifAsAnimatedImage() {
        val store = AttachmentPreviewStore()
        val gif = "GIF89a".encodeToByteArray() + ByteArray(8)

        store.cache(
            messageId = "msg-gif",
            bytes = gif,
            fileName = "loop.gif",
            mimeType = "image/gif",
        )

        val preview = store.state.value["msg-gif"]
        assertEquals(AttachmentKind.IMAGE, preview?.kind)
        assertEquals("image/gif", preview?.mimeType)
        assertTrue(preview?.isAnimatedGif == true)
    }

    @Test
    fun cacheKeepsAudioAsTypedFilePreview() {
        val store = AttachmentPreviewStore()
        val bytes = "audio-bytes".encodeToByteArray()

        store.cache(
            messageId = "msg-audio",
            bytes = bytes,
            fileName = "voice.m4a",
            mimeType = "audio/mp4",
        )

        assertEquals(AttachmentKind.AUDIO, store.state.value["msg-audio"]?.kind)
        assertEquals(bytes.contentHashCode(), store.state.value["msg-audio"]?.bytes?.contentHashCode())
    }
}
