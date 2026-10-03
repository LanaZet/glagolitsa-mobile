// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlin.math.min

/**
 * Выпуклость: тень **снаружи** элемента.
 * Лёгкий внутренний блик сверху — без внутренней тени, чтобы не «пачкать» цвет.
 */
object ConvexSurfaceStyle {
    val castShadowElevation = 5.dp
    val castShadowAmbient = Color.Black.copy(alpha = 0.18f)
    val castShadowSpot = Color.Black.copy(alpha = 0.1f)

    val topSheen = Color.White.copy(alpha = 0.14f)
}

fun Modifier.convexSurface(
    shape: Shape,
    cornerRadius: Dp,
    baseColor: Color,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) {
        return this
            .clip(shape)
            .background(baseColor)
    }

    val light = !GlagolitsaColors.IsDark
    val elevation = if (light) 3.dp else ConvexSurfaceStyle.castShadowElevation
    val ambient = if (light) Color(0xFF3A2E22).copy(alpha = 0.14f) else ConvexSurfaceStyle.castShadowAmbient
    val spot = if (light) Color(0xFF3A2E22).copy(alpha = 0.10f) else ConvexSurfaceStyle.castShadowSpot
    val sheen = if (light) Color.White.copy(alpha = 0.42f) else ConvexSurfaceStyle.topSheen

    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = ambient,
            spotColor = spot,
        )
        .clip(shape)
        .background(baseColor)
        .drawBehind {
            val radius = effectiveCornerRadius(cornerRadius.toPx(), size)
            val corner = CornerRadius(radius, radius)
            val sheenEnd = size.height * 0.42f

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to sheen,
                        0.7f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    startY = 0f,
                    endY = sheenEnd,
                ),
                size = Size(size.width, sheenEnd),
                cornerRadius = corner,
            )
        }
}

private fun effectiveCornerRadius(cornerRadiusPx: Float, size: Size): Float {
    val maxRadius = min(size.width, size.height) / 2f
    return min(cornerRadiusPx, maxRadius)
}