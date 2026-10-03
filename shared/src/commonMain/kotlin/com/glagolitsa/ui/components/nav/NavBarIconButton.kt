// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.components.convexSurface
import com.glagolitsa.ui.navigation.MainTab
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import kotlin.math.min

private val TabIconWellSize = 42.dp
private val TabIconWellCorner = 15.dp
private val UnreadBadgeSize = 16.dp
/** Badge sits slightly outside the well corner so it is not clipped by inset. */
private val UnreadBadgeOffsetX = 5.dp
private val UnreadBadgeOffsetY = (-4).dp

/** Bottom-nav tab: icon-only; soft inset when selected. */
@Composable
fun NavBarTabItem(
    tab: MainTab,
    selected: Boolean,
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) {
        GlagolitsaColors.AccentRed
    } else {
        GlagolitsaColors.OrnamentGold
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val resolvedTint = when {
        pressed && selected -> GlagolitsaColors.AccentRedText
        pressed -> GlagolitsaColors.TextPrimary
        else -> tint
    }

    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                role = Role.Tab
                contentDescription = tab.label
            }
            .padding(vertical = GlagolitsaSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        NavTabIconWell(
            selected = selected,
            unreadCount = unreadCount,
            icon = { NavBarTabIcon(tab = tab, tint = resolvedTint) },
        )
    }
}

/**
 * Icon well + optional unread badge.
 *
 * Active inset uses [Modifier.clip]; the badge is drawn as a sibling of that
 * clipped layer so the count is never cut by the rounded well.
 */
@Composable
private fun NavTabIconWell(
    selected: Boolean,
    unreadCount: Int,
    icon: @Composable () -> Unit,
) {
    Box(modifier = Modifier.size(TabIconWellSize)) {
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navTabActiveInset(),
            )
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
            content = { icon() },
        )
        if (unreadCount > 0) {
            UnreadNavBadge(
                count = unreadCount,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = UnreadBadgeOffsetX, y = UnreadBadgeOffsetY),
            )
        }
    }
}

/** Soft inset well around a selected tab icon. */
private fun Modifier.navTabActiveInset(): Modifier {
    val shape = RoundedCornerShape(TabIconWellCorner)
    val light = !GlagolitsaColors.IsDark
    val fill = if (light) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFE8DCC8).copy(alpha = 0.55f),
                Color(0xFFF4EDE1).copy(alpha = 0.96f),
                Color(0xFFEDE4D4).copy(alpha = 0.88f),
            ),
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                GlagolitsaColors.Surface700.copy(alpha = 0.30f),
                GlagolitsaColors.Background950.copy(alpha = 0.88f),
                GlagolitsaColors.Surface800.copy(alpha = 0.52f),
            ),
        )
    }
    return this
        .clip(shape)
        .background(fill)
        .drawBehind {
            val r = min(TabIconWellCorner.toPx(), min(size.width, size.height) / 2f)
            val corner = CornerRadius(r, r)
            val ink = if (light) Color(0xFF3A2E22) else Color.Black
            val lift = Color.White

            drawRoundRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to ink.copy(alpha = if (light) 0.18f else 0.68f),
                        0.34f to ink.copy(alpha = if (light) 0.08f else 0.30f),
                        1f to Color.Transparent,
                    ),
                    start = Offset(-2.dp.toPx(), -2.dp.toPx()),
                    end = Offset(size.width * 0.85f, size.height * 0.9f),
                ),
                cornerRadius = corner,
            )

            drawRoundRect(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to ink.copy(alpha = if (light) 0.04f else 0.10f),
                        0.64f to ink.copy(alpha = if (light) 0.07f else 0.18f),
                        1f to Color.Transparent,
                    ),
                    center = Offset(size.width * 0.48f, size.height * 0.52f),
                    radius = size.minDimension * 0.72f,
                ),
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                cornerRadius = CornerRadius(r * 0.82f, r * 0.82f),
            )

            drawRoundRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.58f to lift.copy(alpha = if (light) 0.18f else 0.035f),
                        1f to lift.copy(alpha = if (light) 0.48f else 0.13f),
                    ),
                    start = Offset(size.width * 0.25f, size.height * 0.2f),
                    end = Offset(size.width + 1.dp.toPx(), size.height + 1.dp.toPx()),
                ),
                cornerRadius = corner,
            )

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        ink.copy(alpha = if (light) 0.08f else 0.18f),
                        ink.copy(alpha = if (light) 0.03f else 0.06f),
                        lift.copy(alpha = if (light) 0.22f else 0.035f),
                    ),
                ),
                topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                cornerRadius = CornerRadius(r * 0.74f, r * 0.74f),
                style = Stroke(width = 0.8.dp.toPx()),
            )
        }
}

@Composable
private fun UnreadNavBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(UnreadBadgeSize)
            .convexSurface(
                shape = CircleShape,
                cornerRadius = UnreadBadgeSize / 2,
                baseColor = GlagolitsaColors.FilterChipActive,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 9) "9+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.TextOnGold,
            fontWeight = FontWeight.Bold,
        )
    }
}
