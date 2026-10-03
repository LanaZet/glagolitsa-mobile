// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One open-media image on a channel post. */
data class ChannelMediaRef(
    val fileId: String,
    val thumbFileId: String? = null,
    val isCover: Boolean = false,
) {
    /** Prefer server thumb for grids; fall back to full file. */
    fun displayFileId(preferThumb: Boolean): String =
        if (preferThumb) thumbFileId?.takeIf { it.isNotBlank() } ?: fileId else fileId
}

/** Parse metadata.media[] entries (file_id + optional thumb_file_id). */
fun Message.channelMediaRefs(): List<ChannelMediaRef> {
    val meta = metadata ?: return emptyList()
    val mediaEl = meta["media"] ?: return emptyList()
    val arr = mediaEl as? JsonArray ?: runCatching { mediaEl.jsonArray }.getOrNull() ?: return emptyList()
    return arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
        fun field(name: String): String? {
            val v = obj[name] ?: return null
            return (v as? JsonPrimitive)?.contentOrNull
                ?: runCatching { v.jsonPrimitive.content }.getOrNull()
        }
        val id = field("file_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val thumb = field("thumb_file_id")?.takeIf { it.isNotBlank() }
        val cover = field("cover")?.let { it == "true" } ?: false
        ChannelMediaRef(fileId = id, thumbFileId = thumb, isCover = cover)
    }
}

fun Message.channelMediaFileIds(): List<String> = channelMediaRefs().map { it.fileId }

fun Message.hasChannelMedia(): Boolean = channelMediaRefs().isNotEmpty()

/** Non-blank text (ignores placeholder single space used historically). */
fun Message.channelPostText(): String =
    body.trim().let { if (it.isEmpty() || it == " ") "" else it }

/**
 * Visible feed/gallery item: has real text and/or media.
 * Filters out empty shells that only show reaction chrome.
 */
fun Message.isMeaningfulChannelPost(): Boolean =
    channelPostText().isNotEmpty() || hasChannelMedia()

/** Cover image for gallery cell (first media, prefer thumb). */
fun Message.channelCoverRef(): ChannelMediaRef? {
    val refs = channelMediaRefs()
    return refs.firstOrNull { it.isCover } ?: refs.firstOrNull()
}
