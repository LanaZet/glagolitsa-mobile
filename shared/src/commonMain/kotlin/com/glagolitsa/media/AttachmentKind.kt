// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

enum class AttachmentKind(val wireName: String) {
    IMAGE("image"),
    VIDEO("video"),
    VOICE("voice"),
    AUDIO("audio"),
    DOCUMENT("document"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWireName(value: String?): AttachmentKind =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: UNKNOWN
    }
}

fun resolveAttachmentKind(
    mimeType: String?,
    fileName: String?,
    voice: Boolean = false,
): AttachmentKind {
    if (voice) return AttachmentKind.VOICE
    val normalizedMime = AttachmentMediaValidator.normalizeMimeType(mimeType, fileName)
    return when {
        normalizedMime.startsWith("image/") -> AttachmentKind.IMAGE
        normalizedMime.startsWith("video/") -> AttachmentKind.VIDEO
        normalizedMime.startsWith("audio/") -> AttachmentKind.AUDIO
        normalizedMime == "application/octet-stream" -> AttachmentKind.UNKNOWN
        else -> AttachmentKind.DOCUMENT
    }
}
