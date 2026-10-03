// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.call.CallMediaConnectOptions
import com.glagolitsa.call.CallMediaConnectionState
import com.glagolitsa.call.CallMediaEngine
import com.glagolitsa.call.CallMediaEvent
import com.glagolitsa.call.CallMediaState
import com.glagolitsa.call.CallMediaStatsSnapshot
import com.glagolitsa.crypto.TestCryptoEngine
import com.glagolitsa.model.CallActionResponse
import com.glagolitsa.model.CallFeatureKeys
import com.glagolitsa.model.CallMediaConfig
import com.glagolitsa.model.CallSession
import com.glagolitsa.model.CallStatus
import com.glagolitsa.model.CallTokenResponse
import com.glagolitsa.model.CallType
import com.glagolitsa.model.CallControlEventData
import com.glagolitsa.model.User
import com.glagolitsa.model.WsEventType
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.session.SessionStore
import com.glagolitsa.session.SessionTokenProvider
import com.glagolitsa.ui.ClientFeatureFlags
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallControllerMediaFailureTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val secureSession = SecureSessionStore()

    @BeforeTest
    fun setup() = runBlocking {
        SessionStore.clear()
        secureSession.clear()
        ClientFeatureFlags.update(emptyMap())
    }

    @AfterTest
    fun cleanup() = runBlocking {
        SessionStore.clear()
        secureSession.clear()
        ClientFeatureFlags.update(emptyMap())
    }

    @Test
    fun acceptCall_endsInsteadOfStayingConnecting_whenMediaConnectFails() = runBlocking {
        ClientFeatureFlags.update(mapOf(CallFeatureKeys.RTC_LIVEKIT to true))
        SessionStore.setSession("tok", User(id = "callee", username = "callee"))
        var endRequests = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/calls/call-1/accept" -> respondJson(
                    json.encodeToString(
                        CallActionResponse.serializer(),
                        CallActionResponse(call = call(status = CallStatus.CONNECTING, acceptedAt = "2026-08-09T10:00:01Z")),
                    ),
                )
                "/api/calls/call-1/token" -> respondJson(
                    json.encodeToString(
                        CallTokenResponse.serializer(),
                        CallTokenResponse(
                            token = "lk-token",
                            livekit_url = "wss://livekit.test",
                            room_name = "call-call-1",
                            participant_id = "participant-callee",
                            media_config = CallMediaConfig(e2ee = false),
                        ),
                    ),
                )
                "/api/calls/call-1/end" -> {
                    endRequests++
                    respondJson(
                        json.encodeToString(
                            CallActionResponse.serializer(),
                            CallActionResponse(call = call(status = CallStatus.ENDED, endedAt = "2026-08-09T10:00:02Z")),
                        ),
                    )
                }
                else -> respond("""{"error":"unexpected path"}""", HttpStatusCode.NotFound)
            }
        }
        val controller = CallController(
            api = api(engine),
            crypto = { TestCryptoEngine() },
            usernameLookup = { it },
            auth = SessionTokenProvider(secureSession) { "tok" },
            mediaEngine = FailingCallMediaEngine(),
        )
        controller.presentIncoming(call(status = CallStatus.RINGING))

        controller.acceptCall("call-1")

        withTimeout(2_000) {
            while (controller.activeCall.value?.session?.status != CallStatus.ENDED) {
                delay(20)
            }
        }
        val active = controller.activeCall.value
        assertEquals(CallStatus.ENDED, active?.session?.status)
        assertFalse(active?.connectingMedia ?: true)
        assertEquals("Не удалось подключить звонок", active?.lowBandwidthNotice)
        assertTrue(endRequests > 0, "media failure must also end the server call")
    }

    @Test
    fun callConnectedEvent_clearsConnectingMedia_whenSdkConnectIsStillSuspended() = runBlocking {
        ClientFeatureFlags.update(mapOf(CallFeatureKeys.RTC_LIVEKIT to true))
        SessionStore.setSession("tok", User(id = "callee", username = "callee"))
        val media = ControlledCallMediaEngine()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/calls/call-1/accept" -> respondJson(
                    json.encodeToString(
                        CallActionResponse.serializer(),
                        CallActionResponse(call = call(status = CallStatus.CONNECTING, acceptedAt = "2026-08-09T10:00:01Z")),
                    ),
                )
                "/api/calls/call-1/token" -> respondJson(
                    json.encodeToString(
                        CallTokenResponse.serializer(),
                        CallTokenResponse(
                            token = "lk-token",
                            livekit_url = "wss://livekit.test",
                            room_name = "call-call-1",
                            participant_id = "participant-callee",
                            media_config = CallMediaConfig(e2ee = false),
                        ),
                    ),
                )
                "/api/calls/call-1/connected" -> respondJson(
                    json.encodeToString(
                        CallActionResponse.serializer(),
                        CallActionResponse(
                            call = call(
                                status = CallStatus.ACTIVE,
                                acceptedAt = "2026-08-09T10:00:01Z",
                                connectedAt = "2026-08-09T10:00:03Z",
                            ),
                        ),
                    ),
                )
                else -> respond("""{"error":"unexpected path"}""", HttpStatusCode.NotFound)
            }
        }
        val controller = CallController(
            api = api(engine),
            crypto = { TestCryptoEngine() },
            usernameLookup = { it },
            auth = SessionTokenProvider(secureSession) { "tok" },
            mediaEngine = media,
        )
        controller.presentIncoming(call(status = CallStatus.RINGING))

        controller.acceptCall("call-1")
        withTimeout(2_000) {
            media.connectStarted.await()
        }
        assertTrue(controller.activeCall.value?.connectingMedia == true)

        controller.onControlEvent(
            WsEventType.CALL_CONNECTED,
            CallControlEventData(call_id = "call-1", status = CallStatus.ACTIVE),
        )

        val active = controller.activeCall.value
        assertEquals(CallStatus.ACTIVE, active?.session?.status)
        assertFalse(active?.connectingMedia ?: true)

        media.allowConnect.complete(Unit)
        Unit
    }

    @Test
    fun acceptCall_recoversActiveState_whenConnectedResponseTimesOut() = runBlocking {
        ClientFeatureFlags.update(mapOf(CallFeatureKeys.RTC_LIVEKIT to true))
        SessionStore.setSession("tok", User(id = "callee", username = "callee"))
        val media = ControlledCallMediaEngine()
        media.allowConnect.complete(Unit)
        var connectedRequests = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/calls/call-1/accept" -> respondJson(
                    json.encodeToString(
                        CallActionResponse.serializer(),
                        CallActionResponse(call = call(status = CallStatus.CONNECTING, acceptedAt = "2026-08-09T10:00:01Z")),
                    ),
                )
                "/api/calls/call-1/token" -> respondJson(
                    json.encodeToString(
                        CallTokenResponse.serializer(),
                        CallTokenResponse(
                            token = "lk-token",
                            livekit_url = "wss://livekit.test",
                            room_name = "call-call-1",
                            participant_id = "participant-callee",
                            media_config = CallMediaConfig(e2ee = false),
                        ),
                    ),
                )
                "/api/calls/call-1/connected" -> {
                    connectedRequests++
                    delay(10_000)
                    respondJson(
                        json.encodeToString(
                            CallActionResponse.serializer(),
                            CallActionResponse(
                                call = call(
                                    status = CallStatus.ACTIVE,
                                    acceptedAt = "2026-08-09T10:00:01Z",
                                    connectedAt = "2026-08-09T10:00:03Z",
                                ),
                            ),
                        ),
                    )
                }
                "/api/calls/call-1" -> respondJson(
                    json.encodeToString(
                        CallSession.serializer(),
                        call(
                            status = CallStatus.ACTIVE,
                            acceptedAt = "2026-08-09T10:00:01Z",
                            connectedAt = "2026-08-09T10:00:03Z",
                        ),
                    ),
                )
                else -> respond("""{"error":"unexpected path"}""", HttpStatusCode.NotFound)
            }
        }
        val controller = CallController(
            api = api(engine),
            crypto = { TestCryptoEngine() },
            usernameLookup = { it },
            auth = SessionTokenProvider(secureSession) { "tok" },
            mediaEngine = media,
        )
        controller.presentIncoming(call(status = CallStatus.RINGING))

        controller.acceptCall("call-1")

        withTimeout(6_000) {
            while (controller.activeCall.value?.session?.status != CallStatus.ACTIVE ||
                controller.activeCall.value?.connectingMedia == true
            ) {
                delay(20)
            }
        }
        val active = controller.activeCall.value
        assertEquals(CallStatus.ACTIVE, active?.session?.status)
        assertFalse(active?.connectingMedia ?: true)
        assertTrue(connectedRequests > 0)
    }

    @Test
    fun activeCall_clearsWhenServerShowsEnded_evenIfEndedEventIsMissed() = runBlocking {
        ClientFeatureFlags.update(mapOf(CallFeatureKeys.RTC_LIVEKIT to true))
        SessionStore.setSession("tok", User(id = "callee", username = "callee"))
        val media = ControlledCallMediaEngine()
        media.allowConnect.complete(Unit)
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/calls/call-1/accept" -> respondJson(
                    json.encodeToString(
                        CallActionResponse.serializer(),
                        CallActionResponse(call = call(status = CallStatus.CONNECTING, acceptedAt = "2026-08-09T10:00:01Z")),
                    ),
                )
                "/api/calls/call-1/token" -> respondJson(
                    json.encodeToString(
                        CallTokenResponse.serializer(),
                        CallTokenResponse(
                            token = "lk-token",
                            livekit_url = "wss://livekit.test",
                            room_name = "call-call-1",
                            participant_id = "participant-callee",
                            media_config = CallMediaConfig(e2ee = false),
                        ),
                    ),
                )
                "/api/calls/call-1/connected" -> respondJson(
                    json.encodeToString(
                        CallActionResponse.serializer(),
                        CallActionResponse(
                            call = call(
                                status = CallStatus.ACTIVE,
                                acceptedAt = "2026-08-09T10:00:01Z",
                                connectedAt = "2026-08-09T10:00:03Z",
                            ),
                        ),
                    ),
                )
                "/api/calls/call-1" -> respondJson(
                    json.encodeToString(
                        CallSession.serializer(),
                        call(
                            status = CallStatus.ENDED,
                            acceptedAt = "2026-08-09T10:00:01Z",
                            connectedAt = "2026-08-09T10:00:03Z",
                            endedAt = "2026-08-09T10:00:08Z",
                        ),
                    ),
                )
                else -> respond("""{"error":"unexpected path"}""", HttpStatusCode.NotFound)
            }
        }
        val controller = CallController(
            api = api(engine),
            crypto = { TestCryptoEngine() },
            usernameLookup = { it },
            auth = SessionTokenProvider(secureSession) { "tok" },
            mediaEngine = media,
        )
        controller.presentIncoming(call(status = CallStatus.RINGING))

        controller.acceptCall("call-1")
        withTimeout(3_000) {
            while (controller.activeCall.value?.session?.status != CallStatus.ACTIVE) {
                delay(20)
            }
        }

        withTimeout(9_000) {
            while (controller.activeCall.value != null) {
                delay(20)
            }
        }

        assertEquals(null, controller.activeCall.value)
    }

    @Test
    fun incomingRingingCall_clearsWhenServerShowsEnded_evenIfEndedEventIsMissed() = runBlocking {
        SessionStore.setSession("tok", User(id = "callee", username = "callee"))
        var getCallRequests = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/calls/call-1" -> {
                    getCallRequests++
                    respondJson(
                        json.encodeToString(
                            CallSession.serializer(),
                            call(status = CallStatus.ENDED, endedAt = "2026-08-09T10:00:08Z"),
                        ),
                    )
                }
                else -> respond("""{"error":"unexpected path"}""", HttpStatusCode.NotFound)
            }
        }
        val controller = CallController(
            api = api(engine),
            crypto = { TestCryptoEngine() },
            usernameLookup = { it },
            auth = SessionTokenProvider(secureSession) { "tok" },
            mediaEngine = ControlledCallMediaEngine(),
        )

        controller.presentIncoming(call(status = CallStatus.RINGING))

        withTimeout(4_000) {
            while (controller.activeCall.value != null) {
                delay(20)
            }
        }
        assertTrue(getCallRequests > 0)
    }

    @Test
    fun outgoingRingingCall_clearsWhenCalleeRejects_evenIfEndedEventIsMissed() = runBlocking {
        SessionStore.setSession("tok", User(id = "caller", username = "caller"))
        var getCallRequests = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/calls" -> respondJson(
                    json.encodeToString(
                        CallActionResponse.serializer(),
                        CallActionResponse(
                            call = call(status = CallStatus.RINGING).copy(
                                caller_id = "caller",
                                callee_id = "callee",
                            ),
                        ),
                    ),
                )
                "/api/calls/call-1" -> {
                    getCallRequests++
                    respondJson(
                        json.encodeToString(
                            CallSession.serializer(),
                            call(status = CallStatus.REJECTED, endedAt = "2026-08-09T10:00:08Z").copy(
                                caller_id = "caller",
                                callee_id = "callee",
                            ),
                        ),
                    )
                }
                else -> respond("""{"error":"unexpected path"}""", HttpStatusCode.NotFound)
            }
        }
        val controller = CallController(
            api = api(engine),
            crypto = { TestCryptoEngine() },
            usernameLookup = { it },
            auth = SessionTokenProvider(secureSession) { "tok" },
            mediaEngine = ControlledCallMediaEngine(),
        )

        controller.startOutgoingCall(calleeId = "callee", partnerName = "callee")

        withTimeout(5_000) {
            while (controller.activeCall.value != null) {
                delay(20)
            }
        }
        assertTrue(getCallRequests > 0)
    }

    private fun api(engine: MockEngine) = ApiClient(
        baseUrl = "https://api.test",
        httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        },
    )

    private fun call(
        status: String,
        acceptedAt: String? = null,
        connectedAt: String? = null,
        endedAt: String? = null,
    ) = CallSession(
        id = "call-1",
        caller_id = "caller",
        callee_id = "callee",
        call_type = CallType.AUDIO,
        status = status,
        livekit_room_id = "call-call-1",
        created_at = "2026-08-09T10:00:00Z",
        accepted_at = acceptedAt,
        connected_at = connectedAt,
        ended_at = endedAt,
    )

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}

