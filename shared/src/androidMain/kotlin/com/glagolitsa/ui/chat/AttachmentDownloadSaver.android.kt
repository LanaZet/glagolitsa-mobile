// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.glagolitsa.platform.AppLifecycle
import com.glagolitsa.repository.AttachmentPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual object AttachmentDownloadSaver {
    actual val isSupported: Boolean = true

    actual suspend fun saveImage(preview: AttachmentPreview): String = save(preview)

    actual suspend fun save(preview: AttachmentPreview): String = withContext(Dispatchers.IO) {
        val context = AppLifecycle.applicationContextOrNull() ?: error("Application context unavailable")
        val resolver = context.contentResolver
        val mimeType = preview.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val displayName = AttachmentDownloadNames.displayName(
            fileName = preview.fileName,
            mimeType = mimeType,
            fallbackMillis = System.currentTimeMillis(),
        )
        val collection = when {
            mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
        val relativePath = when {
            mimeType.startsWith("image/") -> "${Environment.DIRECTORY_PICTURES}/Glagolitsa"
            mimeType.startsWith("video/") -> "${Environment.DIRECTORY_MOVIES}/Glagolitsa"
            mimeType.startsWith("audio/") -> "${Environment.DIRECTORY_MUSIC}/Glagolitsa"
            else -> "${Environment.DIRECTORY_DOWNLOADS}/Glagolitsa"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collection, values)
            ?: error("Failed to create media record")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(preview.bytes)
                output.flush()
            } ?: error("Failed to open output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri.toString()
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
