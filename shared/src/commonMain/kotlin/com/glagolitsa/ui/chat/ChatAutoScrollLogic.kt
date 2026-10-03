// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.media.AttachmentKind
import com.glagolitsa.model.Message
import kotlin.math.abs

internal const val CHAT_PIN_HYSTERESIS_PX = 48
internal const val CHAT_OLDER_PREFETCH_FROM_END = 2
internal const val CHAT_INITIAL_MESSAGE_WINDOW = 80
internal const val CHAT_TIMELINE_PREFETCH = 3
internal const val CHAT_IMAGE_CACHE_SIZE = 24

/**
 * Scroll to latest only when messages were **appended** at the end.
 * Growing the list by prepending older history must not jump the viewport.
 */
fun shouldAutoScrollToLatest(
    previousCount: Int,
    previousLastId: String?,
    messages: List<Message>,
    pinnedToBottom: Boolean = true,
    lastMessageIsOwn: Boolean = false,
): Boolean {
    if (messages.isEmpty()) return false
    if (messages.size <= previousCount) return false
    val newLastId = messages.last().id
    val appended = previousLastId == null || newLastId != previousLastId
    if (!appended) return false
    if (lastMessageIsOwn) return true
    return pinnedToBottom
}

/** Backward-compatible overload used by older call sites/tests. */
fun shouldAutoScrollToLatest(previousCount: Int, messages: List<Message>): Boolean =
    shouldAutoScrollToLatest(
        previousCount = previousCount,
        previousLastId = null,
        messages = messages,
    )

fun autoScrollTargetIndex(messages: List<Message>): Int? =
    messages.lastIndex.takeIf { messages.isNotEmpty() }

/** Map a message list index to a chronological timeline row index (day headers). */
fun timelineIndexForMessageIndex(
    timeline: List<ChatTimelineItem>,
    messageIndex: Int,
): Int? {
    if (messageIndex < 0) return null
    timeline.forEachIndexed { i, item ->
        if (item is ChatTimelineItem.Bubble && item.messageIndex == messageIndex) {
            return i
        }
    }
    return null
}

/** Newest-first visual rows for [androidx.compose.foundation.lazy.LazyColumn] reverseLayout. */
fun visualTimelineItems(
    timeline: List<ChatTimelineItem>,
    loadingOlder: Boolean,
): List<ChatTimelineVisualItem> {
    if (timeline.isEmpty() && !loadingOlder) return emptyList()
    val out = ArrayList<ChatTimelineVisualItem>(timeline.size + 1)
    for (i in timeline.lastIndex downTo 0) {
        out += ChatTimelineVisualItem.Row(timeline[i])
    }
    if (loadingOlder) out += ChatTimelineVisualItem.LoadingOlder
    return out
}

fun visualIndexForMessageId(
    visual: List<ChatTimelineVisualItem>,
    messageId: String,
): Int? {
    visual.forEachIndexed { index, item ->
        val row = item as? ChatTimelineVisualItem.Row ?: return@forEachIndexed
        val bubble = row.item as? ChatTimelineItem.Bubble ?: return@forEachIndexed
        if (bubble.message.id == messageId || bubble.message.pending_id == messageId) {
            return index
        }
    }
    return null
}

/**
 * Overlay day label for the top of the viewport.
 * Null when the in-list day pill is already at the top — otherwise «Вчера» draws twice.
 */
fun stickyDateLabelForVisibleIndices(
    visual: List<ChatTimelineVisualItem>,
    visibleIndices: List<Int>,
    reverseLayout: Boolean,
): String? {
    if (visual.isEmpty() || visibleIndices.isEmpty()) return null
    val fromTop = if (reverseLayout) {
        visibleIndices.sortedDescending()
    } else {
        visibleIndices.sorted()
    }
    val topIndex = fromTop.firstOrNull { index ->
        visual.getOrNull(index) is ChatTimelineVisualItem.Row
    } ?: return null
    val topRow = (visual[topIndex] as ChatTimelineVisualItem.Row).item
    if (topRow is ChatTimelineItem.DayHeader) return null
    val label = dayLabelForVisualIndex(visual, topIndex, reverseLayout) ?: return null
    val sameHeaderVisible = visibleIndices.any { index ->
        val row = (visual.getOrNull(index) as? ChatTimelineVisualItem.Row)?.item
        row is ChatTimelineItem.DayHeader && row.label == label
    }
    return label.takeUnless { sameHeaderVisible }
}

fun dayLabelForVisualIndex(
    visual: List<ChatTimelineVisualItem>,
    index: Int,
    reverseLayout: Boolean,
): String? {
    if (index !in visual.indices) return null
    val walk = if (reverseLayout) index until visual.size else index downTo 0
    for (i in walk) {
        val row = (visual.getOrNull(i) as? ChatTimelineVisualItem.Row)?.item ?: continue
        if (row is ChatTimelineItem.DayHeader) return row.label
    }
    return null
}

