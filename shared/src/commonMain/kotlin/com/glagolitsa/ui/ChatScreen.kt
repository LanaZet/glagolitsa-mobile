// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.glagolitsa.log.AppLog
import com.glagolitsa.model.CallFeaturePolicy
import com.glagolitsa.model.CallSession
import com.glagolitsa.model.CallType
import com.glagolitsa.model.Chat
import com.glagolitsa.model.Message
import com.glagolitsa.model.ChatMediaOpenPolicy
import com.glagolitsa.model.MessageAction
import com.glagolitsa.model.MessageActionPolicy
import com.glagolitsa.model.MessageEditPolicy
import com.glagolitsa.model.MessagePinPolicy
import com.glagolitsa.model.MessageReplyPolicy
import com.glagolitsa.model.User
import com.glagolitsa.model.isTerminal
import com.glagolitsa.model.statusLabel
import com.glagolitsa.model.toChips
import com.glagolitsa.repository.ConnectionBannerState
import com.glagolitsa.repository.GroupCallWorkflow
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.repository.AttachmentPreview
import com.glagolitsa.repository.connectionStatusText
import com.glagolitsa.security.SecureClipboard
import com.glagolitsa.security.SecureWindowEffect
import com.glagolitsa.session.SessionStore
import com.glagolitsa.ui.chat.ChatAutoScrollEffect
import com.glagolitsa.ui.chat.ChatComposer
import com.glagolitsa.ui.chat.ChatComposerEditQuote
import com.glagolitsa.ui.chat.ChatComposerReplyQuote
import com.glagolitsa.ui.chat.IdentityChangeDialog
import com.glagolitsa.ui.chat.ChatMessageBubble
import com.glagolitsa.ui.chat.ChatOrnamentBackground
import com.glagolitsa.ui.chat.ChatOlderMessagesEffect
import com.glagolitsa.ui.chat.ChatReplyPreviewData
import com.glagolitsa.ui.chat.ChatSyncEffect
import com.glagolitsa.ui.chat.ChatTimelineList
import com.glagolitsa.ui.chat.EmojiPickerPanel
import com.glagolitsa.ui.chat.MessageActionMenu
import com.glagolitsa.ui.chat.MessageDeleteConfirmDialog
import com.glagolitsa.ui.chat.MessageSelectionBar
import com.glagolitsa.ui.chat.ForwardMessageDialog
import com.glagolitsa.ui.chat.MIN_FORWARD_SEARCH_QUERY_LENGTH
import com.glagolitsa.ui.chat.PickedAttachment
import com.glagolitsa.ui.chat.PinnedMessageBanner
import com.glagolitsa.ui.chat.ReplyQuoteInteraction
import com.glagolitsa.ui.chat.ReplyQuoteSelectionReply
import com.glagolitsa.ui.chat.TransientNetworkErrors
import com.glagolitsa.ui.chat.VoiceDictationState
import com.glagolitsa.ui.chat.VoiceDictationStatus
import com.glagolitsa.ui.chat.appendVoiceDictationText
import com.glagolitsa.ui.chat.buildChatThreadBranchPreviews
import com.glagolitsa.ui.chat.buildChatTimeline
import com.glagolitsa.ui.chat.refreshChatChannel
import com.glagolitsa.ui.chat.rememberAttachmentAudioPlayerController
import com.glagolitsa.ui.chat.rememberVoiceDictationController
import com.glagolitsa.ui.chat.rememberAttachmentPicker
import com.glagolitsa.audio.VoiceRecordingDraft
import com.glagolitsa.audio.VoiceSendVariant
import com.glagolitsa.ui.chat.VoiceDraftBar
import com.glagolitsa.ui.chat.rememberVoiceRecorderController
import com.glagolitsa.ui.chat.toDraftPreview
import com.glagolitsa.ui.chat.replySenderLabel
import com.glagolitsa.ui.chat.resolveInlineReplyPreview
import com.glagolitsa.ui.chat.timelineIndexForMessageIndex
import com.glagolitsa.ui.chat.neighborMessageIds
import com.glagolitsa.ui.chat.scrollToTimelineIndex
import com.glagolitsa.ui.chat.visibleMessageWindow
import com.glagolitsa.ui.chat.visibleTimelineMessageIds
import com.glagolitsa.ui.chat.visualIndexForMessageId
import com.glagolitsa.ui.chat.visualNewestIndex
import com.glagolitsa.ui.chat.visualTimelineItems
import com.glagolitsa.ui.chat.ChatTopBar
import com.glagolitsa.ui.chat.GroupCallBanner
import com.glagolitsa.ui.chat.ChatAppearanceSettings
import com.glagolitsa.ui.chat.ChatBubbleGroupPosition
import com.glagolitsa.ui.chat.chatHeaderParticipantSubtitle
import com.glagolitsa.ui.chat.AttachmentDownloadSaver
import com.glagolitsa.ui.chat.AttachmentMediaViewer
import com.glagolitsa.ui.chat.chatBubbleGroupPosition
import com.glagolitsa.ui.chat.chatAppearanceBackground
import com.glagolitsa.ui.chat.loadChatAppearanceSettings
import com.glagolitsa.ui.chat.sendChatMessage
import com.glagolitsa.ui.components.chatComposerInsets
import com.glagolitsa.ui.components.screenTopSafeArea
import com.glagolitsa.ui.profile.presenceRingColor
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

private const val PRESENCE_REFRESH_INTERVAL_MS = 15_000L

internal fun chatPresenceTargetIds(
    chat: Chat,
    currentUserId: String?,
    dmPartnerId: String?,
): List<String> = when {
    dmPartnerId != null -> listOf(dmPartnerId)
    chat.member_ids.isNotEmpty() -> chat.member_ids.filter { it != currentUserId }.distinct()
    else -> emptyList()
}

private fun startAudioCallWithGuard(
    audioPermission: CallAudioPermissionController,
    scope: CoroutineScope,
    onStartingChanged: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    start: suspend () -> Unit,
) {
    onError(null)
    CallFeaturePolicy.startCallBlockedReason(ClientFeatureFlags.snapshot, CallType.AUDIO)?.let {
        onError(it)
        return
    }
    audioPermission.runWithPermission(
        onGranted = {
            onStartingChanged(true)
            scope.launch {
                runCatching { start() }
                    .onFailure {
                        onError(callStartErrorMessage(it.message))
                        onStartingChanged(false)
                    }
            }
        },
        onDenied = { onError(it) },
    )
}

/**
 * Экран переписки.
 *
 * Safe area: только статус-бар сверху; клавиатура поднимает поле ввода через [chatComposerInsets],
 * а не весь экран (см. AndroidManifest adjustNothing).
 *
 * Обновление канала: свайп вверх у низа ленты (новые сообщения внизу).
 */
