// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.Message
import com.glagolitsa.model.User
import com.glagolitsa.model.avatarLabel
import com.glagolitsa.model.displayLabel
import com.glagolitsa.ui.profile.ProfileAvatar
import com.glagolitsa.ui.theme.GlagolitsaColors
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.chat_send_button
import org.jetbrains.compose.resources.painterResource

@Composable
fun ForwardMessageDialog(
    source: Message,
    query: String,
    recipients: List<User>,
    searching: Boolean,
    forwardingUserId: String?,
    error: String?,
    onQueryChange: (String) -> Unit,
    onForwardToUser: (User) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переслать сообщение") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = source.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    label = { Text("Найти пользователя") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = GlagolitsaColors.AccentRed,
                        strokeWidth = 2.dp,
                    )
                }
                ForwardRecipientList(
                    query = query,
                    recipients = recipients,
                    searching = searching,
                    forwardingUserId = forwardingUserId,
                    onForwardToUser = onForwardToUser,
                )
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
            TextButton(onClick = onDismiss, enabled = forwardingUserId == null) {
                Text("Закрыть")
            }
        },
    )
}

@Composable
private fun ForwardRecipientList(
    query: String,
    recipients: List<User>,
    searching: Boolean,
    forwardingUserId: String?,
    onForwardToUser: (User) -> Unit,
) {
    if (recipients.isEmpty() && !searching) {
        Text(
            text = if (query.trim().length >= MIN_FORWARD_SEARCH_QUERY_LENGTH) {
                "Пользователи не найдены"
            } else {
                "Начните вводить @username"
            },
            style = MaterialTheme.typography.bodySmall,
            color = GlagolitsaColors.TextTertiary,
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(recipients, key = { it.id }) { recipient ->
            ForwardRecipientRow(
                user = recipient,
                loading = forwardingUserId == recipient.id,
                onForward = { onForwardToUser(recipient) },
            )
        }
    }
}

@Composable
private fun ForwardRecipientRow(
    user: User,
    loading: Boolean,
    onForward: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GlagolitsaColors.Surface700.copy(alpha = 0.36f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(
            avatarUrl = user.avatar_url,
            fallbackLabel = user.avatarLabel(),
            size = 30.dp,
            presenceColor = null,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "@${user.username}",
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = user.displayLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onForward, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = GlagolitsaColors.AccentRed,
                    strokeWidth = 2.dp,
                )
            } else {
                Image(
                    painter = painterResource(Res.drawable.chat_send_button),
                    contentDescription = "Открыть чат и переслать",
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

internal const val MIN_FORWARD_SEARCH_QUERY_LENGTH = 2
