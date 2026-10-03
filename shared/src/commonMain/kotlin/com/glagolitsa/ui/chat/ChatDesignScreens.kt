// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.glagolitsa.model.CallFeaturePolicy
import com.glagolitsa.model.CallType
import com.glagolitsa.ui.ClientFeatureFlags
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatPermissionPolicy
import com.glagolitsa.model.GroupResponse
import com.glagolitsa.model.GroupRoles
import com.glagolitsa.model.NotificationPreferences
import com.glagolitsa.model.StatusChannels
import com.glagolitsa.model.UpdateNotificationPreferencesRequest
import com.glagolitsa.model.listAvatarLabel
import com.glagolitsa.repository.AttachmentPreview
import com.glagolitsa.repository.GroupCallWorkflow
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.session.SessionStore
import com.glagolitsa.ui.AppBackHandler
import com.glagolitsa.ui.rememberCallAudioPermissionController
import com.glagolitsa.ui.components.GlagolitsaScreenTitle
import com.glagolitsa.ui.components.OrnamentGold
import com.glagolitsa.ui.components.SettingsGearMark
import com.glagolitsa.ui.components.screenSafeArea
import com.glagolitsa.ui.components.screenTopSafeArea
import com.glagolitsa.ui.profile.ProfileAvatar
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlinx.coroutines.launch

@Composable
fun ChatFavoritesPanel(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(GlagolitsaColors.Surface800.copy(alpha = 0.72f))
            .border(0.8.dp, GlagolitsaColors.GlassBorder, PanelShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlagolitsaScreenTitle(title = "Избранное", showMark = false)
        FavoriteRow(icon = "📎", title = "Презентация_концепция.pdf", meta = "2,4 МБ · вчера")
        FavoriteRow(icon = "🖼", title = "Макет_экрана.png", meta = "Изображение · 3 дня назад")
        FavoriteRow(icon = "🎙", title = "Голосовое сообщение", meta = "0:42 · понедельник", waveform = true)
    }
}

@Composable
private fun FavoriteRow(
    icon: String,
    title: String,
    meta: String,
    waveform: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GlagolitsaColors.Surface700.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = icon, style = MaterialTheme.typography.titleSmall)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = GlagolitsaColors.TextPrimary,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextSecondary,
            )
            if (waveform) {
                Spacer(modifier = Modifier.height(6.dp))
                VoiceWaveformPlaceholder()
            }
        }
    }
}

@Composable
private fun VoiceWaveformPlaceholder() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(4, 8, 12, 6, 14, 10, 7, 11, 5, 9, 13, 6).forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(OrnamentGold.copy(alpha = 0.75f)),
            )
        }
    }
}

