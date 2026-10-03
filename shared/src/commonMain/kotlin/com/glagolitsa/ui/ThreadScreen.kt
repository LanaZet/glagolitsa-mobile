// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.Chat
import com.glagolitsa.model.Message
import com.glagolitsa.model.MessageReplyPolicy
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.session.SessionStore
import com.glagolitsa.ui.chat.ChatComposer
import com.glagolitsa.ui.chat.ChatComposerReplyQuote
import com.glagolitsa.ui.chat.ChatMessageBubble
import com.glagolitsa.ui.chat.ChatOrnamentBackground
import com.glagolitsa.ui.chat.ChatReplyPreviewData
import com.glagolitsa.ui.chat.EmojiPickerPanel
import com.glagolitsa.ui.chat.appendVoiceDictationText
import com.glagolitsa.ui.chat.rememberVoiceDictationController
import com.glagolitsa.ui.chat.replySenderLabel
import com.glagolitsa.ui.chat.resolveInlineReplyPreview
import com.glagolitsa.ui.chat.sendChatMessage
import com.glagolitsa.ui.chat.threadReplyCountLabel
import com.glagolitsa.ui.chat.threadReplyWord
import com.glagolitsa.ui.components.AppEmptyState
import com.glagolitsa.ui.components.OrnamentGold
import com.glagolitsa.ui.components.OrnamentRed
import com.glagolitsa.ui.components.chatComposerInsets
import com.glagolitsa.ui.components.screenTopSafeArea
import com.glagolitsa.ui.layout.UiLayout
import com.glagolitsa.ui.theme.GlagolitsaColors

