// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.profile_about_mark
import glagolitsamobile.shared.generated.resources.profile_devices_bot
import glagolitsamobile.shared.generated.resources.profile_favorites_star
import glagolitsamobile.shared.generated.resources.profile_help_mark
import glagolitsamobile.shared.generated.resources.profile_language_mark
import glagolitsamobile.shared.generated.resources.profile_notifications_bell
import glagolitsamobile.shared.generated.resources.profile_protected_history_shield
import glagolitsamobile.shared.generated.resources.profile_recent_calls_phone
import glagolitsamobile.shared.generated.resources.profile_storage_chest
import glagolitsamobile.shared.generated.resources.profile_theme_lamp
import glagolitsamobile.shared.generated.resources.profile_theme_ornament
import glagolitsamobile.shared.generated.resources.profile_update_mark
import glagolitsamobile.shared.generated.resources.split_secret_knot_outline
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

private val MenuIconStroke = 2.8.dp
/** Default box for profile list/grid icons — keep in sync with ProfileMenuRow. */
private val MenuIconSize = 34.dp
internal val LocalProfileMenuIconSize = compositionLocalOf { MenuIconSize }

/** All profile menu asset icons share one size and [tint] (standard OrnamentGold from the slot). */
@Composable
private fun ProfileMenuAssetIcon(
    resource: DrawableResource,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = LocalProfileMenuIconSize.current,
) {
    Image(
        painter = painterResource(resource),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(tint),
        // Fixed square box — all menu glyphs share the same footprint.
        modifier = modifier.size(size),
    )
}

@Composable
fun ProfileMenuStarIcon(tint: Color, modifier: Modifier = Modifier) {
    ProfileMenuAssetIcon(
        resource = Res.drawable.profile_favorites_star,
        tint = tint,
        modifier = modifier,
    )
}

@Composable
fun ProfileMenuPhoneIcon(tint: Color, modifier: Modifier = Modifier) {
    ProfileMenuAssetIcon(
        resource = Res.drawable.profile_recent_calls_phone,
        tint = tint,
        modifier = modifier,
    )
}

@Composable
fun ProfileMenuDevicesIcon(tint: Color, modifier: Modifier = Modifier) {
    ProfileMenuAssetIcon(
        resource = Res.drawable.profile_devices_bot,
        tint = tint,
        modifier = modifier,
    )
}

