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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glagolitsa.media.AttachmentCacheStats
import com.glagolitsa.media.ChatMediaCachePolicy
import com.glagolitsa.media.ChatMediaKeepMode
import com.glagolitsa.ui.theme.GlagolitsaColors

@Composable
internal fun ChatStoragePanel(
    mediaStats: AttachmentCacheStats,
    mediaPolicy: ChatMediaCachePolicy,
    mediaActionInFlight: Boolean,
    mediaError: String?,
    onKeepModeSelected: (ChatMediaKeepMode) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StorageMetricTile(
            label = "Размер кеша",
            value = formatChatMediaCacheBytes(mediaStats.totalBytes),
            modifier = Modifier.fillMaxWidth(),
            highlighted = true,
        )

        StoragePolicyDropdown(
            selectedMode = mediaPolicy.keepMode,
            enabled = !mediaActionInFlight,
            onModeSelected = onKeepModeSelected,
        )

        mediaError?.let { error ->
            val errorShape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(errorShape)
                    .background(GlagolitsaColors.AccentRed.copy(alpha = 0.12f))
                    .border(0.8.dp, GlagolitsaColors.AccentRed.copy(alpha = 0.32f), errorShape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = GlagolitsaColors.AccentRedPale,
                )
            }
        }
    }
}

@Composable
internal fun StorageClearCacheButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(shape)
            .background(
                if (enabled) {
                    GlagolitsaColors.AccentRed.copy(alpha = 0.16f)
                } else {
                    GlagolitsaColors.SurfaceDisabled.copy(alpha = 0.42f)
                },
            )
            .border(0.8.dp, GlagolitsaColors.AccentRed.copy(alpha = if (enabled) 0.44f else 0.12f), shape)
            .then(
                if (enabled) {
                    Modifier
                        .clickable(onClick = onClick)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Очистить кеш"
                        }
                } else {
                    Modifier.semantics {
                        role = Role.Button
                        contentDescription = "Очистить кеш"
                    }
                },
            )
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Очистить кеш",
            style = MaterialTheme.typography.labelMedium,
            color = GlagolitsaColors.AccentRedPale.copy(alpha = if (enabled) 0.94f else 0.48f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun StoragePolicyDropdown(
    selectedMode: ChatMediaKeepMode,
    enabled: Boolean,
    onModeSelected: (ChatMediaKeepMode) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val menuWidth = 176.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlagolitsaColors.Surface700.copy(alpha = if (enabled) 0.44f else 0.26f))
            .border(0.8.dp, GlagolitsaColors.BorderSubtle, shape)
            .then(
                if (enabled) {
                    Modifier
                        .clickable { menuExpanded = true }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Срок хранения кеша: ${selectedMode.label}"
                        }
                } else {
                    Modifier.semantics {
                        role = Role.Button
                        contentDescription = "Срок хранения кеша: ${selectedMode.label}"
                    }
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Хранить кеш",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextSecondary.copy(alpha = if (enabled) 0.92f else 0.44f),
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier.width(menuWidth),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selectedMode.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlagolitsaColors.TextPrimary.copy(alpha = if (enabled) 1f else 0.48f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = GlagolitsaColors.TextTertiary.copy(alpha = if (enabled) 1f else 0.48f),
                        maxLines = 1,
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.width(menuWidth),
                ) {
                    ChatMediaKeepMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (mode == selectedMode) {
                                        GlagolitsaColors.TextPrimary
                                    } else {
                                        GlagolitsaColors.TextSecondary
                                    },
                                    fontWeight = if (mode == selectedMode) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                if (mode != selectedMode) {
                                    onModeSelected(mode)
                                }
                            },
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(
                if (highlighted) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            GlagolitsaColors.Surface700.copy(alpha = 0.72f),
                            GlagolitsaColors.Surface700.copy(alpha = 0.44f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            GlagolitsaColors.Surface700.copy(alpha = 0.54f),
                            GlagolitsaColors.Surface700.copy(alpha = 0.42f),
                        ),
                    )
                },
            )
            .border(
                0.8.dp,
                if (highlighted) {
                    GlagolitsaColors.OrnamentGold.copy(alpha = 0.22f)
                } else {
                    GlagolitsaColors.BorderSubtle
                },
                shape,
            )
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = GlagolitsaColors.TextSecondary.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = GlagolitsaColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
