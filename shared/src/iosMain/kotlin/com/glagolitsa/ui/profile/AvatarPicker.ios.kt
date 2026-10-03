// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import platform.PhotosUI.PHPickerFilter
import platform.UniformTypeIdentifiers.UTTypeImage
import kotlin.math.min

private const val TARGET_SIZE = 256
private const val JPEG_QUALITY = 82
private const val MAX_AVATAR_BASE64_CHARS = 120_000
private const val MAX_AVATAR_SOURCE_BYTES = 25L * 1024L * 1024L
private const val MAX_AVATAR_SOURCE_PIXELS = 50_000_000L

@Composable
actual fun rememberAvatarPicker(onResult: (String?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onResult)
    val coordinator = remember(scope) { IosAvatarPickerCoordinator(scope) { latest(it) } }
    DisposableEffect(coordinator) {
        onDispose { coordinator.close() }
    }
    return remember(coordinator) { { coordinator.pick() } }
}

@OptIn(ExperimentalForeignApi::class)
private class IosAvatarPickerCoordinator(
    private val scope: CoroutineScope,
    private val onResult: (String?) -> Unit,
) {
    private val picker = IosSingleItemPhotoPicker(PHPickerFilter.imagesFilter) { provider ->
        if (provider == null) {
            onResult(null)
            return@IosSingleItemPhotoPicker
        }
        scope.launch {
            val loaded = iosLoadProviderData(provider, listOf(UTTypeImage.identifier), MAX_AVATAR_SOURCE_BYTES)
            if (loaded !is IosProviderDataResult.Loaded) {
                onResult(null)
                return@launch
            }
            val dataUrl = withContext(Dispatchers.Default) {
                processAvatarBytes(loaded.bytes)?.let(::encodeAvatarDataUrl)
            }
            onResult(dataUrl)
        }
    }

    fun pick() = picker.present()

    fun close() = picker.close()
}

private fun processAvatarBytes(sourceBytes: ByteArray): ByteArray? = runCatching {
    if (iosDetectImageMime(sourceBytes) == null) return@runCatching null
    val image = Image.makeFromEncoded(sourceBytes)
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_AVATAR_SOURCE_PIXELS) {
        return@runCatching null
    }
    val side = min(width, height)
    val left = (width - side) / 2f
    val top = (height - side) / 2f
    val surface = Surface.makeRasterN32Premul(TARGET_SIZE, TARGET_SIZE)
    surface.canvas.drawImageRect(
        image = image,
        src = Rect.makeXYWH(left, top, side.toFloat(), side.toFloat()),
        dst = Rect.makeWH(TARGET_SIZE.toFloat(), TARGET_SIZE.toFloat()),
    )
    surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)?.bytes
}.getOrNull()

@OptIn(ExperimentalEncodingApi::class)
private fun encodeAvatarDataUrl(bytes: ByteArray): String? {
    val encoded = Base64.encode(bytes)
    if (encoded.length > MAX_AVATAR_BASE64_CHARS) return null
    return "data:image/jpeg;base64,$encoded"
}
