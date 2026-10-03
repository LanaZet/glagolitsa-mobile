// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private val BtnIconStroke = 2.dp

@Composable
fun GlagolitsaButtonGearIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = Stroke(width = BtnIconStroke.toPx(), cap = StrokeCap.Round)
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(color = tint, radius = size.minDimension * 0.22f, style = stroke)
        val teeth = 6
        for (i in 0 until teeth) {
            val angle = ((i * 60.0) - 90.0) * PI / 180.0
            val inner = size.minDimension * 0.3f
            val outer = size.minDimension * 0.44f
            val cos = kotlin.math.cos(angle).toFloat()
            val sin = kotlin.math.sin(angle).toFloat()
            drawLine(
                color = tint,
                start = Offset(cx + cos * inner, cy + sin * inner),
                end = Offset(cx + cos * outer, cy + sin * outer),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun GlagolitsaButtonSendIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = Stroke(width = BtnIconStroke.toPx(), cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.18f, size.height * 0.82f),
            end = Offset(size.width * 0.82f, size.height * 0.18f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.82f, size.height * 0.18f),
            end = Offset(size.width * 0.5f, size.height * 0.18f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.82f, size.height * 0.18f),
            end = Offset(size.width * 0.82f, size.height * 0.5f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun GlagolitsaButtonBookmarkIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = Stroke(width = BtnIconStroke.toPx(), cap = StrokeCap.Round)
        val left = size.width * 0.24f
        val top = size.height * 0.14f
        val w = size.width * 0.52f
        val h = size.height * 0.72f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(left, top + h * 0.72f),
            end = Offset(size.width / 2f, top + h * 0.92f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(left + w, top + h * 0.72f),
            end = Offset(size.width / 2f, top + h * 0.92f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun GlagolitsaButtonDownloadIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = Stroke(width = BtnIconStroke.toPx(), cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = Offset(size.width / 2f, size.height * 0.16f),
            end = Offset(size.width / 2f, size.height * 0.62f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.34f, size.height * 0.48f),
            end = Offset(size.width / 2f, size.height * 0.66f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.66f, size.height * 0.48f),
            end = Offset(size.width / 2f, size.height * 0.66f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.2f, size.height * 0.82f),
            end = Offset(size.width * 0.8f, size.height * 0.82f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun GlagolitsaButtonTrashIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = Stroke(width = BtnIconStroke.toPx(), cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.22f, size.height * 0.24f),
            end = Offset(size.width * 0.78f, size.height * 0.24f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.26f, size.height * 0.3f),
            size = Size(size.width * 0.48f, size.height * 0.56f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.38f, size.height * 0.14f),
            end = Offset(size.width * 0.62f, size.height * 0.14f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun GlagolitsaButtonCheckIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = Stroke(width = BtnIconStroke.toPx(), cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.22f, size.height * 0.5f),
            end = Offset(size.width * 0.4f, size.height * 0.68f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.4f, size.height * 0.68f),
            end = Offset(size.width * 0.78f, size.height * 0.3f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun GlagolitsaButtonBackIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = Stroke(width = BtnIconStroke.toPx(), cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.58f, size.height * 0.22f),
            end = Offset(size.width * 0.28f, size.height * 0.5f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.28f, size.height * 0.5f),
            end = Offset(size.width * 0.58f, size.height * 0.78f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun GlagolitsaButtonCloseIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = Stroke(width = BtnIconStroke.toPx(), cap = StrokeCap.Round)
        val pad = size.minDimension * 0.26f
        drawLine(tint, Offset(pad, pad), Offset(size.width - pad, size.height - pad), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(size.width - pad, pad), Offset(pad, size.height - pad), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun GlagolitsaButtonNextIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = Stroke(width = BtnIconStroke.toPx(), cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.42f, size.height * 0.22f),
            end = Offset(size.width * 0.72f, size.height * 0.5f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.72f, size.height * 0.5f),
            end = Offset(size.width * 0.42f, size.height * 0.78f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun GlagolitsaButtonPlusIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val stroke = Stroke(width = BtnIconStroke.toPx(), cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = Offset(size.width / 2f, size.height * 0.22f),
            end = Offset(size.width / 2f, size.height * 0.78f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.22f, size.height / 2f),
            end = Offset(size.width * 0.78f, size.height / 2f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun GlagolitsaButtonFingerprintIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(46.dp)) {
        val stroke = Stroke(width = BtnIconStroke.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height

        fun fingerprintLine(builder: Path.() -> Unit) {
            drawPath(
                path = Path().apply(builder),
                color = tint,
                style = stroke,
            )
        }

        fingerprintLine {
            moveTo(w * 0.24f, h * 0.38f)
            cubicTo(w * 0.32f, h * 0.16f, w * 0.68f, h * 0.16f, w * 0.76f, h * 0.38f)
        }
        fingerprintLine {
            moveTo(w * 0.16f, h * 0.52f)
            cubicTo(w * 0.15f, h * 0.20f, w * 0.85f, h * 0.20f, w * 0.84f, h * 0.52f)
        }
        fingerprintLine {
            moveTo(w * 0.29f, h * 0.52f)
            cubicTo(w * 0.31f, h * 0.34f, w * 0.69f, h * 0.34f, w * 0.71f, h * 0.52f)
        }
        fingerprintLine {
            moveTo(w * 0.38f, h * 0.55f)
            cubicTo(w * 0.36f, h * 0.65f, w * 0.32f, h * 0.73f, w * 0.27f, h * 0.80f)
        }
        fingerprintLine {
            moveTo(w * 0.50f, h * 0.49f)
            cubicTo(w * 0.51f, h * 0.61f, w * 0.47f, h * 0.74f, w * 0.39f, h * 0.86f)
        }
        fingerprintLine {
            moveTo(w * 0.62f, h * 0.55f)
            cubicTo(w * 0.63f, h * 0.69f, w * 0.57f, h * 0.82f, w * 0.48f, h * 0.91f)
        }
        fingerprintLine {
            moveTo(w * 0.73f, h * 0.60f)
            cubicTo(w * 0.72f, h * 0.73f, w * 0.66f, h * 0.84f, w * 0.59f, h * 0.92f)
        }

        drawCircle(
            color = tint.copy(alpha = 0.72f),
            radius = 1.5.dp.toPx(),
            center = Offset(w * 0.50f, h * 0.48f),
        )
    }
}
