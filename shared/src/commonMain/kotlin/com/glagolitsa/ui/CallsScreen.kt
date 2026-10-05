// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.CallSession
import com.glagolitsa.model.CallStatus
import com.glagolitsa.model.historyDetailsLabel
import com.glagolitsa.model.partnerIdFor
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.session.SessionStore
import com.glagolitsa.ui.components.AppEmptyState
import com.glagolitsa.ui.components.AppTopBar
import com.glagolitsa.ui.components.AppTopBarTextAction
import com.glagolitsa.ui.i18n.tr
import com.glagolitsa.ui.profile.ProfileAvatar
import com.glagolitsa.ui.theme.GlagolitsaColors

@Composable
fun CallsScreen(
    repository: MessengerRepository,
    bottomContentPadding: Dp = 0.dp,
    onBack: () -> Unit = {},
    onIncomingCall: (CallSession) -> Unit = {},
) {
    val user by SessionStore.user.collectAsState()
    val history by repository.calls.callHistory.collectAsState()
    val knownUserProfiles by repository.knownUserProfiles.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository) {
        loading = true
        error = null
        runCatching { repository.calls.refreshHistory() }
            .onFailure { error = it.message }
        loading = false
    }

    AppBackHandler {
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GlagolitsaColors.ScreenGradient)
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = bottomContentPadding),
    ) {
        AppTopBar(
            title = tr("Звонки", "Calls"),
            modifier = Modifier.padding(bottom = 12.dp),
            actions = {
                AppTopBarTextAction(text = tr("Назад", "Back"), onClick = onBack)
            },
        )

        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = GlagolitsaColors.AccentRed)
            }

            error != null -> Text(
                text = error ?: "",
                color = MaterialTheme.colorScheme.error,
            )

            history.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AppEmptyState(
                    title = tr("История звонков пуста", "No call history yet"),
                    message = tr(
                        "Входящие и исходящие звонки появятся здесь.",
                        "Incoming and outgoing calls will appear here.",
                    ),
                )
            }

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(history, key = { it.id }) { call ->
                    val selfId = user?.id
                    val partnerId = selfId?.let { call.partnerIdFor(it) }
                    val partnerName = partnerId?.let { repository.usernameForSender(it) }
                        ?: tr("Контакт", "Contact")
                    val partnerAvatarUrl = partnerId?.let { knownUserProfiles[it]?.avatar_url ?: repository.avatarUrlForUser(it) }
                    val isIncomingRinging =
                        call.status == CallStatus.RINGING && call.callee_id == selfId

                    CallHistoryRow(
                        partnerName = partnerName,
                        partnerAvatarUrl = partnerAvatarUrl,
                        details = call.historyDetailsLabel(selfId),
                        onClick = {
                            if (isIncomingRinging) {
                                repository.calls.presentIncoming(call)
                                onIncomingCall(call)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CallHistoryRow(
    partnerName: String,
    partnerAvatarUrl: String?,
    details: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) GlagolitsaColors.SurfacePressed else GlagolitsaColors.SurfacePanel)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = tr(
                    "Открыть звонок с $partnerName: $details",
                    "Open call with $partnerName: $details",
                )
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileAvatar(
            avatarUrl = partnerAvatarUrl,
            fallbackLabel = partnerName.take(2),
            size = 44.dp,
            presenceColor = GlagolitsaColors.OrnamentGold,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = partnerName,
                style = MaterialTheme.typography.titleSmall,
                color = GlagolitsaColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
