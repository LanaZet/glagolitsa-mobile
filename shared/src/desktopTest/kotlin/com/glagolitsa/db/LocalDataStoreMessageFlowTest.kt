// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.glagolitsa.jobs.OutboxJobRecord
import com.glagolitsa.jobs.OutboxJobStatus
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.MESSAGE_VISIBILITY_MAIN
import com.glagolitsa.model.MESSAGE_VISIBILITY_THREAD_ONLY
import com.glagolitsa.model.Message
import com.glagolitsa.model.MessageStatus
import com.glagolitsa.media.AttachmentTransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Первая линия: [ChatScreen] читает `repository.observeMainMessages` → [LocalDataStore.observeMainMessages].
 * Убеждаемся, что Flow эмитит обновлённый список при сохранении входящего сообщения.
 */
class LocalDataStoreMessageFlowTest {
    private var store: LocalDataStore? = null

    @AfterTest
    fun tearDown() {
        store?.close()
        store = null
    }

    private fun newStore(): LocalDataStore {
        val created = LocalDataStore(DatabaseDriverFactory().companionInMemory())
        store = created
        return created
    }

    @Test
    fun observeMainMessages_emitsWhenIncomingMessageSaved() = runBlocking {
        val store = newStore()
        val chatId = "chat-dm-1"
        store.saveChat(
            Chat(
                id = chatId,
                title = "bob",
                type = ChatType.DIRECT,
                member_ids = listOf("user-alice", "user-bob"),
            ),
        )

        val emissions = mutableListOf<List<Message>>()
        val collectJob = launch(Dispatchers.Default) {
            store.observeMainMessages(chatId).collect { emissions.add(it) }
        }

        store.saveMessage(
            Message(
                id = "server-1",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "привет",
                created_at = "2026-07-13T10:00:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )

        val first = store.observeMainMessages(chatId).first { it.any { msg -> msg.body == "привет" } }
        assertEquals("привет", first.single().body)

        store.saveMessage(
            Message(
                id = "server-2",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "как дела?",
                created_at = "2026-07-13T10:01:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )

        val latest = store.observeMainMessages(chatId).first { it.size == 2 }
        assertEquals("привет", latest[0].body)
        assertEquals("как дела?", latest[1].body)
        assertTrue(emissions.any { list -> list.any { it.body == "привет" } })

        collectJob.cancel()
    }

    @Test
    fun observeMainMessages_replacesPendingWithServerMessage() = runBlocking {
        val store = newStore()
        val chatId = "chat-dm-2"
        store.saveChat(Chat(id = chatId, title = "bob", type = ChatType.DIRECT))

        store.saveMessage(
            Message(
                id = "pending-1",
                chat_id = chatId,
                sender_id = "user-alice",
                body = "отправляю…",
                pending_id = "pending-1",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
            status = "sending",
        )

        withContext(Dispatchers.Default) {
            store.deleteMessageById("pending-1")
            store.saveMessage(
                Message(
                    id = "server-1",
                    chat_id = chatId,
                    sender_id = "user-alice",
                    body = "отправлено",
                    pending_id = "pending-1",
                    created_at = "2026-07-13T10:02:00Z",
                    visibility = MESSAGE_VISIBILITY_MAIN,
                ),
            )
        }

        val messages = store.observeMainMessages(chatId).first { it.singleOrNull()?.id == "server-1" }
        assertEquals(1, messages.size)
        assertEquals("отправлено", messages.single().body)
    }

    @Test
    fun saveChat_preservesLocalMessagePreviewWhenSyncPayloadHasNoPreview() = runBlocking {
        val store = newStore()
        val chatId = "chat-preview-fallback"
        store.saveChat(Chat(id = chatId, title = "bob", type = ChatType.DIRECT))
        store.saveMessage(
            Message(
                id = "server-1",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "последнее сообщение",
                created_at = "2026-07-13T10:02:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )

        store.saveChat(
            Chat(
                id = chatId,
                title = "bob synced",
                type = ChatType.DIRECT,
                last_message = null,
                last_message_at = null,
                created_at = "2026-07-13T09:00:00Z",
            ),
        )

        val chat = store.observeChats().first().single { it.id == chatId }
        assertEquals("последнее сообщение", chat.last_message)
        assertEquals("2026-07-13T10:02:00Z", chat.last_message_at)
    }

    @Test
    fun saveChat_doesNotOverwriteLocalPreviewWithEncryptedPlaceholder() = runBlocking {
        val store = newStore()
        val chatId = "chat-preview-placeholder"
        store.saveChat(Chat(id = chatId, title = "bob", type = ChatType.DIRECT))
        store.saveMessage(
            Message(
                id = "server-1",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "реальный текст",
                created_at = "2026-07-13T10:02:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )

        store.saveChat(
            Chat(
                id = chatId,
                title = "bob synced",
                type = ChatType.DIRECT,
                last_message = "🔒 Сообщение",
                last_message_at = "2026-07-13T10:03:00Z",
                created_at = "2026-07-13T09:00:00Z",
            ),
        )

        val chat = store.observeChats().first().single { it.id == chatId }
        assertEquals("реальный текст", chat.last_message)
        assertEquals("2026-07-13T10:02:00Z", chat.last_message_at)
    }

    @Test
    fun observeChats_resolvesStaleEncryptedPreviewFromVisibleLocalMessage() = runBlocking {
        val store = newStore()
        val chatId = "chat-preview-stale-placeholder"
        store.saveChat(Chat(id = chatId, title = "bob", type = ChatType.DIRECT))
        store.saveMessage(
            Message(
                id = "local-visible",
                chat_id = chatId,
                sender_id = "user-alice",
                body = "bbbbc",
                created_at = "2026-07-13T03:51:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )
        store.updateChatPreview(
            chatId = chatId,
            lastMessage = "🔒 Сообщение",
            lastMessageAt = "2026-07-13T11:55:00Z",
        )

        val chat = store.observeChats().first().single { it.id == chatId }
        assertEquals("bbbbc", chat.last_message)
        assertEquals("2026-07-13T03:51:00Z", chat.last_message_at)
    }

    @Test
    fun saveMessage_updatesChatPreviewFromLatestMainMessage() = runBlocking {
        val store = newStore()
        val chatId = "chat-preview-save"
        store.saveChat(Chat(id = chatId, title = "bob", type = ChatType.DIRECT))

        store.saveMessage(
            Message(
                id = "older",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "старое сообщение",
                created_at = "2026-07-13T10:00:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )
        store.saveMessage(
            Message(
                id = "newer",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "новое сообщение",
                created_at = "2026-07-13T10:05:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )

        val chat = store.observeChats().first().single { it.id == chatId }
        assertEquals("новое сообщение", chat.last_message)
        assertEquals("2026-07-13T10:05:00Z", chat.last_message_at)
    }

    @Test
    fun replaceMessages_refreshesChatPreviewAfterHistorySync() = runBlocking {
        val store = newStore()
        val chatId = "chat-preview-history"
        store.saveChat(Chat(id = chatId, title = "group", type = ChatType.GROUP))

        store.replaceMessages(
            chatId,
            listOf(
                Message(
                    id = "older",
                    chat_id = chatId,
                    sender_id = "user-alice",
                    body = "раньше",
                    created_at = "2026-07-13T10:00:00Z",
                    visibility = MESSAGE_VISIBILITY_MAIN,
                ),
                Message(
                    id = "newer",
                    chat_id = chatId,
                    sender_id = "user-bob",
                    body = "последнее из истории",
                    created_at = "2026-07-13T10:10:00Z",
                    visibility = MESSAGE_VISIBILITY_MAIN,
                ),
            ),
        )

        val chat = store.observeChats().first().single { it.id == chatId }
        assertEquals("последнее из истории", chat.last_message)
        assertEquals("2026-07-13T10:10:00Z", chat.last_message_at)
    }

    @Test
    fun replaceMessages_preservesUnconfirmedOutgoingMessagesUntilServerEcho() = runBlocking {
        val store = newStore()
        val chatId = "chat-channel-pending"
        store.saveChat(Chat(id = chatId, title = "zcoi", type = ChatType.CHANNEL))

        store.saveMessage(
            Message(
                id = "pending-channel-1",
                chat_id = chatId,
                sender_id = "user-alice",
                body = "проверка канала",
                pending_id = "pending-channel-1",
                status = MessageStatus.SENDING,
                created_at = "2026-07-28T20:00:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
            status = MessageStatus.SENDING,
        )

        store.replaceMessages(chatId, emptyList())

        val preserved = store.observeMainMessages(chatId).first { it.isNotEmpty() }
        assertEquals("pending-channel-1", preserved.single().id)
        assertEquals(MessageStatus.SENDING, preserved.single().status)

        store.replaceMessages(
            chatId,
            listOf(
                Message(
                    id = "server-channel-1",
                    chat_id = chatId,
                    sender_id = "user-alice",
                    body = "проверка канала",
                    pending_id = "pending-channel-1",
                    status = MessageStatus.SENT,
                    created_at = "2026-07-28T20:00:01Z",
                    visibility = MESSAGE_VISIBILITY_MAIN,
                ),
            ),
        )

        val confirmed = store.observeMainMessages(chatId).first { it.singleOrNull()?.id == "server-channel-1" }
        assertEquals(MessageStatus.SENT, confirmed.single().status)
    }

    @Test
    fun saveMessage_deduplicatesRowsWithSamePendingId() = runBlocking {
        val store = newStore()
        val chatId = "chat-dm-dedup"
        store.saveChat(Chat(id = chatId, title = "bob", type = ChatType.DIRECT))

        store.saveMessage(
            Message(
                id = "pending-dup",
                chat_id = chatId,
                sender_id = "user-alice",
                body = "hello",
                pending_id = "pending-dup",
                status = "sending",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )
        store.saveMessage(
            Message(
                id = "relay-env-1",
                chat_id = chatId,
                sender_id = "user-alice",
                body = "hello",
                pending_id = "pending-dup",
                status = "sent",
                created_at = "2026-07-13T10:02:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )

        val messages = store.observeMainMessages(chatId).first { it.size == 1 }
        assertEquals("relay-env-1", messages.single().id)
        assertEquals("pending-dup", messages.single().pending_id)
        assertEquals("sent", messages.single().status)
    }

    @Test
    fun updateMessageStatusByPendingId_updatesStoredRow() = runBlocking {
        val store = newStore()
        val chatId = "chat-dm-read-dedup"
        store.saveChat(Chat(id = chatId, title = "bob", type = ChatType.DIRECT))

        store.saveMessage(
            Message(
                id = "relay-env-old",
                chat_id = chatId,
                sender_id = "user-alice",
                body = "hello",
                pending_id = "pending-read",
                status = "sent",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )
        store.saveMessage(
            Message(
                id = "relay-env-new",
                chat_id = chatId,
                sender_id = "user-alice",
                body = "hello",
                pending_id = "pending-read",
                status = "sent",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )
        val otherChatId = "chat-dm-read-other"
        store.saveChat(Chat(id = otherChatId, title = "carol", type = ChatType.DIRECT))
        store.saveMessage(
            Message(
                id = "relay-env-other",
                chat_id = otherChatId,
                sender_id = "user-carol",
                body = "same client id, different sender",
                pending_id = "pending-read",
                status = "sent",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )

        store.updateMessageStatusByPendingId(
            chatId = chatId,
            senderId = "user-alice",
            pendingId = "pending-read",
            status = "read",
        )

        val messages = store.observeMainMessages(chatId).first { it.isNotEmpty() }
        assertTrue(messages.all { it.status == "read" })
        val otherMessages = store.observeMainMessages(otherChatId).first { it.isNotEmpty() }
        assertEquals("sent", otherMessages.single().status)
    }

    @Test
    fun schemaRepair_removesExistingDuplicatePendingRows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GlagolitsaDatabase.Schema.create(driver)
        val queries = GlagolitsaDatabase(driver).glagolitsaQueries
        val chatId = "chat-dm-repair"
        queries.insertChat(
            id = chatId,
            title = "bob",
            type = ChatType.DIRECT,
            last_message = null,
            last_message_at = null,
            created_at = null,
            description = null,
            visibility = null,
            slug = null,
            encryption = null,
            creator_id = null,
            avatar_url = null,
        )
        queries.insertMessage(
            id = "old-row",
            chat_id = chatId,
            sender_id = "user-alice",
            body = "hello",
            pending_id = "pending-repair",
            status = "sent",
            created_at = "2026-07-13T10:00:00Z",
            reply_to_message_id = null,
            reply_preview_sender_id = null,
            reply_preview_body = null,
            thread_root_id = null,
            thread_parent_id = null,
            visibility = MESSAGE_VISIBILITY_MAIN,
            thread_reply_count = 0,
            last_thread_reply_at = null,
            last_thread_reply_sender_id = null,
            expires_at = null,
            envelope_type = null,
            ciphertext = null,
            sender_device_id = null,
        )
        queries.insertMessage(
            id = "new-row",
            chat_id = chatId,
            sender_id = "user-alice",
            body = "hello",
            pending_id = "pending-repair",
            status = "read",
            created_at = "2026-07-13T10:01:00Z",
            reply_to_message_id = null,
            reply_preview_sender_id = null,
            reply_preview_body = null,
            thread_root_id = null,
            thread_parent_id = null,
            visibility = MESSAGE_VISIBILITY_MAIN,
            thread_reply_count = 0,
            last_thread_reply_at = null,
            last_thread_reply_sender_id = null,
            expires_at = null,
            envelope_type = null,
            ciphertext = null,
            sender_device_id = null,
        )

        DatabaseSchemaRepair.ensureUpToDate(driver)

        val repaired = queries.selectMainMessagesForChat(chatId).executeAsList()
        assertEquals(1, repaired.size)
        assertEquals("new-row", repaired.single().id)
        assertEquals("read", repaired.single().status)
        driver.close()
    }

    @Test
    fun saveMessage_persistsReplyPreviewSnapshot() = runBlocking {
        val store = newStore()
        val chatId = "chat-dm-reply-preview"
        store.saveChat(Chat(id = chatId, title = "bob", type = ChatType.DIRECT))

        store.saveMessage(
            Message(
                id = "reply-1",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "answer",
                created_at = "2026-07-13T10:03:00Z",
                reply_to_message_id = "missing-parent",
                reply_preview_sender_id = "user-alice",
                reply_preview_body = "quoted original",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )

        val stored = store.observeMainMessages(chatId)
            .first { it.any { message -> message.id == "reply-1" } }
            .single { it.id == "reply-1" }
        assertEquals("missing-parent", stored.reply_to_message_id)
        assertEquals("user-alice", stored.reply_preview_sender_id)
        assertEquals("quoted original", stored.reply_preview_body)
    }

    @Test
    fun saveThreadOnlyMessage_refreshesRootSummaryContract() = runBlocking {
        val store = newStore()
        val chatId = "chat-group-1"
        val rootId = "root-1"
        store.saveChat(Chat(id = chatId, title = "Тестовый чат", type = ChatType.GROUP))
        store.saveMessage(
            Message(
                id = rootId,
                chat_id = chatId,
                sender_id = "user-alice",
                body = "root",
                created_at = "2026-07-18T10:00:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )

        store.saveMessage(
            Message(
                id = "thread-reply-1",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "reply in thread",
                created_at = "2026-07-18T10:02:00Z",
                reply_to_message_id = rootId,
                thread_root_id = rootId,
                thread_parent_id = rootId,
                visibility = MESSAGE_VISIBILITY_THREAD_ONLY,
            ),
        )

        val root = store.findMessageById(rootId)
        assertEquals(1, root?.thread_reply_count)
        assertEquals("2026-07-18T10:02:00Z", root?.last_thread_reply_at)
        assertEquals("user-bob", root?.last_thread_reply_sender_id)
    }

    @Test
    fun resetRecoverableOutboxBackoff_requeuesInterruptedProcessingJobs() = runBlocking {
        val store = newStore()
        store.enqueueOutboxJob(
            OutboxJobRecord(
                id = "pending-1",
                jobType = "send_dm",
                chatId = "chat-dm-3",
                payloadJson = "{}",
                status = OutboxJobStatus.PROCESSING,
                attempts = 0,
                nextAttemptAt = null,
                createdAt = "2026-07-13T10:00:00Z",
                lastError = null,
            ),
        )

        assertTrue(store.hasPendingOutboxJobs())
        store.resetRecoverableOutboxBackoff()

        val jobs = store.listDueOutboxJobs("2026-07-13T10:01:00Z", limit = 10)
        assertEquals(1, jobs.size)
        assertEquals("pending-1", jobs.single().id)
        assertEquals(OutboxJobStatus.PENDING, jobs.single().status)
    }

    @Test
    fun repairLocalAttachmentMessageLinksForChat_relinksPendingAttachmentToServerMessageId() = runBlocking {
        val store = newStore()
        val chatId = "chat-dm-attachment-repair"
        store.saveChat(Chat(id = chatId, title = "bob", type = ChatType.DIRECT))

        store.saveMessage(
            Message(
                id = "server-msg-attachment",
                chat_id = chatId,
                sender_id = "user-alice",
                body = "📎 photo.jpg",
                pending_id = "pending-attachment-1",
                created_at = "2026-07-24T12:00:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )

        store.upsertLocalAttachment(
            com.glagolitsa.media.LocalAttachmentRecord(
                cacheId = "cache-attachment-1",
                attachmentId = "remote-attachment-1",
                messageId = "pending-attachment-1",
                chatId = chatId,
                ownerAccountId = "user-alice",
                direction = "outgoing",
                mimeType = "image/jpeg",
                fileName = "photo.jpg",
                encryptedPath = "mem://enc",
                thumbnailPath = "mem://thumb",
                thumbnailKey = "key",
                thumbnailSize = 1,
                encryptedSize = 1,
                plaintextSize = 1,
                state = AttachmentTransferState.UPLOADED,
                lastAccessedAt = "2026-07-24T12:00:00Z",
                createdAt = "2026-07-24T12:00:00Z",
            ),
        )

        store.repairLocalAttachmentMessageLinksForChat(
            chatId = chatId,
            accessedAt = "2026-07-24T12:01:00Z",
        )

        val relinked = store.findLocalAttachment("cache-attachment-1")
        assertNotNull(relinked)
        assertEquals("server-msg-attachment", relinked.messageId)
    }
}