fun visualNewestIndex(visual: List<ChatTimelineVisualItem>): Int? {
    visual.forEachIndexed { index, item ->
        if (item is ChatTimelineVisualItem.Row && item.item is ChatTimelineItem.Bubble) {
            return index
        }
    }
    return null
}

fun isPinnedToLatest(
    reverseLayout: Boolean,
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    canScrollForward: Boolean,
    hysteresisPx: Int = CHAT_PIN_HYSTERESIS_PX,
): Boolean {
    if (reverseLayout) {
        return firstVisibleIndex == 0 && abs(firstVisibleOffset) <= hysteresisPx
    }
    return !canScrollForward
}

fun shouldAnimateScrollToItem(isScrollInProgress: Boolean): Boolean = !isScrollInProgress

fun shouldPrefetchOlder(
    reverseLayout: Boolean,
    firstVisibleIndex: Int,
    totalItems: Int,
    prefetchFromEnd: Int = CHAT_OLDER_PREFETCH_FROM_END,
): Boolean {
    if (totalItems <= 0) return false
    return if (reverseLayout) {
        firstVisibleIndex >= (totalItems - 1 - prefetchFromEnd).coerceAtLeast(0)
    } else {
        firstVisibleIndex <= prefetchFromEnd
    }
}

fun visibleMessageWindow(
    messages: List<Message>,
    oldestKeptId: String?,
    initialWindow: Int = CHAT_INITIAL_MESSAGE_WINDOW,
): List<Message> {
    if (messages.isEmpty()) return emptyList()
    if (oldestKeptId.isNullOrBlank()) {
        return if (messages.size <= initialWindow) {
            messages
        } else {
            messages.subList(messages.size - initialWindow, messages.size)
        }
    }
    val start = messages.indexOfFirst { it.id == oldestKeptId || it.pending_id == oldestKeptId }
    if (start <= 0) return messages
    return messages.subList(start, messages.size)
}

fun neighborMessageIds(
    messages: List<Message>,
    visibleIds: Set<String>,
    radius: Int = CHAT_TIMELINE_PREFETCH,
): Set<String> {
    if (visibleIds.isEmpty() || messages.isEmpty()) return visibleIds
    val out = visibleIds.toMutableSet()
    messages.forEachIndexed { index, message ->
        val hit = message.id in visibleIds || message.pending_id in visibleIds
        if (!hit) return@forEachIndexed
        val from = (index - radius).coerceAtLeast(0)
        val to = (index + radius).coerceAtMost(messages.lastIndex)
        for (i in from..to) {
            out += messages[i].id
            messages[i].pending_id?.let { out += it }
        }
    }
    return out
}

fun reservedAttachmentHeightDp(
    pixelWidth: Int?,
    pixelHeight: Int?,
    maxWidthDp: Float,
    minDp: Float = 116f,
    maxDp: Float = 320f,
    fallbackDp: Float = 188f,
): Float {
    val w = pixelWidth ?: return fallbackDp
    val h = pixelHeight ?: return fallbackDp
    if (w <= 0 || h <= 0) return fallbackDp
    val ratio = w.toFloat() / h.toFloat()
    if (ratio <= 0f) return fallbackDp
    return (maxWidthDp / ratio).coerceIn(minDp, maxDp)
}

fun timelineContentType(item: ChatTimelineItem, attachmentKind: AttachmentKind? = null): String {
    return when (item) {
        is ChatTimelineItem.DayHeader -> "day"
        is ChatTimelineItem.Bubble -> when (attachmentKind) {
            AttachmentKind.IMAGE -> "photo"
            AttachmentKind.VIDEO -> "video"
            AttachmentKind.VOICE -> "voice"
            AttachmentKind.AUDIO, AttachmentKind.DOCUMENT, AttachmentKind.UNKNOWN -> "file"
            null -> if (isAttachmentPlaceholderBody(item.message.body)) "file" else "text"
        }
    }
}

fun visualItemContentType(
    item: ChatTimelineVisualItem,
    attachmentKindFor: (Message) -> AttachmentKind?,
): String = when (item) {
    ChatTimelineVisualItem.LoadingOlder -> "loading"
    is ChatTimelineVisualItem.Row -> {
        val bubble = item.item as? ChatTimelineItem.Bubble
        timelineContentType(item.item, bubble?.let { attachmentKindFor(it.message) })
    }
}

fun isTimelineMessageKey(key: String): Boolean =
    key != ChatTimelineVisualItem.LoadingOlder.listKey && !key.startsWith("day-")

fun visibleTimelineMessageIds(visibleKeys: Iterable<String>): Set<String> =
    visibleKeys.filter(::isTimelineMessageKey).toSet()

sealed class ChatTimelineVisualItem {
    data class Row(val item: ChatTimelineItem) : ChatTimelineVisualItem()
    data object LoadingOlder : ChatTimelineVisualItem()

    val listKey: String
        get() = when (this) {
            is Row -> item.listKey
            LoadingOlder -> "loading-older"
        }
}
