// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaShapes
import com.glagolitsa.ui.theme.GlagolitsaSpacing

private const val ButtonAnimationMs = 175

/** Стили кнопок Design System 1.0. */
enum class GlagolitsaButtonStyle {
    Primary,
    Secondary,
    Tertiary,
    Ghost,
    Danger,
    Success,
    Text,
}

/** Размеры кнопок Design System 1.0: Small 32 · Medium 40 · Large 48. */
enum class GlagolitsaButtonSize {
    Small,
    Medium,
    Large,
}

enum class GlagolitsaNavButtonKind {
    Back,
    Close,
    Done,
    Next,
}

private data class ButtonMetrics(
    val minHeight: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val textStyle: TextStyle,
    val iconGap: Dp,
)

private data class ButtonPalette(
    val container: Color,
    val content: Color,
    val border: Color = Color.Transparent,
)

@Composable
private fun rememberButtonMetrics(size: GlagolitsaButtonSize): ButtonMetrics {
    val typography = MaterialTheme.typography
    return remember(size, typography) {
        when (size) {
            GlagolitsaButtonSize.Small -> ButtonMetrics(
                minHeight = 32.dp,
                horizontalPadding = GlagolitsaSpacing.md,
                verticalPadding = 6.dp,
                textStyle = typography.labelMedium,
                iconGap = GlagolitsaSpacing.sm,
            )

            GlagolitsaButtonSize.Medium -> ButtonMetrics(
                minHeight = 40.dp,
                horizontalPadding = GlagolitsaSpacing.lg,
                verticalPadding = GlagolitsaSpacing.sm,
                textStyle = typography.labelLarge,
                iconGap = GlagolitsaSpacing.sm,
            )

            GlagolitsaButtonSize.Large -> ButtonMetrics(
                minHeight = 48.dp,
                horizontalPadding = GlagolitsaSpacing.xl,
                verticalPadding = GlagolitsaSpacing.md,
                textStyle = typography.titleSmall,
                iconGap = GlagolitsaSpacing.md,
            )
        }
    }
}

