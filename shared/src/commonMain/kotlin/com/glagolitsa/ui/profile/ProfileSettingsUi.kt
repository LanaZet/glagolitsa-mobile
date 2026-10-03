// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.components.avatarGlassBezel
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaShapes
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.chat_title_ornament
import glagolitsamobile.shared.generated.resources.profile_avatar_camera
import glagolitsamobile.shared.generated.resources.profile_bird_ornament
import kotlin.math.min
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

internal data class ProfileMenuEntry(
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
    /** When true, badge uses gold highlight (e.g. available app update). */
    val badgeHighlight: Boolean = false,
    val trailing: String? = null,
    val trailingContent: (@Composable () -> Unit)? = null,
    val icon: @Composable (Color) -> Unit,
    val onClick: () -> Unit = {},
)

internal enum class ProfileMenuLayout {
    List,
    IconGrid,
}

internal enum class ProfileHeroOrnament(
    val storageValue: String,
    val title: String,
    val resource: DrawableResource,
    val heroVerticalOffset: Dp,
) {
    Birds(
        storageValue = "birds",
        title = "Птицы",
        resource = Res.drawable.profile_bird_ornament,
        heroVerticalOffset = 34.dp,
    ),
    Vyaz(
        storageValue = "vyaz",
        title = "Вязь",
        resource = Res.drawable.chat_title_ornament,
        heroVerticalOffset = 12.dp,
    );

    companion object {
        val Default: ProfileHeroOrnament = Birds

        fun storageKey(userId: String): String = "profile.hero_ornament.$userId"

        fun fromStorageValue(value: String?): ProfileHeroOrnament =
            entries.firstOrNull { it.storageValue == value } ?: Default
    }
}

@Composable
internal fun ProfileHeroCard(
    name: String,
    subtitle: String? = null,
    avatar: @Composable () -> Unit,
    ornament: ProfileHeroOrnament = ProfileHeroOrnament.Default,
    modifier: Modifier = Modifier,
    onAvatarClick: (() -> Unit)? = null,
    avatarHint: String? = null,
    presenceColor: Color? = null,
    /** Optional block under name (e.g. status quote) — stays inside the hero card. */
    belowName: (@Composable () -> Unit)? = null,
) {
    val cardShape = GlagolitsaShapes.md
    val avatarSize = 128.dp
    val frameSize = 148.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = frameSize / 2),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = CardDefaults.cardColors(
                containerColor = GlagolitsaColors.Surface800.copy(alpha = 0.72f),
            ),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                ProfileHeroOrnaments(ornament = ornament)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = frameSize / 2 + GlagolitsaSpacing.sm,
                            bottom = GlagolitsaSpacing.md,
                            start = GlagolitsaSpacing.lg,
                            end = GlagolitsaSpacing.lg,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.xs),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.displaySmall,
                        color = GlagolitsaColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlagolitsaColors.TextTertiary,
                        )
                    }
                    // Status only (e.g. «Сохраняем…») — pick photo via the + badge only.
                    if (avatarHint != null) {
                        Text(
                            text = avatarHint,
                            style = MaterialTheme.typography.labelMedium,
                            color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.9f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (belowName != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = GlagolitsaSpacing.xs),
                            contentAlignment = Alignment.Center,
                        ) {
                            belowName()
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = -frameSize / 2)
                .size(frameSize),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(avatarSize + 10.dp)
                    .avatarGlassBezel(rim = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                avatar()
            }

            // Camera badge opens the gallery — avatar itself is not a button.
            if (onAvatarClick != null) {
                val badgeSize = 34.dp
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-2).dp, y = (-2).dp)
                        .size(badgeSize)
                        .clip(CircleShape)
                        .background(GlagolitsaColors.Background950.copy(alpha = 0.94f))
                        .border(
                            width = 1.dp,
                            color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.72f),
                            shape = CircleShape,
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onAvatarClick,
                        )
                        .semantics {
                            role = Role.Button
                            contentDescription = "Выбрать фото аватара"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(Res.drawable.profile_avatar_camera),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/**
 * Gold ornaments on the dark profile hero card.
 * Mirrored pair fills the side gutters under the overlapping avatar.
 */
@Composable
private fun ProfileHeroOrnaments(ornament: ProfileHeroOrnament) {
    // Keep hero ornaments large, but subdued so the avatar/name stay primary.
    val ornamentSize = 118.dp
    val tapSize = 128.dp
    val sideInset = 0.dp
    // Beside name/status, with per-artwork baseline alignment.
    val verticalNudge = ornament.heroVerticalOffset

    Box(modifier = Modifier.fillMaxWidth()) {
        ProfileHeroOrnamentSide(
            ornament = ornament,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = sideInset)
                .offset(y = verticalNudge),
            tapSize = tapSize,
            ornamentSize = ornamentSize,
            mirrored = false,
        )
        ProfileHeroOrnamentSide(
            ornament = ornament,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = sideInset)
                .offset(y = verticalNudge),
            tapSize = tapSize,
            ornamentSize = ornamentSize,
            mirrored = true,
        )
    }
}

