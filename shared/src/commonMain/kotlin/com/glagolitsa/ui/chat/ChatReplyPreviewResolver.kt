// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Message

internal fun resolveInlineReplyPreview(
    message: Message,
    messagesById: Map<String, Message>,
    currentUserId: String?,
    senderNameFor: (String) -> String?,
): ChatReplyPreviewData? {
    val replyToMessageId = message.reply_to_message_id ?: return null
    val parent = messagesById[replyToMessageId]
        ?: messagesById.values.firstOrNull { it.pending_id == replyToMessageId }
    if (parent == null) {
        val previewSenderId = message.reply_preview_sender_id ?: return null
        val previewBody = message.reply_preview_body ?: return null
        return ChatReplyPreviewData(
            senderName = replySenderLabel(
                previewSenderId,
                currentUserId,
                senderNameFor(previewSenderId),
            ),
            body = previewBody,
            // Parent not loaded in this window — still try jump / reply by stored ids.
            targetMessageId = replyToMessageId,
            targetSenderId = previewSenderId,
        )
    }
    if (parent.id == message.id) return null
    return ChatReplyPreviewData(
        senderName = replySenderLabel(
            parent.sender_id,
            currentUserId,
            senderNameFor(parent.sender_id),
        ),
        body = parent.body,
        targetMessageId = parent.id,
        targetSenderId = parent.sender_id,
    )
}
