// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.repository.AttachmentPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual object AttachmentDownloadSaver {
    actual val isSupported: Boolean = true

    actual suspend fun saveImage(preview: AttachmentPreview): String = save(preview)

    actual suspend fun save(preview: AttachmentPreview): String = withContext(Dispatchers.IO) {
        val mimeType = preview.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val downloadsDir = File(System.getProperty("user.home"), "Downloads/Glagolitsa")
        downloadsDir.mkdirs()
        val target = uniqueTarget(
            File(
                downloadsDir,
                AttachmentDownloadNames.displayName(
                    fileName = preview.fileName,
                    mimeType = mimeType,
                    fallbackMillis = System.currentTimeMillis(),
                ),
            ),
        )
        target.writeBytes(preview.bytes)
        target.absolutePath
    }

    private fun uniqueTarget(target: File): File {
        if (!target.exists()) return target
        val name = target.nameWithoutExtension
        val extension = target.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
        var index = 1
        while (true) {
            val candidate = File(target.parentFile, "$name-$index$extension")
            if (!candidate.exists()) return candidate
            index += 1
        }
    }
}
