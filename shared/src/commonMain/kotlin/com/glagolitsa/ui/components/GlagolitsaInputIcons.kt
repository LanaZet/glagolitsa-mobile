// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.chat_attach_icon
import org.jetbrains.compose.resources.painterResource

private val IconStroke = 1.6.dp

@Composable
fun GlagolitsaInputSearchIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        val radius = size.minDimension * 0.34f
        drawCircle(
            color = tint,
            radius = radius,
            center = Offset(size.width * 0.42f, size.height * 0.42f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.64f, size.height * 0.64f),
            end = Offset(size.width * 0.88f, size.height * 0.88f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun GlagolitsaInputClearIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        val pad = size.minDimension * 0.28f
        drawLine(tint, Offset(pad, pad), Offset(size.width - pad, size.height - pad), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(size.width - pad, pad), Offset(pad, size.height - pad), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun GlagolitsaInputUsernameIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        val headRadius = size.minDimension * 0.17f
        drawCircle(
            color = tint,
            radius = headRadius,
            center = Offset(size.width / 2f, size.height * 0.34f),
            style = stroke,
        )
        drawArc(
            color = tint,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(size.width * 0.18f, size.height * 0.5f),
            size = Size(size.width * 0.64f, size.height * 0.42f),
            style = stroke,
        )
    }
}

@Composable
fun GlagolitsaInputEmailIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        val left = size.width * 0.12f
        val top = size.height * 0.28f
        val w = size.width * 0.76f
        val h = size.height * 0.44f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(left, top),
            end = Offset(size.width / 2f, top + h * 0.55f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(left + w, top),
            end = Offset(size.width / 2f, top + h * 0.55f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun GlagolitsaInputLockIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        val bodyLeft = size.width * 0.22f
        val bodyTop = size.height * 0.46f
        val bodyW = size.width * 0.56f
        val bodyH = size.height * 0.38f
        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = stroke,
        )
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.28f, size.height * 0.12f),
            size = Size(size.width * 0.44f, size.height * 0.42f),
            style = stroke,
        )
    }
}

