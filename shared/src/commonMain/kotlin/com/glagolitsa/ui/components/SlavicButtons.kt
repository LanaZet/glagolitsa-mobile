// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.glagolitsa.ui.rememberScreenWidthDp
import com.glagolitsa.ui.rememberScreenWidthDpValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaShapes
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.auth_login_button
import org.jetbrains.compose.resources.imageResource
import kotlin.math.min
import kotlin.math.roundToInt

private val AuthButtonHeight = 52.dp
private val AuthButtonRadius = 18.dp
private val AuthButtonContentPadding = 24.dp
private val AuthButtonSurface = Color(0xFF852A34)
private val AuthButtonText = Color(0xFFD8CFBA)
private val AuthButtonShadowSpot = Color(0xFF7A2C22)
private val AuthButtonTopContourStart = Color(0xFFC7B89D)
private val AuthButtonTopContourMid = Color(0xFFE0D3BA)
private val AuthButtonTopContourEnd = Color(0xFFA56D65)
private val AuthButtonDarkContourStart = Color(0xFF6E3033)
private val AuthButtonDarkContourEnd = Color(0xFF401016)

/** @deprecated Используйте [GlagolitsaButtonStyle]. */
object SlavicButtonStyle {
    val shape = com.glagolitsa.ui.theme.GlagolitsaShapes.sm
    val cornerRadius = 12.dp

    const val NARROW_SCREEN_DP = 340
    const val MEDIUM_SCREEN_DP = 400
    const val ICON_PILL_MAX_WIDTH_FRACTION = 0.44f

    object Primary {
        val containerColor = GlagolitsaColors.AccentRedContainer
        val contentColor = GlagolitsaColors.TextOnAccent
        val disabledContainerColor = GlagolitsaColors.AccentRedContainer.copy(alpha = 0.34f)
        val disabledContentColor = GlagolitsaColors.TextOnAccent.copy(alpha = 0.55f)
    }

    object Secondary {
        val containerColor = GlagolitsaColors.Surface700
        val contentColor = GlagolitsaColors.TextPrimary
        val disabledContainerColor = GlagolitsaColors.Surface700.copy(alpha = 0.45f)
        val disabledContentColor = GlagolitsaColors.TextPrimary.copy(alpha = 0.55f)
    }

    object Outlined {
        val contentColor = GlagolitsaColors.TextPrimary
        val disabledContentColor = GlagolitsaColors.TextDisabled
    }

    object Text {
        val contentColor = GlagolitsaColors.AccentRedText
        val disabledContentColor = GlagolitsaColors.TextDisabled
    }
}

enum class SlavicButtonSize {
    Default,
    Compact,
}

data class SlavicButtonMetrics(
    val height: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val textStyle: TextStyle,
)

private fun SlavicButtonSize.toGlagolitsa(): GlagolitsaButtonSize = when (this) {
    SlavicButtonSize.Compact -> GlagolitsaButtonSize.Medium
    SlavicButtonSize.Default -> GlagolitsaButtonSize.Large
}

@Composable
fun rememberSlavicButtonMetrics(size: SlavicButtonSize = SlavicButtonSize.Default): SlavicButtonMetrics {
    val screenWidthDp = rememberScreenWidthDpValue()
    return remember(screenWidthDp, size) {
        when (size.toGlagolitsa()) {
            GlagolitsaButtonSize.Small -> SlavicButtonMetrics(32.dp, 12.dp, 6.dp, TextStyle.Default)
            GlagolitsaButtonSize.Medium -> SlavicButtonMetrics(40.dp, 16.dp, 8.dp, TextStyle.Default)
            GlagolitsaButtonSize.Large -> SlavicButtonMetrics(48.dp, 20.dp, 12.dp, TextStyle.Default)
        }
    }
}

@Composable
fun rememberSlavicIconPillMaxWidth(): Dp {
    val screenWidth = rememberScreenWidthDp()
    return remember(screenWidth) {
        screenWidth * SlavicButtonStyle.ICON_PILL_MAX_WIDTH_FRACTION
    }
}

