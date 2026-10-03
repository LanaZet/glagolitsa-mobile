// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.components.OrnamentGold
import com.glagolitsa.ui.components.OrnamentRed
import com.glagolitsa.ui.theme.GlagolitsaColors

internal val PanelShape = RoundedCornerShape(16.dp)

@Composable
internal fun InfoSection(
    title: String,
    containerColor: Color = GlagolitsaColors.Surface800.copy(alpha = 0.68f),
    contentPadding: Dp = 14.dp,
    contentSpacing: Dp = 8.dp,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(containerColor)
            .border(0.8.dp, GlagolitsaColors.GlassBorder, PanelShape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(contentSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = OrnamentGold,
                fontWeight = FontWeight.SemiBold,
            )
            trailing?.invoke()
        }
        content()
    }
}

@Composable
internal fun InfoRow(
    label: String,
    value: String,
    labelTrailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && labelTrailing == null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, color = GlagolitsaColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            labelTrailing?.invoke()
        }
        Text(
            value,
            color = GlagolitsaColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = if (onClick != null) {
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            } else {
                Modifier
            },
        )
    }
}

@Composable
internal fun ActionPill(
    text: String,
    color: Color,
    contentColor: Color = GlagolitsaColors.TextPrimary,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    contentDescription: String = text,
    compact: Boolean = false,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(color)
            .then(
                if (enabled && onClick != null) {
                    Modifier
                        .clickable(onClick = onClick)
                        .semantics {
                            role = Role.Button
                            this.contentDescription = contentDescription
                        }
                } else {
                    Modifier.semantics {
                        role = Role.Button
                        this.contentDescription = contentDescription
                    }
                },
            )
            .padding(
                horizontal = if (compact) 16.dp else 18.dp,
                vertical = if (compact) 7.dp else 8.dp,
            ),
    ) {
        Text(
            text,
            color = contentColor.copy(alpha = if (enabled) 1f else 0.45f),
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
internal fun CollapseTextButton(
    text: String,
    onClick: () -> Unit,
    contentDescription: String = text,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(GlagolitsaColors.Surface700.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.TextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun ThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) OrnamentRed.copy(alpha = 0.85f)
                else GlagolitsaColors.Surface700.copy(alpha = 0.85f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.White else GlagolitsaColors.TextSecondary,
        )
    }
}

internal fun formatChatMediaCacheBytes(bytes: Long): String {
    val mb = 1024L * 1024L
    val kb = 1024L
    return when {
        bytes >= mb -> "${bytes / mb} МБ"
        bytes >= kb -> "${bytes / kb} КБ"
        else -> "$bytes Б"
    }
}
