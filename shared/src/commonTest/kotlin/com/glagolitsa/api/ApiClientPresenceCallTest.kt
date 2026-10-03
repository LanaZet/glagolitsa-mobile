// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

import com.glagolitsa.model.CallActionResponse
import com.glagolitsa.model.CallSession
import com.glagolitsa.model.CallStatus
import com.glagolitsa.model.CreateCallRequest
import com.glagolitsa.model.HeartbeatRequest
import com.glagolitsa.model.PresencePrivacySettings
import com.glagolitsa.model.PresenceStatus
import com.glagolitsa.model.PresenceVisibility
import com.glagolitsa.model.UpdatePresencePrivacyRequest
import com.glagolitsa.model.UserPresenceView
import com.glagolitsa.model.UsersPresenceResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
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
import kotlin.test.assertTrue

class ApiClientPresenceCallTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun client(engine: MockEngine) = ApiClient(
        baseUrl = "https://api.test",
        httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        },
    )

    @Test
    fun createCall_postsDeviceScopedRequest() = runTest {
        var path = ""
        var deviceHeader = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            deviceHeader = request.headers["X-Device-Id"].orEmpty()
            respondJson(
                json.encodeToString(
                    CallActionResponse.serializer(),
                    CallActionResponse(
                        call = CallSession(
                            id = "call-1",
                            caller_id = "me",
                            callee_id = "peer",
                            status = CallStatus.RINGING,
                            livekit_room_id = "call-call-1",
                            caller_device_id = "dev-1",
                            created_at = "2026-07-20T10:00:00Z",
                        ),
                    ),
                ),
            )
        }

        val response = client(engine).createCall(
            token = "tok",
            deviceId = "dev-1",
            request = CreateCallRequest(callee_id = "peer", device_id = "dev-1"),
        )

        assertEquals("/api/calls", path)
        assertEquals("dev-1", deviceHeader)
        assertEquals(CallStatus.RINGING, response.call.status)
    }

    @Test
    fun markCallConnected_postsMediaPathConfirmation() = runTest {
        var path = ""
        var deviceHeader = ""
        var body = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            deviceHeader = request.headers["X-Device-Id"].orEmpty()
            body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            respondJson(
                json.encodeToString(
                    CallActionResponse.serializer(),
                    CallActionResponse(
                        call = CallSession(
                            id = "call-1",
                            caller_id = "me",
                            callee_id = "peer",
                            status = CallStatus.ACTIVE,
                            livekit_room_id = "call-call-1",
                            caller_device_id = "dev-1",
                            created_at = "2026-07-20T10:00:00Z",
                        ),
                    ),
                ),
            )
        }

        val response = client(engine).markCallConnected(
            token = "tok",
            callId = "call-1",
            deviceId = "dev-1",
        )

        assertEquals("/api/calls/call-1/connected", path)
        assertEquals("dev-1", deviceHeader)
        assertTrue(body.contains(""""media_path_confirmed":true"""))
        assertEquals(CallStatus.ACTIVE, response.call.status)
    }

    @Test
    fun presenceHeartbeat_postsDeviceScopedRequest() = runTest {
        var path = ""
        var deviceHeader = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            deviceHeader = request.headers["X-Device-Id"].orEmpty()
            respondJson("""{}""")
        }

        client(engine).presenceHeartbeat(
            token = "tok",
            deviceId = "dev-1",
            request = HeartbeatRequest(device_id = "dev-1"),
        )

        assertEquals("/api/presence/heartbeat", path)
        assertEquals("dev-1", deviceHeader)
    }

    @Test
    fun listPresenceUsers_decodesBatchResponse() = runTest {
        var url = ""
        val engine = MockEngine { request ->
            url = request.url.toString()
            respondJson(
                json.encodeToString(
                    UsersPresenceResponse.serializer(),
                    UsersPresenceResponse(
                        users = listOf(
                            UserPresenceView(user_id = "peer", status = PresenceStatus.IN_CALL, in_call = true),
                        ),
                    ),
                ),
            )
        }

        val users = client(engine).listPresenceUsers("tok", listOf("peer"))

        assertTrue(url.contains("/api/presence/users?ids=peer"))
        assertEquals(1, users.size)
        assertEquals(PresenceStatus.IN_CALL, users.single().status)
        assertTrue(users.single().in_call)
    }

    @Test
    fun presencePrivacy_roundTripsVisibilitySettings() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            respondJson(
                json.encodeToString(
                    PresencePrivacySettings.serializer(),
                    PresencePrivacySettings(
                        online_visibility = PresenceVisibility.NOBODY,
                        last_seen_visibility = PresenceVisibility.NOBODY,
                    ),
                ),
            )
        }

        val client = client(engine)
        val current = client.getPresencePrivacy("tok")
        val updated = client.updatePresencePrivacy(
            token = "tok",
            request = UpdatePresencePrivacyRequest(
                online_visibility = PresenceVisibility.NOBODY,
                last_seen_visibility = PresenceVisibility.NOBODY,
            ),
        )

        assertEquals(listOf("/api/presence/privacy", "/api/presence/privacy"), paths)
        assertEquals(PresenceVisibility.NOBODY, current.online_visibility)
        assertEquals(PresenceVisibility.NOBODY, updated.last_seen_visibility)
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
