// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Named color tokens shared by dark and light slots.
 * Light values are a first-pass parchment slot — refine later without
 * touching call sites that go through [GlagolitsaColors].
 */
class GlagolitsaColorScheme(
    val isDark: Boolean,
    val paletteDarkBackground: Color,
    val paletteSurface: Color,
    val paletteSecondary: Color,
    val paletteLight: Color,
    val paletteGold: Color,
    val paletteAccent: Color,
    val background950: Color,
    val background900: Color,
    val background850: Color,
    val surface800: Color,
    val surface700: Color,
    val surface600: Color,
    val surface500: Color,
    val accentRed: Color,
    val accentRedLight: Color,
    val accentRedSoft: Color,
    val accentRedContainer: Color,
    val accentRedContainerPressed: Color,
    val accentRedText: Color,
    val accentRedPale: Color,
    val textOnAccent: Color,
    val textOnGold: Color,
    val statusSuccess: Color,
    val statusSuccessMuted: Color,
    val statusInfo: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val inputPlaceholder: Color,
    val glassFill: Color,
    val glassBorder: Color,
    val glassSelected: Color,
    val surfacePanel: Color,
    val surfacePanelStrong: Color,
    val surfaceFloating: Color,
    val surfacePressed: Color,
    val surfaceDisabled: Color,
    val borderSubtle: Color,
    val borderMuted: Color,
    val dividerSubtle: Color,
    val selectedTint: Color,
    val disabledContent: Color,
    val inputBorderDefault: Color,
    val inputBorderHover: Color,
    val inputFocusBorder: Color,
    val inputFocusGlow: Color,
    val inputFocusHalo: Color,
    val chatBubbleOwn: Color,
    val chatBubbleOwnBorder: Color,
    val chatBubbleOther: Color,
    val chatBubbleOtherBorder: Color,
    val chatWallpaperTop: Color,
    val chatWallpaperMid: Color,
    val chatWallpaperBottom: Color,
    val chatWallpaperTopDeep: Color,
    val chatWallpaperMidDeep: Color,
    val chatWallpaperBottomDeep: Color,
    val chatBubbleOwnTop: Color,
    val chatBubbleOwnMid: Color,
    val chatBubbleOwnBottom: Color,
    val chatBubbleOtherTop: Color,
    val chatBubbleOtherBottom: Color,
    val chatChromeTop: Color,
    val chatChromeBottom: Color,
    val chatFieldTop: Color,
    val chatFieldBottom: Color,
) {
    val accentRedBright: Color get() = accentRedLight
    val ornamentRed: Color get() = accentRed
    val ornamentGold: Color get() = paletteGold
    val statusWarning: Color get() = paletteGold
    val statusError: Color get() = paletteAccent
    val presenceOnlineBright: Color get() = statusSuccess
    val authBackground: Color get() = background950
    val inputShell: Color get() = background900
    val inputCavityTop: Color get() = surface800
    val inputCavityMid: Color get() = background850
    val inputCavityBottom: Color get() = background900
    val inputSuccessBright: Color get() = statusSuccess
    val inputErrorText: Color get() = statusError
    val inputCursor: Color get() = textPrimary
    val filterChipIdle: Color get() = surface700
    val filterChipActive: Color get() = ornamentGold
    val avatarGradientTop: Color get() = surface600
    val avatarGradientBottom: Color get() = surface800
    val presenceOnline: Color get() = statusSuccessMuted
    val presenceAway: Color get() = statusWarning
    val presenceDnd: Color get() = accentRedSoft
    val presenceOffline: Color get() = textTertiary
    val backgroundTop: Color get() = background950
    val backgroundBottom: Color get() = surface800
    val textMuted: Color get() = textTertiary

    val glassNavBorder: Brush = Brush.verticalGradient(
        colors = listOf(
            paletteLight.copy(alpha = if (isDark) 0.24f else 0.36f),
            paletteGold.copy(alpha = if (isDark) 0.12f else 0.22f),
            paletteLight.copy(alpha = if (isDark) 0.05f else 0.10f),
        ),
    )
    val glassNavFill: Brush = Brush.linearGradient(
        colors = listOf(
            surface700.copy(alpha = if (isDark) 0.84f else 0.92f),
            surface800.copy(alpha = if (isDark) 0.80f else 0.94f),
            background950.copy(alpha = if (isDark) 0.92f else 0.96f),
        ),
    )
    val glassNavDepth: Brush = Brush.horizontalGradient(
        colors = listOf(
            Color.Black.copy(alpha = if (isDark) 0.22f else 0.06f),
            Color.Transparent,
            paletteLight.copy(alpha = if (isDark) 0.05f else 0.10f),
        ),
    )
    val glassNavHighlight: Brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to paletteLight.copy(alpha = if (isDark) 0.10f else 0.35f),
            0.5f to paletteGold.copy(alpha = if (isDark) 0.03f else 0.08f),
            1f to Color.Transparent,
        ),
    )
    val glassPillSelectedBubble: Brush = Brush.radialGradient(
        colors = listOf(
            surface600.copy(alpha = 0.68f),
            surface800.copy(alpha = 0.52f),
        ),
    )
    val glassProfileFill: Brush = Brush.radialGradient(
        colors = listOf(
            paletteLight.copy(alpha = if (isDark) 0.08f else 0.18f),
            paletteGold.copy(alpha = if (isDark) 0.02f else 0.08f),
            Color.Transparent,
        ),
    )
    val screenGradient: Brush = Brush.verticalGradient(
        colors = listOf(background950, background900, background850),
    )
    val authScreenGradient: Brush = Brush.verticalGradient(
        colors = listOf(surface800, background950, background900),
    )
    val chatBubbleOwnFill: Brush = Brush.linearGradient(
        colors = listOf(chatBubbleOwnTop, chatBubbleOwnMid, chatBubbleOwnBottom),
    )
    val chatBubbleOtherFill: Brush = Brush.linearGradient(
        colors = listOf(
            chatBubbleOtherTop.copy(alpha = if (isDark) 0.94f else 0.98f),
            chatBubbleOtherBottom.copy(alpha = if (isDark) 0.96f else 1f),
        ),
    )
    val chatChromeFill: Brush = Brush.verticalGradient(
        colors = listOf(
            chatChromeTop.copy(alpha = if (isDark) 0.94f else 0.96f),
            chatChromeBottom.copy(alpha = if (isDark) 0.98f else 0.98f),
        ),
    )
    val chatFieldFill: Brush = Brush.verticalGradient(
        colors = listOf(
            chatFieldTop.copy(alpha = if (isDark) 0.98f else 0.98f),
            chatFieldBottom.copy(alpha = if (isDark) 0.96f else 1f),
        ),
    )
    val overlayHairline: Color = if (isDark) {
        Color.White.copy(alpha = 0.13f)
    } else {
        textPrimary.copy(alpha = 0.12f)
    }
    val overlayHairlineSoft: Color = if (isDark) {
        Color.White.copy(alpha = 0.08f)
    } else {
        textPrimary.copy(alpha = 0.08f)
    }
    val insetOnOwn: Color = Color.Black.copy(alpha = if (isDark) 0.13f else 0.10f)
    val insetOnOther: Color = if (isDark) {
        Color.White.copy(alpha = 0.06f)
    } else {
        textPrimary.copy(alpha = 0.06f)
    }
}

