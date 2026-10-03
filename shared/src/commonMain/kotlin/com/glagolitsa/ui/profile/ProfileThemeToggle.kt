// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.theme_toggle_dark
import glagolitsamobile.shared.generated.resources.theme_toggle_light
import org.jetbrains.compose.resources.painterResource

// Same layout box as GlagolitsaSwitch; assets use the same 195×90 canvas + padding
// so the pill reads the same size as network-status / chat toggles.
private val ThemeToggleWidth = 68.dp
private val ThemeToggleHeight = 40.dp

/**
 * Light/dark theme switch for the profile menu.
 *
 * Mapping:
 * - [dark] = true  → night/moon pill (dark slot)
 * - [dark] = false → day/sun pill (light slot)
 *
 * @param interactive when false, only draws the asset (row handles the tap).
 */
@Composable
fun ProfileThemeToggle(
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // Row owns the tap target when interactive=false.
    val base = modifier
        .size(width = ThemeToggleWidth, height = ThemeToggleHeight)
        .semantics {
            role = Role.Switch
            stateDescription = if (dark) "Тёмная" else "Светлая"
        }
    Image(
        painter = painterResource(
            if (dark) Res.drawable.theme_toggle_dark else Res.drawable.theme_toggle_light,
        ),
        contentDescription = if (dark) "Тёмная тема" else "Светлая тема",
        // Fit + switch-sized canvas: pill matches GlagolitsaSwitch visual scale.
        contentScale = ContentScale.Fit,
        modifier = if (interactive) {
            base.clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onClick = { onDarkChange(!dark) },
            )
        } else {
            base
        },
    )
}
