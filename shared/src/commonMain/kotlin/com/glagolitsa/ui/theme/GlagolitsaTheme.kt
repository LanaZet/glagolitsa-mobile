// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Active token table. Getters read [AppThemeController.mode] so Compose
 * recomposes screens when the profile toggle flips.
 *
 * Light values live in [GlagolitsaPalettes.Light] — edit that slot later.
 */
object GlagolitsaColors {
    private val palette: GlagolitsaColorScheme
        get() = GlagolitsaPalettes.forMode(AppThemeController.mode)

    val IsDark: Boolean get() = palette.isDark

    val PaletteDarkBackground: Color get() = palette.paletteDarkBackground
    val PaletteSurface: Color get() = palette.paletteSurface
    val PaletteSecondary: Color get() = palette.paletteSecondary
    val PaletteLight: Color get() = palette.paletteLight
    val PaletteGold: Color get() = palette.paletteGold
    val PaletteAccent: Color get() = palette.paletteAccent

    val Background950: Color get() = palette.background950
    val Background900: Color get() = palette.background900
    val Background850: Color get() = palette.background850
    val Surface800: Color get() = palette.surface800
    val Surface700: Color get() = palette.surface700
    val Surface600: Color get() = palette.surface600
    val Surface500: Color get() = palette.surface500

    val AccentRed: Color get() = palette.accentRed
    val AccentRedLight: Color get() = palette.accentRedLight
    val AccentRedBright: Color get() = palette.accentRedBright
    val AccentRedSoft: Color get() = palette.accentRedSoft
    val AccentRedContainer: Color get() = palette.accentRedContainer
    val AccentRedContainerPressed: Color get() = palette.accentRedContainerPressed
    val AccentRedText: Color get() = palette.accentRedText
    val AccentRedPale: Color get() = palette.accentRedPale
    val TextOnAccent: Color get() = palette.textOnAccent
    val TextOnGold: Color get() = palette.textOnGold

    val OrnamentRed: Color get() = palette.ornamentRed
    val OrnamentGold: Color get() = palette.ornamentGold

    val StatusSuccess: Color get() = palette.statusSuccess
    val StatusSuccessMuted: Color get() = palette.statusSuccessMuted
    val StatusWarning: Color get() = palette.statusWarning
    val StatusError: Color get() = palette.statusError
    val StatusInfo: Color get() = palette.statusInfo
    val PresenceOnlineBright: Color get() = palette.presenceOnlineBright

    val AuthBackground: Color get() = palette.authBackground
    val InputShell: Color get() = palette.inputShell
    val InputCavityTop: Color get() = palette.inputCavityTop
    val InputCavityMid: Color get() = palette.inputCavityMid
    val InputCavityBottom: Color get() = palette.inputCavityBottom
    val InputBorderDefault: Color get() = palette.inputBorderDefault
    val InputBorderHover: Color get() = palette.inputBorderHover
    val InputFocusBorder: Color get() = palette.inputFocusBorder
    val InputFocusGlow: Color get() = palette.inputFocusGlow
    val InputFocusHalo: Color get() = palette.inputFocusHalo
    val InputPlaceholder: Color get() = palette.inputPlaceholder
    val InputSuccessBright: Color get() = palette.inputSuccessBright
    val InputErrorText: Color get() = palette.inputErrorText

    val TextPrimary: Color get() = palette.textPrimary
    val InputCursor: Color get() = palette.inputCursor
    val TextSecondary: Color get() = palette.textSecondary
    val TextTertiary: Color get() = palette.textTertiary
    val TextDisabled: Color get() = palette.textDisabled

    val FilterChipIdle: Color get() = palette.filterChipIdle
    val FilterChipActive: Color get() = palette.filterChipActive
    val AvatarGradientTop: Color get() = palette.avatarGradientTop
    val AvatarGradientBottom: Color get() = palette.avatarGradientBottom

    val GlassFill: Color get() = palette.glassFill
    val GlassBorder: Color get() = palette.glassBorder
    val GlassSelected: Color get() = palette.glassSelected
    val SurfacePanel: Color get() = palette.surfacePanel
    val SurfacePanelStrong: Color get() = palette.surfacePanelStrong
    val SurfaceFloating: Color get() = palette.surfaceFloating
    val SurfacePressed: Color get() = palette.surfacePressed
    val SurfaceDisabled: Color get() = palette.surfaceDisabled
    val BorderSubtle: Color get() = palette.borderSubtle
    val BorderMuted: Color get() = palette.borderMuted
    val DividerSubtle: Color get() = palette.dividerSubtle
    val SelectedTint: Color get() = palette.selectedTint
    val DisabledContent: Color get() = palette.disabledContent

    val GlassNavBorder: Brush get() = palette.glassNavBorder
    val GlassNavFill: Brush get() = palette.glassNavFill
    val GlassNavDepth: Brush get() = palette.glassNavDepth
    val GlassNavHighlight: Brush get() = palette.glassNavHighlight
    val GlassPillSelectedBubble: Brush get() = palette.glassPillSelectedBubble
    val GlassProfileFill: Brush get() = palette.glassProfileFill

