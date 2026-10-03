// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.theme.GlagolitsaColors
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.toggle_anim_00
import glagolitsamobile.shared.generated.resources.toggle_anim_01
import glagolitsamobile.shared.generated.resources.toggle_anim_02
import glagolitsamobile.shared.generated.resources.toggle_anim_03
import glagolitsamobile.shared.generated.resources.toggle_anim_04
import glagolitsamobile.shared.generated.resources.toggle_anim_05
import glagolitsamobile.shared.generated.resources.toggle_anim_06
import glagolitsamobile.shared.generated.resources.toggle_anim_07
import glagolitsamobile.shared.generated.resources.toggle_anim_08
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

private val SwitchWidth = 68.dp
private val SwitchHeight = 40.dp
private val TouchMinWidth = 72.dp
private val TouchMinHeight = 48.dp
private const val FrameDelayMs = 22L

private val ToggleAnimationFrames: List<DrawableResource> = listOf(
    Res.drawable.toggle_anim_00,
    Res.drawable.toggle_anim_01,
    Res.drawable.toggle_anim_02,
    Res.drawable.toggle_anim_03,
    Res.drawable.toggle_anim_04,
    Res.drawable.toggle_anim_05,
    Res.drawable.toggle_anim_06,
    Res.drawable.toggle_anim_07,
    Res.drawable.toggle_anim_08,
)

/**
 * Branded switch.
 *
 * Dark slot: frame-animated night-orb (`toggle_anim_00…08`).
 * Light slot: parchment/gold pill drawn from theme tokens — the night
 * frames sit on a black halo and stain cream cards.
 *
 * Visual state flips on tap immediately (does not wait for parent/API),
 * so both on→off and off→on always animate and respond.
 */
@Composable
fun GlagolitsaSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compactTouchTarget: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val lastIndex = ToggleAnimationFrames.lastIndex

    // Local visual state flips on tap immediately; parent `checked` remains source of truth.
    var visualChecked by remember { mutableStateOf(checked) }
    var frameIndex by remember { mutableIntStateOf(if (checked) lastIndex else 0) }

    // Sync from parent (optimistic update, server revert, external refresh).
    LaunchedEffect(checked) {
        visualChecked = checked
    }

    // Animate frames toward visualChecked from the current frame.
    LaunchedEffect(visualChecked) {
        val target = if (visualChecked) lastIndex else 0
        var current = frameIndex
        if (current == target) {
            frameIndex = target
            return@LaunchedEffect
        }
        val step = if (target > current) 1 else -1
        while (current != target) {
            current += step
            frameIndex = current
            delay(FrameDelayMs)
        }
    }

    Box(
        modifier = modifier
            .then(
                if (compactTouchTarget) {
                    Modifier
                } else {
                    Modifier.defaultMinSize(minWidth = TouchMinWidth, minHeight = TouchMinHeight)
                },
            )
            .alpha(if (enabled) 1f else 0.48f)
            .semantics {
                role = Role.Switch
                stateDescription = if (visualChecked) "Включено" else "Выключено"
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = {
                    // Always invert parent value — reliable for both ON and OFF.
                    val next = !checked
                    visualChecked = next
                    onCheckedChange(next)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (GlagolitsaColors.IsDark) {
            Image(
                painter = painterResource(
                    ToggleAnimationFrames.getOrElse(frameIndex) {
                        if (visualChecked) ToggleAnimationFrames.last() else ToggleAnimationFrames.first()
                    },
                ),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = SwitchWidth, height = SwitchHeight),
            )
        } else {
            LightThemeSwitchFace(checked = visualChecked)
        }
    }
}

/**
 * Parchment/gold switch for the light slot.
 * Dark theme keeps the illustrated night-orb frames; those frames have a
 * black halo and read as a stain on cream cards.
 */
@Composable
private fun LightThemeSwitchFace(checked: Boolean) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "light-switch-thumb",
    )
    val trackOff = GlagolitsaColors.Surface600
    val trackOn = GlagolitsaColors.OrnamentGold
    val thumbFill = GlagolitsaColors.Surface800
    val borderOff = GlagolitsaColors.BorderMuted
    val borderOn = GlagolitsaColors.OrnamentGold.copy(alpha = 0.72f)
    val sheen = Color.White.copy(alpha = 0.55f)
    val shadow = GlagolitsaColors.TextPrimary.copy(alpha = 0.16f)
    val trackShade = GlagolitsaColors.TextPrimary.copy(alpha = 0.06f)
    val thumbStroke = GlagolitsaColors.BorderSubtle

    Canvas(modifier = Modifier.size(width = SwitchWidth, height = SwitchHeight)) {
        val trackHeight = size.height * 0.58f
        val trackWidth = size.width * 0.86f
        val trackLeft = (size.width - trackWidth) / 2f
        val trackTop = (size.height - trackHeight) / 2f
        val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        val thumbRadius = trackHeight * 0.42f
        val travelPad = thumbRadius + 3.dp.toPx()
        val thumbX = trackLeft + travelPad + (trackWidth - travelPad * 2f) * progress
        val thumbY = size.height / 2f

        val trackColor = lerpColor(trackOff, trackOn, progress)
        val borderColor = lerpColor(borderOff, borderOn, progress)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(trackLeft, trackTop),
            size = Size(trackWidth, trackHeight),
            cornerRadius = corner,
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.22f * (1f - progress * 0.35f)),
                    Color.Transparent,
                    trackShade,
                ),
            ),
            topLeft = Offset(trackLeft, trackTop),
            size = Size(trackWidth, trackHeight),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(trackLeft, trackTop),
            size = Size(trackWidth, trackHeight),
            cornerRadius = corner,
            style = Stroke(width = 1.2.dp.toPx()),
        )

        drawCircle(
            color = shadow,
            radius = thumbRadius,
            center = Offset(thumbX + 0.6.dp.toPx(), thumbY + 1.1.dp.toPx()),
        )
        drawCircle(
            color = thumbFill,
            radius = thumbRadius,
            center = Offset(thumbX, thumbY),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(sheen, Color.Transparent),
                center = Offset(thumbX - thumbRadius * 0.28f, thumbY - thumbRadius * 0.32f),
                radius = thumbRadius * 0.95f,
            ),
            radius = thumbRadius,
            center = Offset(thumbX, thumbY),
        )
        drawCircle(
            color = thumbStroke,
            radius = thumbRadius,
            center = Offset(thumbX, thumbY),
            style = Stroke(width = 0.9.dp.toPx()),
        )
    }
}

private fun lerpColor(from: Color, to: Color, t: Float): Color {
    val amount = t.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * amount,
        green = from.green + (to.green - from.green) * amount,
        blue = from.blue + (to.blue - from.blue) * amount,
        alpha = from.alpha + (to.alpha - from.alpha) * amount,
    )
}
