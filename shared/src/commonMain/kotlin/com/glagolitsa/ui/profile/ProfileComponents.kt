// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

// Переиспользуемые секции и виджеты экрана профиля (Mattermost-подобная раскладка).

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.UserPresence
import com.glagolitsa.ui.components.GlagolitsaSwitch
import com.glagolitsa.ui.components.convexSurface
import com.glagolitsa.ui.theme.GlagolitsaColors


/** Colors for presence rings / chips — bright enough to read on dark chrome. */
fun UserPresence.color(): Color = when (this) {
    UserPresence.Online -> GlagolitsaColors.PresenceOnlineBright
    UserPresence.Away -> GlagolitsaColors.PresenceAway
    UserPresence.Dnd -> GlagolitsaColors.PresenceDnd
    UserPresence.Offline -> GlagolitsaColors.PresenceOffline
}

@Composable
fun ProfileSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = GlagolitsaColors.TextSecondary,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(start = 2.dp, bottom = 4.dp),
    )
}

/** Мягкая группа без рамки — один общий фон вместо вложенных карточек. */
@Composable
fun ProfileSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(GlagolitsaColors.GlassSelected.copy(alpha = 0.22f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        content = content,
    )
}

@Composable
fun ProfileInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 6.dp else 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = GlagolitsaColors.TextMuted,
        )
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = GlagolitsaColors.TextPrimary,
        )
    }
}

@Composable
fun ProfileToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = GlagolitsaColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = GlagolitsaColors.TextSecondary)
        }
        GlagolitsaSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun PresenceChip(
    presence: UserPresence,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chipShape = RoundedCornerShape(percent = 50)
    val containerColor = animateColorAsState(
        targetValue = if (active) {
            presence.color().copy(alpha = 0.38f)
        } else {
            GlagolitsaColors.GlassSelected.copy(alpha = 0.64f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "presence-container",
    )
    val borderColor = animateColorAsState(
        targetValue = if (active) {
            presence.color().copy(alpha = 0.56f)
        } else {
            Color.White.copy(alpha = 0.12f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "presence-border",
    )
    val dotColor = animateColorAsState(
        targetValue = if (active) presence.color() else presence.color().copy(alpha = 0.72f),
        animationSpec = tween(durationMillis = 180),
        label = "presence-dot",
    )
    val iconRingColor = animateColorAsState(
        targetValue = if (active) {
            presence.color().copy(alpha = 0.72f)
        } else {
            presence.color().copy(alpha = 0.42f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "presence-icon-ring",
    )
    val iconHaloColor = animateColorAsState(
        targetValue = if (active) {
            presence.color().copy(alpha = 0.24f)
        } else {
            Color.White.copy(alpha = 0.06f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "presence-icon-halo",
    )
    val textColor = animateColorAsState(
        targetValue = if (active) GlagolitsaColors.TextPrimary else GlagolitsaColors.TextSecondary.copy(alpha = 0.96f),
        animationSpec = tween(durationMillis = 180),
        label = "presence-text",
    )

    Row(
        modifier = modifier
            .convexSurface(
                shape = chipShape,
                cornerRadius = 20.dp,
                baseColor = containerColor.value,
            )
            .border(width = 0.9.dp, color = borderColor.value, shape = chipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (active) 12.dp else 11.dp)
                .clip(CircleShape)
                .background(iconHaloColor.value)
                .border(0.8.dp, iconRingColor.value, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(if (active) 6.dp else 5.dp)
                    .clip(CircleShape)
                    .background(dotColor.value),
            )
        }
        Text(
            text = presence.label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor.value,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
fun PresenceSelector(
    selected: UserPresence,
    onSelected: (UserPresence) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PresenceChip(UserPresence.Online, selected == UserPresence.Online, { onSelected(UserPresence.Online) }, Modifier.weight(1f))
            PresenceChip(UserPresence.Away, selected == UserPresence.Away, { onSelected(UserPresence.Away) }, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PresenceChip(UserPresence.Dnd, selected == UserPresence.Dnd, { onSelected(UserPresence.Dnd) }, Modifier.weight(1f))
            PresenceChip(UserPresence.Offline, selected == UserPresence.Offline, { onSelected(UserPresence.Offline) }, Modifier.weight(1f))
        }
    }
}