private fun resolveButtonPalette(
    style: GlagolitsaButtonStyle,
    enabled: Boolean,
    hovered: Boolean,
    pressed: Boolean,
): ButtonPalette {
    if (!enabled) {
        return when (style) {
            GlagolitsaButtonStyle.Primary -> ButtonPalette(
                container = GlagolitsaColors.AccentRedContainer.copy(alpha = 0.34f),
                content = GlagolitsaColors.TextOnAccent.copy(alpha = 0.55f),
            )

            GlagolitsaButtonStyle.Danger -> ButtonPalette(
                container = GlagolitsaColors.AccentRedContainer.copy(alpha = 0.45f),
                content = GlagolitsaColors.TextOnAccent.copy(alpha = 0.55f),
            )

            GlagolitsaButtonStyle.Success -> ButtonPalette(
                container = GlagolitsaColors.StatusSuccess.copy(alpha = 0.34f),
                content = GlagolitsaColors.TextPrimary.copy(alpha = 0.55f),
            )

            GlagolitsaButtonStyle.Secondary,
            GlagolitsaButtonStyle.Tertiary,
            -> ButtonPalette(
                container = GlagolitsaColors.Surface700.copy(alpha = 0.45f),
                content = GlagolitsaColors.TextPrimary.copy(alpha = 0.55f),
                border = if (style == GlagolitsaButtonStyle.Tertiary) {
                    GlagolitsaColors.GlassBorder.copy(alpha = 0.3f)
                } else {
                    Color.Transparent
                },
            )

            GlagolitsaButtonStyle.Ghost,
            GlagolitsaButtonStyle.Text,
            -> ButtonPalette(
                container = Color.Transparent,
                content = GlagolitsaColors.TextDisabled,
            )
        }
    }

    return when (style) {
        GlagolitsaButtonStyle.Primary -> when {
            pressed -> ButtonPalette(GlagolitsaColors.AccentRedContainerPressed, GlagolitsaColors.TextOnAccent)
            hovered -> ButtonPalette(GlagolitsaColors.AccentRedContainer, GlagolitsaColors.TextOnAccent)
            else -> ButtonPalette(GlagolitsaColors.AccentRedContainer, GlagolitsaColors.TextOnAccent)
        }

        GlagolitsaButtonStyle.Secondary -> when {
            pressed -> ButtonPalette(GlagolitsaColors.Surface800, GlagolitsaColors.TextPrimary)
            hovered -> ButtonPalette(GlagolitsaColors.Surface600, GlagolitsaColors.TextPrimary)
            else -> ButtonPalette(GlagolitsaColors.Surface700, GlagolitsaColors.TextPrimary)
        }

        GlagolitsaButtonStyle.Tertiary -> when {
            pressed -> ButtonPalette(
                container = GlagolitsaColors.Surface800,
                content = GlagolitsaColors.TextPrimary,
                border = GlagolitsaColors.GlassBorder,
            )

            hovered -> ButtonPalette(
                container = GlagolitsaColors.Surface700,
                content = GlagolitsaColors.TextPrimary,
                border = GlagolitsaColors.GlassBorder,
            )

            else -> ButtonPalette(
                container = GlagolitsaColors.Surface800.copy(alpha = 0.6f),
                content = GlagolitsaColors.TextPrimary,
                border = GlagolitsaColors.GlassBorder,
            )
        }

        GlagolitsaButtonStyle.Ghost -> when {
            pressed || hovered -> ButtonPalette(
                container = GlagolitsaColors.Surface700.copy(alpha = 0.5f),
                content = GlagolitsaColors.TextPrimary,
            )

            else -> ButtonPalette(Color.Transparent, GlagolitsaColors.TextPrimary)
        }

        GlagolitsaButtonStyle.Text -> when {
            pressed -> ButtonPalette(
                container = GlagolitsaColors.AccentRed.copy(alpha = 0.2f),
                content = GlagolitsaColors.AccentRedText,
            )

            hovered -> ButtonPalette(
                container = GlagolitsaColors.Surface700.copy(alpha = 0.45f),
                content = GlagolitsaColors.AccentRedText,
            )

            else -> ButtonPalette(Color.Transparent, GlagolitsaColors.AccentRedText)
        }

        GlagolitsaButtonStyle.Danger -> when {
            pressed -> ButtonPalette(GlagolitsaColors.AccentRedContainerPressed, GlagolitsaColors.TextOnAccent)
            hovered -> ButtonPalette(GlagolitsaColors.AccentRedContainer, GlagolitsaColors.TextOnAccent)
            else -> ButtonPalette(GlagolitsaColors.AccentRedContainer, GlagolitsaColors.TextOnAccent)
        }

        GlagolitsaButtonStyle.Success -> when {
            pressed -> ButtonPalette(GlagolitsaColors.StatusSuccessMuted, GlagolitsaColors.TextPrimary)
            hovered -> ButtonPalette(GlagolitsaColors.StatusSuccess, GlagolitsaColors.TextPrimary)
            else -> ButtonPalette(GlagolitsaColors.StatusSuccess, GlagolitsaColors.TextPrimary)
        }
    }
}

/**
 * Кнопка Design System 1.0 — стили, размеры и состояния по референсу BUTTONS.
 */
