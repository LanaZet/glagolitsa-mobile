// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageOrderingTest {
    private fun msg(id: String, createdAt: String?) = Message(
        id = id,
        chat_id = "c1",
        sender_id = "u1",
        body = id,
        created_at = createdAt,
    )

    @Test
    fun sortedForChat_ordersByTimestamp() {
        val list = listOf(
            msg("3", "2026-06-28T15:00:00Z"),
            msg("1", "2026-06-28T14:00:00Z"),
            msg("2", "2026-06-28T14:30:00Z"),
        )
        assertEquals(listOf("1", "2", "3"), list.sortedForChat().map { it.id })
    }

    @Test
    fun sortedForChat_putsUndatedMessagesLast() {
        val list = listOf(
            msg("empty", ""),
            msg("dated", "2026-06-28T12:00:00Z"),
            msg("null", null),
        )
        assertEquals(listOf("dated", "empty", "null"), list.sortedForChat().map { it.id })
    }

    @Test
    fun sortedForChat_handlesMixedIsoFormats() {
        val list = listOf(
            msg("server", "2026-06-28T15:00:00Z"),
            msg("client", "2026-06-28T14:30:00.123Z"),
            msg("nano", "2026-06-28T16:00:00.123456789Z"),
            msg("early", "2026-06-28T14:00:00Z"),
        )
        assertEquals(
            listOf("early", "client", "server", "nano"),
            list.sortedForChat().map { it.id },
        )
    }
}