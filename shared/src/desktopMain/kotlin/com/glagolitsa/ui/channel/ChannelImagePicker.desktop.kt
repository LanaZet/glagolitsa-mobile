// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.channel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

private const val MAX_OPEN_PHOTO_BYTES = 15L * 1024L * 1024L

/** Desktop: AWT FileDialog for jpeg/png/webp (plaintext open path). */
@Composable
actual fun rememberChannelImagePicker(onResult: (PickedChannelImage?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember {
        {
            scope.launch {
                val picked = withContext(Dispatchers.IO) {
                    pickImageFileBlocking()
                }
                onResult(picked)
            }
        }
    }
}

private fun pickImageFileBlocking(): PickedChannelImage? {
    var result: PickedChannelImage? = null
    try {
        SwingUtilities.invokeAndWait {
            val dialog = FileDialog(null as Frame?, "Выберите фото", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name ->
                val lower = name.lowercase()
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".png") || lower.endsWith(".webp")
            }
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir == null || file == null) {
                result = null
                return@invokeAndWait
            }
            val path = File(dir, file)
            if (!path.isFile) {
                result = PickedChannelImage(
                    bytes = byteArrayOf(),
                    mimeType = "image/jpeg",
                    errorMessage = "Файл не найден",
                )
                return@invokeAndWait
            }
            if (path.length() > MAX_OPEN_PHOTO_BYTES) {
                result = PickedChannelImage(
                    bytes = byteArrayOf(),
                    mimeType = "image/jpeg",
                    fileName = file,
                    errorMessage = "Фото слишком большое (максимум 15 МБ)",
                )
                return@invokeAndWait
            }
            val bytes = runCatching { path.readBytes() }.getOrElse {
                result = PickedChannelImage(
                    bytes = byteArrayOf(),
                    mimeType = "image/jpeg",
                    fileName = file,
                    errorMessage = it.message ?: "Не удалось прочитать файл",
                )
                return@invokeAndWait
            }
            if (bytes.isEmpty()) {
                result = PickedChannelImage(
                    bytes = byteArrayOf(),
                    mimeType = "image/jpeg",
                    fileName = file,
                    errorMessage = "Пустой файл",
                )
                return@invokeAndWait
            }
            val mime = when {
                bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
                bytes.size >= 12 && bytes.decodeToString(0, 4) == "RIFF" -> "image/webp"
                file.lowercase().endsWith(".png") -> "image/png"
                file.lowercase().endsWith(".webp") -> "image/webp"
                else -> "image/jpeg"
            }
            result = PickedChannelImage(bytes = bytes, mimeType = mime, fileName = file)
        }
    } catch (e: Exception) {
        result = PickedChannelImage(
            bytes = byteArrayOf(),
            mimeType = "image/jpeg",
            errorMessage = e.message ?: "Ошибка выбора файла",
        )
    }
    return result
}
