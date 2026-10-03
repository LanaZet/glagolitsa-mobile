// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.glagolitsa.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.glagolitsa.ui.rememberScreenWidthDp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.media.AttachmentKind
import com.glagolitsa.model.Chat
import com.glagolitsa.model.Message
import com.glagolitsa.model.formatMessageTime
import com.glagolitsa.model.isFailed
import com.glagolitsa.model.isSending
import com.glagolitsa.model.listAvatarLabel
import com.glagolitsa.repository.AttachmentPreview
import com.glagolitsa.security.SecureClipboard
import com.glagolitsa.ui.components.GlagolitsaChatInput
import com.glagolitsa.ui.components.OrnamentGold
import com.glagolitsa.ui.components.OrnamentRed
import com.glagolitsa.ui.components.SlavicPillTextButton
import com.glagolitsa.ui.components.avatarFrameShadow
import com.glagolitsa.ui.components.avatarGlassBezel
import com.glagolitsa.ui.components.concaveSurface
import com.glagolitsa.ui.layout.ChatLayout
import com.glagolitsa.ui.profile.decodeImageBytes
import com.glagolitsa.ui.profile.ProfileAvatar
import com.glagolitsa.ui.theme.GlagolitsaColors
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.chat_call_rotary_phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import kotlin.math.absoluteValue
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

private val ChatHeaderCardShape = RoundedCornerShape(18.dp)
private val ChatComposerShape = RoundedCornerShape(percent = 50)
/** Bubbles at or under this height keep the incoming avatar vertically centered. */
private val IncomingAvatarCenterMaxHeight = 120.dp
private val ChatReplyPreviewShape = RoundedCornerShape(10.dp)
private val ChatSystemMessageShape = RoundedCornerShape(percent = 50)
private val MinAttachmentImageHeight = 116.dp
private val MaxAttachmentImageHeight = 320.dp
data class ChatReplyPreviewData(
    val senderName: String,
    val body: String,
    /** Message id to scroll to / reply to when acting on the quote. */
    val targetMessageId: String? = null,
    /** Original sender id (for reply fallback when parent is not in the loaded window). */
    val targetSenderId: String? = null,
)

@Composable
fun ChatOrnamentBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val stripe = Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to GlagolitsaColors.Background950.copy(alpha = 0.72f),
                0.22f to GlagolitsaColors.Background900.copy(alpha = 0.34f),
                0.46f to Color.Transparent,
                1f to Color.Transparent,
            ),
        )
        drawRect(stripe)
    }
}

/**
 * Верхняя панель чата.
 *
 * - тап по аватару/имени → [onChatInfo] (просмотр)
 * - ⋮ справа → [onChatSettings] (редактирование настроек)
 * - [onCall] — отдельная кнопка звонка, когда есть собеседник
 */
/**
 * Second line under the chat title.
 * Server connection plate temporarily replaces partner presence (never draws on the title row).
 */
internal fun resolveChatTopBarStatusLine(
    isDirectMessage: Boolean,
    groupSubtitle: String?,
    partnerPresenceStatus: String?,
    connectionStatus: String?,
): Pair<String?, Boolean> {
    // Groups keep participant subtitle; connection plate is a DM/status concern.
    if (!groupSubtitle.isNullOrBlank()) {
        return groupSubtitle to false
    }
    if (!connectionStatus.isNullOrBlank()) {
        return connectionStatus to true
    }
    val presence = partnerPresenceStatus?.takeIf { it.isNotBlank() }
        ?: if (isDirectMessage) "не в сети" else null
    return presence to false
}