@Composable
fun ChatInfoScreen(
    chat: Chat,
    repository: MessengerRepository,
    presenceStatus: String?,
    onBack: () -> Unit,
    onConversationRemoved: () -> Unit = onBack,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val user by SessionStore.user.collectAsState()
    val messages by repository.observeMainMessages(chat.id).collectAsState(initial = emptyList())
    val knownUserProfiles by repository.knownUserProfiles.collectAsState()
    val activeCall by repository.calls.activeCall.collectAsState()
    val presenceMap by repository.presence.partnerPresence.collectAsState()
    val mappedPartnerId = repository.dmPartnerFor(chat.id)
    val callPartnerId = remember(chat, user?.id, mappedPartnerId) {
        resolveChatCallPartnerId(chat, user?.id, mappedPartnerId)
    }
    val avatarUrl = if (chat.isDirectMessage) {
        callPartnerId?.let { knownUserProfiles[it]?.avatar_url ?: repository.avatarUrlForUser(it) }
    } else {
        chat.conversationIconUrl
    }
    var canEditIcon by remember(chat.id) { mutableStateOf(false) }
    var myRole by remember(chat.id) { mutableStateOf<String?>(null) }
    var iconSaving by remember(chat.id) { mutableStateOf(false) }
    var localIconUrl by remember(chat.id, chat.avatar_url) { mutableStateOf(chat.conversationIconUrl) }
    var callError by remember { mutableStateOf<String?>(null) }
    var notifPrefs by remember { mutableStateOf<NotificationPreferences?>(null) }
    var notifPrefsLoaded by remember { mutableStateOf(false) }
    var notifSaving by remember { mutableStateOf(false) }
    var notifError by remember { mutableStateOf<String?>(null) }
    var appearance by remember(chat.id) { mutableStateOf(ChatAppearanceSettings()) }
    var pinnedCount by remember(chat.id) { mutableStateOf(0) }

    LaunchedEffect(chat.id, user?.id, chat.type) {
        if (chat.isDirectMessage) {
            canEditIcon = false
            myRole = null
            return@LaunchedEffect
        }
        val group = runCatching { repository.loadGroup(chat.id) }.getOrNull()
        myRole = group?.members?.firstOrNull { it.user_id == user?.id }?.role
        canEditIcon = group?.let { ChatPermissionPolicy.canChangeInfo(it, user?.id) }
            ?: ChatSettingsInviteUi.isCreator(chat, user?.id)
    }

    LaunchedEffect(chat.id, chat.member_ids, callPartnerId, user?.id) {
        val ids = buildList {
            callPartnerId?.let { add(it) }
            addAll(chat.member_ids.filter { it != user?.id })
        }.distinct()
        if (ids.isNotEmpty()) {
            runCatching { repository.presence.fetchUsers(ids) }
                .onFailure { repository.recoverAuthFailure(it) }
            runCatching {
                if (chat.isDirectMessage) {
                    repository.refreshUserProfiles(ids)
                } else {
                    repository.ensureUserProfiles(ids)
                }
            }
                .onFailure { repository.recoverAuthFailure(it) }
        }
        notifPrefs = runCatching { repository.getNotificationPreferences() }
            .onFailure { repository.recoverAuthFailure(it) }
            .getOrNull()
        notifPrefsLoaded = true
    }

    val pinnedRevision by repository.pinnedMessagesRevision.collectAsState()
    LaunchedEffect(chat.id, pinnedRevision) {
        appearance = repository.loadChatAppearanceSettings(chat.id)
        pinnedCount = repository.countPinnedMessages(chat.id)
    }

    fun updateNotificationPrefs(
        request: UpdateNotificationPreferencesRequest,
        optimistic: (NotificationPreferences) -> NotificationPreferences,
    ) {
        val previous = notifPrefs ?: return
        notifPrefs = optimistic(previous)
        notifError = null
        notifSaving = true
        scope.launch {
            runCatching {
                repository.updateNotificationPreferences(request)
            }.onSuccess { updated ->
                notifPrefs = updated
            }.onFailure { err ->
                notifPrefs = previous
                notifError = err.message ?: "Не удалось сохранить настройки уведомлений"
            }
            notifSaving = false
        }
    }

    val stats = remember(chat, messages, presenceStatus, presenceMap, user?.id, callPartnerId) {
        buildChatInfoStats(
            chat = chat,
            messages = messages,
            presenceStatus = presenceStatus,
            presenceMap = presenceMap,
            currentUserId = user?.id,
            partnerUserId = callPartnerId,
        )
    }
    val mediaFiles = remember(messages) { buildChatMediaFiles(messages) }
    val attachmentPreviews by repository.attachmentPreviews.collectAsState()
    var selectedMediaFilter by remember(chat.id) { mutableStateOf<ChatMediaFilter?>(null) }
    var expandedMediaPreview by remember(chat.id) { mutableStateOf<AttachmentPreview?>(null) }
    var previewUnavailableFile by remember(chat.id) { mutableStateOf<ChatMediaFile?>(null) }
    var startingCall by remember(chat.id) { mutableStateOf(false) }
    val groupCallWorkflow = remember(repository) { GroupCallWorkflow(repository) }
    val audioPermission = rememberCallAudioPermissionController()
    val buttonColor = appearance.buttonColor.colorFor(appearance.darkness)
    val buttonContentColor = appearance.buttonColor.contentColor()
    val profileTextLines = remember(chat.isDirectMessage, callPartnerId, knownUserProfiles) {
        if (!chat.isDirectMessage) {
            StatusChannels.ProfileTextLines(status = null, about = null)
        } else {
            val profile = callPartnerId?.let { knownUserProfiles[it] }
            StatusChannels.profileTextLines(
                status = profile?.status,
                bio = profile?.bio,
            )
        }
    }
    val profileStatus = profileTextLines.status
    val profileAbout = profileTextLines.about

    fun selectMediaFilter(filter: ChatMediaFilter) {
        selectedMediaFilter = if (selectedMediaFilter == filter) null else filter
    }

    LaunchedEffect(activeCall?.session?.id) {
        if (activeCall == null) {
            startingCall = false
        }
    }

    fun startCall(type: String) {
        if (startingCall) return
        CallFeaturePolicy.startCallBlockedReason(ClientFeatureFlags.snapshot, type)?.let {
            callError = it
            return
        }
        if (chat.isGroup) {
            callError = null
            audioPermission.runWithPermission(
                onGranted = {
                    startingCall = true
                    scope.launch {
                        runCatching {
                            groupCallWorkflow.startAndShare(chat.id, chat.title, callType = type)
                        }.onFailure {
                            callError = it.message ?: "Не удалось начать звонок"
                            startingCall = false
                        }
                    }
                },
                onDenied = { callError = it },
            )
            return
        }
        val callee = callPartnerId
        if (callee.isNullOrBlank()) {
            callError = if (chat.isChannel) {
                "Звонки в каналах недоступны"
            } else {
                "Не удалось определить собеседника для звонка"
            }
            return
        }
        callError = null
        audioPermission.runWithPermission(
            onGranted = {
                startingCall = true
                scope.launch {
                    runCatching {
                        repository.calls.startOutgoingCall(callee, chat.title, callType = type)
                    }.onFailure {
                        callError = it.message ?: "Не удалось начать звонок"
                        startingCall = false
                    }
                }
            },
            onDenied = {
                callError = it
            },
        )
    }
    val showCallButton = stats.canCall
    val canStartCall = showCallButton && !startingCall
    val loadedNotificationPreferences = notifPrefs

    // Outer Box paints edge-to-edge; content Column is inset below the status bar
    // (same pattern as ChatScreen — avoids title under system icons).
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(chatAppearanceBackground(appearance.darkness))
            .screenSafeArea(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OrnamentIconButton(
                        size = 30.dp,
                        onClick = onBack,
                        contentDescription = "Назад",
                        iconSize = 16.dp,
                        icon = { tint, m -> BackOrnamentIcon(tint = tint, modifier = m) },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Информация о чате",
                        style = MaterialTheme.typography.titleMedium,
                        color = GlagolitsaColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (showCallButton) {
                    ActionPill(
                        text = if (startingCall) "Звоним..." else "Звонок",
                        color = buttonColor.copy(alpha = 0.82f),
                        contentColor = buttonContentColor,
                        enabled = canStartCall,
                        onClick = {
                            if (canStartCall) {
                                startCall(CallType.AUDIO)
                            }
                        },
                        contentDescription = "Аудиозвонок",
                        compact = true,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 330.dp)
                        .fillMaxWidth()
                        .clip(PanelShape)
                        .background(GlagolitsaColors.Surface800.copy(alpha = 0.58f))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (!chat.isDirectMessage && canEditIcon) {
                        ConversationIconEditor(
                            avatarUrl = localIconUrl ?: avatarUrl,
                            fallbackLabel = chat.listAvatarLabel(),
                            size = 104.dp,
                            saving = iconSaving,
                            hint = "Иконка из галереи",
                            onPicked = { dataUrl ->
                                localIconUrl = dataUrl
                                iconSaving = true
                                scope.launch {
                                    runCatching { repository.updateConversationIcon(chat.id, dataUrl) }
                                        .onFailure { localIconUrl = chat.conversationIconUrl }
                                    iconSaving = false
                                }
                            },
                        )
                    } else {
                        ProfileAvatar(
                            avatarUrl = localIconUrl ?: avatarUrl,
                            fallbackLabel = chat.listAvatarLabel(),
                            size = 104.dp,
                        )
                    }
                    Text(
                        text = chat.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = GlagolitsaColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    profileStatus?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = OrnamentGold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stats.presenceLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlagolitsaColors.TextSecondary,
                    )
                    if (stats.publicSlug != null) {
                        Text(
                            text = "@${stats.publicSlug}",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlagolitsaColors.TextTertiary,
                        )
                    }
                    callError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = GlagolitsaColors.AccentRed,
                        )
                    }
                }
            }

            if (chat.isDirectMessage) {
                InfoSection(title = "О себе") {
                    Text(
                        text = profileAbout ?: "Не указано",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (profileAbout == null) {
                            GlagolitsaColors.TextTertiary
                        } else {
                            GlagolitsaColors.TextPrimary
                        },
                    )
                }
            }

            InfoSection(title = "Уведомления") {
                if (!notifPrefsLoaded) {
                    InfoRow("Сообщения", "…")
                    InfoRow("Звонки", "…")
                } else if (loadedNotificationPreferences == null) {
                    InfoRow("Сообщения", "нет данных")
                    InfoRow("Звонки", "нет данных")
                } else {
                    val prefs = loadedNotificationPreferences
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        PreferenceSwitchRow(
                            label = "Сообщения",
                            checked = prefs.messages_enabled,
                            enabled = !notifSaving,
                            onCheckedChange = { checked ->
                                updateNotificationPrefs(
                                    request = UpdateNotificationPreferencesRequest(messages_enabled = checked),
                                    optimistic = { it.copy(messages_enabled = checked) },
                                )
                            },
                        )
                        PreferenceSwitchRow(
                            label = "Звонки",
                            checked = prefs.calls_enabled,
                            enabled = !notifSaving,
                            onCheckedChange = { checked ->
                                updateNotificationPrefs(
                                    request = UpdateNotificationPreferencesRequest(calls_enabled = checked),
                                    optimistic = { it.copy(calls_enabled = checked) },
                                )
                            },
                        )
                        PreferenceSwitchRow(
                            label = "Текст в пуше",
                            checked = prefs.show_message_preview,
                            enabled = !notifSaving,
                            onCheckedChange = { checked ->
                                updateNotificationPrefs(
                                    request = UpdateNotificationPreferencesRequest(show_message_preview = checked),
                                    optimistic = { it.copy(show_message_preview = checked) },
                                )
                            },
                        )
                    }
                    if (chat.isDirectMessage && !prefs.messages_enabled && !prefs.calls_enabled) {
                        InfoRow("Контакт", "В списке заблокированных")
                    }
                    notifError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = GlagolitsaColors.AccentRed,
                        )
                    }
                }
            }
            InfoSection(title = "Медиа и файлы") {
                InfoRow(
                    "Фото и видео",
                    stats.mediaCount.toString(),
                    onClick = { selectMediaFilter(ChatMediaFilter.Media) },
                )
                InfoRow(
                    "Документы",
                    stats.documentCount.toString(),
                    onClick = { selectMediaFilter(ChatMediaFilter.Documents) },
                )
            }
            selectedMediaFilter?.let { filter ->
                ChatMediaFilesPanel(
                    files = mediaFiles,
                    selectedFilter = filter,
                    attachmentPreviews = attachmentPreviews,
                    onFilterChange = { selectedMediaFilter = it },
                    onCollapse = { selectedMediaFilter = null },
                    onFileClick = { file ->
                        val preview = attachmentPreviews[file.messageId]
                        if (preview != null) {
                            expandedMediaPreview = preview
                        } else {
                            previewUnavailableFile = file
                        }
                    },
                )
            }
            InfoSection(title = "Закреплённые") {
                InfoRow("Закреплено", if (pinnedCount == 0) "Нет" else pinnedCount.toString())
            }
            if (chat.isChannel || chat.isGroup) {
                ChannelSubscriberSection(
                    chat = chat,
                    repository = repository,
                )
            }
            ConversationRemoveActionSection(
                chat = chat,
                currentUserId = user?.id,
                membershipRole = myRole,
                errorMessage = { it.message ?: "Не удалось удалить" },
                onRemove = repository::removeConversation,
                onRemoved = onConversationRemoved,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        expandedMediaPreview?.let { preview ->
            ChatInfoMediaPreviewDialog(
                preview = preview,
                onDismiss = { expandedMediaPreview = null },
            )
        }

        previewUnavailableFile?.let { file ->
            AlertDialog(
                onDismissRequest = { previewUnavailableFile = null },
                title = { Text("Превью недоступно") },
                text = {
                    Text(
                        if (file.kind == ChatMediaKind.Document) {
                            "Для документов пока нет встроенного просмотра."
                        } else {
                            "Не удалось построить превью для этого файла."
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { previewUnavailableFile = null }) {
                        Text("Понятно")
                    }
                },
            )
        }
    }
}