@Composable
fun GlagolitsaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    style: GlagolitsaButtonStyle = GlagolitsaButtonStyle.Primary,
    size: GlagolitsaButtonSize = GlagolitsaButtonSize.Medium,
    shape: Shape = GlagolitsaShapes.sm,
    leadingIcon: (@Composable (Color) -> Unit)? = null,
    trailingIcon: (@Composable (Color) -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val metrics = rememberButtonMetrics(size)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val isInteractive = enabled && !loading

    val palette = resolveButtonPalette(style, isInteractive, hovered, pressed)

    val containerColor by animateColorAsState(
        targetValue = palette.container,
        animationSpec = tween(ButtonAnimationMs),
        label = "buttonContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = palette.content,
        animationSpec = tween(ButtonAnimationMs),
        label = "buttonContent",
    )

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = metrics.minHeight)
            .clip(shape)
            .then(
                if (palette.border != Color.Transparent) {
                    Modifier.border(1.dp, palette.border, shape)
                } else {
                    Modifier
                },
            )
            .background(containerColor)
            .clickable(
                enabled = isInteractive,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { role = Role.Button }
            .padding(
                horizontal = metrics.horizontalPadding,
                vertical = metrics.verticalPadding,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = contentColor,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(metrics.iconGap))
            } else if (leadingIcon != null) {
                Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    leadingIcon(contentColor)
                }
                Spacer(modifier = Modifier.width(metrics.iconGap))
            }

            CompositionLocalProvider(LocalTextStyle provides metrics.textStyle.copy(color = contentColor)) {
                content()
            }

            if (!loading && trailingIcon != null) {
                Spacer(modifier = Modifier.width(metrics.iconGap))
                Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    trailingIcon(contentColor)
                }
            }
        }
    }
}

/** Кнопка только с иконкой — квадратная, реф BUTTONS. */
@Composable
fun GlagolitsaIconButton(
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: GlagolitsaButtonStyle = GlagolitsaButtonStyle.Secondary,
    size: GlagolitsaButtonSize = GlagolitsaButtonSize.Medium,
) {
    val buttonSize = when (size) {
        GlagolitsaButtonSize.Small -> 32.dp
        GlagolitsaButtonSize.Medium -> 40.dp
        GlagolitsaButtonSize.Large -> 48.dp
    }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val palette = resolveButtonPalette(style, enabled, hovered, pressed)
    val shape = GlagolitsaShapes.sm

    val containerColor by animateColorAsState(
        targetValue = palette.container,
        animationSpec = tween(ButtonAnimationMs),
        label = "iconButtonContainer",
    )

    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(shape)
            .then(
                if (palette.border != Color.Transparent) {
                    Modifier.border(1.dp, palette.border, shape)
                } else {
                    Modifier
                },
            )
            .background(containerColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        icon(palette.content)
    }
}

/** Навигационные кнопки: Назад · Закрыть · Готово · Далее. */
@Composable
fun GlagolitsaNavButton(
    kind: GlagolitsaNavButtonKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    when (kind) {
        GlagolitsaNavButtonKind.Back -> GlagolitsaButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            style = GlagolitsaButtonStyle.Ghost,
            size = GlagolitsaButtonSize.Medium,
            leadingIcon = { GlagolitsaButtonBackIcon(tint = it) },
        ) {
            Text(label ?: "Назад")
        }

        GlagolitsaNavButtonKind.Close -> GlagolitsaButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            style = GlagolitsaButtonStyle.Ghost,
            size = GlagolitsaButtonSize.Medium,
            leadingIcon = { GlagolitsaButtonCloseIcon(tint = it) },
        ) {
            Text(label ?: "Закрыть")
        }

        GlagolitsaNavButtonKind.Done -> GlagolitsaButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            style = GlagolitsaButtonStyle.Ghost,
            size = GlagolitsaButtonSize.Medium,
            leadingIcon = { GlagolitsaButtonCheckIcon(tint = it) },
        ) {
            Text(label ?: "Готово")
        }

        GlagolitsaNavButtonKind.Next -> GlagolitsaButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            style = GlagolitsaButtonStyle.Ghost,
            size = GlagolitsaButtonSize.Medium,
            trailingIcon = { GlagolitsaButtonNextIcon(tint = it) },
        ) {
            Text(label ?: "Далее")
        }
    }
}

/** Плавающая кнопка действия (FAB) — красный круг с «+». */
@Composable
fun GlagolitsaFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: GlagolitsaButtonStyle = GlagolitsaButtonStyle.Primary,
    icon: @Composable (Color) -> Unit = { GlagolitsaButtonPlusIcon(tint = it) },
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val palette = resolveButtonPalette(style, enabled, hovered, pressed)

    val containerColor by animateColorAsState(
        targetValue = palette.container,
        animationSpec = tween(ButtonAnimationMs),
        label = "fabContainer",
    )

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        icon(palette.content)
    }
}
