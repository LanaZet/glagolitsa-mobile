// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components.nav

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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.navigation.MainTab
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.nav_calls_phone
import org.jetbrains.compose.resources.painterResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val NavIconStroke = 2.dp
private val NavIconSize = 24.dp
/** Optical size for bitmap phone glyph (slightly larger than stroke icons). */
private val CallsNavIconSize = 26.dp

@Composable
fun NavBarTabIcon(
    tab: MainTab,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    when (tab) {
        MainTab.Chats -> ChatsNavIcon(tint = tint, modifier = modifier)
        MainTab.Contacts -> ContactsNavIcon(tint = tint, modifier = modifier)
        MainTab.Calls -> CallsNavIcon(tint = tint, modifier = modifier)
        MainTab.Settings -> SettingsNavIcon(tint = tint, modifier = modifier)
    }
}

@Composable
private fun ChatsNavIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(NavIconSize)) {
        val stroke = Stroke(width = NavIconStroke.toPx(), cap = StrokeCap.Round)
        val bubbleWidth = size.width * 0.72f
        val bubbleHeight = size.height * 0.56f
        val left = size.width * 0.14f
        val top = size.height * 0.18f

        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(bubbleWidth, bubbleHeight),
            cornerRadius = CornerRadius(bubbleHeight * 0.32f, bubbleHeight * 0.32f),
            style = stroke,
        )

        val lineStart = left + bubbleWidth * 0.18f
        val lineEnd = left + bubbleWidth * 0.82f
        val lineY1 = top + bubbleHeight * 0.36f
        val lineY2 = top + bubbleHeight * 0.58f

        drawLine(tint, Offset(lineStart, lineY1), Offset(lineEnd, lineY1), stroke.width, StrokeCap.Round)
        drawLine(
            tint,
            Offset(lineStart, lineY2),
            Offset(lineStart + (lineEnd - lineStart) * 0.68f, lineY2),
            stroke.width,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun ContactsNavIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(NavIconSize)) {
        val stroke = Stroke(width = NavIconStroke.toPx(), cap = StrokeCap.Round)
        val headRadius = size.minDimension * 0.18f
        val headCenter = Offset(size.width / 2f, size.height * 0.34f)

        drawCircle(color = tint, radius = headRadius, center = headCenter, style = stroke)
        drawArc(
            color = tint,
            startAngle = 205f,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = Offset(size.width * 0.2f, size.height * 0.48f),
            size = Size(size.width * 0.6f, size.height * 0.38f),
            style = stroke,
        )
    }
}

@Composable
private fun CallsNavIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    // free-icon-phone-call-end asset → monochrome white PNG, tinted like other nav/menu icons.
    Image(
        painter = painterResource(Res.drawable.nav_calls_phone),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        contentScale = ContentScale.Fit,
        modifier = modifier.size(CallsNavIconSize),
    )
}

@Composable
private fun SettingsNavIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(NavIconSize)) {
        val stroke = Stroke(width = NavIconStroke.toPx(), cap = StrokeCap.Round)
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(color = tint, radius = size.minDimension * 0.2f, style = stroke)
        val teeth = 8
        for (i in 0 until teeth) {
            val angle = ((i * 45.0) - 90.0) * PI / 180.0
            val inner = size.minDimension * 0.28f
            val outer = size.minDimension * 0.42f
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            drawLine(
                color = tint,
                start = Offset(cx + cosA * inner, cy + sinA * inner),
                end = Offset(cx + cosA * outer, cy + sinA * outer),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }
    }
}