/**
 * Chat settings (from ⋮ in the chat top bar).
 *
 * Clean product surface: who this chat is, invite link, member actions, security for DMs.
 */
@Composable
fun ChatSettingsScreen(
    chat: Chat,
    repository: MessengerRepository,
    onBack: () -> Unit,
    onConversationRemoved: () -> Unit = onBack,
    onSafetyNumber: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showSafetyCodeHelp by remember { mutableStateOf(false) }
    var actionHint by remember(chat.id) { mutableStateOf<String?>(null) }
    var actionBusy by remember(chat.id) { mutableStateOf(false) }
    var isFollowing by remember(chat.id) { mutableStateOf(true) }
    var canEditIcon by remember(chat.id) { mutableStateOf(ChatSettingsInviteUi.isCreator(chat, SessionStore.user.value?.id)) }
    var iconSaving by remember(chat.id) { mutableStateOf(false) }
    var iconUrl by remember(chat.id, chat.avatar_url) { mutableStateOf(chat.conversationIconUrl) }
    var loadedGroup by remember(chat.id) { mutableStateOf<GroupResponse?>(null) }
    var myRole by remember(chat.id) { mutableStateOf<String?>(null) }
    val currentUserId = SessionStore.user.collectAsState().value?.id
    // Settings open from an open chat → membership list (ids may still be empty).
    val isMember = remember(chat.id, chat.member_ids, currentUserId) {
        ChatSettingsInviteUi.isMember(chat, currentUserId, openedFromMembership = true)
    }
    val showAudience = remember(chat.id, chat.creator_id, currentUserId, chat.type, myRole) {
        ChatSettingsInviteUi.showAudienceActions(chat, currentUserId, myRole)
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(chat.id, currentUserId) {
        isFollowing = runCatching { repository.isChannelFollowing(chat.id) }.getOrDefault(true)
        if (ChatSettingsInviteUi.isMultiParty(chat)) {
            val group = runCatching { repository.loadGroup(chat.id) }.getOrNull()
            loadedGroup = group
            myRole = group?.members?.firstOrNull { it.user_id == currentUserId }?.role
            canEditIcon = group?.let { ChatPermissionPolicy.canChangeInfo(it, currentUserId) }
                ?: ChatSettingsInviteUi.isCreator(chat, currentUserId)
        }
    }

    AppBackHandler(enabled = true, onBack = onBack)

    if (showSafetyCodeHelp) {
        AlertDialog(
            onDismissRequest = { showSafetyCodeHelp = false },
            title = { Text("Что такое код безопасности?") },
            text = {
                Text(
                    "Это проверка, что вы общаетесь именно с нужным человеком. " +
                        "Откройте код и сравните его с кодом собеседника (или QR) по другому доверенному каналу. " +
                        "Если коды совпадают — соединение подтверждено.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showSafetyCodeHelp = false }) {
                    Text("Понятно")
                }
            },
        )
    }

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
            GlagolitsaScreenTitle(
                title = "Настройки",
                leadingMark = { SettingsGearMark() },
            )
        }

        InfoSection(
            title = "О чате",
            contentPadding = 12.dp,
            contentSpacing = 6.dp,
        ) {
            if (ChatSettingsInviteUi.isMultiParty(chat)) {
                ConversationIconEditor(
                    avatarUrl = iconUrl,
                    fallbackLabel = chat.listAvatarLabel(),
                    enabled = canEditIcon && !iconSaving,
                    saving = iconSaving,
                    hint = if (canEditIcon) "Выбрать из галереи" else "Иконка чата",
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
            }
            InfoRow("Название", chat.title)
            InfoRow("Тип", ChatSettingsInviteUi.chatKindLabel(chat))
            if (ChatSettingsInviteUi.isMultiParty(chat) && myRole != null) {
                InfoRow("Роль", ChatSettingsInviteUi.membershipRoleLabel(chat, myRole))
                Text(
                    text = ChatSettingsInviteUi.membershipRoleDescription(chat, myRole),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.TextTertiary,
                )
            }
        }

        if (ChatSettingsInviteUi.isMultiParty(chat)) {
            ChatGovernancePanel(
                chat = chat,
                repository = repository,
                onHint = { actionHint = it },
            )
            ChannelSubscriberSection(
                chat = chat,
                repository = repository,
            )
        }

        val privileged = loadedGroup?.let { ChatPermissionPolicy.isAdmin(it, currentUserId) } == true ||
            myRole.equals(GroupRoles.OWNER, ignoreCase = true)
        // Members only: join / follow / leave. Owner/admin see role + governance instead.
        if (showAudience && !privileged) {
            InfoSection(
                title = "Участие",
                contentPadding = 12.dp,
                contentSpacing = 10.dp,
            ) {
                Text(
                    text = if (isMember) {
                        ChatSettingsInviteUi.membershipRoleDescription(chat, myRole ?: GroupRoles.MEMBER)
                    } else {
                        "Вступите, чтобы читать ленту и отслеживать обновления."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.TextTertiary,
                )
                if (ChatSettingsInviteUi.showJoinAction(chat, currentUserId, membershipRole = myRole)) {
                    ActionPill(
                        text = if (actionBusy) "…" else "Вступить",
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
                            text = if (actionBusy) "…" else "Прекратить отслеживание",
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
            errorMessage = { it.message ?: "Не удалось удалить" },
            onRequestRemove = { actionHint = null },
            onRemove = repository::removeConversation,
            onRemoved = onConversationRemoved,
        )

        if (onSafetyNumber != null && chat.isDirectMessage) {
            InfoSection(
                title = "Безопасность",
                contentPadding = 10.dp,
                contentSpacing = 4.dp,
            ) {
                SafetyCodeActionRow(
                    onHelpClick = { showSafetyCodeHelp = true },
                    onOpenClick = onSafetyNumber,
                )
            }
        }

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
