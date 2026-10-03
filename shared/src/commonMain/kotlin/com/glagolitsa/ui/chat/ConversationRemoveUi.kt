// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatDeletePolicy
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlinx.coroutines.launch

@Composable
internal fun ConversationRemoveConfirmDialog(
    chat: Chat,
    action: ChatDeletePolicy.Action,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ChatDeletePolicy.confirmTitle(chat, action)) },
        text = {
            Text(
                ChatDeletePolicy.confirmBody(chat, action),
                color = GlagolitsaColors.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    ChatDeletePolicy.confirmButton(action),
                    color = GlagolitsaColors.AccentRed,
                )
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
internal fun ConversationRemoveSection(
    chat: Chat,
    currentUserId: String?,
    membershipRole: String?,
    actionBusy: Boolean,
    enabled: Boolean = true,
    onRequestRemove: () -> Unit,
) {
    if (!ChatSettingsInviteUi.showRemoveAction(chat, currentUserId, membershipRole)) return
    val action = ChatDeletePolicy.action(chat, currentUserId, membershipRole)
    val label = when (action) {
        ChatDeletePolicy.Action.PermanentDelete -> if (chat.isChannel) "Удалить канал" else "Удалить группу"
        ChatDeletePolicy.Action.Leave -> if (chat.isChannel) "Выйти из канала" else "Выйти"
        ChatDeletePolicy.Action.HideLocally -> "Удалить чат"
    }
    InfoSection(
        title = "Чат",
        contentPadding = 12.dp,
        contentSpacing = 10.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionPill(
                text = if (actionBusy) "…" else label,
                color = GlagolitsaColors.AccentRed.copy(alpha = 0.82f),
                contentColor = Color.White,
                enabled = enabled && !actionBusy,
                onClick = onRequestRemove,
                compact = true,
                contentDescription = ChatDeletePolicy.confirmButton(action),
            )
        }
    }
}

@Composable
internal fun ConversationRemoveActionSection(
    chat: Chat,
    currentUserId: String?,
    membershipRole: String?,
    enabled: Boolean = true,
    errorMessage: (Throwable) -> String,
    onRequestRemove: () -> Unit = {},
    onRemove: suspend (chat: Chat, membershipRole: String?) -> Unit,
    onRemoved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pendingRemove by remember(chat.id) { mutableStateOf(false) }
    var removeBusy by remember(chat.id) { mutableStateOf(false) }
    var removeError by remember(chat.id) { mutableStateOf<String?>(null) }
    val action = ChatDeletePolicy.action(chat, currentUserId, membershipRole)

    ConversationRemoveSection(
        chat = chat,
        currentUserId = currentUserId,
        membershipRole = membershipRole,
        actionBusy = removeBusy,
        enabled = enabled,
        onRequestRemove = {
            removeError = null
            onRequestRemove()
            pendingRemove = true
        },
    )

    removeError?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.AccentRed,
        )
    }

    if (pendingRemove) {
        ConversationRemoveConfirmDialog(
            chat = chat,
            action = action,
            onDismiss = { pendingRemove = false },
            onConfirm = {
                pendingRemove = false
                removeBusy = true
                removeError = null
                scope.launch {
                    runCatching { onRemove(chat, membershipRole) }
                        .onSuccess { onRemoved() }
                        .onFailure { removeError = errorMessage(it) }
                    removeBusy = false
                }
            },
        )
    }
}