private class FailingCallMediaEngine : CallMediaEngine {
    override val state = MutableStateFlow(CallMediaState())
    override val events = MutableSharedFlow<CallMediaEvent>(extraBufferCapacity = 8)
    override val stats = MutableStateFlow<CallMediaStatsSnapshot?>(null)

    override suspend fun connect(options: CallMediaConnectOptions) {
        error("frame cryptor native unavailable")
    }

    override suspend fun publishMicrophone() = Unit
    override suspend fun setMicrophoneMuted(muted: Boolean) = Unit
    override suspend fun setSpeaker(speakerOn: Boolean) = Unit
    override suspend fun disconnect() = Unit
    override fun release() = Unit
}

private class ControlledCallMediaEngine : CallMediaEngine {
    override val state = MutableStateFlow(CallMediaState())
    override val events = MutableSharedFlow<CallMediaEvent>(extraBufferCapacity = 8)
    override val stats = MutableStateFlow<CallMediaStatsSnapshot?>(null)
    val connectStarted = CompletableDeferred<Unit>()
    val allowConnect = CompletableDeferred<Unit>()

    override suspend fun connect(options: CallMediaConnectOptions) {
        connectStarted.complete(Unit)
        state.value = CallMediaState(connection = CallMediaConnectionState.CONNECTING)
        allowConnect.await()
        state.value = CallMediaState(connection = CallMediaConnectionState.CONNECTED)
        events.tryEmit(CallMediaEvent.RoomConnected)
    }

    override suspend fun publishMicrophone() {
        state.value = state.value.copy(
            microphonePublished = true,
            mediaPathConfirmed = true,
        )
        events.tryEmit(CallMediaEvent.MicrophonePublished)
        events.tryEmit(CallMediaEvent.MediaPathConfirmed)
    }

    override suspend fun setMicrophoneMuted(muted: Boolean) = Unit
    override suspend fun setSpeaker(speakerOn: Boolean) = Unit
    override suspend fun disconnect() = Unit
    override fun release() = Unit
}
