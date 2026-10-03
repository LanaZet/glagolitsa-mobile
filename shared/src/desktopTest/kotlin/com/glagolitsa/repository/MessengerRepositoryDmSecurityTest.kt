// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.RoundtripTestCryptoEngine
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.metadata.decodeSealedDmPayload
import com.glagolitsa.metadata.extractPairwiseId
import com.glagolitsa.model.AckMessageQueueRequest
import com.glagolitsa.model.AckMessageQueueResponse
import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.MESSAGE_VISIBILITY_MAIN
import com.glagolitsa.model.MESSAGE_VISIBILITY_THREAD_ONLY
import com.glagolitsa.model.MessageQueueResponse
import com.glagolitsa.model.MessageRelationDraft
import com.glagolitsa.model.MessageDeliverySemantics
import com.glagolitsa.model.MessageStatus
import com.glagolitsa.model.isDelivered
import com.glagolitsa.model.isRead
import com.glagolitsa.model.OneTimePreKeyMaterial
import com.glagolitsa.model.PqPreKeyMaterial
import com.glagolitsa.model.QueuedEnvelope
import com.glagolitsa.model.RelayMessageRequest
import com.glagolitsa.model.RelayMessageResponse
import com.glagolitsa.model.RotateMailboxResponse
import com.glagolitsa.model.SignedPreKeyMaterial
import com.glagolitsa.model.User
import com.glagolitsa.model.UserDevice
import kotlin.test.assertNull
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DM send/receive security: relay ciphertext, identity gate, queue decrypt.
 */
class MessengerRepositoryDmSecurityTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val alice = User(id = "user-alice", username = "alice", email = null)
    private val bob = User(id = "user-bob", username = "bob", email = null)
    private val chatId = "chat-dm-security"
    private val chat = Chat(
        id = chatId,
        title = "bob",
        type = ChatType.DIRECT,
        member_ids = listOf(alice.id, bob.id),
    )
    private val chatJson = json.encodeToString(Chat.serializer(), chat)

    private val keyPrimary = ByteArray(32) { 7 }.encodeBase64()
    private val keyRotated = ByteArray(32) { 8 }.encodeBase64()
    private val aliceDeviceId = "alice-device-1"
    private val aliceMailbox = "mailbox-alice-1"
    private val bobDeviceId = "bob-device-1"
    private val bobMailbox = "mailbox-bob-1"

    private val capturedRelays = mutableListOf<RelayMessageRequest>()
    private val relayQueue = mutableListOf<QueuedEnvelope>()
    private var partnerIdentityKey = keyPrimary

    @BeforeTest
    fun reset() {
        SessionStore.clear()
        capturedRelays.clear()
        relayQueue.clear()
        partnerIdentityKey = keyPrimary
    }

    @AfterTest
    fun cleanup() {
        SessionStore.clear()
    }

    private fun deviceBundle(deviceId: String, accountId: String, identityKey: String): DeviceKeyBundle {
        return DeviceKeyBundle(
            device_id = deviceId,
            account_id = accountId,
            registration_id = 42,
            identity_public_key = identityKey,
            signed_prekey = SignedPreKeyMaterial(1001, identityKey, identityKey, 1_700_000_000_000),
            pq_prekey = PqPreKeyMaterial(2001, identityKey, identityKey, 1_700_000_000_000),
            one_time_prekey = OneTimePreKeyMaterial(301, identityKey),
        )
    }

    private fun userDevices(userId: String): List<UserDevice> = listOf(
        UserDevice(
            device_id = if (userId == bob.id) bobDeviceId else aliceDeviceId,
            mailbox_token = if (userId == bob.id) bobMailbox else aliceMailbox,
            registration_id = 42,
            identity_public_key = partnerIdentityKey,
            device_status = "active",
        ),
    )

    private fun mockEngine(): MockEngine = MockEngine { request ->
        when {
            request.url.encodedPath.endsWith("/api/auth/login") -> {
                val body = request.bodyText()
                val username = Regex(""""username"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                val user = when (username) {
                    "alice" -> alice
                    "bob" -> bob
                    else -> alice
                }
                respond(
                    content = json.encodeToString(
                        AuthResponse.serializer(),
                        AuthResponse(token = "tok-${user.id}", user = user),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            request.url.encodedPath.endsWith("/api/hello") -> respond(
                content = """{"message":"test","server_id":"dm-security-test"}""",
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
                val userId = request.url.encodedPath
                    .substringAfter("/users/")
                    .substringBefore("/devices")
                respond(
                    content = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(UserDevice.serializer()),
                        userDevices(userId),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            request.url.encodedPath.endsWith("/api/messages/relay") -> {
                val relay = json.decodeFromString(RelayMessageRequest.serializer(), request.bodyText())
                capturedRelays.add(relay)
                relay.envelopes.forEach { envelope ->
                    relayQueue.add(
                        QueuedEnvelope(
                            envelope_id = "env-${relayQueue.size + 1}",
                            mailbox_token = envelope.mailbox_token,
                            envelope_type = envelope.envelope_type,
                            ciphertext = envelope.ciphertext,
                            size_bucket = envelope.ciphertext.length,
                            created_at = "2026-07-13T12:00:00Z",
                        ),
                    )
                }
                respond(
                    content = json.encodeToString(
                        RelayMessageResponse.serializer(),
                        RelayMessageResponse(
                            enqueued = relay.envelopes.size,
                            envelope_ids = relay.envelopes.indices.map { "env-${it + 1}" },
                        ),
                    ),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            request.url.encodedPath.endsWith("/api/messages/queue") -> {
                val mailbox = if (SessionStore.user.value?.id == bob.id) bobMailbox else aliceMailbox
                respond(
                    content = json.encodeToString(
                        MessageQueueResponse.serializer(),
                        MessageQueueResponse(envelopes = relayQueue.filter { it.mailbox_token == mailbox }),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            request.url.encodedPath.endsWith("/api/messages/queue/ack") -> {
                val ack = json.decodeFromString(AckMessageQueueRequest.serializer(), request.bodyText())
                val before = relayQueue.size
                relayQueue.removeAll { it.envelope_id in ack.envelope_ids }
                respond(
                    content = json.encodeToString(
                        AckMessageQueueResponse.serializer(),
                        AckMessageQueueResponse(deleted = before - relayQueue.size),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            request.url.encodedPath.endsWith("/api/mailbox/rotate") -> respond(
                content = json.encodeToString(
                    RotateMailboxResponse.serializer(),
                    RotateMailboxResponse(
                        device_id = request.headers["X-Device-Id"] ?: "dev",
                        mailbox_token = if (SessionStore.user.value?.id == bob.id) bobMailbox else aliceMailbox,
                    ),
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

    private fun repository(engine: MockEngine = mockEngine()): MessengerRepository = MessengerRepository(
        driverFactory = DatabaseDriverFactory().companionInMemory(),
        cryptoEngineFactory = CryptoEngineFactory(),
        secureSession = SecureSessionStore(),
        api = ApiClient(
            baseUrl = "https://api.test",
            httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(json) }
            },
        ),
        cryptoEngine = RoundtripTestCryptoEngine(),
    )

    private suspend fun loginAlice(repo: MessengerRepository) {
        repo.login("alice", "password123")
        repo.syncChats()
        repo.ensureDmSession(chatId, bob.id)
    }

    private suspend fun loginBob(repo: MessengerRepository) {
        repo.login("bob", "password123")
        repo.syncChats()
        repo.ensureDmSession(chatId, alice.id)
    }

    private suspend fun loginBobWithoutChatHydration(repo: MessengerRepository) {
        repo.login("bob", "password123")
    }

    @Test
    fun dmSend_relaysEncryptedEnvelopesWithoutPlaintextBody() = runTest {
        val repo = repository()
        loginAlice(repo)

        val secret = "секретное_dm_сообщение"
        val sent = repo.sendMessage(chatId, secret)

        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(1, capturedRelays.size)
        val relay = capturedRelays.single()
        assertTrue(relay.envelopes.isNotEmpty())
        val envelope = relay.envelopes.first()
        assertEquals(3, envelope.envelope_type)
        assertFalse(envelope.ciphertext.contains(secret))
        assertFalse(requestBodyContainsPlaintext(relay, secret))

        val raw = envelope.ciphertext.decodeBase64()
        val extracted = extractPairwiseId(raw)
        assertNotNull(extracted)
        val sealed = decodeSealedDmPayload(
            RoundtripTestCryptoEngine().decryptMessage(
                accountId = alice.id,
                remoteAccountId = bob.id,
                remoteDeviceUuid = bobDeviceId,
                envelopeType = envelope.envelope_type,
                ciphertext = extracted.second,
            )!!,
        )
        assertEquals(secret, sealed?.body)
    }

    @Test
    fun dmSend_deliversToRelayWhileRecipientNeverPollsQueue() = runTest {
        val repo = repository()
        loginAlice(repo)

        val sent = repo.sendMessage(chatId, "offline_recipient_ok")

        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(1, capturedRelays.size)
        assertEquals(1, relayQueue.size, "message must sit in bob mailbox without bob being online")
    }

    /**
     * Contract: single ✓ (SENT) is set when relay accepts envelopes.
     * Bob decrypting the queue must NOT by itself upgrade alice to READ.
     * Only markVisible → dm_read_receipt → alice processQueue yields double ✓.
     */
    @Test
    fun dmSend_singleCheckIsRelayAccept_notPeerDecrypt() = runTest {
        val aliceRepo = repository()
        loginAlice(aliceRepo)
        val sent = aliceRepo.sendMessage(chatId, "relay_accept_not_decrypt")

        // Immediately after send: relay accepted → single ✓ semantics.
        assertEquals(
            MessageDeliverySemantics.AFTER_RELAY_ACCEPT_STATUS,
            sent.status,
            "performDmSend must store SENT on relay 201, not wait for peer decrypt",
        )
        assertTrue(sent.isDelivered())
        assertFalse(sent.isRead())
        assertTrue(MessageDeliverySemantics.showsSingleCheck(sent.status))
        assertFalse(MessageDeliverySemantics.showsDoubleCheck(sent.status))
        assertEquals(1, relayQueue.size, "envelope still in bob mailbox (bob has not polled)")

        // Bob comes online and decrypts — alice status must stay SENT (no read receipt yet).
        SessionStore.clear()
        val bobRepo = repository()
        loginBob(bobRepo)
        bobRepo.processMessageQueue(chatId, force = true)
        val bobHasPlaintext = bobRepo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.body == "relay_accept_not_decrypt" } }
            .any { it.body == "relay_accept_not_decrypt" }
        assertTrue(bobHasPlaintext, "bob must decrypt for this test to be meaningful")

        SessionStore.clear()
        loginAlice(aliceRepo)
        val afterBobDecrypt = aliceRepo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.pending_id == sent.pending_id || it.id == sent.id } }
            .first { it.pending_id == sent.pending_id || it.id == sent.id }
        assertEquals(MessageStatus.SENT, afterBobDecrypt.status)
        assertTrue(afterBobDecrypt.isDelivered())
        assertFalse(afterBobDecrypt.isRead())

        // Read receipt is the only path to double ✓.
        SessionStore.clear()
        loginBob(bobRepo)
        val bobMessages = bobRepo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.body == "relay_accept_not_decrypt" } }
        bobRepo.markChatOpened(chatId)
        bobRepo.markVisibleMessagesRead(chatId, bobMessages)

        SessionStore.clear()
        loginAlice(aliceRepo)
        aliceRepo.processMessageQueue(chatId, force = true)
        val afterRead = aliceRepo.observeMainMessages(chatId)
            .first { messages ->
                messages.any {
                    (it.pending_id == sent.pending_id || it.id == sent.id) &&
                        it.status == MessageStatus.READ
                }
            }
            .first { it.pending_id == sent.pending_id || it.id == sent.id }
        assertEquals(MessageStatus.READ, afterRead.status)
        assertTrue(MessageDeliverySemantics.showsDoubleCheck(afterRead.status))
    }

    @Test
    fun dmSend_replacesOptimisticSendingStatusWithDelivered() = runTest {
        val repo = repository()
        loginAlice(repo)

        val sent = repo.sendMessage(chatId, "status_should_flip")
        val stored = repo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.pending_id == sent.pending_id } }
            .single { it.pending_id == sent.pending_id }

        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(MessageStatus.SENT, stored.status)
    }

    @Test
    fun dmSend_afterRestartRestoresStoredTokenAndFlushesOutbox() = runTest {
        val repo = repository()
        loginAlice(repo)
        capturedRelays.clear()
        SessionStore.clearAccessToken()

        val sent = repo.sendMessage(chatId, "after_restart")

        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(1, capturedRelays.size)
        assertNotNull(SessionStore.token.value)
    }

    @Test
    fun dmSend_blockedUntilIdentityChangeAcknowledged() = runTest {
        val repo = repository()
        loginAlice(repo)

        partnerIdentityKey = keyRotated
        repo.ensureDmSession(chatId, bob.id)
        assertTrue(repo.isPartnerUntrusted(bob.id))

        repo.sendMessage(chatId, "после смены ключа")
        assertEquals(0, capturedRelays.size, "untrusted partner must not relay plaintext")

        repo.acknowledgeIdentityChange(bob.id)
        assertFalse(repo.isPartnerUntrusted(bob.id))

        val sent = repo.sendMessage(chatId, "после принятия ключа")
        assertEquals("после принятия ключа", sent.body)
        assertEquals(1, capturedRelays.size)
    }

    @Test
    fun processMessageQueue_decryptsRelayAndStoresReadableMessage() = runBlocking {
        val aliceRepo = repository()
        loginAlice(aliceRepo)
        aliceRepo.sendMessage(chatId, "roundtrip_secret")

        SessionStore.clear()
        val bobRepo = repository()
        loginBob(bobRepo)
        bobRepo.processMessageQueue(chatId)

        val collected = bobRepo.observeMainMessages(chatId).first { it.isNotEmpty() }
        assertTrue(collected.any { it.body == "roundtrip_secret" })
        assertFalse(collected.any { it.body.contains("🔒 Зашифрованное сообщение") })
        assertTrue(relayQueue.isEmpty(), "processed relay envelope must be acked without UI")
    }

    @Test
    fun processMessageQueue_hydratesNewDmBeforeDecrypt() = runBlocking {
        val aliceRepo = repository()
        loginAlice(aliceRepo)
        aliceRepo.sendMessage(chatId, "new_dm_before_bob_opens")

        SessionStore.clear()
        val bobRepo = repository()
        loginBobWithoutChatHydration(bobRepo)
        bobRepo.processMessageQueue(force = true)

        val collected = bobRepo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.body == "new_dm_before_bob_opens" } }
        assertTrue(collected.any { it.body == "new_dm_before_bob_opens" })
        assertTrue(relayQueue.isEmpty(), "new DM envelope should decrypt and ack after chat hydration")
    }

    @Test
    fun dmSend_usesStableClientMessageIdAcrossHistories() = runBlocking {
        val aliceRepo = repository()
        loginAlice(aliceRepo)
        val sent = aliceRepo.sendMessage(chatId, "same_history_id")

        SessionStore.clear()
        val bobRepo = repository()
        loginBob(bobRepo)
        bobRepo.processMessageQueue(chatId, force = true)

        val received = bobRepo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.body == "same_history_id" } }
            .single { it.body == "same_history_id" }

        assertEquals(sent.pending_id, sent.id)
        assertEquals(sent.id, received.id)
        assertEquals(sent.created_at, received.created_at)
    }

    @Test
    fun dmReply_reachesRecipientWithReplyToFromSealedPayload() = runBlocking {
        val aliceRepo = repository()
        loginAlice(aliceRepo)
        val parent = aliceRepo.sendMessage(chatId, "parent_for_reply")
        capturedRelays.clear()
        relayQueue.clear()

        aliceRepo.sendMessage(
            chatId = chatId,
            body = "reply_body",
            relation = MessageRelationDraft(
                replyToMessageId = parent.id,
                visibility = MESSAGE_VISIBILITY_MAIN,
            ),
        )

        val sealed = decodeCapturedRelaySealed(alice.id, bob.id, bobDeviceId)
        assertEquals(parent.id, sealed?.reply_to_message_id)
        assertEquals(parent.sender_id, sealed?.reply_preview_sender_id)
        assertEquals(parent.body, sealed?.reply_preview_body)
        assertEquals(MESSAGE_VISIBILITY_MAIN, sealed?.visibility)
        assertNull(sealed?.thread_root_id)

        SessionStore.clear()
        val bobRepo = repository()
        loginBob(bobRepo)
        bobRepo.processMessageQueue(chatId, force = true)

        val bobMessages = bobRepo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.body == "reply_body" } }
        val reply = bobMessages.single { it.body == "reply_body" }
        assertEquals(parent.id, reply.reply_to_message_id)
        assertEquals(parent.sender_id, reply.reply_preview_sender_id)
        assertEquals(parent.body, reply.reply_preview_body)
        assertEquals(MESSAGE_VISIBILITY_MAIN, reply.visibility)
        assertNull(reply.thread_root_id)
    }

    @Test
    fun dmThreadDraft_normalizesToMainReplyOnWireAndReceive() = runBlocking {
        val aliceRepo = repository()
        loginAlice(aliceRepo)
        val parent = aliceRepo.sendMessage(chatId, "dm_parent")
        capturedRelays.clear()
        relayQueue.clear()

        aliceRepo.sendMessage(
            chatId = chatId,
            body = "normalized_reply",
            relation = MessageRelationDraft(
                replyToMessageId = parent.id,
                threadRootId = parent.id,
                threadParentId = parent.id,
                visibility = MESSAGE_VISIBILITY_THREAD_ONLY,
            ),
        )

        val sealed = decodeCapturedRelaySealed(alice.id, bob.id, bobDeviceId)
        assertEquals(parent.id, sealed?.reply_to_message_id)
        assertEquals(MESSAGE_VISIBILITY_MAIN, sealed?.visibility)
        assertNull(sealed?.thread_root_id)
        assertNull(sealed?.thread_parent_id)

        SessionStore.clear()
        val bobRepo = repository()
        loginBob(bobRepo)
        bobRepo.processMessageQueue(chatId, force = true)

        val reply = bobRepo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.body == "normalized_reply" } }
            .single { it.body == "normalized_reply" }
        assertEquals(parent.id, reply.reply_to_message_id)
        assertEquals(MESSAGE_VISIBILITY_MAIN, reply.visibility)
        assertNull(reply.thread_root_id)
        assertTrue(
            bobRepo.observeThreadMessages(chatId, parent.id).first().none { it.body == "normalized_reply" },
            "DM must not land as thread_only",
        )
    }

    @Test
    fun readReceiptMarksSenderMessageReadAfterRecipientOpensChat() = runBlocking {
        val aliceRepo = repository()
        loginAlice(aliceRepo)
        val sent = aliceRepo.sendMessage(chatId, "read_receipt_secret")

        SessionStore.clear()
        val bobRepo = repository()
        loginBob(bobRepo)
        bobRepo.processMessageQueue(chatId, force = true)
        val bobMessages = bobRepo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.body == "read_receipt_secret" } }
        relayQueue.clear()
        bobRepo.markChatOpened(chatId)
        bobRepo.markVisibleMessagesRead(chatId, bobMessages)

        assertTrue(relayQueue.isNotEmpty(), "read receipt must be queued back to sender")
        assertTrue(
            relayQueue.all { it.mailbox_token == aliceMailbox },
            "read receipt relay envelopes must target alice devices: ${relayQueue.map { it.mailbox_token }}",
        )

        SessionStore.clear()
        loginAlice(aliceRepo)
        val afterLoginReceipt = aliceRepo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.id == sent.id } }
            .single { it.id == sent.id }
        assertEquals(MessageStatus.READ, afterLoginReceipt.status)

        aliceRepo.processMessageQueue(chatId, force = true)
        val aliceMessages = aliceRepo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.id == sent.id && it.status == MessageStatus.READ } }

        assertEquals(MessageStatus.READ, aliceMessages.single { it.id == sent.id }.status)
        assertTrue(relayQueue.isEmpty(), "read receipt relay envelope must be acked")
    }

    private fun requestBodyContainsPlaintext(relay: RelayMessageRequest, plaintext: String): Boolean {
        val serialized = json.encodeToString(RelayMessageRequest.serializer(), relay)
        return serialized.contains(plaintext)
    }

    private suspend fun decodeCapturedRelaySealed(
        senderAccountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
    ) = run {
        val envelope = capturedRelays.single().envelopes.first()
        val extracted = extractPairwiseId(envelope.ciphertext.decodeBase64())
        assertNotNull(extracted)
        decodeSealedDmPayload(
            RoundtripTestCryptoEngine().decryptMessage(
                accountId = senderAccountId,
                remoteAccountId = remoteAccountId,
                remoteDeviceUuid = remoteDeviceId,
                envelopeType = envelope.envelope_type,
                ciphertext = extracted.second,
            )!!,
        )
    }

    private fun HttpRequestData.bodyText(): String =
        (body as? io.ktor.http.content.TextContent)?.text ?: ""
}
