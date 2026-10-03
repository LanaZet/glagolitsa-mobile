// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.theme.GlagolitsaColors
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.nav_settings_gear
import org.jetbrains.compose.resources.painterResource

val OrnamentGold get() = GlagolitsaColors.OrnamentGold
val OrnamentRed get() = GlagolitsaColors.OrnamentRed

/**
 * Screen title with optional leading mark.
 * [leadingMark] replaces the default quill when provided (e.g. gear for chat settings).
 */
@Composable
fun GlagolitsaScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    showMark: Boolean = true,
    leadingMark: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingMark != null) {
            leadingMark()
            Spacer(modifier = Modifier.width(10.dp))
        } else if (showMark) {
            QuillMark(modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = GlagolitsaColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Gear + Slavic knot — chat settings title mark. */
@Composable
fun SettingsGearMark(
    modifier: Modifier = Modifier,
    tint: Color = OrnamentGold,
) {
    Image(
        painter = painterResource(Res.drawable.nav_settings_gear),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        contentScale = ContentScale.Fit,
        modifier = modifier.size(22.dp),
    )
}

@Composable
fun QuillMark(
    modifier: Modifier = Modifier,
    tint: Color = OrnamentRed,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = Offset(w * 0.15f, h * 0.85f),
            end = Offset(w * 0.82f, h * 0.18f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        val nib = Path().apply {
            moveTo(w * 0.78f, h * 0.22f)
            lineTo(w * 0.92f, h * 0.08f)
            lineTo(w * 0.7f, h * 0.28f)
            close()
        }
        drawPath(nib, tint)
        drawCircle(
            color = OrnamentGold,
            radius = w * 0.07f,
            center = Offset(w * 0.22f, h * 0.78f),
        )
    }
}

@Composable
fun DiamondMark(
    color: Color,
    markSize: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(markSize)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f
        val path = Path().apply {
            moveTo(cx, cy - r)
            lineTo(cx + r, cy)
            lineTo(cx, cy + r)
            lineTo(cx - r, cy)
            close()
        }
        drawPath(path, color)
    }
}