// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import org.jetbrains.skia.Image

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    runCatching {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()

actual fun decodeImageBytesForDisplay(bytes: ByteArray, maxEdgePx: Int): ImageBitmap? =
    decodeImageBytes(bytes)

actual suspend fun loadRemoteImageBitmap(url: String): ImageBitmap? {
    return runCatching {
        val client = HttpClient(io.ktor.client.engine.darwin.Darwin)
        try {
            val bytes: ByteArray = client.get(url).body()
            decodeImageBytes(bytes)
        } finally {
            client.close()
        }
    }.getOrNull()
}
