// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.ChannelSubscriberPolicy
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatPermissionPolicy
import com.glagolitsa.model.GroupResponse
import com.glagolitsa.model.listAvatarLabel
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.session.SessionStore
import com.glagolitsa.ui.AppBackHandler
import com.glagolitsa.ui.chat.ActionPill
import com.glagolitsa.ui.chat.BackOrnamentIcon
import com.glagolitsa.ui.chat.ChannelSubscriberSection
import com.glagolitsa.ui.chat.ChatGovernancePanel
import com.glagolitsa.ui.chat.ChatSettingsInviteUi
import com.glagolitsa.ui.chat.ConversationIconEditor
import com.glagolitsa.ui.chat.ConversationRemoveActionSection
import com.glagolitsa.ui.chat.InfoRow
import com.glagolitsa.ui.chat.InfoSection
import com.glagolitsa.ui.chat.OrnamentIconButton
import com.glagolitsa.ui.components.GlagolitsaScreenTitle
import com.glagolitsa.ui.components.screenTopSafeArea
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlinx.coroutines.launch

/**
 * Channel-only settings menu (not chat/DM settings).
 * Audience: follow / join / leave. Creator: invite link.
 */
@Composable
fun ChannelSettingsScreen(
    chat: Chat,
    repository: MessengerRepository,
    onBack: () -> Unit,
    onConversationRemoved: () -> Unit = onBack,
    modifier: Modifier = Modifier,
) {
    var actionHint by remember(chat.id) { mutableStateOf<String?>(null) }
    var actionBusy by remember(chat.id) { mutableStateOf(false) }
    var isFollowing by remember(chat.id) { mutableStateOf(true) }
    var myRoleLabel by remember(chat.id) { mutableStateOf<String?>(null) }
    var group by remember(chat.id) { mutableStateOf<GroupResponse?>(null) }
    var iconUrl by remember(chat.id, chat.avatar_url) { mutableStateOf(chat.conversationIconUrl) }
    var iconSaving by remember(chat.id) { mutableStateOf(false) }
    val currentUserId = SessionStore.user.collectAsState().value?.id
    val isMember = remember(chat.id, chat.member_ids, currentUserId) {
        ChatSettingsInviteUi.isMember(chat, currentUserId, openedFromMembership = true)
    }
    val myRole = group?.members?.firstOrNull { it.user_id == currentUserId }?.role
    val showAudience = remember(chat.id, chat.creator_id, currentUserId, chat.type, myRole) {
        ChatSettingsInviteUi.showAudienceActions(chat, currentUserId, myRole)
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(chat.id, currentUserId) {
        isFollowing = runCatching { repository.isChannelFollowing(chat.id) }.getOrDefault(true)
        val loaded = runCatching { repository.loadGroup(chat.id) }.getOrNull()
        group = loaded
        val role = loaded?.members?.firstOrNull { it.user_id == currentUserId }?.role
        myRoleLabel = role?.let { ChannelSubscriberPolicy.roleLabel(it) }
    }

    AppBackHandler(enabled = true, onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GlagolitsaColors.Background950)
            .screenTopSafeArea()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrnamentIconButton(
                size = 32.dp,
                onClick = onBack,
                contentDescription = "Назад",
                iconSize = 18.dp,
                icon = { tint, m -> BackOrnamentIcon(tint = tint, modifier = m) },
            )
            Spacer(modifier = Modifier.width(8.dp))
            GlagolitsaScreenTitle(title = "Канал")
        }

        InfoSection(
            title = "О канале",
            contentPadding = 12.dp,
            contentSpacing = 6.dp,
        ) {
            val canEditIcon = group?.let { ChatPermissionPolicy.canChangeInfo(it, currentUserId) }
                ?: ChatSettingsInviteUi.isCreator(chat, currentUserId)
            ConversationIconEditor(
                avatarUrl = iconUrl,
                fallbackLabel = chat.listAvatarLabel(),
                enabled = canEditIcon && !iconSaving,
                saving = iconSaving,
                hint = if (canEditIcon) "Иконка канала из галереи" else "Иконка канала",
                onPicked = { dataUrl ->
                    iconUrl = dataUrl
                    iconSaving = true
                    actionHint = null
                    scope.launch {
                        runCatching { repository.updateConversationIcon(chat.id, dataUrl) }
                            .onSuccess { actionHint = "Иконка обновлена" }
                            .onFailure {
                                iconUrl = chat.conversationIconUrl
                                actionHint = it.message ?: "Не удалось сохранить иконку"
                            }
                        iconSaving = false
                    }
                },
            )
            InfoRow("Название", chat.title.ifBlank { "—" })
            InfoRow("Тип", ChatSettingsInviteUi.chatKindLabel(chat))
            chat.slug?.takeIf { it.isNotBlank() }?.let { slug ->
                InfoRow("Адрес", "@$slug")
            }
            Text(
                text = "Здесь публикуются посты. Это не переписка и не групповой чат.",
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        ChatGovernancePanel(
            chat = chat,
            repository = repository,
            onHint = { actionHint = it },
        )

        ChannelSubscriberSection(
            chat = chat,
            repository = repository,
        )

        if (showAudience) {
            InfoSection(
                title = "Подписка",
                contentPadding = 12.dp,
                contentSpacing = 10.dp,
            ) {
                if (isMember) {
                    InfoRow("Роль", myRoleLabel ?: "подписчик")
                }
                Text(
                    text = if (isMember) {
                        "Можно отключить уведомления-отслеживание или выйти."
                    } else {
                        "Вступите в канал, чтобы читать ленту."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.TextTertiary,
                )
                if (ChatSettingsInviteUi.showJoinAction(chat, currentUserId, membershipRole = myRole)) {
                    ActionPill(
                        text = if (actionBusy) "…" else "Вступить в канал",
                        color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.88f),
                        contentColor = GlagolitsaColors.TextOnGold,
                        enabled = !actionBusy,
                        onClick = {
                            val slug = chat.slug?.trim().orEmpty()
                            if (slug.isEmpty()) return@ActionPill
                            actionBusy = true
                            actionHint = null
                            scope.launch {
                                runCatching { repository.joinChannelBySlug(slug) }
                                    .onSuccess {
                                        actionHint = "Вы вступили в канал"
                                        runCatching { repository.setChannelFollowing(chat.id, true) }
                                        isFollowing = true
                                    }
                                    .onFailure {
                                        actionHint = it.message ?: "Не удалось вступить"
                                    }
                                actionBusy = false
                            }
                        },
                        compact = true,
                        contentDescription = "Вступить в канал",
                    )
                }
                if (ChatSettingsInviteUi.showFollowActions(chat, currentUserId, membershipRole = myRole)) {
                    if (isFollowing) {
                        ActionPill(
                            text = if (actionBusy) "…" else "Не отслеживать",
                            color = GlagolitsaColors.Surface600.copy(alpha = 0.9f),
                            contentColor = GlagolitsaColors.TextPrimary,
                            enabled = !actionBusy,
                            onClick = {
                                actionBusy = true
                                scope.launch {
                                    runCatching { repository.setChannelFollowing(chat.id, false) }
                                        .onSuccess {
                                            isFollowing = false
                                            actionHint = "Отслеживание выключено"
                                        }
                                        .onFailure {
                                            actionHint = it.message ?: "Не удалось сохранить"
                                        }
                                    actionBusy = false
                                }
                            },
                            compact = true,
                            contentDescription = "Прекратить отслеживание канала",
                        )
                    } else {
                        ActionPill(
                            text = if (actionBusy) "…" else "Отслеживать",
                            color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.88f),
                            contentColor = GlagolitsaColors.TextOnGold,
                            enabled = !actionBusy,
                            onClick = {
                                actionBusy = true
                                scope.launch {
                                    runCatching { repository.setChannelFollowing(chat.id, true) }
                                        .onSuccess {
                                            isFollowing = true
                                            actionHint = "Вы отслеживаете канал"
                                        }
                                        .onFailure {
                                            actionHint = it.message ?: "Не удалось сохранить"
                                        }
                                    actionBusy = false
                                }
                            },
                            compact = true,
                            contentDescription = "Отслеживать канал",
                        )
                    }
                }
            }
        }

        ConversationRemoveActionSection(
            chat = chat,
            currentUserId = currentUserId,
            membershipRole = myRole,
            enabled = !actionBusy,
            errorMessage = { it.message ?: "Не удалось удалить канал" },
            onRequestRemove = { actionHint = null },
            onRemove = repository::removeConversation,
            onRemoved = onConversationRemoved,
        )

        actionHint?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.9f),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
