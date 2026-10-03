// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.CallRole
import com.glagolitsa.model.CallStatus
import com.glagolitsa.model.CallUiState
import com.glagolitsa.model.activeStatusLabel
import com.glagolitsa.model.callJoinLink
import com.glagolitsa.model.displayAvatarUrl
import com.glagolitsa.model.isGroupChatCall
import com.glagolitsa.model.partnerIdFor
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.session.SessionStore
import com.glagolitsa.security.SecureClipboard
import com.glagolitsa.ui.components.GlagolitsaButton
import com.glagolitsa.ui.components.GlagolitsaButtonStyle
import com.glagolitsa.ui.profile.ProfileAvatar
import com.glagolitsa.ui.theme.GlagolitsaColors
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.chat_call_rotary_phone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

@Composable
fun CallScreen(
    state: CallUiState,
    repository: MessengerRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var elapsedSec by remember(state.session.id, state.session.connected_at) {
        mutableIntStateOf(state.session.duration_sec)
    }
    var actionBusy by remember(state.session.id) { mutableStateOf(false) }
    var actionError by remember(state.session.id) { mutableStateOf<String?>(null) }
    val audioPermission = rememberCallAudioPermissionController()
    val selfId = SessionStore.user.collectAsState().value?.id
    val knownProfiles by repository.knownUserProfiles.collectAsState()
    val peerId = remember(state.session, selfId) {
        selfId?.let { state.session.partnerIdFor(it) } ?: state.session.caller_id
    }
    var chatAvatarUrl by remember(state.session.id, state.session.chat_id) { mutableStateOf<String?>(null) }

    LaunchedEffect(peerId, state.session.chat_id, state.session.isGroupChatCall()) {
        if (state.session.isGroupChatCall()) {
            val chatId = state.session.chat_id
            chatAvatarUrl = chatId?.let { repository.getLocalChat(it)?.conversationIconUrl }
        }
        val id = peerId?.trim().orEmpty()
        if (id.isNotEmpty()) {
            val cached = knownProfiles[id]?.avatar_url ?: repository.avatarUrlForUser(id)
            if (cached.isNullOrBlank()) {
                runCatching { repository.refreshUserProfiles(listOf(id)) }
            }
        }
    }

    val avatarUrl = remember(state.session, selfId, knownProfiles, chatAvatarUrl) {
        state.session.displayAvatarUrl(
            selfId = selfId,
            userAvatarUrl = { id -> knownProfiles[id]?.avatar_url ?: repository.avatarUrlForUser(id) },
            chatAvatarUrl = chatAvatarUrl,
        )
    }

    LaunchedEffect(state.session.status, state.session.connected_at) {
        if (state.session.status != CallStatus.ACTIVE) return@LaunchedEffect
        while (true) {
            delay(1000)
            elapsedSec += 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        GlagolitsaColors.Background950,
                        GlagolitsaColors.Background900,
                        GlagolitsaColors.Surface800,
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = state.activeStatusLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = GlagolitsaColors.TextSecondary,
                )
                state.lowBandwidthNotice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlagolitsaColors.StatusWarning,
                        textAlign = TextAlign.Center,
                    )
                }
                actionError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlagolitsaColors.StatusError,
                        textAlign = TextAlign.Center,
                    )
                }
                ProfileAvatar(
                    avatarUrl = avatarUrl,
                    fallbackLabel = state.partnerName,
                    size = 120.dp,
                    showPresenceRing = false,
                )
                Text(
                    text = state.partnerName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = GlagolitsaColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                if (state.session.isGroupChatCall() && !state.session.id.startsWith("local-call-")) {
                    Text(
                        text = "Скопировать ссылку",
                        style = MaterialTheme.typography.labelLarge,
                        color = GlagolitsaColors.OrnamentGold,
                        modifier = Modifier.clickable {
                            SecureClipboard.copyWithAutoClear(
                                "group-call",
                                callJoinLink(state.session.id),
                            )
                        },
                    )
                }
                if (state.session.status == CallStatus.ACTIVE) {
                    Text(
                        text = formatDuration(elapsedSec),
                        style = MaterialTheme.typography.titleMedium,
                        color = GlagolitsaColors.TextSecondary,
                    )
                }
                if (state.connectingMedia) {
                    Text(
                        text = "E2EE · подключение к комнате…",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlagolitsaColors.TextTertiary,
                    )
                }
            }

            when {
                state.session.status == CallStatus.RINGING && state.role == CallRole.Incoming -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                        CallActionCircle(
                            label = "Отклонить",
                            tint = GlagolitsaColors.StatusError,
                            enabled = !actionBusy,
                            onClick = {
                                if (actionBusy) return@CallActionCircle
                                actionBusy = true
                                actionError = null
                                repository.calls.rejectCallInBackground(state.session.id)
                            },
                        )
                        CallActionCircle(
                            label = "Принять",
                            tint = GlagolitsaColors.StatusSuccess,
                            enabled = !actionBusy,
                            icon = { modifier ->
                                Image(
                                    painter = painterResource(Res.drawable.chat_call_rotary_phone),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = modifier,
                                )
                            },
                            onClick = {
                                if (actionBusy) return@CallActionCircle
                                audioPermission.runWithPermission(
                                    onGranted = {
                                        scope.launch {
                                            actionBusy = true
                                            actionError = null
                                            runCatching { repository.calls.acceptCall(state.session.id) }
                                                .onFailure {
                                                    actionError = it.message ?: "Не удалось принять звонок"
                                                }
                                            // Hard cap: never leave Accept disabled for full HTTP 15s.
                                            actionBusy = false
                                        }
                                    },
                                    onDenied = {
                                        actionError = it
                                    },
                                )
                            },
                        )
                    }
                }

                state.session.status == CallStatus.ENDED ||
                    state.session.status == CallStatus.REJECTED -> {
                    GlagolitsaButton(
                        onClick = {
                            repository.calls.dismissCall()
                            onDismiss()
                        },
                        style = GlagolitsaButtonStyle.Primary,
                    ) {
                        Text("Закрыть")
                    }
                }

                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        CallToggleButton(
                            label = if (state.muted) "Вкл. звук" else "Без звука",
                            active = state.muted,
                            onClick = {
                                scope.launch {
                                    repository.calls.setMutedMedia(!state.muted)
                                }
                            },
                        )
                        CallActionCircle(
                            label = "Завершить",
                            tint = GlagolitsaColors.AccentRed,
                            large = true,
                            enabled = !actionBusy,
                            onClick = {
                                if (actionBusy) return@CallActionCircle
                                actionBusy = true
                                actionError = null
                                repository.calls.endCallInBackground(state.session.id)
                            },
                        )
                        CallToggleButton(
                            label = if (state.speakerOn) "Телефон" else "Динамик",
                            active = state.speakerOn,
                            onClick = {
                                scope.launch {
                                    repository.calls.setSpeakerMedia(!state.speakerOn)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallActionCircle(
    label: String,
    tint: Color,
    large: Boolean = false,
    enabled: Boolean = true,
    icon: (@Composable (Modifier) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val size = if (large) 72.dp else 64.dp
    val iconSize = if (large) 38.dp else 34.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.92f))
                .border(1.dp, GlagolitsaColors.GlassBorder, CircleShape)
                .alpha(if (enabled) 1f else 0.55f)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                icon(Modifier.size(iconSize))
            } else {
                Text(
                    text = label.first().uppercaseChar().toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = GlagolitsaColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = GlagolitsaColors.TextSecondary,
        )
    }
}

@Composable
private fun CallToggleButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (active) GlagolitsaColors.AccentRed.copy(alpha = 0.35f)
                    else GlagolitsaColors.Surface700.copy(alpha = 0.9f),
                )
                .border(1.dp, GlagolitsaColors.GlassBorder, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "•",
                style = MaterialTheme.typography.titleLarge,
                color = GlagolitsaColors.TextPrimary,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = GlagolitsaColors.TextSecondary,
        )
    }
}

private fun formatDuration(totalSec: Int): String {
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
