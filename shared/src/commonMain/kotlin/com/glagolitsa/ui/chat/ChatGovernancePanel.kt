// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatPermissionPolicy
import com.glagolitsa.model.GroupInviteDto
import com.glagolitsa.model.GroupPerms
import com.glagolitsa.model.GroupResponse
import com.glagolitsa.model.UpdateGroupSettingsRequest
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.security.SecureClipboard
import com.glagolitsa.session.SessionStore
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlinx.coroutines.launch

@Composable
internal fun ChatGovernancePanel(
    chat: Chat,
    repository: MessengerRepository,
    onHint: (String?) -> Unit,
) {
    if (!ChatSettingsInviteUi.isMultiParty(chat)) return
    val scope = rememberCoroutineScope()
    val currentUserId = SessionStore.user.collectAsState().value?.id
    var group by remember(chat.id) { mutableStateOf<GroupResponse?>(null) }
    var invites by remember(chat.id) { mutableStateOf<List<GroupInviteDto>>(emptyList()) }
    var busy by remember(chat.id) { mutableStateOf(false) }
    var linkCopied by remember(chat.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(chat.id) {
        runCatching { repository.loadGroup(chat.id) }
            .onSuccess { loaded ->
                group = loaded
                if (ChatPermissionPolicy.canInvite(loaded, currentUserId)) {
                    val listed = runCatching { repository.listChatInvites(chat.id) }.getOrDefault(emptyList())
                    invites = if (listed.isEmpty() && !chat.isPublicChannel) {
                        runCatching { repository.createChatInvite(chat.id, title = "Основная") }
                            .getOrNull()
                            ?.let { listOf(it) }
                            ?: listed
                    } else {
                        listed
                    }
                }
            }
    }

    val loaded = group
    val canInvite = loaded?.let { ChatPermissionPolicy.canInvite(it, currentUserId) }
        ?: ChatSettingsInviteUi.isCreator(chat, currentUserId)
    val canChangeInfo = loaded?.let { ChatPermissionPolicy.canChangeInfo(it, currentUserId) } == true
    val publicLink = ChatSettingsInviteUi.inviteLinkLabel(chat)

    if (canInvite) {
        InfoSection(
            title = "Пригласительные ссылки",
            contentPadding = 10.dp,
            contentSpacing = 6.dp,
        ) {
            if (publicLink.isNotBlank()) {
                InviteLinkRow(
                    link = publicLink,
                    copied = linkCopied == publicLink,
                    onCopy = {
                        SecureClipboard.copyWithAutoClear("chat-public-invite", publicLink)
                        linkCopied = publicLink
                        onHint(null)
                    },
                )
            }
            invites.forEach { invite ->
                val link = invite.link.ifBlank { "https://glagolitsa.app/join/${invite.token}" }
                InviteLinkRow(
                    link = link,
                    copied = linkCopied == link,
                    onCopy = {
                        SecureClipboard.copyWithAutoClear("chat-invite", link)
                        linkCopied = link
                        onHint(null)
                    },
                    onRevoke = {
                        busy = true
                        scope.launch {
                            runCatching { repository.revokeChatInvite(chat.id, invite.invite_id) }
                                .onSuccess {
                                    invites = invites.filterNot { it.invite_id == invite.invite_id }
                                    onHint("Ссылка отозвана")
                                }
                                .onFailure { onHint(it.message ?: "Не удалось отозвать") }
                            busy = false
                        }
                    },
                )
            }
        }
    }

    val editableGroup = loaded?.takeIf { canChangeInfo }
    if (editableGroup != null) {
        val settings = editableGroup.settings
        InfoSection(
            title = if (chat.isChannel) "Права подписчиков" else "Права участников",
            contentPadding = 12.dp,
            contentSpacing = 8.dp,
        ) {
            Text(
                text = "Как в Telegram: выкл — только админы, вкл — все участники.",
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextTertiary,
            )
            PermissionToggle(
                label = if (chat.isChannel) "Публиковать посты" else "Писать сообщения",
                allAllowed = settings.perm_send_messages == GroupPerms.ALL,
                enabled = !busy,
            ) { next ->
                scope.launch {
                    persistPerm(repository, chat.id, onHint) { copy(perm_send_messages = next) }
                        ?.also { group = it }
                }
            }
            if (!chat.isChannel) {
                PermissionToggle(
                    label = "Приглашать по ссылке",
                    allAllowed = settings.perm_invite == GroupPerms.ALL,
                    enabled = !busy,
                ) { next ->
                    scope.launch {
                        persistPerm(repository, chat.id, onHint) { copy(perm_invite = next) }
                            ?.also { group = it }
                    }
                }
            }
            PermissionToggle(
                label = "Закреплять сообщения",
                allAllowed = settings.perm_pin == GroupPerms.ALL,
                enabled = !busy,
            ) { next ->
                scope.launch {
                    persistPerm(repository, chat.id, onHint) { copy(perm_pin = next) }
                        ?.also { group = it }
                }
            }
            PermissionToggle(
                label = "Менять описание",
                allAllowed = settings.perm_change_info == GroupPerms.ALL,
                enabled = !busy,
            ) { next ->
                scope.launch {
                    persistPerm(repository, chat.id, onHint) { copy(perm_change_info = next) }
                        ?.also { group = it }
                }
            }
            if (chat.isChannel) {
                PermissionToggle(
                    label = "Комментировать",
                    allAllowed = settings.perm_comment != GroupPerms.ADMIN &&
                        settings.perm_comment != GroupPerms.NONE,
                    enabled = !busy,
                ) { next ->
                    scope.launch {
                        persistPerm(repository, chat.id, onHint) { copy(perm_comment = next) }
                            ?.also { group = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionToggle(
    label: String,
    allAllowed: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    PreferenceSwitchRow(
        label = label,
        checked = allAllowed,
        enabled = enabled,
        onCheckedChange = { checked ->
            onChange(if (checked) GroupPerms.ALL else GroupPerms.ADMIN)
        },
    )
}

@Composable
private fun InviteLinkRow(
    link: String,
    copied: Boolean,
    onCopy: () -> Unit,
    onRevoke: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = link,
            style = MaterialTheme.typography.bodySmall,
            color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.95f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (copied) "Готово" else "Копировать",
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable(onClick = onCopy)
                .padding(horizontal = 2.dp, vertical = 4.dp),
        )
        if (onRevoke != null) {
            Text(
                text = "Снять",
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.TextTertiary,
                modifier = Modifier
                    .clickable(onClick = onRevoke)
                    .padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }
    }
}

private suspend fun persistPerm(
    repository: MessengerRepository,
    groupId: String,
    onHint: (String?) -> Unit,
    update: UpdateGroupSettingsRequest.() -> UpdateGroupSettingsRequest,
): GroupResponse? {
    return runCatching {
        repository.updateGroupPermissions(groupId, UpdateGroupSettingsRequest().update())
    }.onFailure {
        onHint(it.message ?: "Не удалось сохранить права")
    }.getOrNull()
}
