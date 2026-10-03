// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.theme.GlagolitsaColors

private val LightInk = Color(0xFF3A2E22)

/** Soft contact shadow under a circular avatar (list / top bar). */
fun Modifier.avatarFrameShadow(): Modifier = drawBehind {
    val light = !GlagolitsaColors.IsDark
    val ink = if (light) LightInk else Color.Black
    val shadowCenter = Offset(size.width * 0.50f, size.height * 0.62f)
    val shadowRadius = size.minDimension * 0.42f
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to ink.copy(alpha = if (light) 0.16f else 0.40f),
                0.48f to ink.copy(alpha = if (light) 0.07f else 0.18f),
                0.82f to ink.copy(alpha = if (light) 0.02f else 0.05f),
                1f to Color.Transparent,
            ),
            center = shadowCenter,
            radius = shadowRadius,
        ),
        radius = shadowRadius,
        center = shadowCenter,
    )
}

/**
 * Glass bezel around an avatar disc — same language as [glassPillFrame]:
 * lift, translucent rim, top sheen, bottom caustic.
 */
fun Modifier.avatarGlassBezel(rim: Dp = 3.5.dp): Modifier {
    val light = !GlagolitsaColors.IsDark
    val ink = if (light) LightInk else Color.Black
    val fill = if (light) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.62f),
                Color(0xFFF4EDE1).copy(alpha = 0.94f),
                Color(0xFFE8DCC8).copy(alpha = 0.82f),
            ),
        )
    } else {
        Brush.radialGradient(
            colors = listOf(
                GlagolitsaColors.Surface800.copy(alpha = 0.92f),
                GlagolitsaColors.Background950.copy(alpha = 0.96f),
            ),
        )
    }
    return this
        .drawBehind {
            val shadowCenter = Offset(size.width * 0.50f, size.height * 0.60f)
            val shadowRadius = size.minDimension * 0.52f
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to ink.copy(alpha = if (light) 0.14f else 0.32f),
                        0.55f to ink.copy(alpha = if (light) 0.06f else 0.14f),
                        1f to Color.Transparent,
                    ),
                    center = shadowCenter,
                    radius = shadowRadius,
                ),
                radius = shadowRadius,
                center = shadowCenter,
            )
        }
        .shadow(
            elevation = if (light) 8.dp else 14.dp,
            shape = CircleShape,
            ambientColor = ink.copy(alpha = if (light) 0.12f else 0.26f),
            spotColor = ink.copy(alpha = if (light) 0.08f else 0.34f),
        )
        .clip(CircleShape)
        .background(fill)
        .drawBehind { drawGlassBezelRim(light = light) }
        .padding(rim)
}

/** Specular lens over a clipped avatar photo. */
fun Modifier.avatarGlassLens(): Modifier = drawWithContent {
    drawContent()
    val light = !GlagolitsaColors.IsDark
    val r = size.minDimension / 2f
    val c = center

    drawCircle(
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = if (light) 0.30f else 0.14f),
                0.20f to Color.White.copy(alpha = if (light) 0.10f else 0.05f),
                0.46f to Color.Transparent,
                1f to Color.Transparent,
            ),
            start = Offset(c.x - r * 0.78f, c.y - r * 0.82f),
            end = Offset(c.x + r * 0.12f, c.y + r * 0.08f),
        ),
        radius = r,
        center = c,
    )

    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.70f to Color.Transparent,
                1f to (if (light) LightInk.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.20f)),
            ),
            center = Offset(c.x, c.y - r * 0.06f),
            radius = r,
        ),
        radius = r,
        center = c,
    )

    drawCircle(
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = if (light) 0.58f else 0.26f),
                0.40f to Color.White.copy(alpha = if (light) 0.16f else 0.08f),
                0.70f to Color.Transparent,
                1f to (if (light) LightInk.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.32f)),
            ),
            start = Offset(c.x - r, c.y - r),
            end = Offset(c.x + r, c.y + r),
        ),
        radius = (r - 0.7.dp.toPx()).coerceAtLeast(0f),
        center = c,
        style = Stroke(width = 1.15.dp.toPx()),
    )

    drawCircle(
        color = Color.White.copy(alpha = if (light) 0.20f else 0.09f),
        radius = (r - 2.1.dp.toPx()).coerceAtLeast(0f),
        center = c,
        style = Stroke(width = 0.65.dp.toPx()),
    )
}

private fun DrawScope.drawGlassBezelRim(light: Boolean) {
    val r = size.minDimension / 2f
    val c = center
    val inset = 1.1.dp.toPx()
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = if (light) 0.72f else 0.24f),
                Color.White.copy(alpha = if (light) 0.16f else 0.06f),
            ),
            start = Offset(c.x - r, c.y - r),
            end = Offset(c.x + r * 0.35f, c.y + r * 0.55f),
        ),
        radius = r - inset,
        center = c,
        style = Stroke(width = 1.35.dp.toPx()),
    )
    val arcPad = 3.dp.toPx()
    drawArc(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = if (light) 0.48f else 0.22f),
                Color.Transparent,
            ),
        ),
        startAngle = 28f,
        sweepAngle = 124f,
        useCenter = false,
        topLeft = Offset(arcPad, arcPad),
        size = Size(size.width - arcPad * 2f, size.height - arcPad * 2f),
        style = Stroke(width = 1.15.dp.toPx(), cap = StrokeCap.Round),
    )
}
