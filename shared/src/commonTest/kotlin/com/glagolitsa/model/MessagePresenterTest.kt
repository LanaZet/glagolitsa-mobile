// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MessagePresenterTest {
    private val message = Message(
        id = "m1",
        chat_id = "c1",
        sender_id = "user-bob",
        body = "hi",
    )

    @Test
    fun senderLabel_showsYouForCurrentUser() {
        assertEquals("Вы", message.senderLabel("user-bob"))
    }

    @Test
    fun senderLabel_usesDmPartnerName() {
        assertEquals("bob", message.senderLabel("other", dmPartnerName = "bob"))
    }

    @Test
    fun senderLabel_usesSenderNameForGroupChat() {
        assertEquals("Bob", message.senderLabel(currentUserId = "other", senderName = "Bob"))
    }

    @Test
    fun senderLabel_fallsBackToParticipant() {
        assertEquals("Участник", message.senderLabel("other"))
    }
}