@Composable
fun ChatScreen(
    chat: Chat,
    repository: MessengerRepository,
    initialFocusMessageId: String? = null,
    onBack: () -> Unit,
    onChatInfo: (() -> Unit)? = null,
    onChatSettings: (() -> Unit)? = null,
    onOpenThread: ((Message) -> Unit)? = null,
    onSafetyNumber: ((String) -> Unit)? = null,
    onForwardChatOpened: ((Chat) -> Unit)? = null,
    onOpenChat: ((Chat) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val callAudioPermission = rememberCallAudioPermissionController()
    val user by SessionStore.user.collectAsState()
    val knownUserProfiles by repository.knownUserProfiles.collectAsState()
    val attachmentPreviews by repository.attachmentPreviews.collectAsState()
    val activeCall by repository.calls.activeCall.collectAsState()
    val allMessages by repository.observeMainMessages(chat.id).collectAsState(initial = emptyList())
    var oldestKeptId by remember(chat.id) { mutableStateOf<String?>(null) }
    var expandWindowAfterLoad by remember(chat.id) { mutableStateOf(false) }
    val messages = remember(allMessages, oldestKeptId) {
        visibleMessageWindow(allMessages, oldestKeptId)
    }
    val outboxErrors by repository.observeOutboxErrors(chat.id).collectAsState(initial = emptyMap())
    val threadSummaries by repository.observeThreadBranchSummaries(chat.id).collectAsState(initial = emptyMap())
    var draft by remember { mutableStateOf("") }
    var sendInFlight by remember(chat.id) { mutableStateOf(false) }
    var replyContext by remember { mutableStateOf<ReplyContext?>(null) }
    var editContext by remember { mutableStateOf<Message?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showIdentityDialog by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<Message?>(null) }
    var attachmentActionMenu by remember { mutableStateOf(false) }
    var pendingDeleteIds by remember { mutableStateOf<Set<String>?>(null) }
    var selectedMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedMediaPreview by remember(chat.id) { mutableStateOf<AttachmentPreview?>(null) }
    var expandedMediaMessage by remember(chat.id) { mutableStateOf<Message?>(null) }
    var forwardMessage by remember { mutableStateOf<Message?>(null) }
    var forwardQuery by remember { mutableStateOf("") }
    var forwardResults by remember { mutableStateOf<List<User>>(emptyList()) }
    var forwardSearching by remember { mutableStateOf(false) }
    var forwardingUserId by remember { mutableStateOf<String?>(null) }
    var forwardError by remember { mutableStateOf<String?>(null) }
    var showEmojiPicker by remember(chat.id) { mutableStateOf(false) }
    var channelRefreshing by remember(chat.id) { mutableStateOf(false) }
    var startingCall by remember(chat.id) { mutableStateOf(false) }
    var liveGroupCall by remember(chat.id) { mutableStateOf<CallSession?>(null) }
    val groupCallWorkflow = remember(repository) { GroupCallWorkflow(repository) }
    var loadingOlder by remember(chat.id) { mutableStateOf(false) }
    var swipeUpPullPx by remember(chat.id) { mutableFloatStateOf(0f) }
    var pinnedMessages by remember(chat.id) { mutableStateOf<List<Message>>(emptyList()) }
    val pinnedRevision by repository.pinnedMessagesRevision.collectAsState()
    val editedMessageIds by repository.editedMessageIds.collectAsState()
    val selectionMode = selectedMessageIds.isNotEmpty()
    val showRefreshIndicator = channelRefreshing || swipeUpPullPx > 24f
    val listState = rememberLazyListState()
    val messagesById = remember(messages) { messages.associateBy { it.id } }
    val timeline = remember(messages) { buildChatTimeline(messages) }
    val activePinned = pinnedMessages.firstOrNull()
    var pendingFocusMessageId by remember(chat.id, initialFocusMessageId) {
        mutableStateOf(initialFocusMessageId)
    }
    var highlightMessageId by remember(chat.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(activeCall?.session?.id, activeCall?.session?.status) {
        val call = activeCall?.session
        if (call == null || call.isTerminal()) {
            startingCall = false
        }
        if (call != null && !call.isTerminal() && call.chat_id == chat.id) {
            liveGroupCall = call
        }
    }

    LaunchedEffect(chat.id, chat.isGroup) {
        if (!chat.isGroup) {
            liveGroupCall = null
            return@LaunchedEffect
        }
        liveGroupCall = runCatching { groupCallWorkflow.activeCallForChat(chat.id) }.getOrNull()
    }

    LaunchedEffect(chat.id, pinnedRevision) {
        pinnedMessages = repository.listPinnedMessages(chat.id)
    }
    fun triggerChannelRefresh() {
        if (channelRefreshing) return
        scope.launch {
            channelRefreshing = true
            swipeUpPullPx = 0f
            error = null
            runCatching {
                repository.refreshChatChannel(chat)
            }.onFailure { err ->
                error = chatScreenErrorMessage(err.message)
            }
            channelRefreshing = false
        }
    }

    val refreshingHolder = remember(chat.id) { mutableStateOf(false) }
    refreshingHolder.value = channelRefreshing
    val swipeUpRefreshConnection = remember(chat.id) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // reverseLayout: visual bottom is newest; extra finger-up when pinned to latest.
                if (refreshingHolder.value) return Offset.Zero
                val atBottom = !listState.canScrollBackward
                if (!atBottom || available.y >= 0f) {
                    if (available.y > 0f) swipeUpPullPx = 0f
                    return Offset.Zero
                }
                val next = swipeUpPullPx + (-available.y)
                swipeUpPullPx = next
                if (shouldTriggerSwipeUpRefresh(next)) {
                    triggerChannelRefresh()
                }
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!refreshingHolder.value &&
                    !listState.canScrollBackward &&
                    shouldTriggerSwipeUpRefresh(swipeUpPullPx)
                ) {
                    triggerChannelRefresh()
                }
                if (available.y >= 0f) {
                    swipeUpPullPx = 0f
                }
                return Velocity.Zero
            }
        }
    }
    val chatReactions by repository.observeReactions(chat.id).collectAsState(initial = emptyMap())
    val threadBranches = remember(threadSummaries, user?.id) {
        buildChatThreadBranchPreviews(
            summaries = threadSummaries,
            currentUserId = user?.id,
            senderNameFor = repository::usernameForSender,
        )
    }
    val partnerId = repository.dmPartnerFor(chat.id)
    val partnerAvatarUrl = partnerId?.let { knownUserProfiles[it]?.avatar_url }
    val presenceMap by repository.presence.partnerPresence.collectAsState()
    val partnerPresence = partnerId?.let { presenceMap[it] }
    val partnerPresenceStatus = partnerPresence?.statusLabel()
    val partnerPresenceColor = partnerPresence?.presenceRingColor()
    val threadRepliesAllowed = MessageReplyPolicy.canReplyInThread(chat)
    val connectionState by repository.connectionBannerState.collectAsState(
        initial = ConnectionBannerState(),
    )
    // Debounced server-connection plate → status line under the name (not a top banner).
    val connectionStatusText = remember(connectionState) { connectionState.connectionStatusText() }

    val groupSubtitle = remember(chat.id, chat.member_ids, presenceMap, chat.isDirectMessage) {
        chatHeaderParticipantSubtitle(chat, presenceMap)
    }

    val untrustedPartners by repository.untrustedPartnerIds.collectAsState()
    val partnerUntrusted = partnerId != null && partnerId in untrustedPartners
    var appearance by remember(chat.id) { mutableStateOf(ChatAppearanceSettings()) }
    LaunchedEffect(chat.id) {
        appearance = repository.loadChatAppearanceSettings(chat.id)
    }
    fun sendPickedAttachment(attachment: PickedAttachment?) {
        if (attachment == null) return
        if (!attachment.errorMessage.isNullOrBlank()) {
            error = attachment.errorMessage
            return
        }
        showEmojiPicker = false
        if (!chat.supportsE2eAttachments) {
            error = "Вложения в канале публикуются через пост в ленте"
            return
        }
        scope.launch {
            error = null
            runCatching {
                val spool = attachment.encryptedSpool
                if (spool != null) {
                    repository.sendEncryptedAttachmentSpool(
                        chatId = chat.id,
                        encryptedPath = spool.path,
                        fileKey = spool.fileKey,
                        encryptedSize = spool.encryptedSize,
                        plaintextSize = spool.plaintextSize,
                        fileName = attachment.fileName,
                        mimeType = attachment.mimeType,
                        kind = attachment.kind,
                        width = attachment.width,
                        height = attachment.height,
                        durationMs = attachment.durationMs,
                        thumbnailBytes = attachment.thumbnailBytes,
                        waveform = attachment.waveform,
                    )
                } else {
                    repository.sendAttachment(
                        chatId = chat.id,
                        fileBytes = attachment.bytes,
                        fileName = attachment.fileName,
                        mimeType = attachment.mimeType,
                        kind = attachment.kind,
                        width = attachment.width,
                        height = attachment.height,
                        durationMs = attachment.durationMs,
                        thumbnailBytes = attachment.thumbnailBytes,
                        waveform = attachment.waveform,
                    )
                }
            }.onFailure { err ->
                error = chatScreenErrorMessage(err.message)
            }
        }
    }
    val attachmentPicker = rememberAttachmentPicker(::sendPickedAttachment)
    val attachmentAudioPlayer = rememberAttachmentAudioPlayerController()

    fun attachmentPreviewFor(message: Message): AttachmentPreview? =
        attachmentPreviews[message.id] ?: message.pending_id?.let { attachmentPreviews[it] }

    fun isPinnedMessage(message: Message): Boolean =
        pinnedMessages.any { pinned ->
            MessagePinPolicy.candidateIds(message).any { MessagePinPolicy.matches(pinned, it) }
        }

    fun closeActionMenu() {
        actionMessage = null
        attachmentActionMenu = false
    }

    fun closeMediaViewer() {
        expandedMediaPreview = null
        expandedMediaMessage = null
    }

    fun openAttachment(message: Message, preview: AttachmentPreview?) {
        if (preview == null) {
            error = "Файл ещё не загружен"
            return
        }
        when {
            preview.isVoice || preview.isAudio -> attachmentAudioPlayer.play(preview)
            ChatMediaOpenPolicy.opensInViewer(preview.isImage, preview.isVideo) -> {
                expandedMediaPreview = preview
                expandedMediaMessage = message
            }
            else -> {
                attachmentActionMenu = true
                actionMessage = message
            }
        }
    }

    fun startReplyTo(message: Message) {
        editContext = null
        replyContext = buildReplyContext(
            message = message,
            currentUserId = user?.id,
            senderName = repository.usernameForSender(message.sender_id),
            quotedBody = null,
        )
    }

    fun startForward(message: Message) {
        forwardMessage = message
        forwardQuery = ""
        forwardResults = emptyList()
        forwardError = null
    }

    fun downloadAttachment(preview: AttachmentPreview?) {
        if (preview == null || preview.bytes.isEmpty()) {
            error = "Файл недоступен для загрузки"
            return
        }
        scope.launch {
            runCatching { AttachmentDownloadSaver.save(preview) }
                .onFailure {
                    error = chatScreenErrorMessage(it.message) ?: "Не удалось загрузить файл"
                }
        }
    }

    fun performMessageAction(
        action: MessageAction,
        selected: Message,
        selectedAttachmentPreview: AttachmentPreview?,
    ) {
        closeActionMenu()
        when (action) {
            MessageAction.REPLY_CHAT -> startReplyTo(selected)
            MessageAction.REPLY_THREAD -> {
                if (threadRepliesAllowed) {
                    onOpenThread?.invoke(selected)
                }
            }
            MessageAction.OPEN -> openAttachment(selected, selectedAttachmentPreview)
            MessageAction.DOWNLOAD -> downloadAttachment(selectedAttachmentPreview)
            MessageAction.FORWARD -> startForward(selected)
            MessageAction.PIN -> {
                scope.launch {
                    runCatching { repository.pinMessage(selected) }
                        .onFailure {
                            error = chatScreenErrorMessage(it.message)
                                ?: "Не удалось закрепить сообщение"
                        }
                }
            }
            MessageAction.UNPIN -> {
                scope.launch {
                    runCatching { repository.unpinMessage(chat.id, selected) }
                        .onFailure {
                            error = chatScreenErrorMessage(it.message)
                                ?: "Не удалось открепить сообщение"
                        }
                }
            }
            MessageAction.ADD_TO_FAVORITES -> {
                scope.launch {
                    runCatching { repository.addMessageToFavorites(selected) }
                        .onFailure { error = chatScreenErrorMessage(it.message) }
                }
            }
            MessageAction.COPY -> {
                if (selected.body.isNotBlank()) {
                    SecureClipboard.copyWithAutoClear(
                        label = "message",
                        text = selected.body,
                    )
                }
            }
            MessageAction.EDIT -> {
                // Messenger-style: load body into composer, cancel reply, show edit banner.
                replyContext = null
                editContext = selected
                draft = selected.body
                showEmojiPicker = false
            }
            MessageAction.SELECT -> {
                selectedMessageIds = setOf(selected.id)
            }
            MessageAction.CANCEL_SEND -> {
                scope.launch {
                    runCatching { repository.cancelSendingMessage(chat.id, selected.id) }
                        .onFailure { error = chatScreenErrorMessage(it.message) }
                }
            }
            MessageAction.RETRY -> {
                scope.launch {
                    runCatching { repository.retryFailedMessage(selected.id) }
                        .onFailure { error = chatScreenErrorMessage(it.message) }
                }
            }
            MessageAction.DELETE -> {
                pendingDeleteIds = setOf(selected.id)
            }
        }
    }

    AppBackHandler(enabled = showEmojiPicker) {
        showEmojiPicker = false
    }
    val voiceDictation = rememberVoiceDictationController(
        onResult = { transcript ->
            draft = appendVoiceDictationText(draft, transcript)
            showEmojiPicker = false
        },
        // Voice/mic errors stay in VoiceDictationState only — never the chat red banner.
        // Empty-composer primary is «Диктовка»; a denied RECORD_AUDIO must not look like
        // a messaging/network failure ("Нет доступа к микрофону").
        onError = {},
    )
    var voiceDraft by remember { mutableStateOf<VoiceRecordingDraft?>(null) }
    val voiceRecorder = rememberVoiceRecorderController(
        onDraft = { draftVoice ->
            voiceDraft = draftVoice
        },
        onError = {},
    )
    AppBackHandler(enabled = voiceDictation.state.isActive || voiceRecorder.state.isActive) {
        voiceDictation.cancel()
        voiceRecorder.cancel()
    }

    LaunchedEffect(chat.id) {
        repository.markChatOpened(chat.id)
        repository.loadMessageReactions(chat.id)
    }

    LaunchedEffect(chat.id, listState) {
        snapshotFlow {
            val visibleKeys = listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
            visibleKeys to messages
        }.collect { (visibleKeys, current) ->
            val visibleIds = visibleTimelineMessageIds(visibleKeys)
            val ids = neighborMessageIds(current, visibleIds)
            repository.restoreImagePreviews(chat.id, ids)
            val visibleMessages = current.filter { it.id in visibleIds || it.pending_id in visibleIds }
            if (visibleMessages.isNotEmpty()) {
                repository.markVisibleMessagesRead(chat.id, visibleMessages)
            }
        }
    }

    LaunchedEffect(forwardMessage, forwardQuery) {
        if (forwardMessage == null) return@LaunchedEffect
        val query = forwardQuery.trim()
        if (query.length < MIN_FORWARD_SEARCH_QUERY_LENGTH) {
            forwardResults = emptyList()
            forwardSearching = false
            return@LaunchedEffect
        }
        forwardSearching = true
        runCatching { repository.searchUsers(query) }
            .onSuccess { forwardResults = it }
            .onFailure { forwardError = chatScreenErrorMessage(it.message) ?: "Не удалось найти пользователей" }
        forwardSearching = false
    }

    LaunchedEffect(chat.id, partnerUntrusted) {
        if (partnerUntrusted && chat.isDirectMessage) {
            showIdentityDialog = true
        }
    }

    LaunchedEffect(chat.id) {
        while (true) {
            delay(60_000)
            repository.purgeExpiredMessages()
        }
    }

    LaunchedEffect(chat.id, partnerId, chat.member_ids, user?.id) {
        val ids = chatPresenceTargetIds(
            chat = chat,
            currentUserId = user?.id,
            dmPartnerId = partnerId,
        )
        if (ids.isEmpty()) return@LaunchedEffect
        while (true) {
            runCatching { repository.presence.heartbeat() }
                .onFailure { repository.recoverAuthFailure(it) }
            runCatching { repository.presence.fetchUsers(ids) }
                .onFailure { repository.recoverAuthFailure(it) }
            delay(PRESENCE_REFRESH_INTERVAL_MS)
        }
    }

    LaunchedEffect(pendingFocusMessageId, allMessages.size) {
        val targetId = pendingFocusMessageId ?: return@LaunchedEffect
        if (messages.none { ReplyQuoteInteraction.matchesFocusTarget(it, targetId) }) {
            val hit = allMessages.firstOrNull { ReplyQuoteInteraction.matchesFocusTarget(it, targetId) }
            if (hit != null) oldestKeptId = hit.id
        }
    }

    LaunchedEffect(pendingFocusMessageId, messages.size, messages.lastOrNull()?.id, timeline.size, loadingOlder) {
        val targetId = pendingFocusMessageId ?: return@LaunchedEffect
        val messageIndex = ReplyQuoteInteraction.focusIndexIn(messages, targetId)
        if (messageIndex >= 0) {
            val visual = visualTimelineItems(timeline, loadingOlder)
            val targetIndex = visualIndexForMessageId(visual, targetId)
                ?: timelineIndexForMessageIndex(timeline, messageIndex)
                ?: messageIndex
            listState.scrollToTimelineIndex(targetIndex)
            highlightMessageId = targetId
            pendingFocusMessageId = null
        }
    }

    ChatOlderMessagesEffect(
        chatId = chat.id,
        messages = messages,
        listState = listState,
        repository = repository,
        loadingOlder = loadingOlder,
        refreshInFlight = channelRefreshing,
        onLoadingOlderChange = { loading ->
            if (loading) expandWindowAfterLoad = true
            loadingOlder = loading
        },
        onError = { error = chatScreenErrorMessage(it) },
    )
    LaunchedEffect(allMessages.firstOrNull()?.id, expandWindowAfterLoad, loadingOlder) {
        if (expandWindowAfterLoad && !loadingOlder) {
            oldestKeptId = allMessages.firstOrNull()?.id
            expandWindowAfterLoad = false
        }
    }

    LaunchedEffect(highlightMessageId) {
        val id = highlightMessageId ?: return@LaunchedEffect
        delay(ReplyQuoteInteraction.FOCUS_HIGHLIGHT_MS)
        if (highlightMessageId == id) {
            highlightMessageId = null
        }
    }

    SecureWindowEffect(enabled = chat.isDirectMessage)

    ChatSyncEffect(
        chat = chat,
        repository = repository,
        onError = { error = chatScreenErrorMessage(it) },
    )
    ChatAutoScrollEffect(
        chatId = chat.id,
        messages = messages,
        listState = listState,
        timeline = timeline,
        currentUserId = user?.id,
        reverseLayout = true,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(chatAppearanceBackground(appearance.darkness))
            .screenTopSafeArea(),
    ) {
        ChatOrnamentBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 0.dp)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // No top “не отправлено / Переслать” banner — delivery state lives only on the
            // bubble (◷ sending, ⚠ only after permanent fail). Outbox still retries quietly.

            if (selectionMode) {
                MessageSelectionBar(
                    count = selectedMessageIds.size,
                    canCopy = MessageActionPolicy.canCopySelected(selectedMessageIds, messagesById),
                    canDelete = MessageActionPolicy.canDeleteSelected(
                        selectedMessageIds,
                        messagesById,
                    ),
                    onCancel = { selectedMessageIds = emptySet() },
                    onCopy = {
                        val text = selectedMessageIds
                            .mapNotNull { messagesById[it]?.body?.takeIf { b -> b.isNotBlank() } }
                            .joinToString("\n")
                        if (text.isNotBlank()) {
                            SecureClipboard.copyWithAutoClear(label = "messages", text = text)
                        }
                        selectedMessageIds = emptySet()
                    },
                    onDelete = {
                        pendingDeleteIds = selectedMessageIds
                    },
                )
            } else {
                ChatTopBar(
                    chat = chat,
                    onBack = onBack,
                    // Partner presence stays here; connection plate is a separate slot that
                    // temporarily replaces the status line («в сети» → «Переподключаемся…»).
                    presenceStatus = if (chat.isDirectMessage) partnerPresenceStatus else null,
                    connectionStatus = if (chat.isDirectMessage) connectionStatusText else null,
                    subtitle = groupSubtitle,
                    presenceColor = if (chat.isDirectMessage) partnerPresenceColor else null,
                    avatarUrl = if (chat.isDirectMessage) partnerAvatarUrl else chat.conversationIconUrl,
                    onCall = when {
                        startingCall -> null
                        chat.isGroup -> {
                            {
                                startAudioCallWithGuard(
                                    audioPermission = callAudioPermission,
                                    scope = scope,
                                    onStartingChanged = { startingCall = it },
                                    onError = { error = it },
                                ) {
                                    groupCallWorkflow.startOrJoin(
                                        chatId = chat.id,
                                        chatTitle = chat.title,
                                        existingCall = liveGroupCall,
                                    )
                                }
                            }
                        }
                        partnerId != null -> {
                            {
                                startAudioCallWithGuard(
                                    audioPermission = callAudioPermission,
                                    scope = scope,
                                    onStartingChanged = { startingCall = it },
                                    onError = { error = it },
                                ) {
                                    repository.calls.startOutgoingCall(partnerId, chat.title)
                                }
                            }
                        }
                        else -> null
                    },
                    onChatInfo = onChatInfo,
                    onChatSettings = onChatSettings,
                )
            }

            val groupCall = liveGroupCall?.takeIf { it.chat_id == chat.id && !it.isTerminal() }
            if (groupCall != null && chat.isGroup) {
                val inThisCall = activeCall?.session?.id == groupCall.id &&
                    activeCall?.session?.isTerminal() != true
                GroupCallBanner(
                    call = groupCall,
                    inThisCall = inThisCall,
                    onCopyLink = { SecureClipboard.copyWithAutoClear("group-call", it) },
                    onJoin = { call ->
                        startAudioCallWithGuard(
                            audioPermission = callAudioPermission,
                            scope = scope,
                            onStartingChanged = { startingCall = it },
                            onError = { error = it },
                        ) {
                            groupCallWorkflow.join(call.id, chat.title)
                        }
                    },
                )
            }

            activePinned?.let { pinned ->
                PinnedMessageBanner(
                    title = "Закреплено",
                    body = pinned.body.ifBlank { "Сообщение" },
                    onClick = { pendingFocusMessageId = pinned.id },
                    onUnpin = {
                        scope.launch {
                            runCatching { repository.unpinMessage(chat.id, pinned) }
                                .onFailure { error = chatScreenErrorMessage(it.message) }
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 6.dp),
                )
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .nestedScroll(swipeUpRefreshConnection)
                    .testTag("chat-messages-refresh"),
            ) {
                ChatTimelineList(
                    timeline = timeline,
                    listState = listState,
                    loadingOlder = loadingOlder,
                    modifier = Modifier.fillMaxSize(),
                    attachmentKindFor = { msg ->
                        (attachmentPreviews[msg.id] ?: msg.pending_id?.let { attachmentPreviews[it] })?.kind
                    },
                    isOwnMessage = { it.sender_id == user?.id },
                    onJumpToLatest = {
                        scope.launch {
                            val visual = visualTimelineItems(timeline, loadingOlder)
                            listState.scrollToTimelineIndex(visualNewestIndex(visual) ?: 0)
                        }
                    },
                ) { message, index, bubbleModifier ->
                    val groupPosition = remember(message.id, index, messages.size) {
                        chatBubbleGroupPosition(messages, index)
                    }
                    val topPadding = if (groupPosition == ChatBubbleGroupPosition.Middle ||
                        groupPosition == ChatBubbleGroupPosition.Last
                    ) {
                        0.dp
                    } else {
                        8.dp
                    }
                    val resolvedSenderName = remember(message.sender_id, knownUserProfiles) {
                        repository.usernameForSender(message.sender_id)
                    }
                    val replyPreview = remember(message.id, message.reply_to_message_id, messagesById) {
                        buildInlineReplyPreview(
                            message = message,
                            messagesById = messagesById,
                            currentUserId = user?.id,
                            repository = repository,
                        )
                    }
                    val chips = chatReactions[message.id].orEmpty().toChips(user?.id)
                    ChatMessageBubble(
                        message = message,
                        currentUserId = user?.id,
                        chat = chat,
                        playingAttachmentMessageId = attachmentAudioPlayer.state.playingMessageId,
                        playingAttachmentDurationMs = attachmentAudioPlayer.state.durationMs.takeIf { it > 0L },
                        sendError = message.pending_id
                            ?.let { outboxErrors[it] }
                            ?: outboxErrors[message.id],
                        senderName = resolvedSenderName,
                        senderAvatarUrl = knownUserProfiles[message.sender_id]?.avatar_url,
                        attachmentPreview = attachmentPreviews[message.id]
                            ?: message.pending_id?.let { attachmentPreviews[it] },
                        replyPreview = replyPreview,
                        threadBranchPreview = threadBranches[message.id],
                        allowThreadReply = threadRepliesAllowed && !selectionMode,
                        selected = message.id in selectedMessageIds,
                        highlighted = highlightMessageId?.let { focusId ->
                            ReplyQuoteInteraction.matchesFocusTarget(message, focusId)
                        } == true,
                        isEdited = message.id in editedMessageIds ||
                            message.pending_id?.let { it in editedMessageIds } == true,
                        reactionChips = chips,
                        groupPosition = groupPosition,
                        onReactionChipClick = { emoji ->
                            scope.launch {
                                repository.toggleMessageReaction(chat.id, message.id, emoji)
                            }
                        },
                        onAttachmentClick = { tapped ->
                            if (selectionMode) {
                                selectedMessageIds = selectedMessageIds.toggle(tapped.id)
                            } else {
                                openAttachment(tapped, attachmentPreviewFor(tapped))
                            }
                        },
                        onMessageClick = { tapped ->
                            if (selectionMode) {
                                selectedMessageIds = selectedMessageIds.toggle(tapped.id)
                            } else {
                                attachmentActionMenu = false
                                actionMessage = tapped
                            }
                        },
                        onMessageLongClick = { longPressed ->
                            if (selectionMode) {
                                selectedMessageIds = selectedMessageIds.toggle(longPressed.id)
                            } else {
                                actionMessage = longPressed
                            }
                        },
                        onThreadBranchClick = { branchRoot ->
                            if (threadRepliesAllowed && !selectionMode) {
                                onOpenThread?.invoke(branchRoot)
                            }
                        },
                        onSwipeReplyInChat = { swiped ->
                            if (selectionMode) return@ChatMessageBubble
                            editContext = null
                            replyContext = buildReplyContext(
                                message = swiped,
                                currentUserId = user?.id,
                                senderName = repository.usernameForSender(swiped.sender_id),
                            )
                        },
                        onSwipeReplyInThread = { swiped ->
                            if (selectionMode) return@ChatMessageBubble
                            if (threadRepliesAllowed) {
                                onOpenThread?.invoke(swiped)
                            }
                        },
                        onInviteLinkClick = { raw ->
                            if (selectionMode) return@ChatMessageBubble
                            scope.launch {
                                runCatching { repository.openInviteLink(raw) }
                                    .onSuccess { opened ->
                                        (onOpenChat ?: onForwardChatOpened)?.invoke(opened)
                                    }
                                    .onFailure {
                                        error = chatScreenErrorMessage(it.message)
                                            ?: "Не удалось вступить по ссылке"
                                    }
                            }
                        },
                        onReplyToTextSelection = { message, selectedText ->
                            if (selectionMode) return@ChatMessageBubble
                            editContext = null
                            replyContext = buildReplyContext(
                                message = message,
                                currentUserId = user?.id,
                                senderName = repository.usernameForSender(message.sender_id),
                                quotedBody = selectedText,
                            )
                        },
                        onNavigateToReply = { targetMessageId ->
                            if (selectionMode) return@ChatMessageBubble
                            pendingFocusMessageId = targetMessageId
                        },
                        onReplyToQuoteSelection = { targetId, targetSenderId, selectedText, previewBody ->
                            if (selectionMode) return@ChatMessageBubble
                            editContext = null
                            val payload = ReplyQuoteSelectionReply(
                                targetMessageId = targetId,
                                targetSenderId = targetSenderId,
                                selectedText = selectedText,
                                previewBody = previewBody,
                            )
                            val parent = ReplyQuoteInteraction.findTargetMessage(
                                messagesById = messagesById,
                                targetMessageId = targetId,
                            ) ?: ReplyQuoteInteraction.fallbackReplyMessage(
                                reply = payload,
                                chatId = chat.id,
                            )
                            replyContext = buildReplyContext(
                                message = parent,
                                currentUserId = user?.id,
                                senderName = repository.usernameForSender(parent.sender_id),
                                quotedBody = selectedText,
                            )
                        },
                        onAttachmentMediaClick = { preview ->
                            if (preview.isVoice || preview.isAudio) {
                                attachmentAudioPlayer.play(preview)
                            } else {
                                allMessages.firstOrNull {
                                    it.id == preview.messageId || it.pending_id == preview.messageId
                                }?.let { host ->
                                    openAttachment(host, preview)
                                } ?: run {
                                    expandedMediaPreview = preview
                                    expandedMediaMessage = null
                                }
                            }
                        },
                        onRetryFailed = { failed ->
                            scope.launch {
                                runCatching { repository.retryFailedMessage(failed.id) }
                                    .onFailure { error = chatScreenErrorMessage(it.message) }
                            }
                        },
                        modifier = bubbleModifier.padding(top = topPadding),
                    )
                }

            }
            if (showRefreshIndicator) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("chat-swipe-up-refresh-indicator"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(GlagolitsaColors.Surface800.copy(alpha = 0.82f))
                            .border(0.6.dp, GlagolitsaColors.GlassBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (channelRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = GlagolitsaColors.OrnamentGold,
                            )
                            Text(
                                text = "Обновление…",
                                style = MaterialTheme.typography.labelSmall,
                                color = GlagolitsaColors.TextSecondary,
                            )
                        } else {
                            Text(
                                text = "Свайп вверх — обновить",
                                style = MaterialTheme.typography.labelSmall,
                                color = GlagolitsaColors.TextSecondary,
                            )
                        }
                    }
                }
            }

            if (partnerUntrusted) {
                Text(
                    text = "Отправка заблокирована: ключ безопасности собеседника изменился.",
                    style = MaterialTheme.typography.labelSmall,
                    color = GlagolitsaColors.AccentRedText,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (showEmojiPicker && !partnerUntrusted) {
                EmojiPickerPanel(
                    onEmojiSelected = { emoji ->
                        draft += emoji
                        showEmojiPicker = false
                    },
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }

            val activeReply = replyContext
            val activeEdit = editContext
            val voiceInputState = remember(voiceRecorder.state, voiceDictation.state) {
                when {
                    voiceRecorder.state.isRecording -> VoiceDictationState(
                        status = VoiceDictationStatus.Listening,
                        partialText = "Запись ${formatVoiceRecorderElapsed(voiceRecorder.state.elapsedMs)}",
                    )
                    voiceRecorder.state.isProcessing -> VoiceDictationState(
                        status = VoiceDictationStatus.Processing,
                        partialText = "Убираем шум…",
                    )
                    !voiceRecorder.state.errorMessage.isNullOrBlank() -> VoiceDictationState(
                        errorMessage = voiceRecorder.state.errorMessage,
                    )
                    voiceDictation.state.isActive || voiceDictation.state.partialText.isNotBlank() -> voiceDictation.state
                    else -> VoiceDictationState()
                }
            }
            val activeVoiceDraft = voiceDraft
            if (activeVoiceDraft != null && !partnerUntrusted) {
                val chosen = activeVoiceDraft.chosen()
                val preview = chosen.toDraftPreview("voice-draft-${activeVoiceDraft.selected.name}")
                VoiceDraftBar(
                    draft = activeVoiceDraft,
                    playing = attachmentAudioPlayer.state.isPlaying(preview),
                    onToggleProcessed = { useProcessed ->
                        voiceDraft = activeVoiceDraft.withSelection(
                            if (useProcessed) VoiceSendVariant.Processed else VoiceSendVariant.Original,
                        )
                        attachmentAudioPlayer.stop()
                    },
                    onPlay = { attachmentAudioPlayer.play(preview) },
                    onSend = {
                        attachmentAudioPlayer.stop()
                        val toSend = voiceDraft?.chosen() ?: return@VoiceDraftBar
                        voiceDraft = null
                        sendPickedAttachment(toSend)
                    },
                    onDiscard = {
                        attachmentAudioPlayer.stop()
                        voiceDraft = null
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            ChatComposer(
                draft = draft,
                onDraftChange = { next ->
                    // Typing must kill live mic capture so it never runs in parallel with send.
                    if (next.isNotEmpty() && (voiceDictation.state.isActive || voiceRecorder.state.isActive)) {
                        voiceDictation.cancel()
                        voiceRecorder.cancel()
                    }
                    draft = next
                },
                enabled = !partnerUntrusted,
                placeholder = if (partnerUntrusted) "Подтвердите ключ безопасности" else "Сообщение...",
                onAttach = {
                    if (editContext != null) return@ChatComposer
                    voiceDictation.cancel()
                    voiceRecorder.cancel()
                    showEmojiPicker = false
                    attachmentPicker.pickFile()
                },
                onAttachPhotoVideo = {
                    if (editContext != null) return@ChatComposer
                    voiceDictation.cancel()
                    voiceRecorder.cancel()
                    showEmojiPicker = false
                    attachmentPicker.pickPhotoOrVideo()
                },
                onAttachFile = {
                    if (editContext != null) return@ChatComposer
                    voiceDictation.cancel()
                    voiceRecorder.cancel()
                    showEmojiPicker = false
                    attachmentPicker.pickFile()
                },
                onAttachCamera = {
                    if (editContext != null) return@ChatComposer
                    voiceDictation.cancel()
                    voiceRecorder.cancel()
                    showEmojiPicker = false
                    attachmentPicker.openCamera()
                },
                onEmoji = {
                    voiceDictation.cancel()
                    voiceRecorder.cancel()
                    showEmojiPicker = !showEmojiPicker
                },
                onVoice = {
                    if (editContext != null) return@ChatComposer
                    showEmojiPicker = false
                    if (voiceRecorder.state.isActive) {
                        voiceRecorder.stop()
                    } else if (voiceDictation.state.isActive) {
                        voiceDictation.stop()
                    } else if (draft.isBlank()) {
                        // Never start mic while there is text to send.
                        voiceDictation.start()
                    }
                },
                onVoiceLongPress = {
                    if (editContext != null || draft.isNotBlank()) return@ChatComposer
                    showEmojiPicker = false
                    if (voiceRecorder.state.isActive) {
                        voiceRecorder.stop()
                    } else {
                        voiceDictation.cancel()
                        voiceRecorder.start()
                    }
                },
                voiceDictationState = voiceInputState,
                voiceIdleContentDescription = "Диктовка, удерживайте для голосового",
                voiceActiveContentDescription = if (voiceRecorder.state.isActive) {
                    "Остановить запись"
                } else {
                    "Остановить диктовку"
                },
                editQuote = activeEdit?.let { editing ->
                    ChatComposerEditQuote(
                        body = editing.body,
                        onClear = {
                            editContext = null
                            draft = ""
                        },
                    )
                },
                replyQuote = if (activeEdit != null) {
                    null
                } else {
                    activeReply?.let { context ->
                        ChatComposerReplyQuote(
                            title = context.senderName,
                            body = context.previewBody,
                            actionLabel = "Ответить",
                            accentColor = GlagolitsaColors.OrnamentGold.copy(alpha = 0.78f),
                            titleColor = GlagolitsaColors.TextPrimary,
                            // Quote of selected text is body-only; skip attachment strip for partial quotes.
                            attachmentPreview = if (context.quotedBody != null) {
                                null
                            } else {
                                attachmentPreviews[context.message.id]
                                    ?: context.message.pending_id?.let { attachmentPreviews[it] }
                            },
                            onClear = { replyContext = null },
                        )
                    }
                },
                onSend = {
                    // Cancel mic first — then send. Order matters for races with the shared FAB.
                    voiceDictation.cancel()
                    voiceRecorder.cancel()
                    // Guard multi-fire before draft recomposes empty (phone sent 3× same text).
                    if (sendInFlight) return@ChatComposer
                    val body = draft.trim()
                    if (body.isEmpty()) return@ChatComposer
                    val editing = editContext
                    if (editing != null) {
                        if (!MessageEditPolicy.canCommitEdit(editing, body)) {
                            // Unchanged or empty — just leave edit mode.
                            editContext = null
                            draft = ""
                            return@ChatComposer
                        }
                        sendInFlight = true
                        draft = ""
                        showEmojiPicker = false
                        editContext = null
                        scope.launch {
                            runCatching { repository.editMessage(editing, body) }
                                .onFailure {
                                    error = chatScreenErrorMessage(it.message)
                                        ?: "Не удалось изменить сообщение"
                                }
                            sendInFlight = false
                        }
                        return@ChatComposer
                    }
                    sendInFlight = true
                    draft = ""
                    showEmojiPicker = false
                    val relation = replyContext?.let {
                        MessageReplyPolicy.chatReplyRelation(
                            parentMessage = it.message,
                            quoteBody = it.quotedBody,
                        )
                    }
                    replyContext = null
                    scope.sendChatMessage(
                        repository = repository,
                        chatId = chat.id,
                        body = body,
                        relation = relation,
                        expiresAtSec = null,
                        // Bubble status carries send failure; never panic-banner network blips.
                        onError = { error = chatScreenErrorMessage(it) },
                        onComplete = { sendInFlight = false },
                    )
                },
                modifier = Modifier
                    .testTag("chat-compose")
                    .chatComposerInsets()
                    .padding(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 10.dp),
            )
        }
    }

    actionMessage?.let { selected ->
        val selectedAttachmentPreview = attachmentPreviewFor(selected)
        val selectedIsPinned = isPinnedMessage(selected)
        val canDownloadAttachment = AttachmentDownloadSaver.isSupported &&
            selectedAttachmentPreview != null &&
            selectedAttachmentPreview.bytes.isNotEmpty()
        val canOpenAttachment = selectedAttachmentPreview?.let {
            it.isVoice || it.isAudio || ChatMediaOpenPolicy.opensInViewer(it.isImage, it.isVideo)
        } == true
        val actions = if (attachmentActionMenu && selectedAttachmentPreview != null) {
            MessageActionPolicy.availableMediaActions(
                message = selected,
                currentUserId = user?.id,
                canDownload = canDownloadAttachment,
                canOpen = canOpenAttachment,
            )
        } else {
            MessageActionPolicy.availableActions(
                message = selected,
                chat = chat,
                currentUserId = user?.id,
                hasImageAttachment = canDownloadAttachment && selectedAttachmentPreview?.isImage == true,
                isPinned = selectedIsPinned,
            )
        }
        if (actions.isEmpty()) {
            actionMessage = null
        } else {
            val myEmoji = chatReactions[selected.id]
                .orEmpty()
                .firstOrNull { it.userId == user?.id }
                ?.emoji
            MessageActionMenu(
                messageBody = selected.body,
                actions = actions,
                isOwn = selected.sender_id == user?.id,
                myReactionEmoji = myEmoji,
                onDismiss = ::closeActionMenu,
                onReact = { emoji ->
                    scope.launch {
                        repository.toggleMessageReaction(chat.id, selected.id, emoji)
                    }
                    closeActionMenu()
                },
                onAction = { action ->
                    performMessageAction(action, selected, selectedAttachmentPreview)
                },
            )
        }
    }

    pendingDeleteIds?.let { ids ->
        MessageDeleteConfirmDialog(
            count = ids.size,
            onDismiss = { pendingDeleteIds = null },
            onConfirm = {
                val toDelete = ids
                pendingDeleteIds = null
                selectedMessageIds = emptySet()
                scope.launch {
                    toDelete.forEach { id ->
                        runCatching { repository.deleteMessage(chat.id, id) }
                            .onFailure { error = chatScreenErrorMessage(it.message) }
                    }
                }
            },
        )
    }

    forwardMessage?.let { source ->
        val me = user?.id
        val defaultRecipients = knownUserProfiles.values
            .asSequence()
            .filter { it.id != me }
            .sortedBy { it.username.lowercase() }
            .take(20)
            .toList()
        val recipients = if (forwardQuery.trim().length >= MIN_FORWARD_SEARCH_QUERY_LENGTH) {
            forwardResults
        } else {
            defaultRecipients
        }
        ForwardMessageDialog(
            source = source,
            query = forwardQuery,
            recipients = recipients,
            searching = forwardSearching,
            forwardingUserId = forwardingUserId,
            error = forwardError,
            onQueryChange = {
                forwardError = null
                forwardQuery = it
            },
            onDismiss = {
                if (forwardingUserId == null) {
                    forwardMessage = null
                    forwardQuery = ""
                    forwardResults = emptyList()
                    forwardError = null
                }
            },
            onForwardToUser = { recipient ->
                if (forwardingUserId != null) return@ForwardMessageDialog
                forwardingUserId = recipient.id
                forwardError = null
                scope.launch {
                    runCatching {
                        val targetChat = repository.openDM(recipient.id)
                        repository.sendMessage(chatId = targetChat.id, body = source.body)
                        targetChat
                    }.onSuccess { targetChat ->
                        forwardingUserId = null
                        forwardMessage = null
                        forwardQuery = ""
                        forwardResults = emptyList()
                        onForwardChatOpened?.invoke(targetChat)
                    }.onFailure {
                        forwardingUserId = null
                        forwardError = chatScreenErrorMessage(it.message) ?: "Не удалось переслать сообщение"
                    }
                }
            },
        )
    }

    if (showIdentityDialog && partnerId != null && partnerUntrusted) {
        IdentityChangeDialog(
            partnerName = chat.title,
            onVerify = {
                showIdentityDialog = false
                onSafetyNumber?.invoke(partnerId)
            },
            onAccept = {
                scope.launch {
                    repository.acknowledgeIdentityChange(partnerId)
                    showIdentityDialog = false
                }
            },
            onDismiss = { showIdentityDialog = false },
        )
    }

    expandedMediaPreview?.let { preview ->
        val host = expandedMediaMessage
        val isPinned = host?.let { isPinnedMessage(it) } == true
        val canDownload = AttachmentDownloadSaver.isSupported && preview.bytes.isNotEmpty()
        val viewerActions = host?.let {
            MessageActionPolicy.availableAttachmentViewerActions(
                message = it,
                canDownload = canDownload,
                isPinned = isPinned,
            )
        }.orEmpty()
        AttachmentMediaViewer(
            preview = preview,
            actions = viewerActions,
            onDismiss = ::closeMediaViewer,
            onAction = { action ->
                val selected = host ?: return@AttachmentMediaViewer
                closeMediaViewer()
                performMessageAction(action, selected, preview)
            },
        )
    }
}

/** Overscroll threshold (px) at bottom of message list to trigger channel refresh. */
internal const val SWIPE_UP_REFRESH_THRESHOLD_PX = 120f

internal fun shouldTriggerSwipeUpRefresh(accumulatedPullPx: Float): Boolean =
    accumulatedPullPx >= SWIPE_UP_REFRESH_THRESHOLD_PX

/**
 * Top-of-chat error line: never expose raw internal failures in the timeline.
 * Details stay in logs; direct user actions get safe, actionable copy.
 */
internal fun chatScreenErrorMessage(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val text = raw.trim()
    AppLog.warning("chat: hidden action error: ${chatErrorLogDetail(text)}")
    return TransientNetworkErrors.userVisibleChatError(text)
}

internal fun callStartErrorMessage(raw: String?): String =
    chatScreenErrorMessage(raw) ?: "Не удалось начать звонок"

private const val CHAT_ERROR_LOG_DETAIL_LIMIT = 240
private const val CHAT_ERROR_REDACTED = "<redacted>"
private const val CHAT_ERROR_LOCAL_ENDPOINT = "<local>"

private val CHAT_ERROR_SECRET_PATTERNS = listOf(
    Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+"),
    Regex("(?i)((?:access|refresh|mailbox|delivery|api)[_-]?token\\s*[:=]\\s*)[^\\s,;]+"),
    Regex("(?i)(password\\s*[:=]\\s*)[^\\s,;]+"),
)

internal fun chatErrorLogDetail(raw: String): String {
    val compact = CHAT_ERROR_SECRET_PATTERNS
        .fold(raw) { acc, pattern -> pattern.replace(acc) { match -> match.groupValues[1] + CHAT_ERROR_REDACTED } }
        .replace(Regex("(?i)(10\\.0\\.2\\.2|127\\.0\\.0\\.1|localhost)(:\\d+)?"), CHAT_ERROR_LOCAL_ENDPOINT)
        .replace(Regex("\\s+"), " ")
    return if (compact.length <= CHAT_ERROR_LOG_DETAIL_LIMIT) {
        compact
    } else {
        compact.take(CHAT_ERROR_LOG_DETAIL_LIMIT) + "..."
    }
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

private data class ReplyContext(
    val message: Message,
    val senderName: String,
    /** When set, reply quote shows this selection instead of the full message body. */
    val quotedBody: String? = null,
) {
    val previewBody: String
        get() = MessageReplyPolicy.resolveQuoteBody(quotedBody, message.body)
}

private fun buildReplyContext(
    message: Message,
    currentUserId: String?,
    senderName: String?,
    quotedBody: String? = null,
): ReplyContext {
    return ReplyContext(
        message = message,
        senderName = replySenderLabel(message.sender_id, currentUserId, senderName),
        quotedBody = quotedBody?.trim()?.takeIf { it.isNotEmpty() },
    )
}

private fun formatVoiceRecorderElapsed(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun buildInlineReplyPreview(
    message: Message,
    messagesById: Map<String, Message>,
    currentUserId: String?,
    repository: MessengerRepository,
): ChatReplyPreviewData? {
    return resolveInlineReplyPreview(
        message = message,
        messagesById = messagesById,
        currentUserId = currentUserId,
        senderNameFor = repository::usernameForSender,
    )
}
