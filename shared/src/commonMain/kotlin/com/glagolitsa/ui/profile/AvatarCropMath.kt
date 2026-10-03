// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import kotlin.math.max
import kotlin.math.roundToInt

data class AvatarCropSelection(
    val left: Int,
    val top: Int,
    val size: Int,
)

internal fun avatarCropSelection(
    bitmapWidth: Int,
    bitmapHeight: Int,
    frameSizePx: Float,
    zoom: Float,
    offsetXPx: Float,
    offsetYPx: Float,
): AvatarCropSelection {
    require(bitmapWidth > 0 && bitmapHeight > 0) { "Bitmap must be non-empty" }

    val safeFrameSize = frameSizePx.coerceAtLeast(1f)
    val safeZoom = zoom.coerceAtLeast(1f)
    val baseScale = max(safeFrameSize / bitmapWidth.toFloat(), safeFrameSize / bitmapHeight.toFloat())
    val scale = baseScale * safeZoom
    val renderedWidth = bitmapWidth * scale
    val renderedHeight = bitmapHeight * scale

    val sourceLeft = (((renderedWidth - safeFrameSize) / 2f) - offsetXPx) / scale
    val sourceTop = (((renderedHeight - safeFrameSize) / 2f) - offsetYPx) / scale
    val cropSize = (safeFrameSize / scale).roundToInt().coerceIn(
        minimumValue = 1,
        maximumValue = minOf(bitmapWidth, bitmapHeight),
    )

    val left = sourceLeft.roundToInt().coerceIn(0, bitmapWidth - cropSize)
    val top = sourceTop.roundToInt().coerceIn(0, bitmapHeight - cropSize)

    return AvatarCropSelection(left = left, top = top, size = cropSize)
}
