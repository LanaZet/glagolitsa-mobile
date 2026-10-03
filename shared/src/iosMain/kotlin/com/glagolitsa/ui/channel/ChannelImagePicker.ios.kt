// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.channel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.glagolitsa.platform.IosProviderDataResult
import com.glagolitsa.platform.IosSingleItemPhotoPicker
import com.glagolitsa.platform.iosLoadProviderData
import com.glagolitsa.ui.chat.iosDetectImageMime
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.PhotosUI.PHPickerFilter
import platform.UniformTypeIdentifiers.UTTypeImage

private const val MAX_OPEN_PHOTO_BYTES = 15L * 1024L * 1024L

@Composable
actual fun rememberChannelImagePicker(onResult: (PickedChannelImage?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onResult)
    val coordinator = remember(scope) { IosChannelImagePickerCoordinator(scope) { latest(it) } }
    DisposableEffect(coordinator) {
        onDispose { coordinator.close() }
    }
    return remember(coordinator) { { coordinator.pick() } }
}

@OptIn(ExperimentalForeignApi::class)
private class IosChannelImagePickerCoordinator(
    private val scope: CoroutineScope,
    private val onResult: (PickedChannelImage?) -> Unit,
) {
    private val picker = IosSingleItemPhotoPicker(PHPickerFilter.imagesFilter) { provider ->
        if (provider == null) {
            onResult(null)
            return@IosSingleItemPhotoPicker
        }
        val name = provider.suggestedName
        scope.launch {
            when (val loaded = iosLoadProviderData(provider, listOf(UTTypeImage.identifier), MAX_OPEN_PHOTO_BYTES)) {
                IosProviderDataResult.TooLarge -> onResult(errorResult(name, "Фото слишком большое (максимум 15 МБ)"))
                IosProviderDataResult.Unavailable -> onResult(errorResult(name, "Не удалось открыть файл"))
                is IosProviderDataResult.Loaded -> {
                    val picked = withContext(Dispatchers.Default) {
                        val bytes = loaded.bytes
                        val detectedMime = iosDetectImageMime(bytes)
                        if (bytes.isEmpty()) {
                            errorResult(name, "Пустое фото нельзя отправить")
                        } else if (detectedMime == null) {
                            errorResult(name, "Неподдерживаемый формат изображения")
                        } else {
                            PickedChannelImage(
                                bytes = bytes,
                                mimeType = detectedMime,
                                fileName = name,
                            )
                        }
                    }
                    onResult(picked)
                }
            }
        }
    }

    fun pick() = picker.present()

    fun close() = picker.close()
}

private fun errorResult(fileName: String?, message: String) = PickedChannelImage(
    bytes = byteArrayOf(),
    mimeType = "image/jpeg",
    fileName = fileName,
    errorMessage = message,
)
