// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.components.GlagolitsaInput
import com.glagolitsa.ui.components.GlagolitsaInputVariant
import com.glagolitsa.ui.components.chatComposerInsets
import com.glagolitsa.ui.theme.GlagolitsaColors

/**
 * Channel post composer — **not** the chat message capsule.
 * Blog-style panel: title, multiline body, explicit «Опубликовать».
 * Photo attach is deferred (gallery path remains elsewhere).
 */
@Composable
fun ChannelPostComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onPublish: () -> Unit,
    sending: Boolean,
    canPublish: Boolean,
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    val panelShape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(GlagolitsaColors.Background950)
            .chatComposerInsets()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(panelShape)
                .background(GlagolitsaColors.Surface800.copy(alpha = 0.92f))
                .border(1.dp, GlagolitsaColors.GlassBorder.copy(alpha = 0.55f), panelShape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Новый пост",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = GlagolitsaColors.TextPrimary,
            )
            Text(
                text = "Публикация в канале — не личное сообщение",
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.TextTertiary,
            )

            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            GlagolitsaInput(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = "Текст поста…",
                modifier = Modifier.fillMaxWidth(),
                variant = GlagolitsaInputVariant.Multiline,
                enabled = !sending,
                singleLine = false,
                minLines = 2,
                maxLines = 6,
            )

            // Warm gold/orange (OrnamentGold) — not AccentRed Primary used in chat/auth.
            val publishEnabled = !sending && canPublish
            val publishShape = RoundedCornerShape(12.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 44.dp)
                    .clip(publishShape)
                    .background(
                        if (publishEnabled) {
                            GlagolitsaColors.OrnamentGold.copy(alpha = 0.92f)
                        } else {
                            GlagolitsaColors.OrnamentGold.copy(alpha = 0.28f)
                        },
                    )
                    .clickable(enabled = publishEnabled, onClick = onPublish)
                    .semantics { role = Role.Button }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (sending) "Публикуем…" else "Опубликовать",
                    color = if (publishEnabled || sending) {
                        GlagolitsaColors.TextOnGold
                    } else {
                        GlagolitsaColors.TextOnGold.copy(alpha = 0.55f)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
