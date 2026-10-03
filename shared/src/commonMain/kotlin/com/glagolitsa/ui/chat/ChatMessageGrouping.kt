// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Message

enum class ChatBubbleGroupPosition {
    Single,
    First,
    Middle,
    Last,
}

internal fun chatBubbleGroupPosition(
    messages: List<Message>,
    index: Int,
): ChatBubbleGroupPosition {
    val message = messages.getOrNull(index) ?: return ChatBubbleGroupPosition.Single
    if (message.isChatSystemMessage()) return ChatBubbleGroupPosition.Single

    val joinsPrevious = messages.getOrNull(index - 1).canJoinBubbleGroupWith(message)
    val joinsNext = message.canJoinBubbleGroupWith(messages.getOrNull(index + 1))

    return when {
        joinsPrevious && joinsNext -> ChatBubbleGroupPosition.Middle
        joinsPrevious -> ChatBubbleGroupPosition.Last
        joinsNext -> ChatBubbleGroupPosition.First
        else -> ChatBubbleGroupPosition.Single
    }
}

internal fun Message.isChatSystemMessage(): Boolean {
    val marker = sender_id.trim().lowercase()
    return marker == "system" || marker == "server" || marker == "service"
}

private fun Message?.canJoinBubbleGroupWith(next: Message?): Boolean {
    if (this == null || next == null) return false
    if (isChatSystemMessage() || next.isChatSystemMessage()) return false
    return chat_id == next.chat_id && sender_id == next.sender_id
}
