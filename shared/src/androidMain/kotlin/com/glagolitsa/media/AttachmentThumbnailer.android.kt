// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

actual object AttachmentThumbnailer {
    actual fun createThumbnail(bytes: ByteArray, mimeType: String?, maxEdgePx: Int): ByteArray? {
        if (!AttachmentMediaValidator.canPreviewImage(bytes, mimeType)) return null
        if (maxEdgePx <= 0) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null
            if (maxOf(width, height) <= maxEdgePx) return bytes
            val sample = computeSampleSize(width, height, maxEdgePx)
            val bitmap = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            val scaled = scaleToMaxEdge(bitmap, maxEdgePx)
            val normalizedMime = mimeType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            ByteArrayOutputStream().use { out ->
                val (format, quality) = when (normalizedMime) {
                    "image/png", "image/gif", "image/bmp" -> Bitmap.CompressFormat.PNG to 100
                    else -> Bitmap.CompressFormat.JPEG to 96
                }
                scaled.compress(format, quality, out)
                out.toByteArray().takeIf { it.isNotEmpty() }
            }.also {
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
        }.getOrNull()
    }

    private fun computeSampleSize(width: Int, height: Int, maxEdgePx: Int): Int {
        var sample = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sample >= maxEdgePx && halfHeight / sample >= maxEdgePx) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdgePx: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val max = maxOf(width, height)
        if (max <= maxEdgePx) return bitmap
        val scale = maxEdgePx.toFloat() / max.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}
