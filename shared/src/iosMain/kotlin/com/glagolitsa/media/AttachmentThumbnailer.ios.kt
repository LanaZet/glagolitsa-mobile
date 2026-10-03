// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

actual object AttachmentThumbnailer {
    actual fun createThumbnail(bytes: ByteArray, mimeType: String?, maxEdgePx: Int): ByteArray? {
        if (!AttachmentMediaValidator.canPreviewImage(bytes, mimeType)) return null
        if (maxEdgePx <= 0) return null
        return runCatching {
            val image = Image.makeFromEncoded(bytes)
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return@runCatching null
            if (maxOf(width, height) <= maxEdgePx) return@runCatching bytes

            val maxEdge = maxOf(width, height).toFloat()
            val scale = maxEdgePx.toFloat() / maxEdge
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(targetWidth, targetHeight)
            surface.canvas.drawImageRect(
                image = image,
                dst = Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()),
            )
            val resized = surface.makeImageSnapshot()
            val normalizedMime = mimeType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            val format = when (normalizedMime) {
                "image/png", "image/gif", "image/bmp" -> EncodedImageFormat.PNG
                else -> EncodedImageFormat.JPEG
            }
            val quality = if (format == EncodedImageFormat.PNG) 100 else 96
            resized.encodeToData(format, quality)?.bytes ?: bytes
        }.getOrNull()
    }
}