@Composable
fun SlavicPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: SlavicButtonSize = SlavicButtonSize.Default,
    loading: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    GlagolitsaButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        loading = loading,
        style = GlagolitsaButtonStyle.Primary,
        size = size.toGlagolitsa(),
        content = content,
    )
}

@Composable
fun AuthRaisedPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val isInteractive = enabled && !loading
    val shape = RoundedCornerShape(AuthButtonRadius)
    val shadowElevation by animateDpAsState(
        targetValue = if (pressed && isInteractive) 4.dp else 9.dp,
        animationSpec = tween(140),
        label = "authButtonShadow",
    )
    val textStyle = MaterialTheme.typography.titleMedium.copy(
        fontWeight = FontWeight.Bold,
        color = if (enabled) AuthButtonText else GlagolitsaColors.TextDisabled,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AuthButtonHeight)
            .defaultMinSize(minHeight = AuthButtonHeight)
            .shadow(
                elevation = shadowElevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (enabled) 0.30f else 0.18f),
                spotColor = AuthButtonShadowSpot.copy(alpha = if (enabled) 0.16f else 0.08f),
            )
            .authButtonOuterContour(enabled = enabled)
            .clip(shape)
            .authButtonSurface(enabled = enabled, pressed = pressed)
            .clickable(
                enabled = isInteractive,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { role = Role.Button }
            .padding(horizontal = AuthButtonContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = textStyle.color,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(GlagolitsaSpacing.sm))
            }
            CompositionLocalProvider(LocalTextStyle provides textStyle) {
                content()
            }
        }
    }
}

@Composable
fun AuthLoginPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isInteractive = enabled && !loading
    val textStyle = MaterialTheme.typography.titleMedium.copy(
        fontWeight = FontWeight.Bold,
        color = if (enabled) AuthButtonText else GlagolitsaColors.TextDisabled,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AuthButtonHeight)
            .defaultMinSize(minHeight = AuthButtonHeight)
            .clickable(
                enabled = isInteractive,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        NineSliceAuthLoginButtonImage(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (enabled) 1f else 0.44f),
        )
        Row(
            modifier = Modifier
                .padding(horizontal = AuthButtonContentPadding)
                .offset(y = (-3).dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = textStyle.color,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(GlagolitsaSpacing.sm))
            }
            CompositionLocalProvider(LocalTextStyle provides textStyle) {
                content()
            }
        }
    }
}

@Composable
private fun NineSliceAuthLoginButtonImage(modifier: Modifier = Modifier) {
    val image = imageResource(Res.drawable.auth_login_button)

    Canvas(modifier = modifier) {
        val sourceCapWidth = image.height.coerceAtMost(image.width / 2)
        val destinationCapWidth = min(size.height, size.width / 2f).roundToInt()
        val destinationWidth = size.width.roundToInt()
        val destinationHeight = size.height.roundToInt()
        val sourceCenterWidth = image.width - sourceCapWidth * 2
        val destinationCenterWidth = destinationWidth - destinationCapWidth * 2

        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(sourceCapWidth, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(destinationCapWidth, destinationHeight),
        )
        if (sourceCenterWidth > 0 && destinationCenterWidth > 0) {
            drawImage(
                image = image,
                srcOffset = IntOffset(sourceCapWidth, 0),
                srcSize = IntSize(sourceCenterWidth, image.height),
                dstOffset = IntOffset(destinationCapWidth, 0),
                dstSize = IntSize(destinationCenterWidth, destinationHeight),
            )
        }
        drawImage(
            image = image,
            srcOffset = IntOffset(image.width - sourceCapWidth, 0),
            srcSize = IntSize(sourceCapWidth, image.height),
            dstOffset = IntOffset(destinationWidth - destinationCapWidth, 0),
            dstSize = IntSize(destinationCapWidth, destinationHeight),
        )
    }
}

