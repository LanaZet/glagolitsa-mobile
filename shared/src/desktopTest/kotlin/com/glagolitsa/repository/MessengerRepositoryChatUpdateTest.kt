// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.TestCryptoEngine
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Интеграция repository → Flow: то, что [com.glagolitsa.ui.ChatScreen] подписывает через
 * `repository.observeMainMessages(chat.id).collectAsState`.
 */
class MessengerRepositoryChatUpdateTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val chatId = "chat-repo-1"
    private val chatJson = json.encodeToString(
        Chat.serializer(),
        Chat(
            id = chatId,
            title = "bob",
            type = ChatType.DIRECT,
            member_ids = listOf("user-alice", "user-bob"),
        ),
    )

    @BeforeTest
    fun resetSession() {
        SessionStore.clear()
    }

    @AfterTest
    fun cleanup() {
        SessionStore.clear()
    }

    private fun repository(engine: MockEngine): MessengerRepository = MessengerRepository(
        driverFactory = DatabaseDriverFactory().companionInMemory(),
        cryptoEngineFactory = CryptoEngineFactory(),
        secureSession = SecureSessionStore(),
        api = ApiClient(
            baseUrl = "https://api.test",
            httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(json) }
            },
        ),
        cryptoEngine = TestCryptoEngine(),
    )

    private fun loggedInEngine(): MockEngine = MockEngine { request ->
        when {
            request.url.encodedPath.endsWith("/api/auth/login") -> respond(
                content = json.encodeToString(
                    AuthResponse.serializer(),
                    AuthResponse(
                        token = "tok-chat",
                        user = User(id = "user-alice", username = "alice", email = null),
                    ),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/api/hello") -> respond(
                content = """{"message":"test","server_id":"chat-update-test"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/api/devices") -> respond(
                content = """{"device_id":"alice-device","prekeys_stored":1,"one_time_prekey_ids":[301]}""",
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

    @Test
    fun applyIncomingMessage_updatesObserveMainMessagesFlow() = runBlocking {
        val repo = repository(loggedInEngine())
        repo.login("alice", "password123")
        repo.syncChats()

        val emissions = mutableListOf<List<Message>>()
        val collectJob = launch(Dispatchers.Default) {
            repo.observeMainMessages(chatId).collect { emissions.add(it) }
        }

        repo.applyIncomingMessage(
            Message(
                id = "env-1",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "новое сообщение",
                created_at = "2026-07-13T11:00:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
            alreadyDecrypted = true,
        )

        val latest = repo.observeMainMessages(chatId).first { list ->
            list.any { it.body == "новое сообщение" }
        }
        assertEquals("новое сообщение", latest.single().body)
        assertTrue(emissions.any { list -> list.any { it.body == "новое сообщение" } })

        collectJob.cancel()
    }

    @Test
    fun applyIncomingMessage_emitsOnIncomingMessagesSharedFlow() = runTest {
        val repo = repository(loggedInEngine())
        repo.login("alice", "password123")
        repo.syncChats()

        val incoming = backgroundScope.launch {
            val message = repo.incomingMessages.first()
            assertEquals("env-2", message.id)
            assertEquals("второе", message.body)
        }

        repo.applyIncomingMessage(
            Message(
                id = "env-2",
                chat_id = chatId,
                sender_id = "user-bob",
                body = "второе",
                created_at = "2026-07-13T11:01:00Z",
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
            alreadyDecrypted = true,
        )
        testScheduler.advanceUntilIdle()
        incoming.join()
    }
}