@Composable
fun GlagolitsaInputEyeIcon(tint: Color, visible: Boolean, modifier: Modifier = Modifier) {
    // Same IconStroke as GlagolitsaInputClearIcon (×) — outline only, no heavier fill.
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val eye = Path().apply {
            moveTo(size.width * 0.08f, cy)
            quadraticBezierTo(cx, size.height * 0.22f, size.width * 0.92f, cy)
            quadraticBezierTo(cx, size.height * 0.78f, size.width * 0.08f, cy)
            close()
        }
        drawPath(path = eye, color = tint, style = stroke)
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.12f,
            center = Offset(cx, cy),
            style = stroke,
        )
        if (!visible) {
            // Slash uses the same stroke weight as the clear (×) icon.
            drawLine(
                color = tint,
                start = Offset(size.width * 0.14f, size.height * 0.86f),
                end = Offset(size.width * 0.86f, size.height * 0.14f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun GlagolitsaInputPhoneIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        val left = size.width * 0.28f
        val top = size.height * 0.1f
        val w = size.width * 0.44f
        val h = size.height * 0.8f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(left + w * 0.5f, top + h * 0.84f),
            end = Offset(left + w * 0.5f, top + h * 0.92f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun GlagolitsaInputCalendarIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        val left = size.width * 0.14f
        val top = size.height * 0.2f
        val w = size.width * 0.72f
        val h = size.height * 0.68f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = stroke,
        )
        drawLine(tint, Offset(left, top + h * 0.28f), Offset(left + w, top + h * 0.28f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(left + w * 0.28f, top - h * 0.08f), Offset(left + w * 0.28f, top + h * 0.12f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(left + w * 0.72f, top - h * 0.08f), Offset(left + w * 0.72f, top + h * 0.12f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun GlagolitsaInputAttachIcon(tint: Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.chat_attach_icon),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(20.dp),
    )
}

@Composable
fun GlagolitsaInputMediaIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        val left = size.width * 0.1f
        val top = size.height * 0.18f
        val w = size.width * 0.8f
        val h = size.height * 0.64f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = stroke,
        )
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.08f,
            center = Offset(size.width * 0.31f, size.height * 0.36f),
            style = stroke,
        )
        val mountain = Path().apply {
            moveTo(size.width * 0.2f, size.height * 0.72f)
            lineTo(size.width * 0.42f, size.height * 0.5f)
            lineTo(size.width * 0.55f, size.height * 0.62f)
            lineTo(size.width * 0.66f, size.height * 0.48f)
            lineTo(size.width * 0.82f, size.height * 0.72f)
        }
        drawPath(path = mountain, color = tint, style = stroke)
    }
}

@Composable
fun GlagolitsaInputFileIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        val left = size.width * 0.24f
        val top = size.height * 0.1f
        val w = size.width * 0.52f
        val h = size.height * 0.8f
        val fold = size.minDimension * 0.2f
        val page = Path().apply {
            moveTo(left, top)
            lineTo(left + w - fold, top)
            lineTo(left + w, top + fold)
            lineTo(left + w, top + h)
            lineTo(left, top + h)
            close()
        }
        drawPath(page, tint, style = stroke)
        drawLine(
            color = tint,
            start = Offset(left + w - fold, top),
            end = Offset(left + w - fold, top + fold),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(left + w - fold, top + fold),
            end = Offset(left + w, top + fold),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(tint, Offset(left + w * 0.22f, top + h * 0.56f), Offset(left + w * 0.78f, top + h * 0.56f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(left + w * 0.22f, top + h * 0.72f), Offset(left + w * 0.68f, top + h * 0.72f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun GlagolitsaInputCameraIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        val left = size.width * 0.12f
        val top = size.height * 0.32f
        val w = size.width * 0.76f
        val h = size.height * 0.48f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.36f, top),
            end = Offset(size.width * 0.43f, size.height * 0.2f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.43f, size.height * 0.2f),
            end = Offset(size.width * 0.58f, size.height * 0.2f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.58f, size.height * 0.2f),
            end = Offset(size.width * 0.65f, top),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.14f,
            center = Offset(size.width * 0.5f, size.height * 0.56f),
            style = stroke,
        )
    }
}

@Composable
fun GlagolitsaInputErrorIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        drawCircle(color = tint, radius = size.minDimension * 0.42f, style = stroke)
        drawLine(
            color = tint,
            start = Offset(size.width / 2f, size.height * 0.3f),
            end = Offset(size.width / 2f, size.height * 0.58f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = tint,
            radius = 1.4.dp.toPx(),
            center = Offset(size.width / 2f, size.height * 0.72f),
        )
    }
}

@Composable
fun GlagolitsaInputSuccessIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        drawCircle(color = tint, radius = size.minDimension * 0.42f, style = stroke)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.3f, size.height * 0.5f),
            end = Offset(size.width * 0.44f, size.height * 0.64f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.44f, size.height * 0.64f),
            end = Offset(size.width * 0.72f, size.height * 0.34f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun GlagolitsaInputPlusIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
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
fun GlagolitsaInputEmojiIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        drawCircle(color = tint, radius = size.minDimension * 0.42f, style = stroke)
        drawCircle(color = tint, radius = 1.5.dp.toPx(), center = Offset(size.width * 0.36f, size.height * 0.42f))
        drawCircle(color = tint, radius = 1.5.dp.toPx(), center = Offset(size.width * 0.64f, size.height * 0.42f))
        drawArc(
            color = tint,
            startAngle = 10f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(size.width * 0.22f, size.height * 0.44f),
            size = Size(size.width * 0.56f, size.height * 0.36f),
            style = stroke,
        )
    }
}

@Composable
fun GlagolitsaInputSendIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.42f, size.height * 0.22f),
            end = Offset(size.width * 0.42f, size.height * 0.72f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.42f, size.height * 0.22f),
            end = Offset(size.width * 0.78f, size.height * 0.5f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.22f, size.height * 0.5f),
            end = Offset(size.width * 0.78f, size.height * 0.5f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun GlagolitsaInputMicIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = IconStroke.toPx(), cap = StrokeCap.Round)
        val bodyW = size.width * 0.34f
        val bodyH = size.height * 0.44f
        val left = (size.width - bodyW) / 2f
        val top = size.height * 0.14f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(bodyW / 2f, bodyW / 2f),
            style = stroke,
        )
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.22f, size.height * 0.46f),
            size = Size(size.width * 0.56f, size.height * 0.34f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width / 2f, size.height * 0.78f),
            end = Offset(size.width / 2f, size.height * 0.9f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}
