// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.net.URL

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    decodeImageBytesForDisplay(bytes, maxEdgePx = 512)

actual fun decodeImageBytesForDisplay(bytes: ByteArray, maxEdgePx: Int): ImageBitmap? = runCatching {
    // Skia full decode; edge is advisory on desktop (rare path). Prefer small server thumbs.
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

actual suspend fun loadRemoteImageBitmap(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        URL(url).openStream().use { stream ->
            decodeImageBytes(stream.readBytes())
        }
    }.getOrNull()
}
