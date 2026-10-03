// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.repository.AttachmentPreview

expect object AttachmentDownloadSaver {
    val isSupported: Boolean

    suspend fun saveImage(preview: AttachmentPreview): String

    suspend fun save(preview: AttachmentPreview): String
}

internal object AttachmentDownloadNames {
    fun displayName(fileName: String?, mimeType: String?, fallbackMillis: Long): String {
        val baseName = fileName
            ?.substringBeforeLast('.')
            ?.trim()
            ?.map { char ->
                when {
                    char.code < 32 || char == '/' || char == '\\' -> '_'
                    else -> char
                }
            }
            ?.joinToString("")
            ?.trim('.', ' ', '_')
            ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: "glagolitsa-$fallbackMillis"
        val fromName = fileName?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it.length <= 8 }
        val mimeExt = extensionForMime(mimeType)
        val ext = if (mimeExt == "bin") fromName ?: mimeExt else mimeExt
        return "$baseName.$ext"
    }

    private fun extensionForMime(mimeType: String?): String =
        when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "application/pdf" -> "pdf"
            "application/zip", "application/x-zip-compressed" -> "zip"
            "application/msword" -> "doc"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            "text/plain" -> "txt"
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "audio/aac" -> "aac"
            "audio/wav" -> "wav"
            "audio/ogg" -> "ogg"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/webm" -> "webm"
            else -> "bin"
        }
}
