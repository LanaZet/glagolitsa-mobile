// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Guards against multi-fire send (triple-tap / double clickable / race before draft clears).
 * Same chat + same body within [windowMs] is treated as a duplicate.
 */
object SendDedupPolicy {
    const val DEFAULT_WINDOW_MS: Long = 1_200L

    fun isDuplicate(
        previousKey: String?,
        previousAtMs: Long,
        chatId: String,
        body: String,
        nowMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): Boolean {
        val key = key(chatId, body)
        if (previousKey != key) return false
        if (previousAtMs <= 0L) return false
        return nowMs - previousAtMs < windowMs
    }

    fun key(chatId: String, body: String): String =
        chatId + "\u0000" + body.trim()
}

/**
 * Read receipts must only fire when the peer is **actively looking** at the chat.
 * Background queue drain / left-open chat screen must not flip ✓✓ on the sender.
 */
object ReadReceiptPolicy {
    fun shouldSendReadReceipts(
        isForeground: Boolean,
        activeChatId: String?,
        chatId: String,
        incomingIds: List<String>,
    ): Boolean {
        if (!isForeground) return false
        if (activeChatId != chatId) return false
        return incomingIds.isNotEmpty()
    }
}
