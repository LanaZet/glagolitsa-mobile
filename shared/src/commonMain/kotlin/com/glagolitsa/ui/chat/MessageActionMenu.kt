// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glagolitsa.model.MessageAction
import com.glagolitsa.model.MessageActionPolicy
import com.glagolitsa.ui.theme.GlagolitsaColors
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.chat_pin_mark
import glagolitsamobile.shared.generated.resources.chat_reply_mark
import org.jetbrains.compose.resources.painterResource

/**
 * iMessage/Telegram-style message context menu:
 * dimmed scrim + reaction tray + message preview + vertical action list.
 * Opened by tap / long-press on a message.
 * Preview is display-only (no tap / selection).
 */
@Composable
fun MessageActionMenu(
    messageBody: String,
    actions: List<MessageAction>,
    isOwn: Boolean,
    myReactionEmoji: String? = null,
    onAction: (MessageAction) -> Unit,
    onReact: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .testTag("message-action-menu")
            .semantics { contentDescription = "Меню сообщения" },
        contentAlignment = Alignment.Center,
    ) {
        val actionListMaxHeight = messageActionListMaxHeight(actions.size, maxHeight)
        Column(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ReactionTray(
                selectedEmoji = myReactionEmoji,
                onReact = onReact,
            )

            MessagePreviewCard(
                body = messageBody.ifBlank { " " },
                isOwn = isOwn,
            )

            Column(
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 300.dp)
                    .heightIn(max = actionListMaxHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlagolitsaColors.Surface800.copy(alpha = 0.98f))
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                actions.forEachIndexed { index, action ->
                    val destructive = MessageActionPolicy.isDestructive(action)
                    val label = MessageActionPolicy.label(action)
                    val leadingIcon = when (action) {
                        MessageAction.PIN, MessageAction.UNPIN -> Res.drawable.chat_pin_mark
                        MessageAction.REPLY_CHAT -> Res.drawable.chat_reply_mark
                        else -> null
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MessageActionRowMinHeight)
                            .clickable { onAction(action) }
                            .semantics {
                                role = Role.Button
                                contentDescription = label
                            }
                            .padding(horizontal = 18.dp, vertical = 14.dp)
                            .testTag("message-action-${action.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (leadingIcon != null) {
                            Image(
                                painter = painterResource(leadingIcon),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (destructive) {
                                GlagolitsaColors.AccentRed
                            } else {
                                GlagolitsaColors.TextPrimary
                            },
                        )
                    }
                    if (index < actions.lastIndex) {
                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.08f),
                            thickness = 0.5.dp,
                        )
                    }
                }
            }
        }
    }
}

internal fun messageActionListMaxHeight(actionCount: Int, viewportHeight: Dp) =
    minOf(
        MessageActionListPreferredMaxHeight,
        MessageActionListVerticalPadding * 2 + MessageActionRowMinHeight * actionCount,
        (viewportHeight - MessageActionMenuReservedHeight).coerceAtLeast(MessageActionListCompactMaxHeight),
    )

private val MessageActionRowMinHeight = 52.dp
private val MessageActionListVerticalPadding = 4.dp
private val MessageActionListCompactMaxHeight = 240.dp
private val MessageActionListPreferredMaxHeight = 440.dp
private val MessageActionMenuReservedHeight = 176.dp

@Composable
fun MessageDeleteConfirmDialog(
    count: Int = 1,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (count > 1) "Удалить сообщения?" else "Удалить сообщение?",
                color = GlagolitsaColors.TextPrimary,
            )
        },
        text = {
            Text(
                text = if (count > 1) {
                    "Будет удалено сообщений: $count. Это действие нельзя отменить."
                } else {
                    "Сообщение будет удалено. Это действие нельзя отменить."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("message-delete-confirm"),
            ) {
                Text("Удалить", color = GlagolitsaColors.AccentRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = GlagolitsaColors.TextPrimary)
            }
        },
    )
}

@Composable
fun MessageSelectionBar(
    count: Int,
    canCopy: Boolean,
    canDelete: Boolean,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlagolitsaColors.Surface800.copy(alpha = 0.96f))
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag("message-selection-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onCancel) {
            Text("Отмена", color = GlagolitsaColors.TextSecondary)
        }
        Text(
            text = MessageActionPolicy.selectionBarLabel(count),
            style = MaterialTheme.typography.titleSmall,
            color = GlagolitsaColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        TextButton(
            onClick = onCopy,
            enabled = canCopy,
        ) {
            Text(
                "Копировать",
                color = if (canCopy) GlagolitsaColors.TextPrimary else GlagolitsaColors.TextTertiary,
            )
        }
        TextButton(
            onClick = onDelete,
            enabled = canDelete,
            modifier = Modifier.testTag("message-selection-delete"),
        ) {
            Text(
                "Удалить",
                color = if (canDelete) GlagolitsaColors.AccentRed else GlagolitsaColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun ReactionTray(
    selectedEmoji: String?,
    onReact: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(GlagolitsaColors.Surface800.copy(alpha = 0.98f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("message-reaction-tray"),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MessageActionPolicy.QuickReactions.forEach { emoji ->
            val selected = emoji == selectedEmoji
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) GlagolitsaColors.AccentRed.copy(alpha = 0.22f)
                        else Color.Transparent,
                    )
                    .clickable { onReact(emoji) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Реакция $emoji"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = emoji, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun MessagePreviewCard(
    body: String,
    isOwn: Boolean,
) {
    Box(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isOwn) GlagolitsaColors.ChatBubbleOwn else GlagolitsaColors.ChatBubbleOther,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isOwn) Color.White else GlagolitsaColors.TextPrimary,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
