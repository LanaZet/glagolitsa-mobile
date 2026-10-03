// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glagolitsa.currentTimeMillis
import com.glagolitsa.model.ChannelSubscriberPolicy
import com.glagolitsa.model.Chat
import com.glagolitsa.model.GroupMemberDto
import com.glagolitsa.model.GroupResponse
import com.glagolitsa.model.GroupRoles
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.session.SessionStore
import com.glagolitsa.ui.profile.ProfileAvatar
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlinx.coroutines.launch

@Composable
internal fun ChannelSubscriberSection(
    chat: Chat,
    repository: MessengerRepository,
) {
    val listKind = chatMemberListKind(chat) ?: return
    val currentUser = SessionStore.user.collectAsState().value
    val currentUserId = currentUser?.id
    var group by remember(chat.id) { mutableStateOf<GroupResponse?>(null) }
    var loadError by remember(chat.id) { mutableStateOf<String?>(null) }
    var selected by remember(chat.id) { mutableStateOf<GroupMemberDto?>(null) }
    var actionError by remember(chat.id) { mutableStateOf<String?>(null) }
    var busy by remember(chat.id) { mutableStateOf(false) }
    val knownUsers by repository.knownUserProfiles.collectAsState()
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        runCatching { repository.loadGroup(chat.id) }
            .onSuccess {
                group = it
                loadError = null
                val ids = it.members.map { member -> member.user_id }
                runCatching { repository.rememberChatMembers(chat.id, ids) }
                currentUser?.let { me -> repository.rememberUsers(listOf(me)) }
                runCatching { repository.ensureUserProfiles(ids) }
            }
            .onFailure {
                loadError = listKind.loadError
            }
    }

    fun runSubscriberAction(
        target: GroupMemberDto,
        failureMessage: String,
        action: suspend () -> Unit,
    ) {
        busy = true
        actionError = null
        scope.launch {
            runCatching { action() }
                .onSuccess {
                    reload()
                    if (selected?.user_id == target.user_id) {
                        selected = null
                    }
                }
                .onFailure { actionError = it.message ?: failureMessage }
            busy = false
        }
    }

    LaunchedEffect(chat.id) { reload() }

    val loaded = group
    if (loaded == null) {
        InfoSection(title = listKind.title) {
            Text(
                text = loadError ?: "Загрузка…",
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextTertiary,
            )
        }
        return
    }
    if (!ChannelSubscriberPolicy.canViewList(loaded, currentUserId, listAlwaysVisible = listKind.listAlwaysVisible)) {
        InfoSection(title = listKind.title) {
            InfoRow("Всего", loaded.members.size.toString())
        }
        return
    }

    val nowMs = currentTimeMillis()
    val rows = ChannelSubscriberPolicy.subscribersForDisplay(loaded.members)
    InfoSection(title = listKind.title) {
        Text(
            text = chatMemberCountText(rows.size, listKind),
            style = MaterialTheme.typography.bodySmall,
            color = GlagolitsaColors.TextTertiary,
        )
        rows.forEach { member ->
            val user = knownUsers[member.user_id]
            val row = chatMemberRowUiModel(
                member = member,
                currentUser = currentUser,
                profile = user,
                cachedUsername = repository.usernameForSender(member.user_id),
                cachedAvatarUrl = repository.avatarUrlForUser(member.user_id),
                kind = listKind,
                nowMs = nowMs,
            )
            ChannelSubscriberRow(
                name = row.name,
                username = row.username,
                avatarUrl = row.avatarUrl,
                roleLabel = row.roleLabel,
                muted = row.muted,
                isSelf = row.isSelf,
                clickable = ChannelSubscriberPolicy.canOpenActions(loaded, currentUserId, member),
                onClick = { selected = member },
            )
        }
        actionError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.AccentRed,
            )
        }
    }

    val target = selected
    if (target != null) {
        val user = knownUsers[target.user_id]
        val name = chatMemberDisplayName(
            memberUserId = target.user_id,
            currentUser = currentUser,
            profile = user,
            cachedUsername = repository.usernameForSender(target.user_id),
        )
        ChannelSubscriberActionsDialog(
            name = name,
            kind = listKind,
            muted = ChannelSubscriberPolicy.isMuted(target, currentTimeMillis()),
            actions = ChannelSubscriberPolicy.availableActions(
                group = loaded,
                actorId = currentUserId,
                target = target,
                nowMs = currentTimeMillis(),
            ),
            busy = busy,
            error = actionError,
            onDismiss = {
                if (!busy) {
                    selected = null
                    actionError = null
                }
            },
            onRestrict = { minutes ->
                runSubscriberAction(target, "Не удалось ограничить") {
                    repository.muteChannelSubscriber(chat.id, target.user_id, minutes)
                }
            },
            onUnrestrict = {
                runSubscriberAction(target, "Не удалось снять ограничение") {
                    repository.unmuteChannelSubscriber(chat.id, target.user_id)
                }
            },
            onKick = {
                runSubscriberAction(target, "Не удалось исключить") {
                    repository.kickChannelSubscriber(chat.id, target.user_id)
                }
            },
            onBan = {
                runSubscriberAction(target, "Не удалось заблокировать") {
                    repository.banChannelSubscriber(chat.id, target.user_id)
                }
            },
            onChangeRole = { role ->
                val fail = when (role) {
                    GroupRoles.ADMIN -> "Не удалось назначить админа"
                    GroupRoles.OWNER -> "Не удалось передать владение"
                    else -> "Не удалось забрать роль"
                }
                runSubscriberAction(target, fail) {
                    repository.updateChannelSubscriberRole(chat.id, target.user_id, role)
                }
            },
        )
    }
}

