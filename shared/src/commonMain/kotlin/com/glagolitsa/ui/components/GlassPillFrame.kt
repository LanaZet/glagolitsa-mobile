// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlin.math.min

/** Стеклянная капсула — тот же стиль, что у нижней навигации. */
enum class GlassPillFrameSize {
    Default,
    Compact,
}

private data class GlassPillFrameMetrics(
    val shadowElevation: Dp,
    val rimWidth: Dp,
)

private fun metricsFor(size: GlassPillFrameSize) = when (size) {
    GlassPillFrameSize.Default -> GlassPillFrameMetrics(shadowElevation = 20.dp, rimWidth = 3.5.dp)
    GlassPillFrameSize.Compact -> GlassPillFrameMetrics(shadowElevation = 10.dp, rimWidth = 2.dp)
}

@Composable
fun GlassPillFrame(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(percent = 50),
    cornerRadius: Dp? = null,
    frameSize: GlassPillFrameSize = GlassPillFrameSize.Default,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.glassPillFrame(
            shape = shape,
            cornerRadius = cornerRadius,
            frameSize = frameSize,
        ),
        content = content,
    )
}

fun Modifier.glassPillFrame(
    shape: Shape = RoundedCornerShape(percent = 50),
    cornerRadius: Dp? = null,
    frameSize: GlassPillFrameSize = GlassPillFrameSize.Default,
): Modifier {
    val metrics = metricsFor(frameSize)
    return this
    .drawBehind {
        val corner = frameCornerRadius(cornerRadius, this.size)
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.2f),
                    Color.White.copy(alpha = 0.06f),
                ),
            ),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = metrics.rimWidth.toPx()),
        )
    }
    .shadow(
        elevation = metrics.shadowElevation,
        shape = shape,
        ambientColor = Color.Black.copy(alpha = 0.24f),
        spotColor = Color.Black.copy(alpha = 0.14f),
    )
    .clip(shape)
    .background(GlagolitsaColors.GlassNavBorder)
    .padding(1.dp)
    .clip(shape)
    .background(GlagolitsaColors.GlassNavFill)
    .drawBehind {
        val corner = frameCornerRadius(cornerRadius, this.size)
        val highlightHeight = this.size.height * 0.58f
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.14f),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = highlightHeight,
            ),
            size = Size(this.size.width, highlightHeight),
            cornerRadius = CornerRadius(corner, corner),
        )

        val glowY = this.size.height - 1.5.dp.toPx()
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.55f),
                    Color.White.copy(alpha = 0.35f),
                    Color.Transparent,
                ),
            ),
            start = Offset(this.size.width * 0.08f, glowY),
            end = Offset(this.size.width * 0.92f, glowY),
            strokeWidth = 1.2.dp.toPx(),
        )
    }
    .background(GlagolitsaColors.GlassNavHighlight, shape)
    .background(GlagolitsaColors.GlassNavDepth, shape)
}

private fun DrawScope.frameCornerRadius(cornerRadius: Dp?, size: Size): Float {
    if (cornerRadius != null) {
        return min(cornerRadius.toPx(), min(size.width, size.height) / 2f)
    }
    return size.height / 2f
}