object GlagolitsaPalettes {
    val Dark: GlagolitsaColorScheme = GlagolitsaColorScheme(
        isDark = true,
        paletteDarkBackground = Color(0xFF0D141C),
        paletteSurface = Color(0xFF151E27),
        paletteSecondary = Color(0xFF1F2935),
        paletteLight = Color(0xFFE7DCC6),
        paletteGold = Color(0xFFD4A373),
        paletteAccent = Color(0xFFBA5624),
        background950 = Color(0xFF0D141C),
        background900 = Color(0xFF101923),
        background850 = Color(0xFF151E27),
        surface800 = Color(0xFF151E27),
        surface700 = Color(0xFF1F2935),
        surface600 = Color(0xFF2A3642),
        surface500 = Color(0xFF3B4A58),
        accentRed = Color(0xFFBA5624),
        accentRedLight = Color(0xFFD75A4D),
        accentRedSoft = Color(0xFFB9362B),
        accentRedContainer = Color(0xFFA92F27),
        accentRedContainerPressed = Color(0xFF9F2D25),
        accentRedText = Color(0xFFE06D61),
        accentRedPale = Color(0xFFF1B5AB),
        textOnAccent = Color.White,
        textOnGold = Color(0xFF0D141C),
        statusSuccess = Color(0xFF2FA45D),
        statusSuccessMuted = Color(0xFF258A4E),
        statusInfo = Color(0xFF5E8FB8),
        textPrimary = Color(0xFFE7DCC6),
        textSecondary = Color(0xFFC7B89D),
        textTertiary = Color(0xFF918775),
        textDisabled = Color(0xFF625E58),
        inputPlaceholder = Color(0xFF918775),
        glassFill = Color(0xFF151E27).copy(alpha = 0.78f),
        glassBorder = Color(0xFFE7DCC6).copy(alpha = 0.12f),
        glassSelected = Color(0xFF1F2935).copy(alpha = 0.90f),
        surfacePanel = Color(0xFF151E27).copy(alpha = 0.72f),
        surfacePanelStrong = Color(0xFF151E27).copy(alpha = 0.88f),
        surfaceFloating = Color(0xFF101923).copy(alpha = 0.96f),
        surfacePressed = Color(0xFF2A3642).copy(alpha = 0.36f),
        surfaceDisabled = Color(0xFF1F2935).copy(alpha = 0.32f),
        borderSubtle = Color(0xFFE7DCC6).copy(alpha = 0.12f),
        borderMuted = Color(0xFF3B4A58).copy(alpha = 0.34f),
        dividerSubtle = Color(0xFF3B4A58).copy(alpha = 0.26f),
        selectedTint = Color(0xFFD4A373).copy(alpha = 0.13f),
        disabledContent = Color(0xFFE7DCC6).copy(alpha = 0.55f),
        inputBorderDefault = Color(0xFF1F2935),
        inputBorderHover = Color(0xFF3B4A58),
        inputFocusBorder = Color(0xFFD4A373),
        inputFocusGlow = Color(0xFFD4A373).copy(alpha = 0.46f),
        inputFocusHalo = Color(0xFFE7DCC6).copy(alpha = 0.28f),
        chatBubbleOwn = Color(0xFF3A2B27),
        chatBubbleOwnBorder = Color(0xFFD4A373).copy(alpha = 0.12f),
        chatBubbleOther = Color(0xFF1A2833),
        chatBubbleOtherBorder = Color.White.copy(alpha = 0.13f),
        chatWallpaperTop = Color(0xFF26323D),
        chatWallpaperMid = Color(0xFF17222D),
        chatWallpaperBottom = Color(0xFF0F1821),
        chatWallpaperTopDeep = Color(0xFF05080B),
        chatWallpaperMidDeep = Color(0xFF070B10),
        chatWallpaperBottomDeep = Color(0xFF020407),
        chatBubbleOwnTop = Color(0xFF4A332E),
        chatBubbleOwnMid = Color(0xFF3A2B27),
        chatBubbleOwnBottom = Color(0xFF2B2321),
        chatBubbleOtherTop = Color(0xFF21303B),
        chatBubbleOtherBottom = Color(0xFF172430),
        chatChromeTop = Color(0xFF142838),
        chatChromeBottom = Color(0xFF0D1A26),
        chatFieldTop = Color(0xFF08151F),
        chatFieldBottom = Color(0xFF0B1B28),
    )

