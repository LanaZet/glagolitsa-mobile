// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.currentTimeMillis
import com.glagolitsa.model.Message
import com.glagolitsa.model.formatChatDayLabel
import com.glagolitsa.model.messageLocalDayKey

/**
 * Flattened chat list rows: day chips between messages when the local calendar day changes.
 */
sealed class ChatTimelineItem {
    abstract val listKey: String

    data class DayHeader(
        val dayKey: String,
        val label: String,
    ) : ChatTimelineItem() {
        override val listKey: String get() = "day-$dayKey"
    }

    data class Bubble(
        val message: Message,
        /** Index in the original chronological [Message] list (for grouping). */
        val messageIndex: Int,
    ) : ChatTimelineItem() {
        override val listKey: String get() = message.id
    }
}

/**
 * Build timeline with a day pill before the first message of each local day.
 * [messages] must already be chronological (oldest → newest).
 */
fun buildChatTimeline(
    messages: List<Message>,
    nowMillis: Long = currentTimeMillis(),
): List<ChatTimelineItem> {
    if (messages.isEmpty()) return emptyList()
    val out = ArrayList<ChatTimelineItem>(messages.size + 4)
    var lastDayKey: String? = null
    messages.forEachIndexed { index, message ->
        val dayKey = messageLocalDayKey(message.created_at) ?: "unknown-$index"
        if (dayKey != lastDayKey) {
            val label = formatChatDayLabel(message.created_at, nowMillis)
                .ifBlank { "—" }
            out += ChatTimelineItem.DayHeader(dayKey = dayKey, label = label)
            lastDayKey = dayKey
        }
        out += ChatTimelineItem.Bubble(message = message, messageIndex = index)
    }
    return out
}