private fun Modifier.authButtonOuterContour(enabled: Boolean): Modifier = drawWithCache {
    val radius = AuthButtonRadius.toPx()
    val corner = CornerRadius(radius, radius)
    val alpha = if (enabled) 1f else 0.42f
    val darkContour = Brush.linearGradient(
        colors = listOf(
            AuthButtonDarkContourStart.copy(alpha = 0.28f * alpha),
            AuthButtonDarkContourEnd.copy(alpha = 0.78f * alpha),
        ),
        start = Offset(size.width * 0.20f, 0f),
        end = Offset(size.width, size.height),
    )
    val topContour = Brush.horizontalGradient(
        colors = listOf(
            AuthButtonTopContourStart.copy(alpha = 0.34f * alpha),
            AuthButtonTopContourMid.copy(alpha = 0.50f * alpha),
            AuthButtonTopContourEnd.copy(alpha = 0.18f * alpha),
        ),
        startX = radius,
        endX = size.width - radius,
    )

    onDrawBehind {
        drawRoundRect(
            brush = darkContour,
            cornerRadius = corner,
            style = Stroke(width = 2.dp.toPx()),
        )
        drawLine(
            brush = topContour,
            start = Offset(radius, 1.2.dp.toPx()),
            end = Offset(size.width - radius, 1.2.dp.toPx()),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun Modifier.authButtonSurface(
    enabled: Boolean,
    pressed: Boolean,
): Modifier = drawWithCache {
    val radius = AuthButtonRadius.toPx()
    val corner = CornerRadius(radius, radius)
    val disabledAlpha = if (enabled) 1f else 0.46f
    val pressShade = if (pressed && enabled) 0.07f else 0f
    val baseColor = AuthButtonSurface.copy(alpha = disabledAlpha)

    onDrawBehind {
        drawRoundRect(color = baseColor, cornerRadius = corner)
        drawRoundRect(
            color = Color.Black.copy(alpha = (0.10f + pressShade) * disabledAlpha),
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx()),
        )
        if (pressShade > 0f) {
            drawRoundRect(
                color = Color.Black.copy(alpha = pressShade),
                cornerRadius = corner,
            )
        }
    }
}

@Composable
fun AuthInlineTextButton(
    prefix: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    GlagolitsaButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        style = GlagolitsaButtonStyle.Text,
        size = GlagolitsaButtonSize.Medium,
        shape = GlagolitsaShapes.pill,
    ) {
        if (prefix.isNotBlank()) {
            Text(prefix, color = GlagolitsaColors.TextSecondary)
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            action,
            color = if (enabled) GlagolitsaColors.TextPrimary else GlagolitsaColors.TextDisabled,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun SlavicPillSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: SlavicButtonSize = SlavicButtonSize.Default,
    content: @Composable RowScope.() -> Unit,
) {
    GlagolitsaButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        style = GlagolitsaButtonStyle.Secondary,
        size = size.toGlagolitsa(),
        content = content,
    )
}

@Composable
fun SlavicPillOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: SlavicButtonSize = SlavicButtonSize.Default,
    content: @Composable RowScope.() -> Unit,
) {
    GlagolitsaButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        style = GlagolitsaButtonStyle.Tertiary,
        size = size.toGlagolitsa(),
        content = content,
    )
}

@Composable
fun SlavicPillTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: SlavicButtonSize = SlavicButtonSize.Default,
    content: @Composable RowScope.() -> Unit,
) {
    GlagolitsaButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        style = GlagolitsaButtonStyle.Text,
        size = size.toGlagolitsa(),
        content = content,
    )
}

/** Кнопка «назад» — навигационная, реф BUTTONS. */
@Composable
fun SlavicBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Назад",
) {
    GlagolitsaNavButton(
        kind = GlagolitsaNavButtonKind.Back,
        onClick = onClick,
        modifier = modifier.widthIn(max = rememberSlavicIconPillMaxWidth()),
        label = label,
    )
}

/** Кнопка отправки — Primary с иконкой, реф BUTTONS. */
@Composable
fun SlavicSendButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Отправить",
) {
    GlagolitsaButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        style = GlagolitsaButtonStyle.Primary,
        size = GlagolitsaButtonSize.Large,
        leadingIcon = { GlagolitsaButtonSendIcon(tint = it) },
    ) {
        Text(label)
    }
}