@Composable
private fun ProfileHeroOrnamentSide(
    ornament: ProfileHeroOrnament,
    modifier: Modifier,
    tapSize: Dp,
    ornamentSize: Dp,
    mirrored: Boolean,
) {
    ProfileHeroOrnamentPreview(
        ornament = ornament,
        mirrored = mirrored,
        alpha = 0.20f,
        modifier = modifier
            .size(tapSize)
            .padding((tapSize - ornamentSize) / 2),
    )
}

@Composable
internal fun ProfileHeroOrnamentPreview(
    ornament: ProfileHeroOrnament,
    modifier: Modifier = Modifier,
    mirrored: Boolean = false,
    alpha: Float = 1f,
) {
    Image(
        painter = painterResource(ornament.resource),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(GlagolitsaColors.OrnamentGold),
        modifier = modifier
            .graphicsLayer {
                scaleX = if (mirrored) -1f else 1f
                this.alpha = alpha
            },
    )
}

@Composable
internal fun ProfileMenuCard(
    items: List<ProfileMenuEntry>,
    modifier: Modifier = Modifier,
    title: String? = null,
    layout: ProfileMenuLayout = ProfileMenuLayout.List,
    /** Denser rows (e.g. «Мои каналы») — smaller icon/padding, less air. */
    compact: Boolean = false,
) {
    if (items.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 9.dp),
    ) {
        if (!title.isNullOrBlank()) {
            ProfileMenuSectionHeader(title = title)
        }

        when (layout) {
            ProfileMenuLayout.IconGrid -> ProfileMenuIconGrid(items = items)
            ProfileMenuLayout.List -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    items.forEachIndexed { index, item ->
                        ProfileMenuRow(item = item, compact = compact)
                        if (index < items.lastIndex) {
                            HorizontalDivider(
                                color = GlagolitsaColors.GlassBorder.copy(alpha = 0.10f),
                                modifier = Modifier.padding(
                                    // Align under title: row pad + icon box + gap
                                    start = if (compact) 40.dp else 56.dp,
                                    end = 8.dp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileMenuSectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "◇",
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.AccentRed.copy(alpha = 0.9f),
        )
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.88f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            GlagolitsaColors.AccentRed.copy(alpha = 0.72f),
                            GlagolitsaColors.AccentRed.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun ProfileMenuIconGrid(items: List<ProfileMenuEntry>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowItems.forEach { item ->
                    ProfileMenuIconCell(
                        item = item,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private val ProfileMenuIconGridCellHeight = 96.dp
private val ProfileMenuIconGridIconSize = 34.dp // same as list row icon box
private val ProfileMenuIconGridPaddingVertical = 8.dp
private val ProfileMenuIconGridPaddingHorizontal = 6.dp
private val ProfileMenuIconGridContentGap = 7.dp

@Composable
private fun ProfileMenuIconCell(
    item: ProfileMenuEntry,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = modifier
            .height(ProfileMenuIconGridCellHeight)
            .clip(shape)
            .then(
                if (pressed) {
                    Modifier.profileMenuPressedSurface(
                        shape = shape,
                        cornerRadius = 12.dp,
                    )
                } else {
                    Modifier.background(Color.Transparent)
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = item.onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = item.title
            }
            .padding(
                horizontal = ProfileMenuIconGridPaddingHorizontal,
                vertical = ProfileMenuIconGridPaddingVertical,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ProfileMenuIconGridContentGap),
    ) {
        ProfileMenuIconSlot(
            item = item,
            size = ProfileMenuIconGridIconSize,
            showBadge = true,
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = GlagolitsaColors.TextPrimary.copy(alpha = 0.94f),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProfileMenuRow(
    item: ProfileMenuEntry,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(if (compact) 10.dp else 12.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // One fixed box for every row icon so the set reads as a uniform asset grid.
    val iconSize = if (compact) 24.dp else 34.dp
    val rowVPad = if (compact) 5.dp else 10.dp
    val rowHPad = if (compact) 8.dp else 10.dp
    val gap = if (compact) 8.dp else 12.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = if (compact) 0.dp else 1.dp)
            .clip(shape)
            .then(
                if (pressed) {
                    Modifier.profileMenuPressedSurface(
                        shape = shape,
                        cornerRadius = if (compact) 10.dp else 12.dp,
                    )
                } else {
                    Modifier.background(Color.Transparent)
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = item.onClick,
            )
            .padding(horizontal = rowHPad, vertical = rowVPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileMenuIconSlot(item = item, size = iconSize)

        Spacer(modifier = Modifier.width(gap))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 0.dp),
        ) {
            Text(
                text = item.title,
                style = if (compact) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = GlagolitsaColors.TextPrimary.copy(alpha = 0.94f),
                fontWeight = if (compact) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.subtitle.isNullOrBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = GlagolitsaColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (item.badge != null) {
            ProfileMenuBadge(
                text = item.badge,
                highlighted = item.badgeHighlight,
                modifier = Modifier.padding(end = if (compact) 6.dp else 10.dp),
            )
        }

        if (item.trailingContent != null) {
            // Toggle / custom control — no chevron (nav rows keep the arrow).
            Box(modifier = Modifier.padding(end = GlagolitsaSpacing.sm)) {
                item.trailingContent.invoke()
            }
        } else if (item.trailing != null) {
            Text(
                text = item.trailing,
                style = if (compact) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = GlagolitsaColors.TextTertiary,
                modifier = Modifier.padding(end = if (compact) 4.dp else GlagolitsaSpacing.sm),
            )
            ProfileMenuChevronIcon(
                tint = GlagolitsaColors.TextTertiary.copy(alpha = 0.58f),
                modifier = if (compact) Modifier.size(16.dp) else Modifier,
            )
        } else {
            ProfileMenuChevronIcon(
                tint = GlagolitsaColors.TextTertiary.copy(alpha = 0.58f),
                modifier = if (compact) Modifier.size(16.dp) else Modifier,
            )
        }
    }
}

@Composable
private fun ProfileMenuIconSlot(
    item: ProfileMenuEntry,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    showBadge: Boolean = false,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalProfileMenuIconSize provides size) {
            item.icon(GlagolitsaColors.OrnamentGold.copy(alpha = 0.92f))
        }
        if (showBadge) item.badge?.let { badge ->
            ProfileMenuBadge(
                text = badge,
                highlighted = item.badgeHighlight,
                compact = true,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/**
 * Neumorphic inset for pressed menu rows: flat base + edge-only inner shadows.
 * No full-surface diagonal wash — that reads poorly on wide rectangles.
 */
private fun Modifier.profileMenuPressedSurface(
    shape: RoundedCornerShape,
    cornerRadius: Dp,
): Modifier {
    val light = !GlagolitsaColors.IsDark
    val ink = if (light) Color(0xFF3A2E22) else Color.Black
    val lift = Color.White
    return this
        .clip(shape)
        .drawBehind {
            val radius = cornerRadius.toPx().coerceAtMost(min(size.width, size.height) / 2f)
            val corner = CornerRadius(radius, radius)
            // Depth of the soft rim; keep shallow so wide rows stay clean.
            val rim = 3.5.dp.toPx()

            // Flat recessed base (no interior gradient).
            drawRoundRect(
                color = GlagolitsaColors.Background900.copy(alpha = 0.96f),
                cornerRadius = corner,
            )

            // Top inner shadow — light comes from top-left, so the top edge is dark.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to ink.copy(alpha = if (light) 0.16f else 0.38f),
                        0.55f to ink.copy(alpha = if (light) 0.06f else 0.12f),
                        1f to Color.Transparent,
                    ),
                    startY = 0f,
                    endY = rim,
                ),
                size = Size(size.width, rim),
                cornerRadius = corner,
            )
            // Left inner shadow.
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to ink.copy(alpha = if (light) 0.12f else 0.28f),
                        0.55f to ink.copy(alpha = if (light) 0.05f else 0.08f),
                        1f to Color.Transparent,
                    ),
                    startX = 0f,
                    endX = rim,
                ),
                size = Size(rim, size.height),
                cornerRadius = corner,
            )

            // Bottom inner highlight.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.45f to lift.copy(alpha = if (light) 0.18f else 0.03f),
                        1f to lift.copy(alpha = if (light) 0.38f else 0.10f),
                    ),
                    startY = size.height - rim,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - rim),
                size = Size(size.width, rim),
                cornerRadius = corner,
            )
            // Right inner highlight.
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.45f to lift.copy(alpha = if (light) 0.14f else 0.02f),
                        1f to lift.copy(alpha = if (light) 0.28f else 0.07f),
                    ),
                    startX = size.width - rim,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - rim, 0f),
                size = Size(rim, size.height),
                cornerRadius = corner,
            )
        }
}

@Composable
private fun ProfileMenuBadge(
    text: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(percent = 50)
    val baseColor = if (highlighted) {
        GlagolitsaColors.OrnamentGold.copy(alpha = 0.18f)
    } else {
        GlagolitsaColors.AccentRed.copy(alpha = 0.26f)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(baseColor)
            .border(
                width = 0.65.dp,
                color = if (highlighted) {
                    GlagolitsaColors.OrnamentGold.copy(alpha = 0.36f)
                } else {
                    GlagolitsaColors.AccentRed.copy(alpha = 0.34f)
                },
                shape = shape,
            )
            .padding(
                horizontal = if (compact) 5.dp else 8.dp,
                vertical = if (compact) 1.dp else 2.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) {
                GlagolitsaColors.OrnamentGold
            } else {
                GlagolitsaColors.TextPrimary.copy(alpha = 0.92f)
            },
            maxLines = 1,
        )
    }
}
