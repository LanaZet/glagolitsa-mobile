// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    decodeImageBytesForDisplay(bytes, maxEdgePx = 512)

actual fun decodeImageBytesForDisplay(bytes: ByteArray, maxEdgePx: Int): ImageBitmap? = runCatching {
    val edge = maxEdgePx.coerceAtLeast(64)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w > 0 && h > 0) {
        while (w / sample > edge * 2 || h / sample > edge * 2) {
            sample *= 2
        }
        // Also respect max edge roughly
        while (max(w / sample, h / sample) > edge && sample < 64) {
            sample *= 2
        }
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

actual suspend fun loadRemoteImageBitmap(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL(url).openConnection() as? HttpURLConnection ?: return@runCatching null
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = true
            connection.inputStream.use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
