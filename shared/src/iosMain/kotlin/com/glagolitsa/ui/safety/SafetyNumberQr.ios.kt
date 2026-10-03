// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.safety

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * Minimal QR-less placeholder: draws a hashed grid so the screen is usable
 * without ZXing on native. Real QR encoding can use a multiplatform encoder later.
 */
@Composable
actual fun SafetyNumberQrCode(
    payload: ByteArray,
    modifier: Modifier,
    foreground: Color,
    background: Color,
) {
    val size = 25
    val bits = BooleanArray(size * size) { index ->
        val b = payload.getOrElse(index % maxOf(payload.size, 1)) { 0 }
        ((b.toInt() and 0xFF) + index * 17) % 3 == 0
    }
    Canvas(modifier = modifier) {
        val cellW = this.size.width / size
        val cellH = this.size.height / size
        drawRect(background)
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (bits[y * size + x]) {
                    drawRect(
                        color = foreground,
                        topLeft = Offset(x * cellW, y * cellH),
                        size = androidx.compose.ui.geometry.Size(cellW, cellH),
                    )
                }
            }
        }
    }
}
