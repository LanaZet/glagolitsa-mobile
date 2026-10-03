// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

object AvatarImageProcessor {
    // Меньший размер — меньше RAM при скролле профиля и записи в SQLite.
    private const val TARGET_SIZE = 256
    private const val JPEG_QUALITY = 82

    fun decodePreviewBitmap(sourceBytes: ByteArray): Bitmap? = decodeSampledBitmap(sourceBytes)

    fun processForUpload(sourceBytes: ByteArray, cropSelection: AvatarCropSelection? = null): ByteArray? {
        var bitmap = decodeSampledBitmap(sourceBytes) ?: return null

        return runCatching {
            bitmap = cropSelection?.let { cropSquare(bitmap, it) } ?: centerCropSquare(bitmap)
            bitmap = scaleSquare(bitmap, TARGET_SIZE)
            encodeJpeg(bitmap)
        }.getOrNull()
    }

    private fun decodeSampledBitmap(sourceBytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxSide = TARGET_SIZE * 2,
            )
        }

        return BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, decodeOptions)
    }

    private fun centerCropSquare(source: Bitmap): Bitmap {
        val side = min(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2

        val cropped = Bitmap.createBitmap(source, left, top, side, side)
        if (cropped !== source) {
            source.recycle()
        }
        return cropped
    }

    private fun cropSquare(source: Bitmap, selection: AvatarCropSelection): Bitmap {
        val side = selection.size.coerceAtLeast(1)
        val left = selection.left.coerceIn(0, (source.width - side).coerceAtLeast(0))
        val top = selection.top.coerceIn(0, (source.height - side).coerceAtLeast(0))
        val maxSide = minOf(side, source.width - left, source.height - top)
        val cropped = Bitmap.createBitmap(source, left, top, maxSide, maxSide)
        if (cropped !== source) {
            source.recycle()
        }
        return cropped
    }

    private fun scaleSquare(source: Bitmap, size: Int): Bitmap {
        if (source.width == size && source.height == size) return source

        val scaled = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val destination = Rect(0, 0, size, size)
        canvas.drawBitmap(source, null, destination, paint)

        if (scaled !== source) {
            source.recycle()
        }
        return scaled
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray {
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sampleSize = 1
        var currentWidth = width
        var currentHeight = height

        while (max(currentWidth, currentHeight) > maxSide) {
            sampleSize *= 2
            currentWidth = width / sampleSize
            currentHeight = height / sampleSize
        }

        return sampleSize.coerceAtLeast(1)
    }
}