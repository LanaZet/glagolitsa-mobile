// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatCreationPolicy
import com.glagolitsa.model.ChatVisibility
import com.glagolitsa.model.listAvatarLabel
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.ui.chat.ConversationIconEditor
import com.glagolitsa.ui.components.AppTopBar
import com.glagolitsa.ui.components.AppTopBarTextAction
import com.glagolitsa.ui.components.GlassTextField
import com.glagolitsa.ui.components.GlagolitsaButton
import com.glagolitsa.ui.components.screenTopSafeArea
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlinx.coroutines.launch

/**
 * Create channel form (private-only for now).
 * Cancel (leading) / title / Create (trailing, disabled until valid).
 */
@Composable
fun CreateChannelScreen(
    repository: MessengerRepository,
    onCancel: () -> Unit,
    onCreated: (Chat) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var avatarUrl by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Product temporarily ships private channels only (no public slug / discover).
    val visibility = ChatVisibility.PRIVATE
    val canCreate = ChatCreationPolicy.canCreateChannel(
        title = title,
        visibility = visibility,
        slug = "",
        slugAvailable = true,
    )

    fun submit() {
        if (!canCreate || creating) return
        creating = true
        error = null
        scope.launch {
            runCatching {
                repository.createChannel(
                    title = title,
                    description = description,
                    visibility = visibility,
                    slug = null,
                    avatarUrl = avatarUrl,
                )
            }.onSuccess { chat ->
                onCreated(chat)
            }.onFailure {
                error = it.message ?: "Не удалось создать канал"
                creating = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GlagolitsaColors.Background950)
            .screenTopSafeArea()
            .padding(horizontal = 16.dp),
    ) {
        AppTopBar(
            title = "Новый канал",
            navigation = {
                AppTopBarTextAction(
                    text = "Отмена",
                    onClick = onCancel,
                    enabled = !creating,
                    color = GlagolitsaColors.AccentRed,
                    modifier = Modifier.semantics { contentDescription = "Отмена" },
                )
            },
            actions = {
                if (creating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(18.dp)
                            .padding(end = 8.dp),
                        color = GlagolitsaColors.AccentRed,
                        strokeWidth = 2.dp,
                    )
                } else {
                    AppTopBarTextAction(
                        text = "Создать",
                        onClick = { submit() },
                        enabled = canCreate,
                        fontWeight = FontWeight.SemiBold,
                        color = GlagolitsaColors.AccentRed,
                        modifier = Modifier
                            .testTag("channel-create-submit")
                            .semantics { contentDescription = "Создать канал" },
                    )
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            InsetSection(title = "О канале") {
                ConversationIconEditor(
                    avatarUrl = avatarUrl,
                    fallbackLabel = Chat(id = "draft", title = title).listAvatarLabel(),
                    onPicked = { avatarUrl = it },
                    enabled = !creating,
                    size = 96.dp,
                    hint = "Иконка канала из галереи",
                )
                GlassTextField(
                    value = title,
                    onValueChange = { title = it.take(64) },
                    label = "Название",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("channel-title"),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                GlassTextField(
                    value = description,
                    onValueChange = { description = it.take(280) },
                    label = "Описание (необязательно)",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                )
            }

            InsetSection(title = "Доступ") {
                Text(
                    text = "Приватный",
                    style = MaterialTheme.typography.titleSmall,
                    color = GlagolitsaColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = ChatCreationPolicy.visibilitySubtitle(ChatVisibility.PRIVATE),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.TextSecondary,
                )
            }

            Text(
                text = ChatCreationPolicy.encryptionDisclaimer(visibility),
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            error?.let {
                Text(
                    text = it,
                    color = GlagolitsaColors.AccentRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            GlagolitsaButton(
                onClick = { submit() },
                enabled = canCreate && !creating,
                loading = creating,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("channel-create-button"),
            ) {
                Text(
                    text = "Создать канал",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InsetSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.TextTertiary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(GlagolitsaColors.Surface800.copy(alpha = 0.55f))
                .padding(14.dp),
        ) {
            content()
        }
    }
}