    val PresenceOnline: Color get() = palette.presenceOnline
    val PresenceAway: Color get() = palette.presenceAway
    val PresenceDnd: Color get() = palette.presenceDnd
    val PresenceOffline: Color get() = palette.presenceOffline

    val ChatBubbleOwn: Color get() = palette.chatBubbleOwn
    val ChatBubbleOwnBorder: Color get() = palette.chatBubbleOwnBorder
    val ChatBubbleOther: Color get() = palette.chatBubbleOther
    val ChatBubbleOtherBorder: Color get() = palette.chatBubbleOtherBorder
    val ChatWallpaperTop: Color get() = palette.chatWallpaperTop
    val ChatWallpaperMid: Color get() = palette.chatWallpaperMid
    val ChatWallpaperBottom: Color get() = palette.chatWallpaperBottom
    val ChatWallpaperTopDeep: Color get() = palette.chatWallpaperTopDeep
    val ChatWallpaperMidDeep: Color get() = palette.chatWallpaperMidDeep
    val ChatWallpaperBottomDeep: Color get() = palette.chatWallpaperBottomDeep
    val ChatBubbleOwnTop: Color get() = palette.chatBubbleOwnTop
    val ChatBubbleOwnMid: Color get() = palette.chatBubbleOwnMid
    val ChatBubbleOwnBottom: Color get() = palette.chatBubbleOwnBottom
    val ChatBubbleOtherTop: Color get() = palette.chatBubbleOtherTop
    val ChatBubbleOtherBottom: Color get() = palette.chatBubbleOtherBottom
    val ChatChromeTop: Color get() = palette.chatChromeTop
    val ChatChromeBottom: Color get() = palette.chatChromeBottom
    val ChatFieldTop: Color get() = palette.chatFieldTop
    val ChatFieldBottom: Color get() = palette.chatFieldBottom
    val ChatBubbleOwnFill: Brush get() = palette.chatBubbleOwnFill
    val ChatBubbleOtherFill: Brush get() = palette.chatBubbleOtherFill
    val ChatChromeFill: Brush get() = palette.chatChromeFill
    val ChatFieldFill: Brush get() = palette.chatFieldFill
    val OverlayHairline: Color get() = palette.overlayHairline
    val OverlayHairlineSoft: Color get() = palette.overlayHairlineSoft
    val InsetOnOwn: Color get() = palette.insetOnOwn
    val InsetOnOther: Color get() = palette.insetOnOther

    val ScreenGradient: Brush get() = palette.screenGradient
    val AuthScreenGradient: Brush get() = palette.authScreenGradient

    val BackgroundTop: Color get() = palette.backgroundTop
    val BackgroundBottom: Color get() = palette.backgroundBottom
    val TextMuted: Color get() = palette.textMuted
}

private fun materialScheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = GlagolitsaPalettes.Dark.accentRedContainer,
        onPrimary = GlagolitsaPalettes.Dark.textOnAccent,
        secondary = GlagolitsaPalettes.Dark.surface600,
        onSecondary = GlagolitsaPalettes.Dark.textPrimary,
        tertiary = GlagolitsaPalettes.Dark.ornamentGold,
        onTertiary = GlagolitsaPalettes.Dark.textOnGold,
        background = GlagolitsaPalettes.Dark.background950,
        onBackground = GlagolitsaPalettes.Dark.textPrimary,
        surface = GlagolitsaPalettes.Dark.surface800,
        onSurface = GlagolitsaPalettes.Dark.textPrimary,
        surfaceVariant = GlagolitsaPalettes.Dark.surface700,
        onSurfaceVariant = GlagolitsaPalettes.Dark.textSecondary,
        error = GlagolitsaPalettes.Dark.statusError,
        onError = Color.White,
        outline = GlagolitsaPalettes.Dark.inputBorderDefault,
        outlineVariant = GlagolitsaPalettes.Dark.inputBorderHover,
    )
} else {
    lightColorScheme(
        primary = GlagolitsaPalettes.Light.accentRedContainer,
        onPrimary = GlagolitsaPalettes.Light.textOnAccent,
        secondary = GlagolitsaPalettes.Light.surface600,
        onSecondary = GlagolitsaPalettes.Light.textPrimary,
        tertiary = GlagolitsaPalettes.Light.ornamentGold,
        onTertiary = GlagolitsaPalettes.Light.textOnGold,
        background = GlagolitsaPalettes.Light.background950,
        onBackground = GlagolitsaPalettes.Light.textPrimary,
        surface = GlagolitsaPalettes.Light.surface800,
        onSurface = GlagolitsaPalettes.Light.textPrimary,
        surfaceVariant = GlagolitsaPalettes.Light.surface700,
        onSurfaceVariant = GlagolitsaPalettes.Light.textSecondary,
        error = GlagolitsaPalettes.Light.statusError,
        onError = Color.White,
        outline = GlagolitsaPalettes.Light.inputBorderDefault,
        outlineVariant = GlagolitsaPalettes.Light.inputBorderHover,
    )
}

@Composable
fun GlagolitsaTheme(
    dark: Boolean = AppThemeController.isDark,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = materialScheme(dark),
        typography = glagolitsaTypography(),
        content = content,
    )
}
