// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.theme.GlagolitsaColors
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.chat_pin_mark
import org.jetbrains.compose.resources.painterResource

/**
 * Compact strip under the chat top bar for the currently pinned message
 * (Telegram-style). Tap focuses the message; close unpins.
 */
@Composable
fun PinnedMessageBanner(
    title: String,
    body: String,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlagolitsaColors.Surface800.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "Закреплённое сообщение"
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("chat-pinned-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painter = painterResource(Res.drawable.chat_pin_mark),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = GlagolitsaColors.OrnamentGold,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = body.normalizeQuotePreview(),
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextPrimary.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "✕",
            style = MaterialTheme.typography.titleMedium,
            color = GlagolitsaColors.TextSecondary,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onUnpin)
                .semantics {
                    role = Role.Button
                    contentDescription = "Открепить"
                }
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag("chat-pinned-unpin"),
        )
    }
}