@Composable
fun ThreadScreen(
    chat: Chat,
    parentMessage: Message,
    repository: MessengerRepository,
    onBack: () -> Unit,
) {
    if (!MessageReplyPolicy.canReplyInThread(chat)) {
        LaunchedEffect(chat.id) {
            onBack()
        }
        return
    }

    val scope = rememberCoroutineScope()
    val user by SessionStore.user.collectAsState()
    val knownUserProfiles by repository.knownUserProfiles.collectAsState()
    val threadMessages by repository.observeThreadMessages(
        chatId = chat.id,
        threadRootId = parentMessage.id,
    ).collectAsState(initial = emptyList())
    // Channel threads: hydrate open replies from server (not e2e cache).
    LaunchedEffect(chat.id, parentMessage.id, chat.isChannel) {
        if (chat.isChannel) {
            runCatching {
                repository.refreshChannelThread(chat.id, parentMessage.id)
            }
        }
    }
    var draft by remember { mutableStateOf("") }
    var replyToMessage by remember { mutableStateOf<Message?>(null) }
    var replyQuotedBody by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showEmojiPicker by remember(chat.id, parentMessage.id) { mutableStateOf(false) }
    var showAllReplies by remember(parentMessage.id) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val messagesById = remember(parentMessage, threadMessages) {
        (threadMessages + parentMessage).associateBy { it.id }
    }
    val replyWindow = remember(threadMessages, showAllReplies) {
        buildThreadReplyWindow(
            messages = threadMessages,
            showAll = showAllReplies,
        )
    }
    // Backend counters can be ahead of the local encrypted cache; show the larger value in the header.
    val replyCount = maxOf(parentMessage.thread_reply_count, threadMessages.size)

    fun selectReplyTarget(message: Message, quotedBody: String? = null) {
        replyToMessage = message
        replyQuotedBody = quotedBody?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun clearReplyTarget() {
        replyToMessage = null
        replyQuotedBody = null
    }

    fun retryFailedMessage(message: Message) {
        scope.launch {
            runCatching { repository.retryFailedMessage(message.id) }
                .onFailure { error = it.message }
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
        // Same as ChatScreen: do not promote voice/mic errors into the thread banner.
        onError = {},
    )
    AppBackHandler(enabled = voiceDictation.state.isActive) {
        voiceDictation.cancel()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .screenTopSafeArea(),
    ) {
        ChatOrnamentBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = UiLayout.CHAT_HORIZONTAL_PADDING.dp)
                .padding(top = UiLayout.CHAT_TOP_PADDING.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ThreadTopBar(
                replyCount = replyCount,
                onBack = onBack,
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item("thread-root") {
                    ChatMessageBubble(
                        message = parentMessage,
                        currentUserId = user?.id,
                        chat = chat,
                        senderName = repository.usernameForSender(parentMessage.sender_id),
                        senderAvatarUrl = knownUserProfiles[parentMessage.sender_id]?.avatar_url,
                        allowThreadReply = false,
                        chatReplySwipeLabel = "↩ Ответ в ветке",
                        onMessageLongClick = { selectReplyTarget(it) },
                        onSwipeReplyInChat = { selectReplyTarget(it) },
                        onReplyToTextSelection = { message, quote ->
                            selectReplyTarget(message, quotedBody = quote)
                        },
                        onRetryFailed = ::retryFailedMessage,
                    )
                }

                item("thread-order") {
                    ThreadOrderPill()
                }

                if (threadMessages.isEmpty()) {
                    item("thread-empty") {
                        AppEmptyState(
                            title = "Пока нет ответов",
                            message = "Ответы на это сообщение появятся здесь.",
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(
                    items = replyWindow.visibleMessages,
                    key = { it.id },
                    contentType = { "thread-text" },
                ) { message ->
                    val resolvedSenderName = repository.usernameForSender(message.sender_id)
                    val replyPreview = buildThreadInlineReplyPreview(
                        message = message,
                        messagesById = messagesById,
                        currentUserId = user?.id,
                        senderNameFor = repository::usernameForSender,
                    )
                    ThreadTimelineMessage(
                        message = message,
                        isFirst = message.id == replyWindow.visibleMessages.firstOrNull()?.id,
                        isLast = message.id == replyWindow.visibleMessages.lastOrNull()?.id &&
                            replyWindow.hiddenCount == 0,
                        currentUserId = user?.id,
                        chat = chat,
                        senderName = resolvedSenderName,
                        senderAvatarUrl = knownUserProfiles[message.sender_id]?.avatar_url,
                        replyPreview = replyPreview,
                        onMessageLongClick = { selectReplyTarget(it) },
                        onSwipeReplyInChat = { selectReplyTarget(it) },
                        onReplyToTextSelection = { target, quote ->
                            selectReplyTarget(target, quotedBody = quote)
                        },
                        onRetryFailed = ::retryFailedMessage,
                    )
                }

                if (replyWindow.hiddenCount > 0) {
                    item("thread-show-more") {
                        ThreadShowMoreButton(
                            hiddenCount = replyWindow.hiddenCount,
                            onClick = { showAllReplies = true },
                        )
                    }
                }
            }

            if (showEmojiPicker) {
                EmojiPickerPanel(
                    onEmojiSelected = { emoji ->
                        draft += emoji
                        showEmojiPicker = false
                    },
                )
            }

            val activeReply = replyToMessage
            ChatComposer(
                draft = draft,
                onDraftChange = { next ->
                    if (next.isNotEmpty() && voiceDictation.state.isActive) {
                        voiceDictation.cancel()
                    }
                    draft = next
                },
                onAttach = {
                    voiceDictation.cancel()
                    showEmojiPicker = false
                    error = "Вложения в ветках пока не поддержаны"
                },
                onEmoji = {
                    voiceDictation.cancel()
                    showEmojiPicker = !showEmojiPicker
                },
                onVoice = {
                    showEmojiPicker = false
                    if (voiceDictation.state.isActive) {
                        voiceDictation.stop()
                    } else if (draft.isBlank()) {
                        voiceDictation.start()
                    }
                },
                voiceDictationState = voiceDictation.state,
                replyQuote = activeReply?.let { selected ->
                    ChatComposerReplyQuote(
                        title = resolveSenderName(
                            selected,
                            user?.id,
                            repository.usernameForSender(selected.sender_id),
                        ),
                        body = MessageReplyPolicy.resolveQuoteBody(replyQuotedBody, selected.body),
                        actionLabel = "Ответить",
                        accentColor = GlagolitsaColors.OrnamentRed,
                        titleColor = GlagolitsaColors.OrnamentGold,
                        onClear = { clearReplyTarget() },
                    )
                },
                onSend = {
                    voiceDictation.cancel()
                    val body = draft.trim()
                    if (body.isEmpty()) return@ChatComposer
                    draft = ""
                    showEmojiPicker = false
                    scope.sendChatMessage(
                        repository = repository,
                        chatId = chat.id,
                        body = body,
                        relation = MessageReplyPolicy.threadReplyRelation(
                            threadRootMessage = parentMessage,
                            replyToMessage = replyToMessage,
                            quoteBody = replyQuotedBody,
                        ),
                        onError = { error = it },
                    )
                    clearReplyTarget()
                },
                modifier = Modifier
                    .chatComposerInsets()
                    .padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun ThreadTopBar(
    replyCount: Int,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "‹",
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(start = 13.dp, top = 2.dp),
            style = MaterialTheme.typography.headlineSmall,
            color = OrnamentGold,
        )
        Column(
            modifier = Modifier.padding(start = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = "Ветка",
                style = MaterialTheme.typography.titleMedium,
                color = GlagolitsaColors.TextPrimary.copy(alpha = 0.96f),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = threadReplyCountLabel(replyCount),
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextSecondary.copy(alpha = 0.90f),
            )
        }
    }
}

@Composable
private fun ThreadOrderPill(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Сначала новые",
            style = MaterialTheme.typography.labelMedium,
            color = GlagolitsaColors.TextSecondary.copy(alpha = 0.92f),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "⌄",
            modifier = Modifier.padding(start = 7.dp),
            style = MaterialTheme.typography.titleSmall,
            color = OrnamentGold.copy(alpha = 0.84f),
        )
    }
}

@Composable
private fun ThreadTimelineMessage(
    message: Message,
    isFirst: Boolean,
    isLast: Boolean,
    currentUserId: String?,
    chat: Chat,
    senderName: String?,
    senderAvatarUrl: String?,
    replyPreview: ChatReplyPreviewData?,
    onMessageLongClick: (Message) -> Unit,
    onSwipeReplyInChat: (Message) -> Unit,
    onReplyToTextSelection: (Message, String) -> Unit,
    onRetryFailed: (Message) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        ThreadTimelineRail(
            isFirst = isFirst,
            isLast = isLast,
            modifier = Modifier
                .width(34.dp)
                .fillMaxHeight(),
        )
        ChatMessageBubble(
            message = message,
            currentUserId = currentUserId,
            chat = chat,
            senderName = senderName,
            senderAvatarUrl = senderAvatarUrl,
            replyPreview = replyPreview,
            allowThreadReply = false,
            chatReplySwipeLabel = "↩ Ответ в ветке",
            onMessageLongClick = onMessageLongClick,
            onReplyToTextSelection = onReplyToTextSelection,
            onSwipeReplyInChat = onSwipeReplyInChat,
            onRetryFailed = onRetryFailed,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ThreadTimelineRail(
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val x = size.width * 0.52f
        val top = if (isFirst) size.height * 0.18f else 0f
        val bottom = if (isLast) size.height * 0.42f else size.height
        drawLine(
            color = OrnamentRed.copy(alpha = 0.86f),
            start = androidx.compose.ui.geometry.Offset(x, top),
            end = androidx.compose.ui.geometry.Offset(x, bottom),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = GlagolitsaColors.Background950,
            radius = 6.4.dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(x, size.height * 0.30f),
        )
        drawCircle(
            color = OrnamentRed,
            radius = 4.2.dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(x, size.height * 0.30f),
        )
        drawCircle(
            color = OrnamentGold.copy(alpha = 0.92f),
            radius = 1.9.dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(x, size.height * 0.30f),
        )
    }
}

@Composable
private fun ThreadShowMoreButton(
    hiddenCount: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 34.dp, top = 2.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(GlagolitsaColors.Surface800.copy(alpha = 0.70f))
            .border(0.7.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Показать ещё $hiddenCount ${threadReplyWord(hiddenCount)}",
                style = MaterialTheme.typography.labelLarge,
                color = GlagolitsaColors.TextSecondary.copy(alpha = 0.94f),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "⌄",
                style = MaterialTheme.typography.titleSmall,
                color = OrnamentGold.copy(alpha = 0.88f),
            )
        }
    }
}

private fun resolveSenderName(
    message: Message,
    currentUserId: String?,
    senderName: String?,
): String = replySenderLabel(message.sender_id, currentUserId, senderName)

private fun buildThreadInlineReplyPreview(
    message: Message,
    messagesById: Map<String, Message>,
    currentUserId: String?,
    senderNameFor: (String) -> String?,
): ChatReplyPreviewData? {
    return resolveInlineReplyPreview(
        message = message,
        messagesById = messagesById,
        currentUserId = currentUserId,
        senderNameFor = senderNameFor,
    )
}
