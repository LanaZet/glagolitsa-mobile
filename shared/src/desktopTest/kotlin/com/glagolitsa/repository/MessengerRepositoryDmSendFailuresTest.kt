// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.RoundtripTestCryptoEngine
import com.glagolitsa.crypto.TestCryptoEngine
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.MessageStatus
import com.glagolitsa.model.OneTimePreKeyMaterial
import com.glagolitsa.model.PqPreKeyMaterial
import com.glagolitsa.model.RelayMessageRequest
import com.glagolitsa.model.RelayMessageResponse
import com.glagolitsa.model.RotateMailboxResponse
import com.glagolitsa.model.SignedPreKeyMaterial
import com.glagolitsa.model.User
import com.glagolitsa.model.UserDevice
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.session.SessionStore
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
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Signal-style send/outbox failure matrix for DM:
 * transient network → keep SENDING + retry; permanent identity → FAILED; retry recovers.
 */
class MessengerRepositoryDmSendFailuresTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val alice = User(id = "user-alice", username = "alice", email = null)
    private val bob = User(id = "user-bob", username = "bob", email = null)
    private val chatId = "chat-dm-failures"
    private val chat = Chat(
        id = chatId,
        title = "bob",
        type = ChatType.DIRECT,
        member_ids = listOf(alice.id, bob.id),
    )
    private val chatJson = json.encodeToString(Chat.serializer(), chat)

    private val keyPrimary = ByteArray(32) { 5 }.encodeBase64()
    private val keyRotated = ByteArray(32) { 6 }.encodeBase64()
    private val bobDeviceId = "bob-device-fail-1"
    private val bobMailbox = "mailbox-bob-fail-1"

    private val capturedRelays = mutableListOf<RelayMessageRequest>()
    private var partnerIdentityKey = keyPrimary
    /** null = success; otherwise relay returns this HTTP status. */
    private var relayFailStatus: HttpStatusCode? = null
    private var relayFailMessage: String? = null
    private var relayException: Throwable? = null
    private var bobDevices: List<UserDevice> = emptyList()
    private var useEncryptFailCrypto = false

    @BeforeTest
    fun reset() {
        SessionStore.clear()
        capturedRelays.clear()
        partnerIdentityKey = keyPrimary
        relayFailStatus = null
        relayFailMessage = null
        relayException = null
        useEncryptFailCrypto = false
        bobDevices = listOf(
            UserDevice(
                device_id = bobDeviceId,
                mailbox_token = bobMailbox,
                registration_id = 42,
                identity_public_key = keyPrimary,
                device_status = "active",
            ),
        )
    }

    @AfterTest
    fun cleanup() {
        SessionStore.clear()
    }

    private fun deviceBundle(deviceId: String, accountId: String, identityKey: String): DeviceKeyBundle =
        DeviceKeyBundle(
            device_id = deviceId,
            account_id = accountId,
            registration_id = 42,
            identity_public_key = identityKey,
            signed_prekey = SignedPreKeyMaterial(1001, identityKey, identityKey, 1_700_000_000_000),
            pq_prekey = PqPreKeyMaterial(2001, identityKey, identityKey, 1_700_000_000_000),
            one_time_prekey = OneTimePreKeyMaterial(301, identityKey),
        )

    private fun mockEngine(): MockEngine = MockEngine { request ->
        when {
            request.url.encodedPath.endsWith("/api/auth/login") ||
                request.url.encodedPath.endsWith("/api/auth/refresh") -> {
                val body = request.bodyText()
                val username = Regex(""""username"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                val user = when {
                    username == "bob" -> bob
                    SessionStore.user.value?.id == bob.id -> bob
                    else -> alice
                }
                respond(
                    content = json.encodeToString(
                        AuthResponse.serializer(),
                        AuthResponse(token = "tok-${user.id}-refreshed", user = user),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            request.url.encodedPath.endsWith("/api/hello") -> respond(
                content = """{"message":"test","server_id":"dm-send-failures-test"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/api/devices") && request.method.value == "POST" -> respond(
                content = """{"device_id":"test-device-${SessionStore.user.value?.id}","prekeys_stored":1}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/api/chats") -> respond(
                content = "[$chatJson]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/bundle") -> {
                val deviceId = request.url.encodedPath.removeSuffix("/bundle").substringAfterLast("/")
                val accountId = if (deviceId == bobDeviceId) bob.id else alice.id
                respond(
                    content = json.encodeToString(
                        DeviceKeyBundle.serializer(),
                        deviceBundle(deviceId, accountId, partnerIdentityKey),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            request.url.encodedPath.contains("/users/") && request.url.encodedPath.endsWith("/devices") -> {
                val path = request.url.encodedPath
                val devices = when {
                    path.contains(bob.id) -> bobDevices.map {
                        it.copy(identity_public_key = partnerIdentityKey)
                    }
                    else -> emptyList()
                }
                respond(
                    content = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(UserDevice.serializer()),
                        devices,
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            request.url.encodedPath.endsWith("/api/messages/relay") -> {
                relayException?.let { throw it }
                val fail = relayFailStatus
                if (fail != null) {
                    respond(
                        content = """{"error":"${relayFailMessage ?: "relay fail ${fail.value}"}"}""",
                        status = fail,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                } else {
                    val relay = json.decodeFromString(RelayMessageRequest.serializer(), request.bodyText())
                    capturedRelays.add(relay)
                    respond(
                        content = json.encodeToString(
                            RelayMessageResponse.serializer(),
                            RelayMessageResponse(
                                enqueued = relay.envelopes.size,
                                envelope_ids = relay.envelopes.indices.map { "env-fail-${it + 1}" },
                            ),
                        ),
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
            request.url.encodedPath.endsWith("/api/messages/queue") -> respond(
                content = json.encodeToString(
                    com.glagolitsa.model.MessageQueueResponse.serializer(),
                    com.glagolitsa.model.MessageQueueResponse(envelopes = emptyList()),
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

    private fun repository(): MessengerRepository = MessengerRepository(
        driverFactory = DatabaseDriverFactory().companionInMemory(),
        cryptoEngineFactory = CryptoEngineFactory(),
        secureSession = SecureSessionStore(),
        api = ApiClient(
            baseUrl = "https://api.test",
            httpClient = HttpClient(mockEngine()) {
                install(ContentNegotiation) { json(json) }
            },
        ),
        cryptoEngine = if (useEncryptFailCrypto) {
            TestCryptoEngine(registrationAvailable = true, encryptAlwaysNull = true)
        } else {
            RoundtripTestCryptoEngine()
        },
    )

    private suspend fun loginAlice(repo: MessengerRepository) {
        repo.login("alice", "password123")
        repo.syncChats()
        repo.ensureDmSession(chatId, bob.id)
    }

    @Test
    fun relay_serverError_keepsSendingStatusForRetry() = runTest {
        relayFailStatus = HttpStatusCode.InternalServerError
        val repo = repository()
        loginAlice(repo)

        val sent = repo.sendMessage(chatId, "will_retry")

        assertEquals(0, capturedRelays.size)
        val stored = repo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.pending_id == sent.pending_id || it.id == sent.id } }
            .single { it.pending_id == sent.pending_id || it.id == sent.id || it.body.startsWith("will_retry") }
        assertEquals(MessageStatus.SENDING, stored.status)
        assertTrue(stored.body.contains("will_retry"))
    }

    @Test
    fun relay_forbidden_keepsSendingWithoutSuccessfulRelay() = runTest {
        // 403 is not a permanent outbox error and does not enter the 401 refresh loop.
        relayFailStatus = HttpStatusCode.Forbidden
        val repo = repository()
        loginAlice(repo)

        val sent = repo.sendMessage(chatId, "forbidden_send")
        assertEquals(0, capturedRelays.size, "forbidden must not count as successful relay")
        assertEquals(MessageStatus.SENDING, sent.status)
        assertTrue(sent.body.contains("forbidden_send"))
    }

    @Test
    fun relay_rateLimited_keepsSendingForRetry() = runTest {
        relayFailStatus = HttpStatusCode.TooManyRequests
        val repo = repository()
        loginAlice(repo)

        repo.sendMessage(chatId, "rate_limited_send")
        assertEquals(0, capturedRelays.size)
        assertEquals(MessageStatus.SENDING, messageByBody(repo, "rate_limited_send").status)
    }

    @Test
    fun relayEnvelopeLimitExceeded_marksFailedInsteadOfLeavingSending() = runTest {
        relayFailStatus = HttpStatusCode.TooManyRequests
        relayFailMessage = "relay envelope limit exceeded"
        val repo = repository()
        loginAlice(repo)

        repo.sendMessage(chatId, "quota_limited_send")

        assertEquals(0, capturedRelays.size)
        assertEquals(MessageStatus.FAILED, messageByBody(repo, "quota_limited_send").status)
    }

    @Test
    fun relay_payloadTooLarge_keepsSendingForRetry() = runTest {
        relayFailStatus = HttpStatusCode.PayloadTooLarge
        val repo = repository()
        loginAlice(repo)

        repo.sendMessage(chatId, "too_large_send")
        assertEquals(0, capturedRelays.size)
        assertEquals(MessageStatus.SENDING, messageByBody(repo, "too_large_send").status)
    }

    @Test
    fun processOutbox_afterSuccessfulSend_doesNotRelayAgain() = runTest {
        val repo = repository()
        loginAlice(repo)

        val sent = repo.sendMessage(chatId, "once_only")
        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(1, capturedRelays.size)

        capturedRelays.clear()
        repo.processOutbox()
        assertEquals(0, capturedRelays.size, "already-sent message must not re-relay")
        assertEquals(MessageStatus.SENT, messageByBody(repo, "once_only").status)
    }

    @Test
    fun untrustedPartner_marksFailedPermanentlyWithoutRelay() = runTest {
        val repo = repository()
        loginAlice(repo)

        partnerIdentityKey = keyRotated
        repo.ensureDmSession(chatId, bob.id)
        assertTrue(repo.isPartnerUntrusted(bob.id))

        val sent = repo.sendMessage(chatId, "blocked_secret")

        assertEquals(0, capturedRelays.size)
        val stored = repo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.pending_id == sent.pending_id || it.body.contains("blocked_secret") } }
            .single { it.pending_id == sent.pending_id || it.body.contains("blocked_secret") }
        assertEquals(MessageStatus.FAILED, stored.status)
    }

    @Test
    fun retryFailedMessage_afterIdentityAck_delivers() = runTest {
        val repo = repository()
        loginAlice(repo)

        partnerIdentityKey = keyRotated
        repo.ensureDmSession(chatId, bob.id)
        val failed = repo.sendMessage(chatId, "retry_after_ack")
        val failedStored = repo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.body.contains("retry_after_ack") } }
            .single { it.body.contains("retry_after_ack") }
        assertEquals(MessageStatus.FAILED, failedStored.status)

        repo.acknowledgeIdentityChange(bob.id)
        repo.retryFailedMessage(failedStored.id)

        val recovered = repo.observeMainMessages(chatId)
            .first { messages ->
                messages.any {
                    it.body.contains("retry_after_ack") && it.status == MessageStatus.SENT
                }
            }
            .single { it.body.contains("retry_after_ack") }
        assertEquals(MessageStatus.SENT, recovered.status)
        assertEquals(1, capturedRelays.size)
        assertEquals(failed.pending_id ?: failed.id, recovered.pending_id ?: recovered.id)
    }

    @Test
    fun retryAfterTransientRelayError_deliversWhenRelayRecovers() = runTest {
        relayFailStatus = HttpStatusCode.InternalServerError
        val repo = repository()
        loginAlice(repo)

        val pending = repo.sendMessage(chatId, "recoverable_send")
        val stuck = repo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.body.contains("recoverable_send") } }
            .single { it.body.contains("recoverable_send") }
        assertEquals(MessageStatus.SENDING, stuck.status)
        assertEquals(0, capturedRelays.size)

        relayFailStatus = null
        repo.retryFailedMessage(stuck.id)

        val delivered = repo.observeMainMessages(chatId)
            .first { messages ->
                messages.any {
                    it.body.contains("recoverable_send") && it.status == MessageStatus.SENT
                }
            }
            .single { it.body.contains("recoverable_send") }
        assertEquals(MessageStatus.SENT, delivered.status)
        assertEquals(1, capturedRelays.size)
        assertEquals(pending.pending_id, delivered.pending_id)
    }

    @Test
    fun timeoutThenSuccessfulRetry_marksSentAndClearsOutboxError() = runTest {
        relayException = IOException("Request timeout has expired [url=https://api.test/api/messages/relay]")
        val repo = repository()
        loginAlice(repo)

        repo.sendMessage(chatId, "timeout_then_sent")

        val stuck = messageByBody(repo, "timeout_then_sent")
        assertEquals(MessageStatus.SENDING, stuck.status)
        assertEquals(0, capturedRelays.size)
        val errors = repo.observeOutboxErrors(chatId).first()
        assertEquals(1, errors.size)
        assertTrue(
            errors.values.single().contains("Request timeout has expired"),
            "outbox error should explain why sending is stuck: $errors",
        )

        relayException = null
        repo.retryFailedMessage(stuck.id)

        val delivered = repo.observeMainMessages(chatId)
            .first { messages ->
                messages.any {
                    it.body.contains("timeout_then_sent") && it.status == MessageStatus.SENT
                }
            }
            .single { it.body.contains("timeout_then_sent") }
        assertEquals(MessageStatus.SENT, delivered.status)
        assertEquals(1, capturedRelays.size)
        assertEquals(emptyMap(), repo.observeOutboxErrors(chatId).first())
    }

    @Test
    fun partnerWithNoActiveDevices_marksRelayAcceptedAndKeepsJob() = runTest {
        bobDevices = emptyList()
        val repo = repository()
        loginAlice(repo)

        val sent = repo.sendMessage(chatId, "no_devices_partner")
        assertEquals(0, capturedRelays.size)
        assertEquals(MessageStatus.SENT, messageByBody(repo, "no_devices_partner").status)
        assertEquals(emptyMap(), repo.observeOutboxErrors(chatId).first())
        assertTrue(sent.body.contains("no_devices_partner"))
    }

    @Test
    fun partnerWithOnlyPendingDevices_marksRelayAcceptedAndKeepsJob() = runTest {
        bobDevices = listOf(
            UserDevice(
                device_id = bobDeviceId,
                mailbox_token = bobMailbox,
                registration_id = 42,
                identity_public_key = keyPrimary,
                device_status = "pending",
            ),
        )
        val repo = repository()
        loginAlice(repo)

        repo.sendMessage(chatId, "pending_only_partner")
        assertEquals(0, capturedRelays.size)
        assertEquals(MessageStatus.SENT, messageByBody(repo, "pending_only_partner").status)
        assertEquals(emptyMap(), repo.observeOutboxErrors(chatId).first())
    }

    @Test
    fun encryptFailure_producesNoEnvelopesAndKeepsSending() = runTest {
        useEncryptFailCrypto = true
        val repo = repository()
        loginAlice(repo)

        repo.sendMessage(chatId, "encrypt_fail_body")
        assertEquals(0, capturedRelays.size)
        assertEquals(MessageStatus.SENDING, messageByBody(repo, "encrypt_fail_body").status)
    }

    @Test
    fun blankBodyStillEnqueuesAndAttemptsRelay() = runTest {
        // Client does not reject empty text; outbox still runs (server/UI may guard separately).
        val repo = repository()
        loginAlice(repo)

        val sent = repo.sendMessage(chatId, "   ")
        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(1, capturedRelays.size)
    }

    @Test
    fun processOutbox_withoutSession_skipsWithoutRelay() = runTest {
        val repo = repository()
        loginAlice(repo)
        capturedRelays.clear()
        SessionStore.clear()

        repo.processOutbox()
        assertEquals(0, capturedRelays.size)
    }

    private suspend fun messageByBody(repo: MessengerRepository, bodyPart: String) =
        repo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.body.contains(bodyPart) } }
            .single { it.body.contains(bodyPart) }

    private fun HttpRequestData.bodyText(): String =
        (body as? io.ktor.http.content.TextContent)?.text ?: ""
}