@Composable
fun ProfileMenuNoiseIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(LocalProfileMenuIconSize.current)) {
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        val mids = listOf(0.28f, 0.52f, 0.86f, 0.40f, 0.70f, 0.34f)
        val gap = size.width * 0.10f
        val startX = size.width * 0.16f
        mids.forEachIndexed { index, heightFrac ->
            val x = startX + index * gap
            val half = size.height * heightFrac * 0.28f
            drawLine(
                color = tint,
                start = Offset(x, size.height * 0.50f - half),
                end = Offset(x, size.height * 0.50f + half),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }
        drawLine(
            color = tint,
            start = Offset(size.width * 0.18f, size.height * 0.78f),
            end = Offset(size.width * 0.82f, size.height * 0.22f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun ProfileMenuStorageIcon(tint: Color, modifier: Modifier = Modifier) {
    ProfileMenuAssetIcon(
        resource = Res.drawable.profile_storage_chest,
        tint = tint,
        modifier = modifier,
    )
}

@Composable
fun ProfileMenuSplitSecretIcon(tint: Color, modifier: Modifier = Modifier) {
    ProfileMenuAssetIcon(
        resource = Res.drawable.split_secret_knot_outline,
        tint = tint,
        modifier = modifier,
    )
}

@Composable
fun ProfileMenuLockIcon(tint: Color, modifier: Modifier = Modifier) {
    ProfileMenuAssetIcon(
        resource = Res.drawable.profile_protected_history_shield,
        tint = tint,
        modifier = modifier,
    )
}

@Composable
fun ProfileMenuBellIcon(tint: Color, modifier: Modifier = Modifier) {
    ProfileMenuAssetIcon(
        resource = Res.drawable.profile_notifications_bell,
        tint = tint,
        modifier = modifier,
    )
}

@Composable
fun ProfileMenuUpdateIcon(tint: Color, modifier: Modifier = Modifier) {
    ProfileMenuAssetIcon(
        resource = Res.drawable.profile_update_mark,
        tint = tint,
        modifier = modifier,
    )
}

/** Oil lamp / light — row «Тема» (light/dark). */
@Composable
fun ProfileMenuThemeIcon(tint: Color, modifier: Modifier = Modifier) {
    ProfileMenuAssetIcon(
        resource = Res.drawable.profile_theme_lamp,
        tint = tint,
        modifier = modifier,
    )
}

/** Decorative ornament — row «Оформление». */
@Composable
fun ProfileMenuAppearanceIcon(tint: Color, modifier: Modifier = Modifier) {
    ProfileMenuAssetIcon(
        resource = Res.drawable.profile_theme_ornament,
        tint = tint,
        modifier = modifier,
    )
}

@Composable
fun ProfileMenuGlobeIcon(tint: Color, modifier: Modifier = Modifier) {
    ProfileMenuAssetIcon(
        resource = Res.drawable.profile_language_mark,
        tint = tint,
        modifier = modifier,
    )
}

/** Broadcast / channel mark for profile «Мои каналы» rows (compact default). */
@Composable
fun ProfileMenuChannelIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(LocalProfileMenuIconSize.current)) {
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.18f, size.height * 0.34f),
            size = Size(size.width * 0.22f, size.height * 0.32f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = stroke,
        )
        val cone = Path().apply {
            moveTo(size.width * 0.40f, size.height * 0.36f)
            lineTo(size.width * 0.62f, size.height * 0.22f)
            lineTo(size.width * 0.62f, size.height * 0.78f)
            lineTo(size.width * 0.40f, size.height * 0.64f)
            close()
        }
        drawPath(path = cone, color = tint, style = stroke)
        drawArc(
            color = tint,
            startAngle = -40f,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = Offset(size.width * 0.52f, size.height * 0.28f),
            size = Size(size.width * 0.28f, size.height * 0.44f),
            style = stroke,
        )
        drawArc(
            color = tint,
            startAngle = -40f,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = Offset(size.width * 0.60f, size.height * 0.22f),
            size = Size(size.width * 0.32f, size.height * 0.56f),
            style = stroke,
        )
    }
}

@Composable
fun ProfileMenuHelpIcon(tint: Color, modifier: Modifier = Modifier) {
    ProfileMenuAssetIcon(
        resource = Res.drawable.profile_help_mark,
        tint = tint,
        modifier = modifier,
    )
}

@Composable
fun ProfileMenuInfoIcon(tint: Color, modifier: Modifier = Modifier) {
    ProfileMenuAssetIcon(
        resource = Res.drawable.profile_about_mark,
        tint = tint,
        modifier = modifier,
    )
}

@Composable
fun ProfileMenuLogoutIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(LocalProfileMenuIconSize.current)) {
        val stroke = Stroke(width = MenuIconStroke.toPx(), cap = StrokeCap.Round)
        val left = size.width * 0.28f
        val top = size.height * 0.24f
        val bottom = size.height * 0.76f
        drawLine(
            color = tint,
            start = Offset(left, top),
            end = Offset(left, bottom),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawArc(
            color = tint,
            startAngle = -35f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = Offset(size.width * 0.34f, size.height * 0.18f),
            size = Size(size.width * 0.44f, size.height * 0.64f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.52f, size.height * 0.5f),
            end = Offset(size.width * 0.76f, size.height * 0.5f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.68f, size.height * 0.38f),
            end = Offset(size.width * 0.76f, size.height * 0.5f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.68f, size.height * 0.62f),
            end = Offset(size.width * 0.76f, size.height * 0.5f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun ProfileMenuChevronIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = Stroke(width = MenuIconStroke.toPx(), cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.34f, size.height * 0.24f),
            end = Offset(size.width * 0.66f, size.height * 0.5f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.66f, size.height * 0.5f),
            end = Offset(size.width * 0.34f, size.height * 0.76f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}