@Composable
fun ChatTopBar(
    chat: Chat,
    onBack: () -> Unit,
    onCall: (() -> Unit)? = null,
    onChatInfo: (() -> Unit)? = null,
    onChatSettings: (() -> Unit)? = null,
    /** Partner presence («в сети») — replaced while [connectionStatus] is set. */
    presenceStatus: String? = null,
    /**
     * Server connection plate («Переподключаемся…»).
     * Shown only on the status line under the name, never beside the title.
     */
    connectionStatus: String? = null,
    subtitle: String? = null,
    presenceColor: Color? = null,
    avatarUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    val actionSize = ChatLayout.topBarActionSize
    val iconSize = ChatLayout.topBarIconSize
    val (statusLine, isConnectionPlate) = resolveChatTopBarStatusLine(
        isDirectMessage = chat.isDirectMessage,
        groupSubtitle = subtitle,
        partnerPresenceStatus = presenceStatus,
        connectionStatus = connectionStatus,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OrnamentIconButton(
            size = actionSize,
            onClick = onBack,
            contentDescription = "Назад",
            iconSize = iconSize,
            icon = { tint, m -> BackOrnamentIcon(tint = tint, modifier = m) },
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Title cluster: open chat info (Telegram/iMessage pattern).
        Row(
            modifier = Modifier
                .weight(1f)
                .widthIn(min = 0.dp)
                .then(
                    if (onChatInfo != null) {
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onChatInfo)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Информация о чате"
                            }
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (chat.isDirectMessage) {
                ChatTopBarAvatarButton(
                    avatarUrl = avatarUrl,
                    fallbackLabel = chat.listAvatarLabel(),
                    presenceColor = presenceColor,
                )
            } else {
                ProfileAvatar(
                    avatarUrl = avatarUrl,
                    fallbackLabel = chat.listAvatarLabel(),
                    size = ChatLayout.topBarAvatarSize,
                    presenceColor = null,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 0.dp)
                    .padding(start = 8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = chat.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = GlagolitsaColors.TextPrimary.copy(alpha = 0.95f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!statusLine.isNullOrBlank()) {
                        Text(
                            text = statusLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isConnectionPlate) {
                                GlagolitsaColors.OrnamentGold.copy(alpha = 0.92f)
                            } else {
                                GlagolitsaColors.TextSecondary.copy(alpha = 0.9f)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics {
                                contentDescription = if (isConnectionPlate) {
                                    "Соединение с сервером: $statusLine"
                                } else {
                                    "Статус: $statusLine"
                                }
                            },
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (onCall != null) {
                OrnamentIconButton(
                    size = actionSize,
                    onClick = onCall,
                    contentDescription = "Позвонить",
                    icon = { _, m -> PhoneOrnamentIcon(modifier = m) },
                    iconSize = 30.dp,
                )
            }
            if (onChatSettings != null) {
                OrnamentIconButton(
                    size = actionSize,
                    onClick = onChatSettings,
                    contentDescription = "Настройки чата",
                    icon = { tint, m -> DotsOrnamentIcon(tint = tint, modifier = m) },
                    iconSize = iconSize,
                )
            }
        }
    }
}

@Composable
private fun ChatTopBarAvatarButton(
    avatarUrl: String?,
    fallbackLabel: String,
    presenceColor: Color?,
    modifier: Modifier = Modifier,
) {
    val avatarSize = ChatLayout.topBarAvatarSize
    val frameSize = avatarSize + 10.dp

    Box(
        modifier = modifier
            .size(frameSize)
            .avatarFrameShadow(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize + 4.dp)
                .avatarGlassBezel(rim = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            ProfileAvatar(
                avatarUrl = avatarUrl,
                fallbackLabel = fallbackLabel,
                size = avatarSize,
                // Keep ring as last-known partner presence; connection plate is text only.
                presenceColor = presenceColor,
            )
        }
    }
}

@Composable
fun ChatDayPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(ChatComposerShape)
            .background(GlagolitsaColors.Surface800.copy(alpha = 0.54f))
            .border(0.7.dp, GlagolitsaColors.OverlayHairline, ChatComposerShape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = GlagolitsaColors.TextPrimary.copy(alpha = 0.96f),
        )
    }
}

/** Один пузырь в ленте: декоративные углы, контраст и время как в референсе. */
@Composable
fun ChatMessageBubble(
    message: Message,
    currentUserId: String?,
    chat: Chat,
    senderName: String? = null,
    senderAvatarUrl: String? = null,
    attachmentPreview: AttachmentPreview? = null,
    sendError: String? = null,
    replyPreview: ChatReplyPreviewData? = null,
    threadBranchPreview: ChatThreadBranchPreviewData? = null,
    allowThreadReply: Boolean = true,
    chatReplySwipeLabel: String = "↩ Ответ в чате",
    threadReplySwipeLabel: String = "🧵 Ответ в ветке",
    onMessageClick: ((Message) -> Unit)? = null,
    onMessageLongClick: ((Message) -> Unit)? = null,
    onThreadBranchClick: ((Message) -> Unit)? = null,
    onSwipeReplyInChat: ((Message) -> Unit)? = null,
    onSwipeReplyInThread: ((Message) -> Unit)? = null,
    /** Reply to a drag-selected quote inside the message body (selected text, not full body). */
    onReplyToTextSelection: ((Message, selectedText: String) -> Unit)? = null,
    /** Tap a glagolitsa.app/c/… or /join/… invite in the bubble. */
    onInviteLinkClick: ((raw: String) -> Unit)? = null,
    /** Jump to the original message for an inline reply quote (after quote tap → go icon). */
    onNavigateToReply: ((targetMessageId: String) -> Unit)? = null,
    /**
     * Reply to the original quoted message using a fragment selected inside the quote preview.
     * Args: targetMessageId, targetSenderId?, selectedText, previewBody.
     */
    onReplyToQuoteSelection: ((
        targetMessageId: String,
        targetSenderId: String?,
        selectedText: String,
        previewBody: String,
    ) -> Unit)? = null,
    onAttachmentMediaClick: ((AttachmentPreview) -> Unit)? = null,
    onAttachmentClick: ((Message) -> Unit)? = null,
    playingAttachmentMessageId: String? = null,
    playingAttachmentDurationMs: Long? = null,
    onRetryFailed: ((Message) -> Unit)? = null,
    selected: Boolean = false,
    /** Temporary highlight after “go to quote” navigation. */
    highlighted: Boolean = false,
    /** Show “изм.” after local/server edit. */
    isEdited: Boolean = false,
    reactionChips: List<com.glagolitsa.model.ReactionChip> = emptyList(),
    onReactionChipClick: ((String) -> Unit)? = null,
    groupPosition: ChatBubbleGroupPosition = ChatBubbleGroupPosition.Single,
    modifier: Modifier = Modifier,
) {
    if (message.isChatSystemMessage()) {
        ChatSystemMessageBubble(
            text = message.body,
            modifier = modifier,
        )
        return
    }

    val isOwn = message.sender_id == currentUserId
    val timeLabel = formatMessageTime(message.created_at)
    val bubbleShape = chatBubbleShape(isOwn = isOwn, position = groupPosition)
    val showIncomingAvatar = !isOwn && groupPosition.showsIncomingAvatar()
    val showSender = !chat.isDirectMessage && showIncomingAvatar && !senderName.isNullOrBlank()
    val bubbleFill = if (isOwn) {
        GlagolitsaColors.ChatBubbleOwnFill
    } else {
        GlagolitsaColors.ChatBubbleOtherFill
    }
    val borderColor = if (isOwn) {
        GlagolitsaColors.ChatBubbleOwnBorder
    } else {
        GlagolitsaColors.ChatBubbleOtherBorder
    }
    val timeColor = if (isOwn) {
        Color.White.copy(alpha = 0.68f)
    } else {
        GlagolitsaColors.TextSecondary.copy(alpha = 0.72f)
    }
    val bodyColor = if (isOwn) Color.White else GlagolitsaColors.TextPrimary.copy(alpha = 0.97f)
    val screenWidth = rememberScreenWidthDp()
    val maxBubbleWidth = (screenWidth * 0.78f).coerceAtLeast(248.dp)
    val attachmentKind = attachmentPreview?.kind
    val richMediaAttachment = attachmentKind == AttachmentKind.IMAGE || attachmentKind == AttachmentKind.VIDEO
    val openMediaAttachment: (() -> Unit)? = when {
        attachmentPreview == null -> null
        onAttachmentClick != null -> {
            { onAttachmentClick.invoke(message) }
        }
        onAttachmentMediaClick != null &&
            (attachmentPreview.isImage || attachmentPreview.isVideo ||
                attachmentPreview.isVoice || attachmentPreview.isAudio) -> {
            { onAttachmentMediaClick.invoke(attachmentPreview) }
        }
        else -> null
    }
    val swipeTriggerPx = with(LocalDensity.current) { 42.dp.toPx() }
    val maxSwipePx = with(LocalDensity.current) { 96.dp.toPx() }
    val minSwipePx = if (allowThreadReply) -maxSwipePx else 0f
    var horizontalOffsetPx by remember(message.id) { mutableFloatStateOf(0f) }
    val visualOffsetPx = swipeVisualOffset(horizontalOffsetPx, maxSwipePx)
    val swipeDragState = rememberDraggableState { delta ->
        horizontalOffsetPx = (horizontalOffsetPx + delta).coerceIn(minSwipePx, maxSwipePx)
    }
    // Short bubbles: avatar centered on the row. Tall bubbles: sit lower (near the bottom),
    // so the face tracks the last lines instead of floating mid-wall of text.
    var bubbleHeightPx by remember(message.id) { mutableIntStateOf(0) }
    val shortBubbleMaxHeightPx = with(LocalDensity.current) { IncomingAvatarCenterMaxHeight.toPx() }
    val avatarRowAlignment = if (
        bubbleHeightPx == 0 || bubbleHeightPx <= shortBubbleMaxHeightPx
    ) {
        Alignment.CenterVertically
    } else {
        Alignment.Bottom
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Full-width row wash when focused via “go to quote” — covers avatar + bubble vertically.
        val rowHighlightModifier = if (highlighted) {
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            GlagolitsaColors.OrnamentGold.copy(alpha = 0.14f),
                            GlagolitsaColors.OrnamentGold.copy(alpha = 0.08f),
                            GlagolitsaColors.OrnamentGold.copy(alpha = 0.12f),
                        ),
                    ),
                )
                .padding(horizontal = 4.dp, vertical = 4.dp)
        } else {
            Modifier
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(rowHighlightModifier),
            horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
            verticalAlignment = avatarRowAlignment,
        ) {
            if (!isOwn) {
                Box(
                    modifier = Modifier
                        .width(43.dp)
                        .padding(bottom = if (avatarRowAlignment == Alignment.Bottom) 2.dp else 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showIncomingAvatar) {
                        MessageSenderAvatar(
                            label = senderName ?: chat.title,
                            avatarUrl = senderAvatarUrl,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .onSizeChanged { size ->
                        if (bubbleHeightPx != size.height) {
                            bubbleHeightPx = size.height
                        }
                    },
            ) {
                ChatMessageSwipeCue(
                    swipeOffsetPx = horizontalOffsetPx,
                    triggerPx = swipeTriggerPx,
                    allowThreadReply = allowThreadReply,
                    chatReplySwipeLabel = chatReplySwipeLabel,
                    threadReplySwipeLabel = threadReplySwipeLabel,
                    modifier = Modifier.matchParentSize(),
                )

                Column(
                    modifier = Modifier
                        .then(
                            if (richMediaAttachment) {
                                Modifier.width(maxBubbleWidth)
                            } else {
                                Modifier.widthIn(max = maxBubbleWidth)
                            },
                        )
                        .offset { IntOffset(visualOffsetPx.roundToInt(), 0) }
                        .clip(bubbleShape)
                        .draggable(
                            state = swipeDragState,
                            orientation = Orientation.Horizontal,
                            enabled = onSwipeReplyInChat != null || onSwipeReplyInThread != null,
                            onDragStopped = {
                                val finalOffset = horizontalOffsetPx
                                when {
                                    finalOffset >= swipeTriggerPx -> onSwipeReplyInChat?.invoke(message)
                                    allowThreadReply && finalOffset <= -swipeTriggerPx -> {
                                        onSwipeReplyInThread?.invoke(message)
                                    }
                                }
                                animateSwipeBackToZero(finalOffset) { horizontalOffsetPx = it }
                            },
                        )
                        .combinedClickable(
                            onClick = {
                                onMessageClick?.invoke(message)
                            },
                            onLongClick = {
                                // Prefer explicit menu; fallback keeps copy for callers without handler.
                                if (onMessageLongClick != null) {
                                    onMessageLongClick.invoke(message)
                                } else if (message.body.isNotBlank()) {
                                    SecureClipboard.copyWithAutoClear(
                                        label = "message",
                                        text = message.body,
                                    )
                                }
                            },
                        )
                        .background(bubbleFill, bubbleShape)
                        .border(
                            width = if (selected) 2.dp else 0.9.dp,
                            color = when {
                                selected -> GlagolitsaColors.AccentRed.copy(alpha = 0.85f)
                                highlighted -> GlagolitsaColors.OrnamentGold.copy(alpha = 0.45f)
                                else -> borderColor
                            },
                            shape = bubbleShape,
                        )
                        .then(
                            if (highlighted) {
                                Modifier.drawWithContent {
                                    drawContent()
                                    drawRoundRect(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                GlagolitsaColors.OrnamentGold.copy(alpha = 0.14f),
                                                GlagolitsaColors.OrnamentGold.copy(alpha = 0.08f),
                                                GlagolitsaColors.OrnamentGold.copy(alpha = 0.12f),
                                            ),
                                        ),
                                        cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        )
                        .drawBehind {
                            drawBubbleInnerHighlight(isOwn = isOwn)
                        }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (showSender) {
                        Text(
                            text = senderName.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = OrnamentGold.copy(alpha = 0.92f),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    replyPreview?.let { preview ->
                        ChatReplyPreview(
                            preview = preview,
                            isOwn = isOwn,
                            onNavigateToReply = onNavigateToReply,
                            onReplyToQuoteSelection = onReplyToQuoteSelection,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (attachmentPreview != null) {
                        ChatAttachmentPreview(
                            preview = attachmentPreview,
                            timeLabel = timeLabel,
                            message = message,
                            isOwn = isOwn,
                            playing = isAttachmentPlaying(
                                preview = attachmentPreview,
                                message = message,
                                playingMessageId = playingAttachmentMessageId,
                            ),
                            playbackDurationMs = playingAttachmentDurationMs,
                            onClick = {
                                openMediaAttachment?.invoke()
                            },
                            onRetryFailed = onRetryFailed,
                            sendError = sendError,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val hideAttachmentPlaceholderBody = attachmentPreview != null &&
                        isAttachmentPlaceholderBody(message.body)
                    val showBody = !hideAttachmentPlaceholderBody
                    // Time sits in the bottom-end of the bubble, not glued to short text.
                    when {
                        showBody && attachmentPreview == null -> {
                            MessageBodyWithCornerTime(
                                body = message.body,
                                bodyColor = bodyColor,
                                timeLabel = timeLabel,
                                timeColor = timeColor,
                                message = message,
                                isOwn = isOwn,
                                isEdited = isEdited,
                                onRetryFailed = onRetryFailed,
                                sendError = sendError,
                                onReplyToTextSelection = onReplyToTextSelection?.let { handler ->
                                    { quote -> handler(message, quote) }
                                },
                                onInviteLinkClick = onInviteLinkClick,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        showBody -> {
                            SelectableAutoCopyMessageText(
                                text = message.body,
                                color = bodyColor,
                                style = MaterialTheme.typography.bodyLarge,
                                onReplyToSelection = onReplyToTextSelection?.let { handler ->
                                    { quote -> handler(message, quote) }
                                },
                                onInviteLinkClick = onInviteLinkClick,
                                modifier = Modifier.testTag("chat-message-body"),
                            )
                        }
                    }
                    if (reactionChips.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .align(if (isOwn) Alignment.End else Alignment.Start)
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            reactionChips.forEach { chip ->
                                Text(
                                    text = "${chip.emoji} ${chip.count}".trim(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (chip.reactedByMe) {
                                        GlagolitsaColors.AccentRed
                                    } else {
                                        bodyColor.copy(alpha = 0.85f)
                                    },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (chip.reactedByMe) {
                                                GlagolitsaColors.AccentRed.copy(alpha = 0.16f)
                                            } else {
                                                Color.White.copy(alpha = 0.08f)
                                            },
                                        )
                                        .clickable(enabled = onReactionChipClick != null) {
                                            onReactionChipClick?.invoke(chip.emoji)
                                        }
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        threadBranchPreview?.let { preview ->
            val branchContainerModifier = if (isOwn) {
                Modifier
                    .width(maxBubbleWidth)
                    .offset(x = (-28).dp, y = (-3).dp)
            } else {
                Modifier
                    .widthIn(max = maxBubbleWidth)
                    .offset(y = (-3).dp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.Top,
            ) {
                if (!isOwn) {
                    Spacer(modifier = Modifier.width(43.dp))
                }
                Column(
                    modifier = branchContainerModifier,
                ) {
                    ThreadBranchAttachedPreview(
                        preview = preview,
                        isOwn = isOwn,
                        onClick = { onThreadBranchClick?.invoke(message) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatAttachmentPreview(
    preview: AttachmentPreview,
    timeLabel: String,
    message: Message,
    isOwn: Boolean,
    playing: Boolean = false,
    playbackDurationMs: Long? = null,
    onClick: () -> Unit,
    onRetryFailed: ((Message) -> Unit)?,
    sendError: String?,
    modifier: Modifier = Modifier,
) {
    when (preview.kind) {
        AttachmentKind.IMAGE -> ChatAttachmentImage(
            preview = preview,
            timeLabel = timeLabel,
            message = message,
            isOwn = isOwn,
            onClick = onClick,
            onRetryFailed = onRetryFailed,
            sendError = sendError,
            modifier = modifier,
        )
        AttachmentKind.VIDEO -> ChatAttachmentVideo(
            preview = preview,
            timeLabel = timeLabel,
            message = message,
            isOwn = isOwn,
            onClick = onClick,
            onRetryFailed = onRetryFailed,
            sendError = sendError,
            modifier = modifier,
        )
        AttachmentKind.VOICE -> ChatAttachmentVoice(
            preview = preview,
            timeLabel = timeLabel,
            message = message,
            isOwn = isOwn,
            playing = playing,
            playbackDurationMs = playbackDurationMs,
            onClick = onClick,
            onRetryFailed = onRetryFailed,
            sendError = sendError,
            modifier = modifier,
        )
        AttachmentKind.AUDIO,
        AttachmentKind.DOCUMENT,
        AttachmentKind.UNKNOWN,
        -> ChatAttachmentFileCard(
            preview = preview,
            timeLabel = timeLabel,
            message = message,
            isOwn = isOwn,
            onClick = onClick,
            onRetryFailed = onRetryFailed,
            sendError = sendError,
            modifier = modifier,
        )
    }
}

private suspend fun animateSwipeBackToZero(
    from: Float,
    onValue: (Float) -> Unit,
) {
    if (from == 0f) return
    animate(
        initialValue = from,
        targetValue = 0f,
        animationSpec = tween(durationMillis = 170),
    ) { value, _ ->
        onValue(value)
    }
}

@Composable
private fun rememberCachedChatImage(cacheKey: String, bytes: ByteArray): ImageBitmap? {
    var imageBitmap by remember(cacheKey) {
        mutableStateOf(ChatImageBitmapCache.get(cacheKey))
    }
    LaunchedEffect(cacheKey, bytes.size) {
        val cached = ChatImageBitmapCache.get(cacheKey)
        if (cached != null) {
            imageBitmap = cached
            return@LaunchedEffect
        }
        if (bytes.isEmpty()) return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            decodeImageBytes(bytes)
        }
        if (decoded != null) {
            ChatImageBitmapCache.put(cacheKey, decoded)
            imageBitmap = decoded
        }
    }
    return imageBitmap
}

@Composable
private fun ChatAttachmentMediaFrame(
    preview: AttachmentPreview,
    timeLabel: String,
    message: Message,
    isOwn: Boolean,
    cacheKey: String,
    fallbackLabel: String,
    backgroundAlpha: Float,
    onClick: () -> Unit,
    onRetryFailed: ((Message) -> Unit)?,
    sendError: String?,
    modifier: Modifier = Modifier,
    topStartOverlay: @Composable BoxScope.() -> Unit = {},
    centerOverlay: @Composable BoxScope.() -> Unit = {},
    footerLeading: @Composable RowScope.() -> Unit = {},
) {
    val imageBitmap = rememberCachedChatImage(cacheKey, preview.displayBytes)
    val shape = RoundedCornerShape(13.dp)
    val bitmap = imageBitmap
    BoxWithConstraints(modifier = modifier) {
        val reserved = reservedAttachmentHeightDp(
            pixelWidth = preview.width ?: bitmap?.width,
            pixelHeight = preview.height ?: bitmap?.height,
            maxWidthDp = maxWidth.value,
        ).dp
        val imageHeight = reserved
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(imageHeight)
                .clip(shape)
                .background(Color.Black.copy(alpha = backgroundAlpha))
                .border(0.8.dp, Color.White.copy(alpha = 0.13f), shape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = preview.fileName ?: fallbackLabel,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = preview.fileName ?: fallbackLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = GlagolitsaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            topStartOverlay()
            centerOverlay()
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.42f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                footerLeading()
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.92f),
                )
                if (isOwn) {
                    MessageDeliveryIndicator(
                        message = message,
                        tint = Color.White.copy(alpha = 0.92f),
                        timeColor = Color.White.copy(alpha = 0.92f),
                        onRetryFailed = onRetryFailed,
                        sendError = sendError,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatAttachmentImage(
    preview: AttachmentPreview,
    timeLabel: String,
    message: Message,
    isOwn: Boolean,
    onClick: () -> Unit,
    onRetryFailed: ((Message) -> Unit)?,
    sendError: String?,
    modifier: Modifier = Modifier,
) {
    ChatAttachmentMediaFrame(
        preview = preview,
        timeLabel = timeLabel,
        message = message,
        isOwn = isOwn,
        cacheKey = preview.messageId,
        fallbackLabel = "Вложение",
        backgroundAlpha = 0.18f,
        onClick = onClick,
        onRetryFailed = onRetryFailed,
        sendError = sendError,
        modifier = modifier,
        topStartOverlay = {
            if (preview.isAnimatedGif) {
                Text(
                    text = "GIF",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.94f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.46f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        },
    )
}

@Composable
private fun ChatAttachmentVideo(
    preview: AttachmentPreview,
    timeLabel: String,
    message: Message,
    isOwn: Boolean,
    onClick: () -> Unit,
    onRetryFailed: ((Message) -> Unit)?,
    sendError: String?,
    modifier: Modifier = Modifier,
) {
    ChatAttachmentMediaFrame(
        preview = preview,
        timeLabel = timeLabel,
        message = message,
        isOwn = isOwn,
        cacheKey = "video-${preview.messageId}",
        fallbackLabel = "Видео",
        backgroundAlpha = 0.30f,
        onClick = onClick,
        onRetryFailed = onRetryFailed,
        sendError = sendError,
        modifier = modifier,
        centerOverlay = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.48f))
                    .border(1.dp, Color.White.copy(alpha = 0.38f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                PlayTriangleIcon(tint = Color.White, modifier = Modifier.size(22.dp))
            }
        },
        footerLeading = {
            preview.durationMs?.let {
                Text(
                    text = formatMediaDuration(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.92f),
                )
            }
        },
    )
}

@Composable
private fun ChatAttachmentVoice(
    preview: AttachmentPreview,
    timeLabel: String,
    message: Message,
    isOwn: Boolean,
    playing: Boolean,
    playbackDurationMs: Long? = null,
    onClick: () -> Unit,
    onRetryFailed: ((Message) -> Unit)?,
    sendError: String?,
    modifier: Modifier = Modifier,
) {
    val waveTint = if (isOwn) {
        Color.White.copy(alpha = 0.52f)
    } else {
        GlagolitsaColors.OrnamentGold.copy(alpha = 0.46f)
    }
    val playedTint = if (isOwn) {
        Color.White.copy(alpha = 0.94f)
    } else {
        GlagolitsaColors.OrnamentGold
    }
    val durationMs = preview.durationMs?.takeIf { it > 0L }
        ?: playbackDurationMs?.takeIf { it > 0L }
        ?: 0L
    var progress by remember(preview.messageId) { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, durationMs, preview.messageId) {
        if (!playing) {
            progress = 0f
            return@LaunchedEffect
        }
        progress = 0f
        if (durationMs > 0L) {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMs.toInt().coerceIn(1, Int.MAX_VALUE),
                    easing = LinearEasing,
                ),
            ) { value, _ -> progress = value }
        }
    }
    val pulse = if (playing) rememberVoicePlaybackPulse() else 0f
    val elapsedMs = if (playing && durationMs > 0L) {
        (durationMs * progress).toLong()
    } else {
        durationMs
    }

    Row(
        modifier = modifier
            .widthIn(min = 232.dp, max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = if (playing) 0.18f else 0.12f))
            .border(
                0.8.dp,
                if (playing) {
                    GlagolitsaColors.OrnamentGold.copy(alpha = 0.28f + 0.16f * pulse)
                } else {
                    Color.White.copy(alpha = 0.13f)
                },
                RoundedCornerShape(18.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics {
                contentDescription = if (playing) {
                    "Пауза голосового"
                } else {
                    "Слушать голосовое"
                }
            }
            .testTag(if (playing) "chat-voice-playing" else "chat-voice-attachment"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (playing) {
                Box(
                    modifier = Modifier
                        .size((42 + 6 * pulse).dp)
                        .border(
                            1.2.dp,
                            GlagolitsaColors.OrnamentGold.copy(alpha = 0.42f * (1f - pulse * 0.35f)),
                            CircleShape,
                        ),
                )
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (playing) {
                            GlagolitsaColors.OrnamentGold.copy(alpha = 0.90f)
                        } else {
                            GlagolitsaColors.OrnamentGold.copy(alpha = 0.18f)
                        },
                    )
                    .border(
                        0.8.dp,
                        GlagolitsaColors.OrnamentGold.copy(alpha = if (playing) 0.55f else 0.32f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (playing) {
                    PauseBarsIcon(
                        tint = Color(0xFF2B2321),
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    PlayTriangleIcon(
                        tint = GlagolitsaColors.OrnamentGold,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            VoiceWaveform(
                values = preview.waveform,
                seed = preview.messageId,
                tint = waveTint,
                playedTint = playedTint,
                playing = playing,
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatMediaDuration(elapsedMs.takeIf { it > 0L } ?: durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (playing) playedTint else GlagolitsaColors.TextSecondary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = GlagolitsaColors.TextSecondary,
                    )
                    if (isOwn) {
                        MessageDeliveryIndicator(
                            message = message,
                            tint = GlagolitsaColors.TextSecondary,
                            timeColor = GlagolitsaColors.TextSecondary,
                            onRetryFailed = onRetryFailed,
                            sendError = sendError,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatAttachmentFileCard(
    preview: AttachmentPreview,
    timeLabel: String,
    message: Message,
    isOwn: Boolean,
    onClick: () -> Unit,
    onRetryFailed: ((Message) -> Unit)?,
    sendError: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(min = 214.dp, max = 320.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color.Black.copy(alpha = 0.12f))
            .border(0.8.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(GlagolitsaColors.OrnamentGold.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = attachmentKindGlyph(preview.kind),
                style = MaterialTheme.typography.titleMedium,
                color = GlagolitsaColors.OrnamentGold,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = preview.fileName ?: attachmentKindLabel(preview.kind),
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = when {
                preview.isVoice -> listOfNotNull("Голосовое", preview.durationMs?.let(::formatMediaDuration))
                preview.isAudio -> listOfNotNull("Аудио", preview.durationMs?.let(::formatMediaDuration))
                else -> listOfNotNull(attachmentKindLabel(preview.kind), preview.mimeType?.takeIf { it != "application/octet-stream" })
            }.joinToString(" • ")
            Text(
                text = details.ifBlank { "Файл" },
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.TextSecondary,
            )
            if (isOwn) {
                MessageDeliveryIndicator(
                    message = message,
                    tint = GlagolitsaColors.TextSecondary,
                    timeColor = GlagolitsaColors.TextSecondary,
                    onRetryFailed = onRetryFailed,
                    sendError = sendError,
                )
            }
        }
    }
}

@Composable
private fun VoiceWaveform(
    values: List<Int>,
    seed: String,
    tint: Color,
    playedTint: Color = tint,
    playing: Boolean = false,
    progress: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val bars = remember(values, seed) {
        if (values.isNotEmpty()) {
            values.take(42).map { it.coerceIn(0, 100) / 100f }
        } else {
            deterministicVoiceBars(seed, 34)
        }
    }
    val phase = if (playing) rememberVoicePlaybackPhase() else 0f
    Canvas(modifier = modifier) {
        if (bars.isEmpty()) return@Canvas
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(2.dp.toPx())
        val playheadX = size.width * progress.coerceIn(0f, 1f)
        bars.forEachIndexed { index, normalized ->
            val amplitude = voiceBarAnimatedAmplitude(
                base = normalized,
                playing = playing,
                phase = phase,
                index = index,
            )
            val height = (size.height * (0.22f + amplitude * 0.72f)).coerceAtLeast(3.dp.toPx())
            val x = index * (barWidth + gap)
            val played = playing && voiceBarIsPlayed(index, bars.size, progress)
            drawLine(
                color = if (played) playedTint else tint,
                start = Offset(x + barWidth / 2f, (size.height - height) / 2f),
                end = Offset(x + barWidth / 2f, (size.height + height) / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
        if (playing && progress > 0f) {
            drawCircle(
                color = playedTint,
                radius = 3.2.dp.toPx(),
                center = Offset(playheadX.coerceIn(barWidth / 2f, size.width - barWidth / 2f), size.height / 2f),
            )
        }
    }
}

@Composable
private fun rememberVoicePlaybackPulse(): Float {
    val transition = rememberInfiniteTransition(label = "voice-play-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice-play-pulse-value",
    )
    return pulse
}

@Composable
private fun rememberVoicePlaybackPhase(): Float {
    val transition = rememberInfiniteTransition(label = "voice-wave-phase")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 780, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "voice-wave-phase-value",
    )
    return phase
}

private fun deterministicVoiceBars(seed: String, count: Int): List<Float> {
    var state = seed.fold(0x4D2A1F3B) { acc, char -> acc * 31 + char.code }
    return List(count) {
        state = state * 1103515245 + 12345
        val value = ((state ushr 16) and 0x7FFF) / 32767f
        value.coerceIn(0.08f, 1f)
    }
}

private fun attachmentImageHeight(
    bitmap: ImageBitmap?,
    width: Dp,
): Dp {
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
        return 188.dp
    }

    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
    return (width / ratio).coerceIn(MinAttachmentImageHeight, MaxAttachmentImageHeight)
}

@Composable
private fun PlayTriangleIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.18f)
            lineTo(size.width * 0.28f, size.height * 0.82f)
            lineTo(size.width * 0.82f, size.height * 0.50f)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
private fun PauseBarsIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val barWidth = size.width * 0.22f
        val gap = size.width * 0.16f
        val left = (size.width - barWidth * 2f - gap) / 2f
        val top = size.height * 0.18f
        val height = size.height * 0.64f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(barWidth, height),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(left + barWidth + gap, top),
            size = Size(barWidth, height),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
        )
    }
}

private fun attachmentKindLabel(kind: AttachmentKind): String =
    when (kind) {
        AttachmentKind.IMAGE -> "Фото"
        AttachmentKind.VIDEO -> "Видео"
        AttachmentKind.VOICE -> "Голосовое"
        AttachmentKind.AUDIO -> "Аудио"
        AttachmentKind.DOCUMENT,
        AttachmentKind.UNKNOWN,
        -> "Файл"
    }

private fun attachmentKindGlyph(kind: AttachmentKind): String =
    when (kind) {
        AttachmentKind.VIDEO -> "▶"
        AttachmentKind.VOICE -> "🎙"
        AttachmentKind.AUDIO -> "♪"
        AttachmentKind.IMAGE -> "▧"
        AttachmentKind.DOCUMENT,
        AttachmentKind.UNKNOWN,
        -> "□"
    }

@Composable
private fun ThreadBranchAttachedPreview(
    preview: ChatThreadBranchPreviewData,
    isOwn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        if (!isOwn) {
            ThreadBranchAttachmentRail(
                active = preview.hasUnread,
                attachFromEnd = false,
                modifier = Modifier
                    .width(46.dp)
                    .fillMaxHeight(),
            )
        }
        ChatThreadBranchPreview(
            preview = preview,
            isOwn = isOwn,
            onClick = onClick,
            modifier = Modifier
                .weight(1f)
                .padding(top = 14.dp),
        )
        if (isOwn) {
            ThreadBranchAttachmentRail(
                active = preview.hasUnread,
                attachFromEnd = true,
                modifier = Modifier
                    .width(46.dp)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
fun ChatSystemMessageBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(ChatSystemMessageShape)
                .background(GlagolitsaColors.Surface800.copy(alpha = 0.62f))
                .border(0.7.dp, Color.White.copy(alpha = 0.11f), ChatSystemMessageShape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = GlagolitsaColors.TextSecondary.copy(alpha = 0.95f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MessageSenderAvatar(
    label: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
) {
    val initials = label
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifBlank { "?" }

    Box(
        modifier = modifier
            .size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            ProfileAvatar(
                avatarUrl = avatarUrl,
                fallbackLabel = initials,
                size = 36.dp,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                GlagolitsaColors.Surface600.copy(alpha = 0.82f),
                                GlagolitsaColors.Surface800.copy(alpha = 0.92f),
                            ),
                        ),
                    )
                    .border(0.8.dp, Color.White.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.labelMedium,
                    color = GlagolitsaColors.TextPrimary.copy(alpha = 0.95f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Message body with timestamp pinned to the bottom-end corner of the bubble.
 *
 * Short text stays on the left; time stays in its usual corner with a reserved gap,
 * instead of packing tightly against the last glyph.
 */
@Composable
private fun MessageBodyWithCornerTime(
    body: String,
    bodyColor: Color,
    timeLabel: String,
    timeColor: Color,
    message: Message,
    isOwn: Boolean,
    isEdited: Boolean = false,
    onRetryFailed: ((Message) -> Unit)?,
    sendError: String?,
    onReplyToTextSelection: ((selectedText: String) -> Unit)? = null,
    onInviteLinkClick: ((raw: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Reserve enough trailing space so the corner time never sits on top of the body
    // and keeps a comfortable gap from short replies like "ghh".
    val timeReserve = when {
        isOwn && message.isFailed() -> 104.dp
        isOwn && message.isSending() && !sendError.isNullOrBlank() -> 148.dp
        isOwn && isEdited -> 78.dp
        isOwn -> 56.dp
        isEdited -> 64.dp
        else -> 44.dp
    }
    val collapsible = MessageBodyCollapsePolicy.isCollapsible(body)
    var expanded by remember(message.id, body) { mutableStateOf(false) }
    val maxLines = if (collapsible && !expanded) {
        MessageBodyCollapsePolicy.COLLAPSED_MAX_LINES
    } else {
        Int.MAX_VALUE
    }

    Column(modifier = modifier) {
        Box {
            SelectableAutoCopyMessageText(
                text = body,
                color = bodyColor,
                style = MaterialTheme.typography.bodyLarge,
                onReplyToSelection = onReplyToTextSelection,
                onInviteLinkClick = onInviteLinkClick,
                longPressToSelect = true,
                maxLines = maxLines,
                modifier = Modifier
                    .padding(end = timeReserve)
                    .testTag("chat-message-body"),
            )
            MessageBubbleTimeMeta(
                timeLabel = timeLabel,
                timeColor = timeColor,
                message = message,
                isOwn = isOwn,
                isEdited = isEdited,
                onRetryFailed = onRetryFailed,
                sendError = sendError,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 1.dp),
            )
        }
        if (collapsible) {
            Text(
                text = if (expanded) "Свернуть" else "Ещё",
                style = MaterialTheme.typography.labelMedium,
                color = if (isOwn) {
                    GlagolitsaColors.OrnamentGold.copy(alpha = 0.9f)
                } else {
                    OrnamentGold.copy(alpha = 0.9f)
                },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { expanded = !expanded }
                    .testTag("chat-message-body-expand"),
            )
        }
    }
}

@Composable
private fun MessageBubbleTimeMeta(
    timeLabel: String,
    timeColor: Color,
    message: Message,
    isOwn: Boolean,
    isEdited: Boolean = false,
    onRetryFailed: ((Message) -> Unit)?,
    sendError: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isEdited) {
            Text(
                text = "изм.",
                style = MaterialTheme.typography.labelSmall,
                color = timeColor.copy(alpha = 0.85f),
                maxLines = 1,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = timeColor,
            maxLines = 1,
        )
        if (isOwn) {
            Spacer(modifier = Modifier.width(4.dp))
            MessageDeliveryIndicator(
                message = message,
                tint = Color.White.copy(alpha = 0.74f),
                timeColor = timeColor,
                onRetryFailed = onRetryFailed,
                sendError = sendError,
            )
        }
    }
}

/**
 * Inline reply quote inside a bubble.
 *
 * Flow (see [ReplyQuoteInteraction]):
 * 1) Tap quote → clear message-preview card + “go to” icon
 * 2) Tap go icon → scroll to original message
 * Bubble body taps still open the message menu.
 */
@Composable
private fun ChatReplyPreview(
    preview: ChatReplyPreviewData,
    isOwn: Boolean,
    onNavigateToReply: ((targetMessageId: String) -> Unit)?,
    onReplyToQuoteSelection: ((
        targetMessageId: String,
        targetSenderId: String?,
        selectedText: String,
        previewBody: String,
    ) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val accent = if (isOwn) GlagolitsaColors.OrnamentGold.copy(alpha = 0.58f) else OrnamentRed.copy(alpha = 0.82f)
    val fill = if (isOwn) {
        GlagolitsaColors.InsetOnOwn
    } else {
        GlagolitsaColors.InsetOnOther
    }
    val border = if (isOwn) {
        Color.Black.copy(alpha = 0.04f)
    } else {
        GlagolitsaColors.OverlayHairlineSoft
    }
    val senderColor = if (isOwn) GlagolitsaColors.TextPrimary.copy(alpha = 0.86f) else OrnamentGold.copy(alpha = 0.86f)
    val textColor = if (isOwn) GlagolitsaColors.TextSecondary.copy(alpha = 0.74f) else GlagolitsaColors.TextSecondary.copy(alpha = 0.82f)
    val compactQuote = preview.body.normalizeQuotePreview()
    val fullPreviewText = preview.body.trim().ifBlank { compactQuote }
    val targetId = preview.targetMessageId
    val canOpen = ReplyQuoteInteraction.canOpenPreview(fullPreviewText, targetId)
    val canJump = ReplyQuoteInteraction.canNavigate(targetId) && onNavigateToReply != null
    var quoteUi by remember(targetId, preview.body) {
        mutableStateOf(ReplyQuoteUiState.Idle)
    }
    val expanded = ReplyQuoteInteraction.showsPreview(quoteUi)
    val showGoTo = ReplyQuoteInteraction.showsGoControl(quoteUi, targetId) && canJump
    val quoteInteraction = remember { MutableInteractionSource() }
    val goInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier.widthIn(max = if (expanded) 300.dp else 240.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Compact quote strip — tap opens the message preview below.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(ChatReplyPreviewShape)
                .background(fill)
                .border(0.6.dp, border, ChatReplyPreviewShape)
                .clickable(
                    enabled = canOpen,
                    interactionSource = quoteInteraction,
                    indication = null,
                    onClick = {
                        quoteUi = ReplyQuoteInteraction.onQuoteClick(
                            current = quoteUi,
                            body = fullPreviewText,
                            targetMessageId = targetId,
                        )
                    },
                )
                .semantics {
                    if (canOpen) {
                        role = Role.Button
                        contentDescription = "Цитата. Нажмите, чтобы открыть превью"
                    }
                }
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .testTag("chat-reply-quote"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .clip(ChatComposerShape)
                    .background(accent),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat-reply-quote-text"),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Text(
                    text = preview.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = senderColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = compactQuote,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Distinct message preview card — only after quote tap.
        if (expanded) {
            val canReplyFromSelection = ReplyQuoteSelectionAction.Reply in
                ReplyQuoteInteraction.selectionMenuActions(quoteUi, targetId) &&
                onReplyToQuoteSelection != null
            ReplyQuoteMessagePreviewCard(
                senderName = preview.senderName,
                body = fullPreviewText,
                isOwn = isOwn,
                accent = accent,
                showGoTo = showGoTo,
                onGoTo = if (showGoTo && targetId != null) {
                    {
                        onNavigateToReply?.invoke(targetId)
                        quoteUi = ReplyQuoteInteraction.onGoClick()
                    }
                } else {
                    null
                },
                goInteraction = goInteraction,
                onReplyToSelection = if (canReplyFromSelection) {
                    { selectedText ->
                        val resolved = ReplyQuoteInteraction.resolveSelectionReply(
                            state = quoteUi,
                            selectedText = selectedText,
                            targetMessageId = targetId,
                            targetSenderId = preview.targetSenderId,
                            previewBody = fullPreviewText,
                        ) ?: return@ReplyQuoteMessagePreviewCard
                        onReplyToQuoteSelection?.invoke(
                            resolved.targetMessageId,
                            resolved.targetSenderId,
                            resolved.selectedText,
                            resolved.previewBody,
                        )
                        quoteUi = ReplyQuoteInteraction.onDismiss()
                    }
                } else {
                    null
                },
            )
        }
    }
}

/** Visible quote-message preview shown after tapping the compact quote strip. */
@Composable
private fun ReplyQuoteMessagePreviewCard(
    senderName: String,
    body: String,
    isOwn: Boolean,
    accent: Color,
    showGoTo: Boolean,
    onGoTo: (() -> Unit)?,
    goInteraction: MutableInteractionSource,
    onReplyToSelection: ((selectedText: String) -> Unit)?,
) {
    val cardShape = RoundedCornerShape(12.dp)
    val bodyColor = if (isOwn) Color.White.copy(alpha = 0.9f) else GlagolitsaColors.TextPrimary.copy(alpha = 0.94f)
    val bodyScroll = rememberScrollState()
    val maxBodyHeight = ReplyQuoteInteraction.PREVIEW_BODY_MAX_HEIGHT_DP.dp
    val absorbInteraction = remember { MutableInteractionSource() }

    val bodyModifier = Modifier
        .fillMaxWidth()
        .height(maxBodyHeight)
        .verticalScroll(bodyScroll)
        .testTag("chat-reply-quote-preview-body")
    val baseColor = if (isOwn) {
        GlagolitsaColors.ChatBubbleOwn.copy(alpha = 0.96f)
    } else {
        GlagolitsaColors.ChatBubbleOther.copy(alpha = 0.96f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .concaveSurface(
                shape = cardShape,
                cornerRadius = 12.dp,
                baseColor = baseColor,
                focused = true,
            )
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.55f),
                shape = cardShape,
            )
            .clickable(
                interactionSource = absorbInteraction,
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("chat-reply-quote-preview"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent.copy(alpha = 0.9f)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Превью",
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = 0.95f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = senderName,
                style = MaterialTheme.typography.labelMedium,
                color = if (isOwn) Color.White.copy(alpha = 0.92f) else OrnamentGold.copy(alpha = 0.92f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Fixed-height scroll; long-press to select so scroll stays free.
            Box(modifier = bodyModifier) {
                SelectableAutoCopyMessageText(
                    text = body,
                    color = bodyColor,
                    style = MaterialTheme.typography.bodyMedium,
                    onReplyToSelection = onReplyToSelection,
                    longPressToSelect = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (showGoTo && onGoTo != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable(
                        interactionSource = goInteraction,
                        indication = null,
                        onClick = onGoTo,
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = "Перейти к цитате"
                    }
                    .testTag("chat-reply-quote-go"),
                contentAlignment = Alignment.Center,
            ) {
                ReplyQuoteGoIcon(
                    tint = if (isOwn) {
                        GlagolitsaColors.OrnamentGold.copy(alpha = 0.95f)
                    } else {
                        OrnamentGold.copy(alpha = 0.95f)
                    },
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/** Compact “jump to message” glyph for the reply-quote go button. */
@Composable
private fun ReplyQuoteGoIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.22f, size.height * 0.78f),
            end = Offset(size.width * 0.78f, size.height * 0.22f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.42f, size.height * 0.22f),
            end = Offset(size.width * 0.78f, size.height * 0.22f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.78f, size.height * 0.22f),
            end = Offset(size.width * 0.78f, size.height * 0.58f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ChatThreadBranchPreview(
    preview: ChatThreadBranchPreviewData,
    isOwn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(13.dp)
    val accent = if (preview.hasUnread) OrnamentRed else OrnamentGold.copy(alpha = 0.84f)
    val light = !GlagolitsaColors.IsDark
    val fill = if (light) {
        Brush.linearGradient(
            colors = listOf(
                GlagolitsaColors.Surface600.copy(alpha = 0.88f),
                GlagolitsaColors.Background850.copy(alpha = 0.96f),
            ),
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                GlagolitsaColors.Surface700.copy(alpha = if (isOwn) 0.58f else 0.54f),
                GlagolitsaColors.Surface800.copy(alpha = 0.76f),
            ),
        )
    }
    val border = if (preview.hasUnread) {
        OrnamentRed.copy(alpha = 0.26f)
    } else if (light) {
        GlagolitsaColors.TextPrimary.copy(alpha = 0.12f)
    } else {
        Color.White.copy(alpha = 0.10f)
    }
    val titleColor = if (preview.hasUnread) OrnamentRed else OrnamentGold
    val metaColor = GlagolitsaColors.TextSecondary.copy(alpha = 0.88f)
    val mutedColor = GlagolitsaColors.TextTertiary.copy(alpha = 0.84f)
    val lastText = preview.lastBody?.normalizeQuotePreview()
    val visibleParticipants = preview.participantLabels.take(4)
    val hiddenReplyCount = (preview.replyCount - visibleParticipants.size).coerceAtLeast(0)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .border(0.55.dp, border, shape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "Открыть ветку: ${threadReplyCountLabel(preview.replyCount)}"
            }
            .padding(start = 9.dp, top = 8.dp, end = 7.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThreadTopicOrnamentIcon(
                active = preview.hasUnread,
                modifier = Modifier.size(26.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Ветка",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = titleColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = threadReplyCountLabel(preview.replyCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = GlagolitsaColors.TextPrimary.copy(alpha = 0.88f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ThreadDisclosureIndicator(
                        tint = GlagolitsaColors.TextPrimary.copy(alpha = 0.72f),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = threadLastActivityText(
                            senderName = preview.lastSenderName,
                            body = lastText,
                            activityLabel = preview.lastActivityLabel,
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = metaColor,
                        fontWeight = if (preview.hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ThreadParticipantRow(
                        labels = visibleParticipants.take(3),
                        hiddenCount = hiddenReplyCount,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadBranchAttachmentRail(
    active: Boolean,
    attachFromEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val color = if (active) OrnamentRed else OrnamentGold.copy(alpha = 0.72f)
        val strokeWidth = 1.6.dp.toPx()
        val x = if (attachFromEnd) size.width - 16.dp.toPx() else 16.dp.toPx()
        val turnY = 40.dp.toPx().coerceAtMost(size.height - 8.dp.toPx())
        val dotRadius = 3.8.dp.toPx()
        val horizontalEndX = if (attachFromEnd) dotRadius else size.width - dotRadius
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, turnY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(x, turnY),
            end = Offset(horizontalEndX, turnY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = color,
            radius = dotRadius,
            center = Offset(horizontalEndX, turnY),
        )
    }
}

@Composable
private fun ThreadDisclosureIndicator(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = tint,
        )
    }
}

@Composable
private fun ThreadTopicOrnamentIcon(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val color = if (active) OrnamentRed.copy(alpha = 0.96f) else OrnamentGold.copy(alpha = 0.84f)
        val stroke = Stroke(width = 2.1.dp.toPx(), cap = StrokeCap.Round)
        val center = Offset(size.width * 0.48f, size.height * 0.50f)
        drawLine(
            color = color,
            start = Offset(size.width * 0.18f, size.height * 0.84f),
            end = Offset(size.width * 0.82f, size.height * 0.16f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        listOf(
            Offset(size.width * 0.28f, size.height * 0.35f),
            Offset(size.width * 0.42f, size.height * 0.24f),
            Offset(size.width * 0.62f, size.height * 0.70f),
            Offset(size.width * 0.74f, size.height * 0.58f),
        ).forEachIndexed { index, leaf ->
            val direction = if (index < 2) -1f else 1f
            drawLine(
                color = color.copy(alpha = 0.92f),
                start = center,
                end = leaf,
                strokeWidth = 1.45.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = color.copy(alpha = 0.95f),
                radius = 2.15.dp.toPx(),
                center = Offset(leaf.x + direction * 1.5.dp.toPx(), leaf.y),
            )
        }
    }
}

@Composable
private fun ThreadParticipantRow(
    labels: List<String>,
    hiddenCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEach { label ->
            ThreadParticipantAvatar(label = label)
        }
        if (hiddenCount > 0) {
            Text(
                text = "+$hiddenCount",
                style = MaterialTheme.typography.labelMedium,
                color = GlagolitsaColors.TextPrimary.copy(alpha = 0.88f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(GlagolitsaColors.Surface600.copy(alpha = 0.72f))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ThreadParticipantAvatar(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        GlagolitsaColors.Surface600.copy(alpha = 0.96f),
                        GlagolitsaColors.Surface800.copy(alpha = 0.96f),
                    ),
                ),
            )
            .border(0.8.dp, OrnamentGold.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = threadParticipantInitial(label),
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.TextPrimary.copy(alpha = 0.96f),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun threadLastActivityText(
    senderName: String,
    body: String?,
    activityLabel: String?,
): String {
    val text = if (body.isNullOrBlank()) senderName else "$senderName: $body"
    return if (activityLabel.isNullOrBlank()) text else "$text · $activityLabel"
}

private fun threadParticipantInitial(label: String): String =
    label.trim().firstOrNull()?.uppercase() ?: "?"

private fun ChatBubbleGroupPosition.showsIncomingAvatar(): Boolean =
    this == ChatBubbleGroupPosition.Single || this == ChatBubbleGroupPosition.First

private fun swipeVisualOffset(offsetPx: Float, maxSwipePx: Float): Float {
    if (offsetPx == 0f || maxSwipePx <= 0f) return 0f
    val progress = (offsetPx.absoluteValue / maxSwipePx).coerceIn(0f, 1f)
    val resistance = 1f - (0.18f * progress)
    return offsetPx.sign * offsetPx.absoluteValue * resistance
}

@Composable
private fun ChatMessageSwipeCue(
    swipeOffsetPx: Float,
    triggerPx: Float,
    allowThreadReply: Boolean,
    chatReplySwipeLabel: String,
    threadReplySwipeLabel: String,
    modifier: Modifier = Modifier,
) {
    val isThreadSwipe = allowThreadReply && swipeOffsetPx < 0f
    val progress = (swipeOffsetPx.absoluteValue / triggerPx).coerceIn(0f, 1f)
    val cueColor = if (isThreadSwipe) OrnamentRed else OrnamentGold
    val label = if (isThreadSwipe) threadReplySwipeLabel else chatReplySwipeLabel
    val actionLabel = if (isThreadSwipe) "В тред" else "Ответить"

    Box(
        modifier = modifier
            .padding(horizontal = 8.dp)
            .graphicsLayer {
                alpha = progress
                val scale = 0.84f + (0.16f * progress)
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (progress > 0.05f) {
                    Modifier.semantics {
                        contentDescription = label
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = if (isThreadSwipe) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(cueColor.copy(alpha = 0.12f + 0.14f * progress))
                    .border(0.8.dp, cueColor.copy(alpha = 0.30f + 0.28f * progress), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(17.dp)) {
                    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    val y = size.height * 0.52f
                    val arrowStartX = if (isThreadSwipe) size.width * 0.28f else size.width * 0.72f
                    val arrowEndX = if (isThreadSwipe) size.width * 0.72f else size.width * 0.28f
                    val headDirection = if (isThreadSwipe) -1f else 1f
                    val headX = arrowEndX

                    drawLine(
                        color = cueColor.copy(alpha = 0.82f + 0.18f * progress),
                        start = Offset(arrowStartX, y),
                        end = Offset(arrowEndX, y),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = cueColor.copy(alpha = 0.82f + 0.18f * progress),
                        start = Offset(headX, y),
                        end = Offset(headX + headDirection * size.width * 0.24f, size.height * 0.30f),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = cueColor.copy(alpha = 0.82f + 0.18f * progress),
                        start = Offset(headX, y),
                        end = Offset(headX + headDirection * size.width * 0.24f, size.height * 0.74f),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                }
            }
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = cueColor.copy(alpha = 0.72f + 0.20f * progress),
                maxLines = 1,
            )
        }
    }
}

private fun chatBubbleShape(
    isOwn: Boolean,
    position: ChatBubbleGroupPosition,
): RoundedCornerShape {
    val round = 18.dp
    val joined = 5.dp
    return when {
        isOwn -> when (position) {
            ChatBubbleGroupPosition.Single -> RoundedCornerShape(round)
            ChatBubbleGroupPosition.First -> RoundedCornerShape(
                topStart = round,
                topEnd = round,
                bottomEnd = joined,
                bottomStart = round,
            )
            ChatBubbleGroupPosition.Middle -> RoundedCornerShape(
                topStart = round,
                topEnd = joined,
                bottomEnd = joined,
                bottomStart = round,
            )
            ChatBubbleGroupPosition.Last -> RoundedCornerShape(
                topStart = round,
                topEnd = joined,
                bottomEnd = round,
                bottomStart = round,
            )
        }
        else -> when (position) {
            ChatBubbleGroupPosition.Single -> RoundedCornerShape(round)
            ChatBubbleGroupPosition.First -> RoundedCornerShape(
                topStart = round,
                topEnd = round,
                bottomEnd = round,
                bottomStart = joined,
            )
            ChatBubbleGroupPosition.Middle -> RoundedCornerShape(
                topStart = joined,
                topEnd = round,
                bottomEnd = round,
                bottomStart = joined,
            )
            ChatBubbleGroupPosition.Last -> RoundedCornerShape(
                topStart = joined,
                topEnd = round,
                bottomEnd = round,
                bottomStart = round,
            )
        }
    }
}

/** Optional reply context rendered inside the composer text field. */
data class ChatComposerReplyQuote(
    val title: String,
    val body: String,
    val actionLabel: String = "Ответить",
    val accentColor: Color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.78f),
    val titleColor: Color = GlagolitsaColors.TextPrimary,
    val attachmentPreview: AttachmentPreview? = null,
    val onClear: () -> Unit,
)

/** Optional edit context — same banner slot as reply (edit takes priority). */
data class ChatComposerEditQuote(
    val body: String,
    val onClear: () -> Unit,
    val actionLabel: String = "Редактирование",
    val accentColor: Color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.88f),
    val titleColor: Color = GlagolitsaColors.TextPrimary,
)

/** Поле ввода сообщения — CHAT INPUT из Design System 1.0. */
@Composable
fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "Сообщение...",
    onAttach: () -> Unit = {},
    onAttachPhotoVideo: (() -> Unit)? = null,
    onAttachFile: (() -> Unit)? = null,
    onAttachCamera: (() -> Unit)? = null,
    onEmoji: () -> Unit = {},
    onVoice: () -> Unit = {},
    onVoiceLongPress: (() -> Unit)? = null,
    voiceDictationState: VoiceDictationState = VoiceDictationState(),
    voiceIdleContentDescription: String = "Диктовка",
    voiceActiveContentDescription: String = "Остановить диктовку",
    replyQuote: ChatComposerReplyQuote? = null,
    editQuote: ChatComposerEditQuote? = null,
) {
    // Edit mode replaces reply banner (messenger convention).
    val headerQuote = when {
        editQuote != null -> ChatComposerReplyQuote(
            title = "Сообщение",
            body = editQuote.body,
            actionLabel = editQuote.actionLabel,
            accentColor = editQuote.accentColor,
            titleColor = editQuote.titleColor,
            onClear = editQuote.onClear,
        )
        else -> replyQuote
    }
    GlagolitsaChatInput(
        value = draft,
        onValueChange = onDraftChange,
        modifier = modifier,
        placeholder = if (editQuote != null) "Изменить сообщение..." else placeholder,
        enabled = enabled,
        onAttach = onAttach,
        onAttachPhotoVideo = onAttachPhotoVideo,
        onAttachFile = onAttachFile,
        onAttachCamera = onAttachCamera,
        onEmoji = onEmoji,
        onVoice = onVoice,
        onVoiceLongPress = onVoiceLongPress,
        voiceDictationState = voiceDictationState,
        voiceIdleContentDescription = voiceIdleContentDescription,
        voiceActiveContentDescription = voiceActiveContentDescription,
        onSend = if (enabled) onSend else null,
        replyHeader = if (headerQuote == null) {
            null
        } else {
            {
                ReplyQuoteBanner(
                    title = headerQuote.title,
                    body = headerQuote.body,
                    actionLabel = headerQuote.actionLabel,
                    accentColor = headerQuote.accentColor,
                    titleColor = headerQuote.titleColor,
                    attachmentPreview = headerQuote.attachmentPreview,
                    surface = ReplyQuoteSurface.Embedded,
                    onClear = headerQuote.onClear,
                )
            }
        },
    )
}

/** Кнопка подгрузки истории при прокрутке вверх. */
@Composable
fun ChatLoadOlderButton(
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SlavicPillTextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(if (loading) "Загрузка..." else "Загрузить старые сообщения")
    }
}

@Composable
internal fun OrnamentIconButton(
    size: Dp,
    onClick: () -> Unit,
    contentDescription: String,
    iconSize: Dp = ChatLayout.topBarIconSize,
    icon: @Composable (Color, Modifier) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        icon(OrnamentGold, Modifier.size(iconSize))
    }
}

private fun DrawScope.drawOrnamentLine(
    startX: Float,
    endX: Float,
    y: Float,
    color: Color,
    strokePx: Float,
    withDiamonds: Boolean,
) {
    drawLine(
        color = color,
        start = Offset(startX, y),
        end = Offset(endX, y),
        strokeWidth = strokePx,
        cap = StrokeCap.Round,
    )
    if (!withDiamonds) return
    val segment = (endX - startX) / 6f
    repeat(4) { i ->
        val cx = startX + segment * (i + 1)
        val h = 5.dp.toPx()
        drawLine(color, Offset(cx - h / 2, y), Offset(cx, y - h / 2), strokePx, StrokeCap.Round)
        drawLine(color, Offset(cx, y - h / 2), Offset(cx + h / 2, y), strokePx, StrokeCap.Round)
        drawLine(color, Offset(cx + h / 2, y), Offset(cx, y + h / 2), strokePx, StrokeCap.Round)
        drawLine(color, Offset(cx, y + h / 2), Offset(cx - h / 2, y), strokePx, StrokeCap.Round)
    }
}

private fun DrawScope.drawBubbleInnerHighlight(isOwn: Boolean) {
    val highlight = if (isOwn) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.07f)
    val shadow = Color.Black.copy(alpha = if (isOwn) 0.16f else 0.22f)
    drawLine(
        color = highlight,
        start = Offset(14.dp.toPx(), 1.dp.toPx()),
        end = Offset(size.width - 14.dp.toPx(), 1.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawLine(
        color = shadow,
        start = Offset(16.dp.toPx(), size.height - 1.dp.toPx()),
        end = Offset(size.width - 16.dp.toPx(), size.height - 1.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

@Composable
internal fun BackOrnamentIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        val midY = size.height / 2f
        drawLine(
            color = tint,
            start = Offset(size.width * 0.84f, midY),
            end = Offset(size.width * 0.24f, midY),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.24f, midY),
            end = Offset(size.width * 0.52f, size.height * 0.22f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.24f, midY),
            end = Offset(size.width * 0.52f, size.height * 0.78f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = OrnamentRed.copy(alpha = 0.75f),
            start = Offset(size.width * 0.52f, size.height * 0.22f),
            end = Offset(size.width * 0.56f, size.height * 0.17f),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun SearchOrnamentIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        val radius = size.minDimension * 0.28f
        val cx = size.width * 0.44f
        val cy = size.height * 0.44f
        drawCircle(color = tint, radius = radius, center = Offset(cx, cy), style = stroke)
        drawLine(
            color = tint,
            start = Offset(cx + radius * 0.64f, cy + radius * 0.64f),
            end = Offset(size.width * 0.84f, size.height * 0.84f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        DiamondMarkCanvas(
            center = Offset(cx, cy - radius * 0.9f),
            side = 4.dp.toPx(),
            color = OrnamentRed,
            strokeWidth = 1.dp.toPx(),
        )
    }
}

@Composable
private fun PhoneOrnamentIcon(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.chat_call_rotary_phone),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

@Composable
private fun DotsOrnamentIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
        val x = size.width * 0.5f
        val ys = listOf(size.height * 0.2f, size.height * 0.5f, size.height * 0.8f)
        ys.forEachIndexed { index, y ->
            val side = if (index == 1) 4.4.dp.toPx() else 4.dp.toPx()
            DiamondMarkCanvas(
                center = Offset(x, y),
                side = side,
                color = if (index == 1) OrnamentRed else tint,
                strokeWidth = stroke.width,
            )
        }
    }
}

@Composable
private fun AttachOrnamentIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = tint,
            startAngle = 155f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(size.width * 0.18f, size.height * 0.12f),
            size = Size(size.width * 0.58f, size.height * 0.76f),
            style = stroke,
        )
        drawArc(
            color = tint,
            startAngle = 165f,
            sweepAngle = 220f,
            useCenter = false,
            topLeft = Offset(size.width * 0.3f, size.height * 0.22f),
            size = Size(size.width * 0.4f, size.height * 0.56f),
            style = stroke,
        )
        drawLine(
            color = OrnamentRed.copy(alpha = 0.78f),
            start = Offset(size.width * 0.74f, size.height * 0.78f),
            end = Offset(size.width * 0.86f, size.height * 0.82f),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun SmileOrnamentIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) * 0.42f
        drawCircle(color = tint, radius = radius, center = center, style = stroke)
        drawCircle(color = tint, radius = 1.2.dp.toPx(), center = Offset(size.width * 0.4f, size.height * 0.44f))
        drawCircle(color = tint, radius = 1.2.dp.toPx(), center = Offset(size.width * 0.6f, size.height * 0.44f))
        drawArc(
            color = tint,
            startAngle = 22f,
            sweepAngle = 136f,
            useCenter = false,
            topLeft = Offset(size.width * 0.32f, size.height * 0.42f),
            size = Size(size.width * 0.36f, size.height * 0.3f),
            style = stroke,
        )
    }
}

@Composable
private fun BirdOrnamentIcon(
    tint: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.16f, size.height * 0.64f),
            end = Offset(size.width * 0.44f, size.height * 0.5f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.44f, size.height * 0.5f),
            end = Offset(size.width * 0.84f, size.height * 0.44f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.44f, size.height * 0.5f),
            end = Offset(size.width * 0.66f, size.height * 0.2f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.44f, size.height * 0.5f),
            end = Offset(size.width * 0.62f, size.height * 0.8f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        DiamondMarkCanvas(
            center = Offset(size.width * 0.88f, size.height * 0.52f),
            side = 4.2.dp.toPx(),
            color = accent,
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun DrawScope.DiamondMarkCanvas(
    center: Offset,
    side: Float,
    color: Color,
    strokeWidth: Float,
) {
    drawLine(color, Offset(center.x, center.y - side), Offset(center.x + side, center.y), strokeWidth, StrokeCap.Round)
    drawLine(color, Offset(center.x + side, center.y), Offset(center.x, center.y + side), strokeWidth, StrokeCap.Round)
    drawLine(color, Offset(center.x, center.y + side), Offset(center.x - side, center.y), strokeWidth, StrokeCap.Round)
    drawLine(color, Offset(center.x - side, center.y), Offset(center.x, center.y - side), strokeWidth, StrokeCap.Round)
}
