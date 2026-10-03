// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

/** Create media upload slot — open path only for channels. */
@Serializable
data class CreateMediaSlotRequest(
    val kind: String = MediaKind.PHOTO,
    val chat_id: String? = null,
    val mime_type: String? = null,
    val content_mode: String? = null, // "encrypted" | "open"
)

@Serializable
data class CreateMediaSlotResponse(
    val file_id: String,
    val expires_at: String? = null,
    val max_bytes: Long = 0,
    val kind: String = MediaKind.PHOTO,
    val content_mode: String? = null,
)

@Serializable
data class MediaFileInfo(
    val file_id: String,
    val kind: String = MediaKind.PHOTO,
    val mime_type: String = "application/octet-stream",
    val size_bytes: Long = 0,
    val size_bucket: Int = 0,
    val content_hash_encrypted: String? = null,
    val content_mode: String? = null,
    /** Server-generated open gallery thumb (small JPEG). */
    val thumb_file_id: String? = null,
    val cdn_url: String? = null,
    val expires_at: String? = null,
    val created_at: String? = null,
)

object MediaContentMode {
    const val ENCRYPTED = "encrypted"
    const val OPEN = "open"
}

object MediaKind {
    const val PHOTO = "photo"
    const val VIDEO = "video"
    const val DOCUMENT = "document"
    const val VOICE = "voice"
    const val AUDIO = "audio"
    const val THUMBNAIL = "thumbnail"
    const val STICKER = "sticker"
}
