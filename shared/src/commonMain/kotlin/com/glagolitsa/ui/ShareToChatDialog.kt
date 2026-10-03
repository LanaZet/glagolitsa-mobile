// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ShareIntakePolicy
import com.glagolitsa.model.listAvatarLabel
import com.glagolitsa.share.IncomingShare
import com.glagolitsa.ui.profile.ProfileAvatar
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing

/**
 * Pick a chat/channel for text shared from YouTube, Telegram, browser, etc.
 */
@Composable
fun ShareToChatDialog(
    share: IncomingShare,
    targets: List<Chat>,
    sendingChatId: String?,
    error: String?,
    onSendToChat: (Chat) -> Unit,
    onDismiss: () -> Unit,
) {
    val previewUrl = ShareIntakePolicy.primaryUrl(share.text)
    val previewBody = share.text
    val header = share.subject?.trim()?.takeIf { it.isNotEmpty() }
        ?: previewUrl
        ?: "Ссылка / текст"

    AlertDialog(
        onDismissRequest = {
            if (sendingChatId == null) onDismiss()
        },
        title = {
            Text(
                text = "Отправить в…",
                style = MaterialTheme.typography.titleMedium,
                color = GlagolitsaColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlagolitsaColors.Surface800.copy(alpha = 0.72f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelMedium,
                        color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = previewBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlagolitsaColors.TextSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (targets.isEmpty()) {
                    Text(
                        text = "Нет чатов или каналов для отправки. Создайте канал или откройте чат.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlagolitsaColors.TextTertiary,
                    )
                } else {
                    Text(
                        text = "Каналы сверху — удобно кинуть ссылку из YouTube / Telegram",
                        style = MaterialTheme.typography.labelSmall,
                        color = GlagolitsaColors.TextTertiary,
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(targets, key = { it.id }) { chat ->
                            ShareTargetRow(
                                chat = chat,
                                busy = sendingChatId == chat.id,
                                enabled = sendingChatId == null,
                                onClick = { onSendToChat(chat) },
                            )
                        }
                    }
                }

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlagolitsaColors.AccentRed,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = sendingChatId == null,
            ) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun ShareTargetRow(
    chat: Chat,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val kind = ShareIntakePolicy.kindLabel(chat)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "Отправить в ${chat.title}"
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm),
    ) {
        ProfileAvatar(
            avatarUrl = chat.conversationIconUrl,
            fallbackLabel = chat.listAvatarLabel(),
            size = 40.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.title,
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = kind,
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = GlagolitsaColors.AccentRed,
                strokeWidth = 1.8.dp,
            )
        }
    }
}
