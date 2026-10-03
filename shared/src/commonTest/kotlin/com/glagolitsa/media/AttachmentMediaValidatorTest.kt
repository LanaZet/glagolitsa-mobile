// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AttachmentMediaValidatorTest {
    @Test
    fun validateOutgoingRejectsUnsafeInput() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentMediaValidator.validateOutgoing(ByteArray(0), "photo.jpg", "image/jpeg")
        }
        assertFailsWith<IllegalArgumentException> {
            AttachmentMediaValidator.validateOutgoing(byteArrayOf(1), "../photo.jpg", "image/jpeg")
        }
    }

    @Test
    fun canPreviewImageRequiresMimeAndSignatureMatch() {
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

        assertTrue(AttachmentMediaValidator.canPreviewImage(png, "image/png"))
        assertFalse(AttachmentMediaValidator.canPreviewImage("not a jpeg".encodeToByteArray(), "image/jpeg"))
        assertFalse(AttachmentMediaValidator.canPreviewImage(png, "image/jpeg"))
    }

    @Test
    fun normalizeMimeTypeFallsBackToSafeDefault() {
        assertEquals("image/jpeg", AttachmentMediaValidator.normalizeMimeType(null, "photo.jpg"))
        assertEquals("application/octet-stream", AttachmentMediaValidator.normalizeMimeType(null, "blob.unknown"))
        assertEquals("image/png", AttachmentMediaValidator.normalizeMimeType(" image/png; charset=utf-8 ", "photo.bin"))
    }

    @Test
    fun validatePlaintextForPreviewInfersImageMimeFromContent() {
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
        val validated = AttachmentMediaValidator.validatePlaintextForPreview(
            bytes = png,
            fileName = null,
            mimeType = "application/octet-stream",
        )

        assertNotNull(validated)
        assertEquals("image/png", validated.mimeType)
        assertTrue(validated.canPreviewImage)
    }

    @Test
    fun mp4BrandIsNotSniffedAsHeic() {
        val mp4Like = byteArrayOf(
            0x00,
            0x00,
            0x00,
            0x18,
            'f'.code.toByte(),
            't'.code.toByte(),
            'y'.code.toByte(),
            'p'.code.toByte(),
            'm'.code.toByte(),
            'p'.code.toByte(),
            '4'.code.toByte(),
            '2'.code.toByte(),
        )

        assertEquals(null, AttachmentMediaValidator.sniffSupportedImageMime(mp4Like))
        assertFalse(AttachmentMediaValidator.canPreviewImage(mp4Like, "image/heic"))
    }
}
