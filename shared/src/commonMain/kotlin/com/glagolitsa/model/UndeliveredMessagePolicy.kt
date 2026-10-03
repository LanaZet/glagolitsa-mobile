// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Pure rules for local undelivered messages that can be re-queued (outbox recovery).
 *
 * Covers both explicit [MessageStatus.FAILED] and stuck [MessageStatus.SENDING]
 * so temporary network / multi-device dirt does not permanently lose the user's text.
 */
object UndeliveredMessagePolicy {
    fun isOwnUndelivered(message: Message, currentUserId: String?): Boolean {
        if (currentUserId.isNullOrBlank()) return false
        if (message.sender_id != currentUserId) return false
        if (message.body.isBlank() && message.ciphertext.isNullOrBlank()) return false
        return message.isFailed() || message.isSending()
    }

    fun selectForRetry(
        messages: List<Message>,
        currentUserId: String?,
        chatId: String? = null,
    ): List<Message> =
        messages.filter { message ->
            (chatId == null || message.chat_id == chatId) &&
                isOwnUndelivered(message, currentUserId)
        }

    /**
     * Kept for tests / future settings UI only — chat screen no longer shows a top banner.
     * Delivery state is bubble-only (sending / failed retry).
     */
    fun retryBannerLabel(count: Int): String =
        when {
            count <= 0 -> ""
            count == 1 -> "1 сообщение не отправлено"
            else -> "$count сообщений не отправлено"
        }
}
