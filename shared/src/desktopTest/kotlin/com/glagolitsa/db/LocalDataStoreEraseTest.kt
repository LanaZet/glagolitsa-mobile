// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.db

import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.Message
import com.glagolitsa.model.User
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Local history erase: purge TTL, clear store, multi-account isolation (file DBs).
 */
class LocalDataStoreEraseTest {
    private var store: LocalDataStore? = null
    private var previousHome: String? = null

    @AfterTest
    fun tearDown() {
        store?.close()
        store = null
        previousHome?.let { System.setProperty("user.home", it) }
        previousHome = null
    }

    private fun newInMemory(): LocalDataStore {
        val created = LocalDataStore(DatabaseDriverFactory().companionInMemory())
        store = created
        return created
    }

    private fun newFileBacked(): LocalDataStore {
        previousHome = System.getProperty("user.home")
        val tmp = kotlin.io.path.createTempDirectory("glag-db-erase").toFile()
        System.setProperty("user.home", tmp.absolutePath)
        val created = LocalDataStore(DatabaseDriverFactory())
        store = created
        return created
    }

    private fun chat(id: String = "chat-1") = Chat(
        id = id,
        title = "title",
        type = ChatType.DIRECT,
        member_ids = listOf("a", "b"),
    )

    private fun msg(
        id: String,
        chatId: String = "chat-1",
        body: String = "hello",
        expiresAt: String? = null,
    ) = Message(
        id = id,
        chat_id = chatId,
        sender_id = "a",
        body = body,
        created_at = "2026-07-01T00:00:00Z",
        expires_at = expiresAt,
    )

    @Test
    fun purgeExpiredMessages_removesOnlyExpired() = runBlocking {
        val store = newInMemory()
        store.saveChat(chat())
        store.saveMessage(msg("keep", expiresAt = "2099-01-01T00:00:00Z"))
        store.saveMessage(msg("gone", expiresAt = "2020-01-01T00:00:00Z"))
        store.saveMessage(msg("no-ttl", expiresAt = null))

        store.purgeExpiredMessages(nowIso = "2026-07-18T00:00:00Z")

        val left = store.observeMainMessages("chat-1").first()
        val ids = left.map { it.id }.toSet()
        assertTrue("keep" in ids)
        assertTrue("no-ttl" in ids)
        assertTrue("gone" !in ids)
    }

    @Test
    fun clear_wipesMessagesChatsAndSettings() = runBlocking {
        val store = newInMemory()
        store.saveChat(chat())
        store.saveMessage(msg("m1"))
        store.saveSetting("k", "v")

        store.clear(userConfirmedErase = true)

        assertTrue(store.observeChats().first().isEmpty())
        assertTrue(store.observeMainMessages("chat-1").first().isEmpty())
        assertEquals("", store.loadSetting("k"))
    }

    @Test
    fun clear_withoutUserConfirmation_throwsAndKeepsHistory() = runBlocking {
        val store = newInMemory()
        store.saveChat(chat())
        store.saveMessage(msg("m1"))

        val failed = runCatching { store.clear(userConfirmedErase = false) }
        assertTrue(failed.isFailure)
        assertEquals(listOf("m1"), store.observeMainMessages("chat-1").first().map { it.id })
    }

    @Test
    fun deleteMessageById_removesSingleRow() = runBlocking {
        val store = newInMemory()
        store.saveChat(chat())
        store.saveMessage(msg("m1"))
        store.saveMessage(msg("m2"))

        store.deleteMessageById("m1")

        val left = store.observeMainMessages("chat-1").first()
        assertEquals(listOf("m2"), left.map { it.id })
    }

    @Test
    fun deleteChatFromList_hidesChatAcrossSyncUntilExplicitlyRevealed() = runBlocking {
        val store = newInMemory()
        val chat = chat()
        store.saveChat(chat)
        store.saveMessage(msg("m1"))

        store.deleteChatFromList(chat.id)
        store.replaceChats(listOf(chat.copy(title = "synced again")))

        assertTrue(store.observeChats().first().isEmpty())
        assertTrue(store.observeMainMessages(chat.id).first().isEmpty())

        store.saveChat(chat.copy(title = "opened again"), revealHidden = true)

        assertEquals(listOf(chat.id), store.observeChats().first().map { it.id })
    }

    @Test
    fun replaceChats_dropsGroupMissingOnServer() = runBlocking {
        val store = newInMemory()
        val group = Chat(id = "g1", title = "Семья", type = ChatType.GROUP)
        store.saveChat(group)
        store.saveMessage(msg("m1", chatId = "g1"))

        store.replaceChats(emptyList())

        assertTrue(store.observeChats().first().isEmpty())
        assertTrue(store.observeMainMessages("g1").first().isEmpty())
    }

    @Test
    fun switchAccount_isolatesMessageHistoryBetweenUsers() = runBlocking {
        val store = newFileBacked()
        store.switchAccount("user-a")
        store.saveChat(chat("chat-a"))
        store.saveMessage(msg("only-a", chatId = "chat-a", body = "secret-a"))

        store.switchAccount("user-b")
        store.saveChat(chat("chat-b"))
        store.saveMessage(msg("only-b", chatId = "chat-b", body = "secret-b"))

        // B must not see A's history
        assertTrue(store.observeMainMessages("chat-a").first().isEmpty())
        assertEquals(listOf("only-b"), store.observeMainMessages("chat-b").first().map { it.id })

        store.switchAccount("user-a")
        assertEquals(listOf("only-a"), store.observeMainMessages("chat-a").first().map { it.id })
        assertTrue(store.observeMainMessages("chat-b").first().isEmpty())
    }

    @Test
    fun switchAccount_nullClosesActiveWithoutThrowing() = runBlocking {
        val store = newFileBacked()
        store.switchAccount("user-a")
        store.saveChat(chat())
        store.switchAccount(null)
        // Opening default namespace is empty relative to user-a file DB.
        assertNull(store.loadCachedUser())
    }

    @Test
    fun switchAccount_migratesLegacyDefaultHistoryIntoMatchingAccountNamespace() = runBlocking {
        val store = newFileBacked()
        store.saveCachedUser(User(id = "user-a", username = "alice"))
        store.saveChat(chat("chat-a"))
        store.saveMessage(msg("legacy-a", chatId = "chat-a", body = "from-default-db"))

        store.switchAccount("user-a")

        assertEquals("user-a", store.loadCachedUser()?.id)
        assertEquals(listOf("chat-a"), store.observeChats().first().map { it.id })
        assertEquals(listOf("legacy-a"), store.observeMainMessages("chat-a").first().map { it.id })
    }
}
