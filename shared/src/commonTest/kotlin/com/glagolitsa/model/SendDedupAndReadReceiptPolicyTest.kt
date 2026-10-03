// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendDedupAndReadReceiptPolicyTest {

    @Test
    fun sendDedup_collapsesSameChatAndBodyWithinWindow() {
        val key = SendDedupPolicy.key("dm-1", "привет")
        assertTrue(
            SendDedupPolicy.isDuplicate(
                previousKey = key,
                previousAtMs = 1_000L,
                chatId = "dm-1",
                body = "  привет  ",
                nowMs = 1_000L + 300L,
            ),
        )
        assertFalse(
            SendDedupPolicy.isDuplicate(
                previousKey = key,
                previousAtMs = 1_000L,
                chatId = "dm-1",
                body = "привет",
                nowMs = 1_000L + SendDedupPolicy.DEFAULT_WINDOW_MS + 1L,
            ),
        )
    }

    @Test
    fun sendDedup_allowsDifferentBodyOrChat() {
        val key = SendDedupPolicy.key("dm-1", "a")
        assertFalse(
            SendDedupPolicy.isDuplicate(
                previousKey = key,
                previousAtMs = 1_000L,
                chatId = "dm-1",
                body = "b",
                nowMs = 1_100L,
            ),
        )
        assertFalse(
            SendDedupPolicy.isDuplicate(
                previousKey = key,
                previousAtMs = 1_000L,
                chatId = "dm-2",
                body = "a",
                nowMs = 1_100L,
            ),
        )
    }

    @Test
    fun readReceipt_requiresForegroundActiveChatAndIds() {
        assertTrue(
            ReadReceiptPolicy.shouldSendReadReceipts(
                isForeground = true,
                activeChatId = "dm-1",
                chatId = "dm-1",
                incomingIds = listOf("m1"),
            ),
        )
        assertFalse(
            ReadReceiptPolicy.shouldSendReadReceipts(
                isForeground = false,
                activeChatId = "dm-1",
                chatId = "dm-1",
                incomingIds = listOf("m1"),
            ),
            "background must not emit read receipts",
        )
        assertFalse(
            ReadReceiptPolicy.shouldSendReadReceipts(
                isForeground = true,
                activeChatId = null,
                chatId = "dm-1",
                incomingIds = listOf("m1"),
            ),
        )
        assertFalse(
            ReadReceiptPolicy.shouldSendReadReceipts(
                isForeground = true,
                activeChatId = "other",
                chatId = "dm-1",
                incomingIds = listOf("m1"),
            ),
        )
        assertFalse(
            ReadReceiptPolicy.shouldSendReadReceipts(
                isForeground = true,
                activeChatId = "dm-1",
                chatId = "dm-1",
                incomingIds = emptyList(),
            ),
        )
    }

}
