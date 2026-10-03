// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.model.Message
import com.glagolitsa.model.sortedForChat
import kotlin.test.Test
import kotlin.test.assertEquals

/** Чистые unit-тесты логики чата (без Android/Robolectric). */
class SendMessageLogicTest {
    @Test
    fun afterPendingRemoved_onlyServerMessageRemains() {
        val pendingId = "pending-1"
        val all = listOf(
            Message(pendingId, "c1", "u1", "hi", pendingId, ""),
            Message("server-1", "c1", "u1", "hi", pendingId, "2026-06-28T15:00:00Z"),
        )

        val stored = all.filterNot { it.id == pendingId }.sortedForChat()

        assertEquals(1, stored.size)
        assertEquals("server-1", stored.single().id)
        assertEquals(pendingId, stored.single().pending_id)
    }

    @Test
    fun incomingRelayMessageId_prefersClientMessageIdForPreviewKey() {
        assertEquals(
            "pending-photo-1",
            incomingRelayMessageId(clientMessageId = "pending-photo-1", envelopeId = "env-42"),
        )
    }

    @Test
    fun incomingRelayMessageId_fallsBackToEnvelopeIdForLegacyPayloads() {
        assertEquals(
            "env-legacy",
            incomingRelayMessageId(clientMessageId = " ", envelopeId = "env-legacy"),
        )
    }
}
