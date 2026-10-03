// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.db

import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.Message
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalDataStoreHistoryMergeTest {
    @Test
    fun mergeAccountHistory_fillsGapsWithoutDeletingLocal() = runBlocking {
        val store = LocalDataStore(DatabaseDriverFactory().companionInMemory())
        store.saveChat(Chat(id = "c1", title = "local", type = ChatType.DIRECT))
        store.saveMessage(Message(id = "local-1", chat_id = "c1", sender_id = "user-a", body = "keep"))

        store.mergeAccountHistory(
            chats = listOf(Chat(id = "c1", title = "cloud-title", type = ChatType.DIRECT)),
            messages = listOf(
                Message(id = "cloud-1", chat_id = "c1", sender_id = "other", body = "from-cloud"),
                Message(id = "local-1", chat_id = "c1", sender_id = "user-a", body = "keep"),
            ),
        )

        val messages = store.observeMainMessages("c1").first()
        assertEquals(setOf("local-1", "cloud-1"), messages.map { it.id }.toSet())
        assertTrue(messages.any { it.body == "keep" })
        assertTrue(messages.any { it.body == "from-cloud" })
        store.close()
    }
}
