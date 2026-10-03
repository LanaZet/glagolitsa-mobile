// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.crypto.CryptoEngine
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.RoundtripTestCryptoEngine
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.metadata.decodeSealedGroupPayload
import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.MESSAGE_VISIBILITY_MAIN
import com.glagolitsa.model.MESSAGE_VISIBILITY_THREAD_ONLY
import com.glagolitsa.model.Message
import com.glagolitsa.model.MessageRelationDraft
import com.glagolitsa.model.MessageStatus
import com.glagolitsa.model.MessagesPageResponse
import com.glagolitsa.model.RelayMessageResponse
import com.glagolitsa.model.RotateMailboxResponse
import com.glagolitsa.model.SendMessageRequest
import com.glagolitsa.model.User
import com.glagolitsa.model.UserDevice
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.session.SessionStore
import com.glagolitsa.util.decodeBase64
import com.glagolitsa.util.encodeBase64
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group threads: thread_only stays out of main timeline, lands in thread query,
 * and does not update chat preview.
 */
class MessengerRepositoryGroupThreadTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val alice = User(id = "user-alice", username = "alice", email = null)
    private val bob = User(id = "user-bob", username = "bob", email = null)
    private val groupId = "chat-group-thread"
    private val group = Chat(
        id = groupId,
        title = "Team",
        type = ChatType.GROUP,
        member_ids = listOf(alice.id, bob.id),
        last_message = null,
        last_message_at = null,
    )

    private val key = ByteArray(32) { 11 }.encodeBase64()
    private val bobDeviceId = "bob-group-dev"
    private val bobMailbox = "mailbox-bob-group"

    private var groupChatState = group
    private var nextMessageSeq = 1
    private val storedServerMessages = mutableListOf<Message>()

    @BeforeTest
    fun reset() {
        SessionStore.clear()
        groupChatState = group
        nextMessageSeq = 1
        storedServerMessages.clear()
    }

    @AfterTest
    fun cleanup() {
        SessionStore.clear()
    }

    private fun mockEngine(): MockEngine = MockEngine { request ->
        when {
            request.url.encodedPath.endsWith("/api/auth/login") -> respond(
                content = json.encodeToString(
                    AuthResponse.serializer(),
                    AuthResponse(token = "tok-alice", user = alice),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/api/hello") -> respond(
                content = """{"message":"test","server_id":"group-thread-test"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/api/devices") && request.method.value == "POST" -> respond(
                content = """{"device_id":"test-device-user-alice","prekeys_stored":1}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/api/chats") && request.method.value == "GET" -> respond(
                content = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(Chat.serializer()),
                    listOf(groupChatState),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.contains("/users/") && request.url.encodedPath.endsWith("/devices") -> respond(
                content = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(UserDevice.serializer()),
                    listOf(
                        UserDevice(
                            device_id = bobDeviceId,
                            mailbox_token = bobMailbox,
                            registration_id = 42,
                            identity_public_key = key,
                            device_status = "active",
                        ),
                    ),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/bundle") -> respond(
                content = """{"device_id":"$bobDeviceId","account_id":"${bob.id}","registration_id":42,"identity_public_key":"$key","signed_prekey":{"id":1,"public_key":"$key","signature":"$key","created_at":1},"pq_prekey":{"id":2,"public_material":"$key","signature":"$key","created_at":1},"one_time_prekey":{"id":3,"public_key":"$key"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/api/messages/relay") -> respond(
                content = json.encodeToString(
                    RelayMessageResponse.serializer(),
                    RelayMessageResponse(enqueued = 1, envelope_ids = listOf("skdm-env-1")),
                ),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/messages") && request.method.value == "POST" -> {
                val body = request.bodyText()
                val req = json.decodeFromString(SendMessageRequest.serializer(), body)
                val id = "gmsg-${nextMessageSeq++}"
                val message = Message(
                    id = id,
                    chat_id = groupId,
                    sender_id = alice.id,
                    body = "",
                    pending_id = req.pending_id,
                    status = MessageStatus.SENT,
                    created_at = "2026-07-17T12:00:0${nextMessageSeq}Z",
                    reply_to_message_id = req.reply_to_message_id,
                    thread_root_id = req.thread_root_id,
                    thread_parent_id = req.thread_parent_id,
                    visibility = req.visibility ?: MESSAGE_VISIBILITY_MAIN,
                    envelope_type = req.envelope_type,
                    ciphertext = req.ciphertext,
                    sender_device_id = req.sender_device_id,
                )
                storedServerMessages.add(message)
                if (message.visibility != MESSAGE_VISIBILITY_THREAD_ONLY) {
                    groupChatState = groupChatState.copy(
                        last_message = "server-preview-placeholder",
                        last_message_at = message.created_at,
                    )
                }
                respond(
                    content = json.encodeToString(Message.serializer(), message),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            request.url.encodedPath.contains("/messages") && request.method.value == "GET" -> respond(
                content = json.encodeToString(
                    MessagesPageResponse.serializer(),
                    MessagesPageResponse(messages = storedServerMessages.toList(), has_more = false),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/api/mailbox/rotate") -> respond(
                content = json.encodeToString(
                    RotateMailboxResponse.serializer(),
                    RotateMailboxResponse(device_id = "dev", mailbox_token = "mb"),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/api/devices") && request.method.value == "GET" -> respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            else -> respond("{}", HttpStatusCode.OK)
        }
    }

    private fun repository(
        cryptoEngine: CryptoEngine = RoundtripTestCryptoEngine(),
    ): MessengerRepository = MessengerRepository(
        driverFactory = DatabaseDriverFactory().companionInMemory(),
        cryptoEngineFactory = CryptoEngineFactory(),
        secureSession = SecureSessionStore(),
        api = ApiClient(
            baseUrl = "https://api.test",
            httpClient = HttpClient(mockEngine()) {
                install(ContentNegotiation) { json(json) }
            },
        ),
        cryptoEngine = cryptoEngine,
    )

    @Test
    fun groupThreadReply_hiddenFromMainVisibleInThreadAndSkipsPreview() = runTest {
        val repo = repository()
        repo.login("alice", "password123")
        repo.syncChats()

        val root = repo.sendMessage(groupId, "root_in_main")
        assertEquals(MESSAGE_VISIBILITY_MAIN, root.visibility ?: MESSAGE_VISIBILITY_MAIN)
        assertEquals("root_in_main", root.body)

        val chatsAfterRoot = repo.observeChats().first { list ->
            list.any { it.id == groupId && it.last_message == "root_in_main" }
        }
        assertEquals("root_in_main", chatsAfterRoot.single { it.id == groupId }.last_message)

        val threadReply = repo.sendMessage(
            chatId = groupId,
            body = "thread_only_reply",
            relation = MessageRelationDraft(
                replyToMessageId = root.id,
                threadRootId = root.id,
                threadParentId = root.id,
                visibility = MESSAGE_VISIBILITY_THREAD_ONLY,
            ),
        )

        assertEquals(MESSAGE_VISIBILITY_THREAD_ONLY, threadReply.visibility)
        assertEquals(root.id, threadReply.thread_root_id)
        assertEquals(root.id, threadReply.thread_parent_id)
        assertEquals(root.id, threadReply.reply_to_message_id)
        assertEquals(root.sender_id, threadReply.reply_preview_sender_id)
        assertEquals(root.body, threadReply.reply_preview_body)
        assertEquals("thread_only_reply", threadReply.body)

        val main = repo.observeMainMessages(groupId).first { it.isNotEmpty() }
        assertTrue(main.any { it.body == "root_in_main" })
        assertTrue(main.none { it.body == "thread_only_reply" })

        val thread = repo.observeThreadMessages(groupId, root.id)
            .first { messages -> messages.any { it.body == "thread_only_reply" } }
        val storedThreadReply = thread.single { it.body == "thread_only_reply" }
        assertEquals(root.sender_id, storedThreadReply.reply_preview_sender_id)
        assertEquals(root.body, storedThreadReply.reply_preview_body)

        val sealedReply = storedServerMessages
            .single { it.pending_id == threadReply.pending_id }
            .ciphertext
            ?.let { decodeSealedGroupPayload(it.decodeBase64()) }
        assertEquals(root.sender_id, sealedReply?.reply_preview_sender_id)
        assertEquals(root.body, sealedReply?.reply_preview_body)

        val chatsAfterThread = repo.observeChats().first { list -> list.any { it.id == groupId } }
        assertEquals(
            "root_in_main",
            chatsAfterThread.single { it.id == groupId }.last_message,
            "thread_only must not update chat preview",
        )
    }

    @Test
    fun groupIncomingThreadOnly_decryptsIntoThreadListNotMain() = runTest {
        val repo = repository()
        repo.login("alice", "password123")
        repo.syncChats()

        val root = repo.sendMessage(groupId, "incoming_root")
        val sealed = com.glagolitsa.metadata.encodeSealedGroupPayload(
            com.glagolitsa.metadata.SealedGroupPayload(
                sender_account_id = bob.id,
                sender_device_id = bobDeviceId,
                chat_id = groupId,
                body = "incoming_thread_reply",
                reply_to_message_id = root.id,
                reply_preview_sender_id = root.sender_id,
                reply_preview_body = root.body,
                thread_root_id = root.id,
                thread_parent_id = root.id,
                visibility = MESSAGE_VISIBILITY_THREAD_ONLY,
            ),
        )
        // RoundtripTestCryptoEngine group encrypt is identity; ciphertext is sealed bytes.
        repo.applyIncomingMessage(
            Message(
                id = "incoming-thread-1",
                chat_id = groupId,
                sender_id = bob.id,
                body = "",
                created_at = "2026-07-17T13:00:00Z",
                envelope_type = 4,
                ciphertext = sealed.encodeBase64(),
                sender_device_id = bobDeviceId,
            ),
            alreadyDecrypted = false,
        )

        val main = repo.observeMainMessages(groupId).first { it.any { m -> m.body == "incoming_root" } }
        assertTrue(main.none { it.body == "incoming_thread_reply" })

        val thread = repo.observeThreadMessages(groupId, root.id)
            .first { it.any { m -> m.body == "incoming_thread_reply" } }
        val reply = thread.single { it.body == "incoming_thread_reply" }
        assertEquals(MESSAGE_VISIBILITY_THREAD_ONLY, reply.visibility)
        assertEquals(root.id, reply.thread_root_id)
        assertEquals(root.sender_id, reply.reply_preview_sender_id)
        assertEquals(root.body, reply.reply_preview_body)
        assertNull(main.firstOrNull { it.id == reply.id })
    }

    @Test
    fun ownGroupMessages_doNotShowEncryptedPlaceholderInMainRepliesThreadsOrSummaries() = runTest {
        val repo = repository(OwnGroupEchoCannotDecryptCryptoEngine())
        repo.login("alice", "password123")
        repo.syncChats()

        val root = repo.sendMessage(groupId, "signal_root_plaintext")
        val ordinaryReply = repo.sendMessage(
            chatId = groupId,
            body = "signal_reply_plaintext",
            relation = MessageRelationDraft(
                replyToMessageId = root.id,
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )
        val threadReply = repo.sendMessage(
            chatId = groupId,
            body = "signal_thread_plaintext",
            relation = MessageRelationDraft(
                replyToMessageId = root.id,
                threadRootId = root.id,
                threadParentId = root.id,
                visibility = MESSAGE_VISIBILITY_THREAD_ONLY,
            ),
        )

        assertEquals("signal_root_plaintext", root.body)
        assertEquals("signal_reply_plaintext", ordinaryReply.body)
        assertEquals("signal_thread_plaintext", threadReply.body)
        assertEquals(root.sender_id, ordinaryReply.reply_preview_sender_id)
        assertEquals(root.body, ordinaryReply.reply_preview_body)
        assertEquals(root.sender_id, threadReply.reply_preview_sender_id)
        assertEquals(root.body, threadReply.reply_preview_body)
        assertNoEncryptedPlaceholderInUserSurfaces(
            repo = repo,
            rootMessageId = root.id,
            ordinaryBodies = setOf("signal_root_plaintext", "signal_reply_plaintext"),
            threadBody = "signal_thread_plaintext",
            phase = "after send ack",
        )

        storedServerMessages.toList().forEach { serverEcho ->
            repo.applyIncomingMessage(serverEcho, alreadyDecrypted = false)
        }
        assertNoEncryptedPlaceholderInUserSurfaces(
            repo = repo,
            rootMessageId = root.id,
            ordinaryBodies = setOf("signal_root_plaintext", "signal_reply_plaintext"),
            threadBody = "signal_thread_plaintext",
            phase = "after websocket echo",
        )

        repo.syncMessages(groupId)
        val syncedSnapshot = repo.observeMessages(groupId).first()
        assertTrue(
            syncedSnapshot.isNotEmpty(),
            "after page sync: local messages are empty; server=${storedServerMessages.map { it.id to it.visibility }}",
        )
        assertNoEncryptedPlaceholderInUserSurfaces(
            repo = repo,
            rootMessageId = root.id,
            ordinaryBodies = setOf("signal_root_plaintext", "signal_reply_plaintext"),
            threadBody = "signal_thread_plaintext",
            phase = "after page sync",
        )
    }

    private suspend fun assertNoEncryptedPlaceholderInUserSurfaces(
        repo: MessengerRepository,
        rootMessageId: String,
        ordinaryBodies: Set<String>,
        threadBody: String,
        phase: String,
    ) {
        val allMessages = repo.observeMessages(groupId).first()
        assertNoEncryptedPlaceholder(allMessages, "$phase: all messages")
        ordinaryBodies.plus(threadBody).forEach { body ->
            assertTrue(
                allMessages.any { it.body == body },
                "$phase: full message list must contain '$body', actual=${allMessages.map { it.id to it.body }}",
            )
        }

        val mainMessages = repo.observeMainMessages(groupId).first()
        assertNoEncryptedPlaceholder(mainMessages, "$phase: main messages")
        ordinaryBodies.forEach { body ->
            assertTrue(
                mainMessages.any { it.body == body },
                "$phase: main timeline must contain '$body', actual=${mainMessages.map { it.id to it.body }}",
            )
        }
        assertFalse(
            mainMessages.any { it.body == threadBody },
            "$phase: thread-only reply must stay out of main timeline",
        )

        val threadMessages = repo.observeThreadMessages(groupId, rootMessageId).first()
        assertNoEncryptedPlaceholder(threadMessages, "$phase: thread messages")
        assertTrue(
            threadMessages.any { it.body == threadBody },
            "$phase: thread must contain own plaintext reply, actual=${threadMessages.map { it.id to it.body }}",
        )

        val threadSummary = repo.observeThreadBranchSummaries(groupId)
            .first()
            .getValue(rootMessageId)
        assertEquals(threadBody, threadSummary.lastReply?.body, "$phase: branch preview body")
        assertFalse(
            threadSummary.lastReply?.body.orEmpty().contains(ENCRYPTED_PLACEHOLDER),
            "$phase: branch preview must not show encrypted placeholder",
        )

        val chat = repo.observeChats().first().single { it.id == groupId }
        assertFalse(
            chat.last_message.orEmpty().contains(ENCRYPTED_PLACEHOLDER),
            "$phase: chat list preview must not show encrypted placeholder",
        )
    }

    private fun assertNoEncryptedPlaceholder(messages: List<Message>, phase: String) {
        assertFalse(
            messages.any { it.body.contains(ENCRYPTED_PLACEHOLDER) },
            "$phase contains encrypted placeholder: ${messages.map { it.id to it.body }}",
        )
    }

    private fun HttpRequestData.bodyText(): String =
        (body as? io.ktor.http.content.TextContent)?.text ?: ""

    companion object {
        private const val ENCRYPTED_PLACEHOLDER = "🔒 Зашифрованное сообщение"
    }
}

private class OwnGroupEchoCannotDecryptCryptoEngine(
    private val delegate: CryptoEngine = RoundtripTestCryptoEngine(),
) : CryptoEngine by delegate {
    override suspend fun decryptGroupMessage(
        accountId: String,
        senderAccountId: String,
        senderDeviceId: String,
        chatId: String,
        ciphertext: ByteArray,
    ): ByteArray? = null
}