@Composable
private fun ChannelSubscriberRow(
    name: String,
    username: String?,
    avatarUrl: String?,
    roleLabel: String,
    muted: Boolean,
    isSelf: Boolean,
    clickable: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProfileAvatar(
            avatarUrl = avatarUrl,
            fallbackLabel = name,
            size = 36.dp,
            showPresenceRing = false,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            val subtitle = buildList {
                username?.takeIf { it.isNotBlank() }?.let { add("@$it") }
                add(roleLabel)
                if (isSelf) add("вы")
                if (muted) add("ограничен")
            }.joinToString(" · ")
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (muted) GlagolitsaColors.AccentRed else GlagolitsaColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChannelSubscriberActionsDialog(
    name: String,
    kind: ChatMemberListKind,
    muted: Boolean,
    actions: List<ChannelSubscriberPolicy.Action>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRestrict: (Int) -> Unit,
    onUnrestrict: () -> Unit,
    onKick: () -> Unit,
    onBan: () -> Unit,
    onChangeRole: (String) -> Unit,
) {
    var confirmOwner by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val roleActions = listOf(
                    ChannelSubscriberPolicy.Action.PromoteAdmin,
                    ChannelSubscriberPolicy.Action.DemoteMember,
                    ChannelSubscriberPolicy.Action.TransferOwner,
                )
                if (roleActions.any { it in actions }) {
                    Text(
                        text = "Роль",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlagolitsaColors.TextSecondary,
                    )
                    if (ChannelSubscriberPolicy.Action.PromoteAdmin in actions) {
                        RestrictionChip(
                            label = "Сделать админом",
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onChangeRole(GroupRoles.ADMIN) },
                        )
                    }
                    if (ChannelSubscriberPolicy.Action.DemoteMember in actions) {
                        RestrictionChip(
                            label = "Забрать админа",
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onChangeRole(GroupRoles.MEMBER) },
                        )
                    }
                    if (ChannelSubscriberPolicy.Action.TransferOwner in actions) {
                        if (confirmOwner) {
                            Text(
                                text = "Вы перестанете быть владельцем. $name станет владельцем ${kind.ownerTargetLabel}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = GlagolitsaColors.TextSecondary,
                            )
                            RestrictionChip(
                                label = "Передать владение",
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onChangeRole(GroupRoles.OWNER) },
                            )
                        } else {
                            RestrictionChip(
                                label = "Сделать владельцем",
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { confirmOwner = true },
                            )
                        }
                    }
                }
                if (ChannelSubscriberPolicy.Action.Restrict in actions) {
                    Text(
                        text = "Ограничить на время",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlagolitsaColors.TextSecondary,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChannelSubscriberPolicy.restrictDurations.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                pair.forEach { duration ->
                                    RestrictionChip(
                                        label = duration.label,
                                        enabled = !busy,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onRestrict(duration.minutes) },
                                    )
                                }
                                if (pair.size == 1) {
                                    Box(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                if (ChannelSubscriberPolicy.Action.Unrestrict in actions) {
                    TextButton(onClick = onUnrestrict, enabled = !busy) {
                        Text("Снять ограничения")
                    }
                }
                if (ChannelSubscriberPolicy.Action.Kick in actions) {
                    TextButton(onClick = onKick, enabled = !busy) {
                        Text(kind.kickLabel)
                    }
                }
                if (ChannelSubscriberPolicy.Action.Ban in actions) {
                    TextButton(onClick = onBan, enabled = !busy) {
                        Text("Заблокировать навсегда")
                    }
                }
                if (muted) {
                    Text(
                        text = "Сейчас действуют ограничения.",
                        style = MaterialTheme.typography.labelSmall,
                        color = GlagolitsaColors.TextTertiary,
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = GlagolitsaColors.AccentRed,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Закрыть")
            }
        },
    )
}

@Composable
private fun RestrictionChip(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(GlagolitsaColors.Surface600.copy(alpha = if (enabled) 0.9f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = GlagolitsaColors.TextPrimary,
        )
    }
}
