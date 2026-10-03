// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.RoundtripTestCryptoEngine
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.MESSAGE_VISIBILITY_MAIN
import com.glagolitsa.model.Message
import com.glagolitsa.model.User
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MessengerRepositoryUnreadTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val chatId = "chat-unread-1"
    private val chatJson = json.encodeToString(
        Chat.serializer(),
        Chat(id = chatId, title = "bob", type = ChatType.DIRECT, member_ids = listOf("user-alice", "user-bob")),
    )

    @BeforeTest
    fun reset() = SessionStore.clear()

    @AfterTest
    fun cleanup() = SessionStore.clear()

    private fun repository(): MessengerRepository {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/auth/login") -> respond(
                    content = json.encodeToString(
                        AuthResponse.serializer(),
                        AuthResponse(token = "tok", user = User(id = "user-alice", username = "alice", email = null)),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/api/hello") -> respond(
                    content = """{"message":"test","server_id":"unread-test"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/api/devices") -> respond(
                    content = """{"device_id":"test-device-user-alice","prekeys_stored":1,"one_time_prekey_ids":[301]}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/api/chats") -> respond(
                    content = "[$chatJson]",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> respond("{}", HttpStatusCode.OK)
            }
        }
        return MessengerRepository(
            driverFactory = DatabaseDriverFactory().companionInMemory(),
            cryptoEngineFactory = CryptoEngineFactory(),
            secureSession = SecureSessionStore(),
            api = ApiClient(
                baseUrl = "https://api.test",
                httpClient = HttpClient(engine) { install(ContentNegotiation) { json(json) } },
            ),
            cryptoEngine = RoundtripTestCryptoEngine(),
        )
    }

    @Test
    fun applyIncomingMessage_incrementsUnreadWhenChatNotActive() = runTest {
        val repo = repository()
        repo.login("alice", "password123")
        repo.syncChats()

        val isNew = repo.applyIncomingMessage(
            Message(
                id = "env-u1",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "новое",
                created_at = "2026-07-13T12:00:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
            alreadyDecrypted = true,
        )
        testScheduler.advanceUntilIdle()

        assertEquals(true, isNew)
        assertEquals(1, repo.unreadCounts.value[chatId])
    }

    @Test
    fun applyIncomingMessage_doesNotIncrementUnreadForDuplicatePendingId() = runTest {
        val repo = repository()
        repo.login("alice", "password123")
        repo.syncChats()

        repo.applyIncomingMessage(
            Message(
                id = "env-u4",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "дубликат",
                pending_id = "client-msg-1",
                created_at = "2026-07-13T12:00:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
            alreadyDecrypted = true,
        )
        val duplicateIsNew = repo.applyIncomingMessage(
            Message(
                id = "env-u4-retry",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "дубликат",
                pending_id = "client-msg-1",
                created_at = "2026-07-13T12:00:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
            alreadyDecrypted = true,
        )
        testScheduler.advanceUntilIdle()

        assertEquals(false, duplicateIsNew)
        assertEquals(1, repo.unreadCounts.value[chatId])
    }

    @Test
    fun markChatOpened_clearsUnreadForChat() = runTest {
        val repo = repository()
        repo.login("alice", "password123")
        repo.syncChats()

        repo.applyIncomingMessage(
            Message(
                id = "env-u2",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "ещё одно",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
            alreadyDecrypted = true,
        )
        repo.markChatOpened(chatId)
        testScheduler.advanceUntilIdle()

        assertEquals(0, repo.unreadCounts.value[chatId] ?: 0)
    }

    @Test
    fun applyIncomingMessage_skipsUnreadForActiveChat() = runTest {
        val repo = repository()
        repo.login("alice", "password123")
        repo.syncChats()
        repo.markChatOpened(chatId)

        repo.applyIncomingMessage(
            Message(
                id = "env-u3",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "в открытом чате",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
            alreadyDecrypted = true,
        )
        testScheduler.advanceUntilIdle()

        assertEquals(0, repo.unreadCounts.value[chatId] ?: 0)
    }
}