    /**
     * Light slot: warm parchment + wine ink from the brand swatch.
     * Own bubbles use the same wine; incoming stay cream. Dark slot is untouched.
     */
    val Light: GlagolitsaColorScheme = GlagolitsaColorScheme(
        isDark = false,
        paletteDarkBackground = Color(0xFFF4EDE1),
        paletteSurface = Color(0xFFFAF4EA),
        paletteSecondary = Color(0xFFE8DCC8),
        paletteLight = Color(0xFF4E1C24),
        paletteGold = Color(0xFFB7843F),
        paletteAccent = Color(0xFFBA5624),
        background950 = Color(0xFFF4EDE1),
        background900 = Color(0xFFEDE4D4),
        background850 = Color(0xFFE6D9C4),
        surface800 = Color(0xFFFAF4EA),
        surface700 = Color(0xFFF0E4D0),
        surface600 = Color(0xFFE0D0B6),
        surface500 = Color(0xFFC8B48E),
        accentRed = Color(0xFFBA5624),
        accentRedLight = Color(0xFFC85A3C),
        accentRedSoft = Color(0xFFA33A2A),
        accentRedContainer = Color(0xFFBA5624),
        accentRedContainerPressed = Color(0xFF9F2D25),
        accentRedText = Color(0xFF9A3A28),
        accentRedPale = Color(0xFFE8B4A6),
        textOnAccent = Color.White,
        textOnGold = Color(0xFF4E1C24),
        statusSuccess = Color(0xFF2A8A52),
        statusSuccessMuted = Color(0xFF237446),
        statusInfo = Color(0xFF3E6F96),
        textPrimary = Color(0xFF4E1C24),
        textSecondary = Color(0xFF6E3840),
        textTertiary = Color(0xFF8C5C62),
        textDisabled = Color(0xFFB08A8E),
        inputPlaceholder = Color(0xFF8C5C62),
        glassFill = Color(0xFFFAF4EA).copy(alpha = 0.88f),
        glassBorder = Color(0xFF4E1C24).copy(alpha = 0.12f),
        glassSelected = Color(0xFFE8DCC8).copy(alpha = 0.92f),
        surfacePanel = Color(0xFFFAF4EA).copy(alpha = 0.86f),
        surfacePanelStrong = Color(0xFFFAF4EA).copy(alpha = 0.94f),
        surfaceFloating = Color(0xFFF4EDE1).copy(alpha = 0.97f),
        surfacePressed = Color(0xFFC8B48E).copy(alpha = 0.28f),
        surfaceDisabled = Color(0xFFE8DCC8).copy(alpha = 0.40f),
        borderSubtle = Color(0xFF4E1C24).copy(alpha = 0.12f),
        borderMuted = Color(0xFFC8B48E).copy(alpha = 0.55f),
        dividerSubtle = Color(0xFFC8B48E).copy(alpha = 0.42f),
        selectedTint = Color(0xFFB7843F).copy(alpha = 0.16f),
        disabledContent = Color(0xFF4E1C24).copy(alpha = 0.42f),
        inputBorderDefault = Color(0xFFD4C4A8),
        inputBorderHover = Color(0xFFC8B48E),
        inputFocusBorder = Color(0xFFB7843F),
        inputFocusGlow = Color(0xFFB7843F).copy(alpha = 0.28f),
        inputFocusHalo = Color(0xFFB7843F).copy(alpha = 0.16f),
        chatBubbleOwn = Color(0xFF5C222C),
        chatBubbleOwnBorder = Color(0xFF3A141A).copy(alpha = 0.28f),
        chatBubbleOther = Color(0xFFF3E8D6),
        chatBubbleOtherBorder = Color(0xFF4E1C24).copy(alpha = 0.10f),
        chatWallpaperTop = Color(0xFFF8F1E6),
        chatWallpaperMid = Color(0xFFF0E6D4),
        chatWallpaperBottom = Color(0xFFE6D8C0),
        chatWallpaperTopDeep = Color(0xFFE4D4B8),
        chatWallpaperMidDeep = Color(0xFFD4C2A0),
        chatWallpaperBottomDeep = Color(0xFFC8B48E),
        chatBubbleOwnTop = Color(0xFF6E2A36),
        chatBubbleOwnMid = Color(0xFF5C222C),
        chatBubbleOwnBottom = Color(0xFF4A1820),
        chatBubbleOtherTop = Color(0xFFF7F0E4),
        chatBubbleOtherBottom = Color(0xFFEDE3D0),
        chatChromeTop = Color(0xFFFAF4EA),
        chatChromeBottom = Color(0xFFF0E4D0),
        chatFieldTop = Color(0xFFF7F0E4),
        chatFieldBottom = Color(0xFFEDE4D4),
    )

    fun forMode(mode: AppThemeMode): GlagolitsaColorScheme =
        if (mode.isDark) Dark else Light
}
