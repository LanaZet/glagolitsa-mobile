// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

import com.glagolitsa.model.Chat
import com.glagolitsa.model.CreateDMRequest
import com.glagolitsa.model.Message
import com.glagolitsa.model.MessagesPageResponse
import com.glagolitsa.model.SendMessageRequest
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiClientChatTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun client(engine: MockEngine) = ApiClient(
        baseUrl = "https://api.test",
        httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        },
    )

    @Test
    fun createDM_postsToDmEndpoint() = runTest {
        var path = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(
                content = json.encodeToString(
                    Chat.serializer(),
                    Chat(id = "chat-1", title = "bob", type = "dm"),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val chat = client(engine).createDM("tok", CreateDMRequest(user_id = "user-bob"))
        assertTrue(path.endsWith("/api/chats/dm"))
        assertEquals("chat-1", chat.id)
    }

    @Test
    fun listMessages_includesBeforeQueryParam() = runTest {
        var url = ""
        val engine = MockEngine { request ->
            url = request.url.toString()
            respond(
                content = json.encodeToString(
                    MessagesPageResponse.serializer(),
                    MessagesPageResponse(messages = emptyList(), has_more = false),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        client(engine).listMessages("tok", "chat-1", before = "msg-older", limit = 20)
        assertTrue(url.contains("before=msg-older"))
        assertTrue(url.contains("limit=20"))
    }

    @Test
    fun sendMessage_postsEncryptedGroupPayload() = runTest {
        var path = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(
                content = json.encodeToString(
                    Message.serializer(),
                    Message(id = "m1", chat_id = "chat-1", sender_id = "u1", ciphertext = "Zm9v"),
                ),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val response = client(engine).sendMessage(
            token = "tok",
            chatId = "chat-1",
            request = SendMessageRequest(
                ciphertext = "Zm9v",
                envelope_type = 4,
                sender_device_id = "dev-1",
            ),
        )
        assertEquals("m1", response.id)
        assertTrue(path.contains("/api/chats/chat-1/messages"))
    }

    @Test
    fun sendMessage_dmPlaintextRejectedWith410() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"direct messages must use encrypted relay"}""",
                status = HttpStatusCode.Gone,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val error = assertFailsWith<ApiException> {
            client(engine).sendMessage(
                token = "tok",
                chatId = "chat-dm",
                request = SendMessageRequest(body = "plaintext"),
            )
        }
        assertEquals(HttpStatusCode.Gone, error.status)
    }
}