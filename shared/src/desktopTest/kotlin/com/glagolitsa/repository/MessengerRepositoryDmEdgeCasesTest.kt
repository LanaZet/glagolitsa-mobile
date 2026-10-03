// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.api.ApiException
import com.glagolitsa.crypto.CryptoEngine
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.EncryptedPayload
import com.glagolitsa.crypto.RoundtripTestCryptoEngine
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.db.GlagolitsaDatabase
import com.glagolitsa.db.LocalDataStore
import com.glagolitsa.metadata.PAYLOAD_KIND_DM_READ_RECEIPT
import com.glagolitsa.metadata.encodeSealedDmPayload
import com.glagolitsa.metadata.prependPairwiseId
import com.glagolitsa.metadata.pairwiseIdForPair
import com.glagolitsa.metadata.SealedDmPayload
import com.glagolitsa.model.AckMessageQueueRequest
import com.glagolitsa.model.AckMessageQueueResponse
import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.MESSAGE_VISIBILITY_MAIN
import com.glagolitsa.model.MessageQueueResponse
import com.glagolitsa.model.MessageStatus
import com.glagolitsa.model.OneTimePreKeyMaterial
import com.glagolitsa.model.PqPreKeyMaterial
import com.glagolitsa.model.QueuedEnvelope
import com.glagolitsa.model.RelayMessageRequest
import com.glagolitsa.model.RelayMessageResponse
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessengerRepositoryDmEdgeCasesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val alice = User(id = "user-alice", username = "alice", email = null)
    private val bob = User(id = "user-bob", username = "bob", email = null)
    private val chatId = "chat-edge-1"
    private val otherChatId = "chat-edge-2"
    private val key = ByteArray(32) { 3 }.encodeBase64()

    private var queueFetchCount = 0
    private var queueEnvelopes = listOf<QueuedEnvelope>()
    private var queueReturns429 = false
    private val ackedEnvelopeIds = mutableListOf<String>()
    private val relayRequests = mutableListOf<RelayMessageRequest>()

    @BeforeTest
    fun reset() {
        SessionStore.clear()
        queueFetchCount = 0
        queueEnvelopes = emptyList()
        queueReturns429 = false
        ackedEnvelopeIds.clear()
        relayRequests.clear()
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
                content = """{"message":"test","server_id":"repository-edge-test"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/api/devices") && request.method.value == "POST" -> respond(
                content = """{"device_id":"test-device-user-alice","prekeys_stored":1}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/api/chats") -> respond(
                content = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(Chat.serializer()),
                    listOf(
                        Chat(chatId, "bob", ChatType.DIRECT, listOf(alice.id, bob.id)),
                        Chat(otherChatId, "other", ChatType.DIRECT, listOf(alice.id, "user-other")),
                    ),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/api/messages/queue/ack") -> {
                val ack = json.decodeFromString(AckMessageQueueRequest.serializer(), request.bodyText())
                ackedEnvelopeIds.addAll(ack.envelope_ids)
                respond(
                    content = json.encodeToString(
                        AckMessageQueueResponse.serializer(),
                        AckMessageQueueResponse(deleted = ack.envelope_ids.size),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            request.url.encodedPath.endsWith("/api/messages/relay") -> {
                val relay = json.decodeFromString(RelayMessageRequest.serializer(), request.bodyText())
                relayRequests.add(relay)
                respond(
                    content = json.encodeToString(
                        RelayMessageResponse.serializer(),
                        RelayMessageResponse(
                            enqueued = relay.envelopes.size,
                            envelope_ids = relay.envelopes.mapIndexed { index, _ -> "relay-$index" },
                        ),
                    ),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            request.url.encodedPath.endsWith("/api/messages/queue") -> {
                queueFetchCount++
                if (queueReturns429) {
                    respond(
                        content = """{"error":"rate limit exceeded"}""",
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                } else {
                    respond(
                        content = json.encodeToString(
                            MessageQueueResponse.serializer(),
                            MessageQueueResponse(envelopes = queueEnvelopes),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
            request.url.encodedPath.contains("/users/") && request.url.encodedPath.endsWith("/devices") -> respond(
                content = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(UserDevice.serializer()),
                    listOf(
                        UserDevice("bob-dev", "mb", 42, key, "active"),
                    ),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            request.url.encodedPath.endsWith("/bundle") -> respond(
                content = json.encodeToString(
                    DeviceKeyBundle.serializer(),
                    DeviceKeyBundle(
                        device_id = "bob-dev",
                        account_id = bob.id,
                        registration_id = 42,
                        identity_public_key = key,
                        signed_prekey = SignedPreKeyMaterial(1, key, key, 1L),
                        pq_prekey = PqPreKeyMaterial(2, key, key, 1L),
                        one_time_prekey = OneTimePreKeyMaterial(3, key),
                    ),
                ),
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

    private class ResetTrackingCryptoEngine(
        private val delegate: CryptoEngine = RoundtripTestCryptoEngine(),
    ) : CryptoEngine by delegate {
        val events = mutableListOf<String>()

        override suspend fun resetSession(
            accountId: String,
            remoteAccountId: String,
            remoteDeviceId: String,
        ) {
            events.add("reset:$remoteDeviceId")
            delegate.resetSession(accountId, remoteAccountId, remoteDeviceId)
        }

        override suspend fun encryptMessage(
            accountId: String,
            remoteAccountId: String,
            remoteDeviceId: String,
            plaintext: ByteArray,
        ): EncryptedPayload? {
            events.add("encrypt:$remoteDeviceId")
            return delegate.encryptMessage(accountId, remoteAccountId, remoteDeviceId, plaintext)
        }
    }

    private suspend fun loginAndSync(repo: MessengerRepository) {
        repo.login("alice", "password123")
        repo.syncChats()
        repo.ensureDmSession(chatId, bob.id)
        queueFetchCount = 0
    }

    private fun sealedEnvelope(
        body: String,
        targetChatId: String = chatId,
        envelopeId: String = "env-edge",
    ): QueuedEnvelope {
        val pairwise = pairwiseIdForPair(alice.id, bob.id)
        val sealed = encodeSealedDmPayload(
            SealedDmPayload(
                pairwise_id = pairwise,
                sender_account_id = bob.id,
                sender_device_id = "bob-dev",
                chat_id = targetChatId,
                body = body,
            ),
        )
        val crypto = RoundtripTestCryptoEngine()
        val encrypted = runBlocking {
            crypto.encryptMessage(alice.id, bob.id, "bob-dev", sealed)
        }
        val ciphertext = prependPairwiseId(pairwise, encrypted.ciphertext).encodeBase64()
        return QueuedEnvelope(
            envelope_id = envelopeId,
            mailbox_token = "mb",
            envelope_type = encrypted.envelopeType,
            ciphertext = ciphertext,
            size_bucket = ciphertext.length,
            created_at = "2026-07-13T12:00:00Z",
        )
    }

    private fun sealedReadReceiptEnvelope(
        messageIds: List<String>,
        targetChatId: String = chatId,
        envelopeId: String = "env-read",
    ): QueuedEnvelope {
        val pairwise = pairwiseIdForPair(alice.id, bob.id)
        val sealed = encodeSealedDmPayload(
            SealedDmPayload(
                pairwise_id = pairwise,
                sender_account_id = bob.id,
                sender_device_id = "bob-dev",
                chat_id = targetChatId,
                kind = PAYLOAD_KIND_DM_READ_RECEIPT,
                read_message_ids = messageIds,
            ),
        )
        val crypto = RoundtripTestCryptoEngine()
        val encrypted = runBlocking {
            crypto.encryptMessage(alice.id, bob.id, "bob-dev", sealed)
        }
        val ciphertext = prependPairwiseId(pairwise, encrypted.ciphertext).encodeBase64()
        return QueuedEnvelope(
            envelope_id = envelopeId,
            mailbox_token = "mb",
            envelope_type = encrypted.envelopeType,
            ciphertext = ciphertext,
            size_bucket = ciphertext.length,
            created_at = "2026-07-13T12:01:00Z",
        )
    }

    private fun localStore(repo: MessengerRepository): LocalDataStore {
        val direct = MessengerRepository::class.java.declaredFields.firstOrNull { it.name == "local" }
        if (direct != null) {
            direct.isAccessible = true
            return direct.get(repo) as LocalDataStore
        }
        val delegate = MessengerRepository::class.java.getDeclaredField("local\$delegate")
        delegate.isAccessible = true
        return (delegate.get(repo) as Lazy<*>).value as LocalDataStore
    }

    private fun database(store: LocalDataStore): GlagolitsaDatabase {
        val activeStoreField = LocalDataStore::class.java.getDeclaredField("activeStore")
        activeStoreField.isAccessible = true
        val activeStore = activeStoreField.get(store) as kotlinx.coroutines.flow.MutableStateFlow<*>
        val handle = activeStore.value ?: error("LocalDataStore active handle is null")
        val databaseField = handle.javaClass.getDeclaredField("database")
        databaseField.isAccessible = true
        return databaseField.get(handle) as GlagolitsaDatabase
    }

    private fun insertLegacyDuplicateMessage(
        store: LocalDataStore,
        id: String,
        pendingId: String,
        createdAt: String,
    ) {
        database(store).glagolitsaQueries.insertMessage(
            id = id,
            chat_id = chatId,
            sender_id = alice.id,
            body = "legacy pending",
            pending_id = pendingId,
            status = MessageStatus.SENT,
            created_at = createdAt,
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
    }

    @Test
    fun processMessageQueue_skipsEnvelopeWithoutPairwisePrefix() = runBlocking {
        val repo = repository()
        loginAndSync(repo)
        queueEnvelopes = listOf(
            QueuedEnvelope(
                envelope_id = "bad-env",
                mailbox_token = "mb",
                envelope_type = 3,
                ciphertext = byteArrayOf(1, 2, 3).encodeBase64(),
                size_bucket = 4,
            ),
        )
        repo.processMessageQueue(chatId, force = true)
        val messages = repo.observeMainMessages(chatId).first { true }
        assertTrue(messages.isEmpty())
        assertTrue(ackedEnvelopeIds.isEmpty())
    }

    @Test
    fun processMessageQueue_filtersByActiveChatId() = runBlocking {
        val repo = repository()
        loginAndSync(repo)
        queueEnvelopes = listOf(
            sealedEnvelope("for-bob", chatId, "env-1"),
            sealedEnvelope("for-other", otherChatId, "env-2"),
        )
        repo.processMessageQueue(chatId, force = true)
        val messages = repo.observeMainMessages(chatId).first { it.isNotEmpty() }
        assertEquals(1, messages.size)
        assertEquals("for-bob", messages.single().body)
        assertEquals(listOf("env-1"), ackedEnvelopeIds)
    }

    @Test
    fun processMessageQueue_throttlesRapidPolls() = runBlocking {
        val repo = repository()
        loginAndSync(repo)
        repo.processMessageQueue(chatId, force = true)
        repo.processMessageQueue(chatId, force = false)
        assertEquals(1, queueFetchCount)
    }

    @Test
    fun sendMessage_devLocalResetsPartnerSessionBeforeEncryptingDm() = runBlocking {
        val crypto = ResetTrackingCryptoEngine()
        val repo = repository(crypto)
        loginAndSync(repo)
        crypto.events.clear()

        val sent = repo.sendMessage(chatId, "fresh-prekey-after-reinstall")

        assertEquals(MessageStatus.SENT, sent.status)
        val resetIndex = crypto.events.indexOf("reset:bob-dev")
        val encryptIndex = crypto.events.indexOf("encrypt:bob-dev")
        assertTrue(resetIndex >= 0, "partner session must be reset before dev/local DM encrypt")
        assertTrue(encryptIndex > resetIndex, "partner encrypt must happen after reset")
        assertTrue(relayRequests.isNotEmpty())
        assertTrue(relayRequests.last().envelopes.isNotEmpty())
    }

    @Test
    fun processMessageQueue_on429_doesNotCrash() = runBlocking {
        queueReturns429 = true
        val repo = repository()
        loginAndSync(repo)
        repo.processMessageQueue(chatId, force = true)
        assertEquals(1, queueFetchCount)
    }

    @Test
    fun drainPendingSync_drainsQueueWhenChatIsOpen() = runBlocking {
        val repo = repository()
        loginAndSync(repo)
        queueEnvelopes = listOf(sealedEnvelope("while-open", chatId, "env-open"))
        repo.markChatOpened(chatId)
        repo.drainPendingSync()
        val messages = repo.observeMainMessages(chatId).first { it.isNotEmpty() }
        assertEquals(1, queueFetchCount)
        assertEquals("while-open", messages.single().body)
        assertEquals(listOf("env-open"), ackedEnvelopeIds)
    }

    @Test
    fun processMessageQueue_duplicateEnvelopeIdDoesNotCrash() = runBlocking {
        val env = sealedEnvelope("dup-body", chatId, "env-dup")
        queueEnvelopes = listOf(env, env.copy())
        val repo = repository()
        loginAndSync(repo)
        repo.processMessageQueue(chatId, force = true)
        val messages = repo.observeMainMessages(chatId).first { it.isNotEmpty() }
        assertTrue(messages.any { it.body == "dup-body" })
    }

    @Test
    fun processMessageQueue_readReceiptUpdatesAllRowsWithSamePendingId() = runBlocking {
        val repo = repository()
        loginAndSync(repo)
        val local = localStore(repo)
        val pendingId = "pending-read-edge"
        insertLegacyDuplicateMessage(
            store = local,
            id = pendingId,
            pendingId = pendingId,
            createdAt = "2026-07-13T10:00:00Z",
        )
        insertLegacyDuplicateMessage(
            store = local,
            id = "server-read-edge",
            pendingId = pendingId,
            createdAt = "2026-07-13T10:01:00Z",
        )

        queueEnvelopes = listOf(sealedReadReceiptEnvelope(listOf(pendingId), envelopeId = "env-read"))
        repo.processMessageQueue(chatId, force = true)

        val messages = repo.observeMainMessages(chatId).first { it.size == 2 }
        assertTrue(messages.all { it.status == MessageStatus.READ })
        assertEquals(listOf("env-read"), ackedEnvelopeIds)
    }

    @Test
    fun processMessageQueue_forceTrueStillWorksAfterThrottle() = runBlocking {
        val repo = repository()
        loginAndSync(repo)
        repo.processMessageQueue(chatId, force = true)
        repo.processMessageQueue(chatId, force = true)
        assertEquals(2, queueFetchCount)
    }

    private fun HttpRequestData.bodyText(): String =
        (body as? io.ktor.http.content.TextContent)?.text ?: ""
}
