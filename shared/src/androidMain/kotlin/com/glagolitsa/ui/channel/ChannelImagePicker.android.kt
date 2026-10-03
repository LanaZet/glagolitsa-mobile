// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.channel

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Match server open photo limit (15 MiB). */
private const val MAX_OPEN_PHOTO_BYTES = 15L * 1024L * 1024L

private val openImageMimes = arrayOf(
    "image/jpeg",
    "image/png",
    "image/webp",
)

@Composable
actual fun rememberChannelImagePicker(onResult: (PickedChannelImage?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                        }
                    val sizeHint = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                        ?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
                        } ?: -1L
                    if (sizeHint > MAX_OPEN_PHOTO_BYTES) {
                        return@runCatching PickedChannelImage(
                            bytes = byteArrayOf(),
                            mimeType = "image/jpeg",
                            fileName = fileName,
                            errorMessage = "Фото слишком большое (максимум 15 МБ)",
                        )
                    }
                    val mime = (resolver.getType(uri) ?: "image/jpeg").lowercase()
                    if (mime !in openImageMimes && !mime.startsWith("image/")) {
                        return@runCatching PickedChannelImage(
                            bytes = byteArrayOf(),
                            mimeType = mime,
                            fileName = fileName,
                            errorMessage = "Нужен JPEG, PNG или WebP",
                        )
                    }
                    val stream = resolver.openInputStream(uri)
                        ?: return@runCatching PickedChannelImage(
                            bytes = byteArrayOf(),
                            mimeType = mime,
                            fileName = fileName,
                            errorMessage = "Не удалось открыть файл",
                        )
                    val bytes = stream.use { it.readBytes() }
                    if (bytes.isEmpty()) {
                        return@runCatching PickedChannelImage(
                            bytes = byteArrayOf(),
                            mimeType = mime,
                            fileName = fileName,
                            errorMessage = "Пустой файл",
                        )
                    }
                    if (bytes.size > MAX_OPEN_PHOTO_BYTES) {
                        return@runCatching PickedChannelImage(
                            bytes = byteArrayOf(),
                            mimeType = mime,
                            fileName = fileName,
                            errorMessage = "Фото слишком большое (максимум 15 МБ)",
                        )
                    }
                    val normalizedMime = when {
                        mime in openImageMimes -> mime
                        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
                        bytes.size >= 12 && bytes.decodeToString(0, 4) == "RIFF" -> "image/webp"
                        else -> mime
                    }
                    PickedChannelImage(
                        bytes = bytes,
                        mimeType = normalizedMime,
                        fileName = fileName,
                    )
                }.getOrElse {
                    PickedChannelImage(
                        bytes = byteArrayOf(),
                        mimeType = "image/jpeg",
                        errorMessage = it.message ?: "Ошибка выбора фото",
                    )
                }
            }
            onResult(picked)
        }
    }
    return remember(launcher) {
        { launcher.launch(openImageMimes) }
    }
}
