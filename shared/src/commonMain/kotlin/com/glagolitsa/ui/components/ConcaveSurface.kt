// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlin.math.min

/**
 * Вогнутость: тень **внутри** элемента, без внешнего elevation.
 *
 * Градиент идёт **сверху вниз** (тёмное → светлое), граница перехода
 * **плавная и слегка диагональная** (не горизонтальный «срез»).
 */
object ConcaveSurfaceStyle {
    val innerShadow = Color.Black.copy(alpha = 0.30f)
    val innerHighlight = Color.White.copy(alpha = 0.08f)
}

fun Modifier.concaveSurface(
    shape: Shape,
    cornerRadius: Dp,
    baseColor: Color,
    focused: Boolean = false,
): Modifier {
    val light = !GlagolitsaColors.IsDark
    val shadow = if (light) Color(0xFF3A2E22) else Color.Black
    val shadowAlpha = when {
        light && focused -> 0.16f
        light -> 0.12f
        focused -> 0.36f
        else -> ConcaveSurfaceStyle.innerShadow.alpha
    }
    val highlightAlpha = when {
        light && focused -> 0.42f
        light -> 0.32f
        focused -> 0.11f
        else -> ConcaveSurfaceStyle.innerHighlight.alpha
    }

    return this
        .clip(shape)
        .background(baseColor)
        .drawBehind {
            val radius = effectiveCornerRadius(cornerRadius.toPx(), size)
            val corner = CornerRadius(radius, radius)

            // Top → bottom wash, slightly skewed L→R so the edge reads as a soft diagonal.
            drawRoundRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to shadow.copy(alpha = shadowAlpha),
                        0.28f to shadow.copy(alpha = shadowAlpha * 0.55f),
                        0.58f to shadow.copy(alpha = shadowAlpha * 0.18f),
                        0.82f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    start = Offset(size.width * 0.08f, 0f),
                    end = Offset(size.width * 0.92f, size.height),
                ),
                cornerRadius = corner,
            )

            // Bottom light (same diagonal axis) — soft lift at the lower edge.
            drawRoundRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.48f to Color.Transparent,
                        0.72f to Color.White.copy(alpha = highlightAlpha * 0.35f),
                        1f to Color.White.copy(alpha = highlightAlpha),
                    ),
                    start = Offset(size.width * 0.12f, 0f),
                    end = Offset(size.width * 0.95f, size.height),
                ),
                cornerRadius = corner,
            )
        }
}

private fun effectiveCornerRadius(cornerRadiusPx: Float, size: androidx.compose.ui.geometry.Size): Float {
    val maxRadius = min(size.width, size.height) / 2f
    return min(cornerRadiusPx, maxRadius)
}
