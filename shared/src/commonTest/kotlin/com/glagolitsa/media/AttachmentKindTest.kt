// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentKindTest {
    @Test
    fun resolvesExplicitVoiceBeforeMimeOrName() {
        assertEquals(
            AttachmentKind.VOICE,
            resolveAttachmentKind("audio/mp4", "recording.m4a", voice = true),
        )
    }

    @Test
    fun resolvesMediaKindsFromMimeType() {
        assertEquals(AttachmentKind.IMAGE, resolveAttachmentKind("image/jpeg", "photo.bin"))
        assertEquals(AttachmentKind.VIDEO, resolveAttachmentKind("video/mp4", "clip.bin"))
        assertEquals(AttachmentKind.AUDIO, resolveAttachmentKind("audio/mpeg", "track.bin"))
    }

    @Test
    fun resolvesMediaKindsFromFileNameWhenMimeIsGeneric() {
        assertEquals(AttachmentKind.IMAGE, resolveAttachmentKind("application/octet-stream", "photo.webp"))
        assertEquals(AttachmentKind.VIDEO, resolveAttachmentKind("application/octet-stream", "clip.mov"))
        assertEquals(AttachmentKind.AUDIO, resolveAttachmentKind("application/octet-stream", "voice.ogg"))
    }

    @Test
    fun resolvesOfficeAndPdfAsDocument() {
        assertEquals(AttachmentKind.DOCUMENT, resolveAttachmentKind(null, "contract.pdf"))
        assertEquals(AttachmentKind.DOCUMENT, resolveAttachmentKind(null, "report.docx"))
        assertEquals(AttachmentKind.DOCUMENT, resolveAttachmentKind("text/plain", "notes.txt"))
    }

    @Test
    fun keepsUnknownForGenericOrMissingMetadata() {
        assertEquals(AttachmentKind.UNKNOWN, resolveAttachmentKind("application/octet-stream", "blob.bin"))
        assertEquals(AttachmentKind.UNKNOWN, resolveAttachmentKind(null, null))
    }

    @Test
    fun parsesWireNameCaseInsensitively() {
        assertEquals(AttachmentKind.VIDEO, AttachmentKind.fromWireName(" Video "))
        assertEquals(AttachmentKind.UNKNOWN, AttachmentKind.fromWireName("unsupported"))
    }
}
