// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.crypto.CryptoEngine
import com.glagolitsa.crypto.CryptoSelfTestResult
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.DeviceIdentity
import com.glagolitsa.crypto.EncryptedPayload
import com.glagolitsa.crypto.SafetyNumberInfo
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.metadata.pairwiseIdForPair
import com.glagolitsa.metadata.prependPairwiseId
import com.glagolitsa.model.AckMessageQueueRequest
import com.glagolitsa.model.AckMessageQueueResponse
import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.MessageQueueResponse
import com.glagolitsa.model.MessageStatus
import com.glagolitsa.model.OneTimePreKeyMaterial
import com.glagolitsa.model.PqPreKeyMaterial
import com.glagolitsa.model.QueuedEnvelope
import com.glagolitsa.model.RegisterDeviceRequest
import com.glagolitsa.model.RegisterDeviceResponse
import com.glagolitsa.model.RelayMessageRequest
import com.glagolitsa.model.RelayMessageResponse
import com.glagolitsa.model.RotateMailboxResponse
import com.glagolitsa.model.RotateSignedPreKeyRequest
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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Signal-style DM acceptance: two isolated clients, relay between them, and success only
 * after the receiver decrypts and the UI-facing message stream exposes plaintext.
 */
class MessengerRepositoryDmAcceptanceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val secureSession = SecureSessionStore()
    private val server = MarcoPoloRelayServer(json)

    @BeforeTest
    fun reset() {
        SessionStore.clear()
        server.reset()
    }

    @AfterTest
    fun cleanup() {
        SessionStore.clear()
    }

    @Test
    fun marcoToPolo_acceptanceFetchedQueueDecryptedStoredAndVisibleInUiStream() = runTest {
        val marco = client(MarcoPoloRelayServer.MARCO, installLabel = "marco-phone")
        val polo = client(MarcoPoloRelayServer.POLO, installLabel = "polo-phone")

        marco.loginAndPreparePeer(MarcoPoloRelayServer.POLO)
        polo.loginAndPreparePeer(MarcoPoloRelayServer.MARCO)
        server.clearTransportObservations()

        val body = "Marco->Polo acceptance ${System.nanoTime()}"
        assertDmDelivered(sender = marco, receiver = polo, body = body)
    }

    @Test
    fun poloToMarco_acceptanceWorksInReverseDirection() = runTest {
        val marco = client(MarcoPoloRelayServer.MARCO, installLabel = "marco-phone")
        val polo = client(MarcoPoloRelayServer.POLO, installLabel = "polo-phone")

        marco.loginAndPreparePeer(MarcoPoloRelayServer.POLO)
        polo.loginAndPreparePeer(MarcoPoloRelayServer.MARCO)
        server.clearTransportObservations()

        val body = "Polo->Marco acceptance ${System.nanoTime()}"
        assertDmDelivered(sender = polo, receiver = marco, body = body)
    }

    @Test
    fun marcoToPolo_afterPoloSignedPrekeyReregistrationStillDecrypts() = runTest {
        val marco = client(MarcoPoloRelayServer.MARCO, installLabel = "marco-phone")
        val polo = client(MarcoPoloRelayServer.POLO, installLabel = "polo-phone")

        marco.loginAndPreparePeer(MarcoPoloRelayServer.POLO)
        polo.loginAndPreparePeer(MarcoPoloRelayServer.MARCO)
        assertDmDelivered(sender = marco, receiver = polo, body = "warmup ${System.nanoTime()}")

        server.clearTransportObservations()
        polo.rotateSignedPrekeyAndRegisterAgain(peer = MarcoPoloRelayServer.MARCO)

        val body = "after Polo signed prekey rotation ${System.nanoTime()}"
        assertDmDelivered(sender = marco, receiver = polo, body = body)
        assertTrue(
            marco.crypto.resetEvents.any { it.remoteAccountId == MarcoPoloRelayServer.POLO.id },
            "Marco must reset the stale partner session before encrypting to Polo's refreshed prekey",
        )
    }

    @Test
    fun dirtyQueue_doesNotBlockLaterDecryptableMarcoToPoloMessage() = runTest {
        val marco = client(MarcoPoloRelayServer.MARCO, installLabel = "marco-phone")
        val polo = client(MarcoPoloRelayServer.POLO, installLabel = "polo-phone")

        marco.loginAndPreparePeer(MarcoPoloRelayServer.POLO)
        polo.loginAndPreparePeer(MarcoPoloRelayServer.MARCO)
        val badEnvelopeId = "env-dirty-stale"
        val pairwise = pairwiseIdForPair(MarcoPoloRelayServer.MARCO.id, MarcoPoloRelayServer.POLO.id)
        server.injectEnvelopeForDevice(
            deviceId = polo.deviceId,
            envelope = QueuedEnvelope(
                envelope_id = badEnvelopeId,
                mailbox_token = server.mailboxForDevice(polo.deviceId),
                envelope_type = 3,
                ciphertext = prependPairwiseId(pairwise, "not-a-valid-acceptance-ciphertext".encodeToByteArray())
                    .encodeBase64(),
                size_bucket = 128,
                created_at = "2026-07-27T00:00:00Z",
            ),
        )
        server.clearTransportObservations()

        val body = "clean after dirty ${System.nanoTime()}"
        assertDmDelivered(sender = marco, receiver = polo, body = body, expectedInitialQueueSize = 2)
        assertTrue(
            badEnvelopeId in server.ackedEnvelopeIds,
            "Desktop/dev acceptance should ack/drop undecryptable dirty envelope instead of starving good messages",
        )
        assertTrue(server.queueForDevice(polo.deviceId).isEmpty())
    }

    private suspend fun assertDmDelivered(
        sender: AcceptanceClient,
        receiver: AcceptanceClient,
        body: String,
        expectedInitialQueueSize: Int = 1,
    ) {
        val sent = sender.run {
            repo.sendMessage(MarcoPoloRelayServer.CHAT_ID, body)
        }

        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(1, server.relayRequests.size, "${sender.user.username} must POST one relay request")
        assertFalse(
            server.serializedRelayRequests().contains(body),
            "relay payload must not contain plaintext",
        )
        assertEquals(
            expectedInitialQueueSize,
            server.queueForDevice(receiver.deviceId).size,
            "${receiver.user.username} mailbox must contain queued envelope(s) before polling",
        )
        assertFalse(
            receiver.repo.observeMainMessages(MarcoPoloRelayServer.CHAT_ID).first()
                .any { it.body == body },
            "${receiver.user.username} UI stream must not show the message before queue fetch",
        )

        receiver.run {
            repo.processMessageQueue(MarcoPoloRelayServer.CHAT_ID, force = true)
        }

        assertTrue(server.queueFetchesByDevice[receiver.deviceId].orEmpty().isNotEmpty())
        assertTrue(receiver.crypto.decryptEvents.any { it.remoteAccountId == sender.user.id })
        assertTrue(server.ackedEnvelopeIds.isNotEmpty(), "${receiver.user.username} must ack after processing")

        val uiMessages = receiver.repo.observeMainMessages(MarcoPoloRelayServer.CHAT_ID)
            .first { messages -> messages.any { it.body == body } }
        val visible = uiMessages.single { it.body == body }
        assertEquals(sender.user.id, visible.sender_id)
        assertEquals(MessageStatus.SENT, visible.status)
    }

    private fun client(user: User, installLabel: String): AcceptanceClient {
        val crypto = TrackingCryptoEngine(AcceptanceCryptoEngine(installLabel = installLabel))
        val repo = MessengerRepository(
            driverFactory = DatabaseDriverFactory().companionInMemory(),
            cryptoEngineFactory = CryptoEngineFactory(),
            secureSession = secureSession,
            api = ApiClient(
                baseUrl = "https://acceptance.local",
                httpClient = HttpClient(server.engine()) {
                    install(ContentNegotiation) { json(json) }
                },
            ),
            cryptoEngine = crypto,
        )
        return AcceptanceClient(user, repo, crypto)
    }

    private inner class AcceptanceClient(
        val user: User,
        val repo: MessengerRepository,
        val crypto: TrackingCryptoEngine,
    ) {
        lateinit var deviceId: String
            private set

        suspend fun loginAndPreparePeer(peer: User) {
            SessionStore.clear()
            repo.login(user.username, "password123")
            deviceId = crypto.ensureDeviceIdentity(user.id).deviceId
            activate()
            repo.syncChats()
            repo.ensureDmSession(MarcoPoloRelayServer.CHAT_ID, peer.id)
        }

        suspend fun <T> run(block: suspend AcceptanceClient.() -> T): T {
            activate()
            return block()
        }

        private suspend fun activate() {
            secureSession.setActiveUser(user.id)
            SessionStore.setSession(
                token = MarcoPoloRelayServer.tokenFor(user),
                user = user,
                refreshToken = MarcoPoloRelayServer.refreshTokenFor(user),
            )
        }

        suspend fun rotateSignedPrekeyAndRegisterAgain(peer: User) {
            crypto.rotateSignedPrekeyForTest(user.id)
            SessionStore.clear()
            repo.login(user.username, "password123")
            deviceId = crypto.ensureDeviceIdentity(user.id).deviceId
            activate()
            repo.syncChats()
            repo.ensureDmSession(MarcoPoloRelayServer.CHAT_ID, peer.id)
        }
    }

    private class TrackingCryptoEngine(
        private val delegate: AcceptanceCryptoEngine,
    ) : CryptoEngine {
        data class DecryptEvent(
            val accountId: String,
            val remoteAccountId: String,
            val remoteDeviceId: String,
            val envelopeType: Int,
        )

        val decryptEvents = mutableListOf<DecryptEvent>()
        val resetEvents = mutableListOf<DecryptEvent>()

        fun rotateSignedPrekeyForTest(accountId: String) {
            delegate.rotateSignedPrekeyForTest(accountId)
        }

        override suspend fun ensureDeviceIdentity(accountId: String): DeviceIdentity =
            delegate.ensureDeviceIdentity(accountId)

        override suspend fun clearDeviceIdentity(accountId: String) =
            delegate.clearDeviceIdentity(accountId)

        override suspend fun buildDeviceRegistration(accountId: String): RegisterDeviceRequest? =
            delegate.buildDeviceRegistration(accountId)

        override suspend fun ensureSession(
            accountId: String,
            remoteAccountId: String,
            remoteDeviceId: String,
            bundle: DeviceKeyBundle,
        ): Boolean = delegate.ensureSession(accountId, remoteAccountId, remoteDeviceId, bundle)

        override suspend fun resetSession(accountId: String, remoteAccountId: String, remoteDeviceId: String) {
            resetEvents += DecryptEvent(accountId, remoteAccountId, remoteDeviceId, envelopeType = 0)
            delegate.resetSession(accountId, remoteAccountId, remoteDeviceId)
        }

        override suspend fun encryptMessage(
            accountId: String,
            remoteAccountId: String,
            remoteDeviceId: String,
            plaintext: ByteArray,
        ): EncryptedPayload? = delegate.encryptMessage(accountId, remoteAccountId, remoteDeviceId, plaintext)

        override suspend fun decryptMessage(
            accountId: String,
            remoteAccountId: String,
            remoteDeviceUuid: String,
            envelopeType: Int,
            ciphertext: ByteArray,
        ): ByteArray? {
            decryptEvents += DecryptEvent(accountId, remoteAccountId, remoteDeviceUuid, envelopeType)
            return delegate.decryptMessage(accountId, remoteAccountId, remoteDeviceUuid, envelopeType, ciphertext)
        }

        override suspend fun trustRemoteIdentity(
            accountId: String,
            remoteAccountId: String,
            remoteDeviceId: String,
        ): Boolean = delegate.trustRemoteIdentity(accountId, remoteAccountId, remoteDeviceId)

        override suspend fun buildPrekeyReplenishment(
            accountId: String,
            count: Int,
        ): List<OneTimePreKeyMaterial>? = delegate.buildPrekeyReplenishment(accountId, count)

        override suspend fun buildSignedPreKeyRotation(accountId: String): RotateSignedPreKeyRequest? =
            delegate.buildSignedPreKeyRotation(accountId)

        override suspend fun createSenderKeyDistribution(accountId: String, deviceId: String, chatId: String): ByteArray? =
            delegate.createSenderKeyDistribution(accountId, deviceId, chatId)

        override suspend fun processSenderKeyDistribution(
            accountId: String,
            senderAccountId: String,
            senderDeviceId: String,
            chatId: String,
            distributionBytes: ByteArray,
        ): Boolean = delegate.processSenderKeyDistribution(
            accountId,
            senderAccountId,
            senderDeviceId,
            chatId,
            distributionBytes,
        )

        override suspend fun encryptGroupMessage(
            accountId: String,
            deviceId: String,
            chatId: String,
            plaintext: ByteArray,
        ): EncryptedPayload? = delegate.encryptGroupMessage(accountId, deviceId, chatId, plaintext)

        override suspend fun decryptGroupMessage(
            accountId: String,
            senderAccountId: String,
            senderDeviceId: String,
            chatId: String,
            ciphertext: ByteArray,
        ): ByteArray? = delegate.decryptGroupMessage(accountId, senderAccountId, senderDeviceId, chatId, ciphertext)

        override suspend fun safetyNumber(
            accountId: String,
            remoteAccountId: String,
            remoteIdentityPublicKey: ByteArray,
            remoteRegistrationId: Int,
        ): SafetyNumberInfo? = delegate.safetyNumber(accountId, remoteAccountId, remoteIdentityPublicKey, remoteRegistrationId)

        override fun runSelfTest(): CryptoSelfTestResult = delegate.runSelfTest()
    }

    private class AcceptanceCryptoEngine(
        private val installLabel: String,
    ) : CryptoEngine {
        private val identities = mutableMapOf<String, DeviceIdentity>()
        private val signedPrekeyVersions = mutableMapOf<String, Int>()
        private val sessions = mutableMapOf<String, Int>()

        fun rotateSignedPrekeyForTest(accountId: String) {
            signedPrekeyVersions[accountId] = signedPrekeyVersion(accountId) + 1
        }

        override suspend fun ensureDeviceIdentity(accountId: String): DeviceIdentity =
            identities.getOrPut(accountId) {
                val label = installLabel.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
                DeviceIdentity(
                    accountId = accountId,
                    deviceId = "acceptance-device-$accountId$label",
                    registrationId = 42,
                    identityPublicKey = ByteArray(32) { accountId.hashCode().toByte() },
                )
            }

        override suspend fun clearDeviceIdentity(accountId: String) {
            identities.remove(accountId)
            sessions.keys.removeAll { it.startsWith("$accountId:") }
        }

        override suspend fun buildDeviceRegistration(accountId: String): RegisterDeviceRequest {
            val identity = ensureDeviceIdentity(accountId)
            val identityKey = identity.identityPublicKey.encodeBase64()
            val signedPrekey = signedPrekeyMaterial(accountId, identityKey)
            return RegisterDeviceRequest(
                device_id = identity.deviceId,
                registration_id = identity.registrationId,
                identity_public_key = identityKey,
                signed_prekey = signedPrekey,
                pq_prekey = PqPreKeyMaterial(
                    id = signedPrekey.id + 1_000,
                    public_material = identityKey,
                    signature = identityKey,
                    created_at = 1_700_000_000_000,
                ),
                one_time_prekeys = listOf(OneTimePreKeyMaterial(signedPrekey.id + 2_000, identityKey)),
            )
        }

        override suspend fun ensureSession(
            accountId: String,
            remoteAccountId: String,
            remoteDeviceId: String,
            bundle: DeviceKeyBundle,
        ): Boolean {
            sessions.putIfAbsent(sessionKey(accountId, remoteAccountId, remoteDeviceId), bundle.signed_prekey.id)
            return true
        }

        override suspend fun resetSession(accountId: String, remoteAccountId: String, remoteDeviceId: String) {
            sessions.remove(sessionKey(accountId, remoteAccountId, remoteDeviceId))
        }

        override suspend fun encryptMessage(
            accountId: String,
            remoteAccountId: String,
            remoteDeviceId: String,
            plaintext: ByteArray,
        ): EncryptedPayload? {
            val version = sessions[sessionKey(accountId, remoteAccountId, remoteDeviceId)] ?: return null
            val header = "SPK:$version\n".encodeToByteArray()
            return EncryptedPayload(envelopeType = 3, ciphertext = header + xor(plaintext))
        }

        override suspend fun decryptMessage(
            accountId: String,
            remoteAccountId: String,
            remoteDeviceUuid: String,
            envelopeType: Int,
            ciphertext: ByteArray,
        ): ByteArray? {
            val markerEnd = ciphertext.indexOf('\n'.code.toByte())
            if (markerEnd <= 4) return null
            val marker = ciphertext.copyOfRange(0, markerEnd).decodeToString()
            val version = marker.removePrefix("SPK:").toIntOrNull() ?: return null
            if (version != signedPrekeyMaterial(accountId, ensureDeviceIdentity(accountId).identityPublicKey.encodeBase64()).id) {
                return null
            }
            return xor(ciphertext.copyOfRange(markerEnd + 1, ciphertext.size))
        }

        override suspend fun trustRemoteIdentity(
            accountId: String,
            remoteAccountId: String,
            remoteDeviceId: String,
        ): Boolean = true

        override suspend fun buildPrekeyReplenishment(accountId: String, count: Int): List<OneTimePreKeyMaterial>? = null

        override suspend fun buildSignedPreKeyRotation(accountId: String): RotateSignedPreKeyRequest? = null

        override suspend fun createSenderKeyDistribution(accountId: String, deviceId: String, chatId: String): ByteArray? =
            byteArrayOf(0x01, 0x02)

        override suspend fun processSenderKeyDistribution(
            accountId: String,
            senderAccountId: String,
            senderDeviceId: String,
            chatId: String,
            distributionBytes: ByteArray,
        ): Boolean = true

        override suspend fun encryptGroupMessage(
            accountId: String,
            deviceId: String,
            chatId: String,
            plaintext: ByteArray,
        ): EncryptedPayload? = EncryptedPayload(envelopeType = 4, ciphertext = plaintext)

        override suspend fun decryptGroupMessage(
            accountId: String,
            senderAccountId: String,
            senderDeviceId: String,
            chatId: String,
            ciphertext: ByteArray,
        ): ByteArray? = ciphertext

        override suspend fun safetyNumber(
            accountId: String,
            remoteAccountId: String,
            remoteIdentityPublicKey: ByteArray,
            remoteRegistrationId: Int,
        ): SafetyNumberInfo? = null

        override fun runSelfTest(): CryptoSelfTestResult =
            CryptoSelfTestResult(identityGenerated = true, roundtripOk = true)

        private fun signedPrekeyVersion(accountId: String): Int =
            signedPrekeyVersions.getOrPut(accountId) { 1 }

        private fun signedPrekeyMaterial(accountId: String, key: String): SignedPreKeyMaterial {
            val version = signedPrekeyVersion(accountId)
            return SignedPreKeyMaterial(
                id = 1_000 + version,
                public_key = key,
                signature = key,
                created_at = 1_700_000_000_000 + version,
            )
        }

        private fun sessionKey(accountId: String, remoteAccountId: String, remoteDeviceId: String): String =
            "$accountId:$remoteAccountId:$remoteDeviceId"

        private fun xor(bytes: ByteArray): ByteArray =
            ByteArray(bytes.size) { index -> (bytes[index].toInt() xor 0x5A).toByte() }
    }

    private class MarcoPoloRelayServer(private val json: Json) {
        private data class RegisteredDevice(
            val user: User,
            val request: RegisterDeviceRequest,
            val mailboxToken: String,
        )

        val relayRequests = mutableListOf<RelayMessageRequest>()
        val queueFetchesByDevice = mutableMapOf<String, MutableList<List<String>>>()
        val ackedEnvelopeIds = mutableListOf<String>()
        private val devicesById = mutableMapOf<String, RegisteredDevice>()
        private val queuesByMailbox = mutableMapOf<String, MutableList<QueuedEnvelope>>()

        fun reset() {
            relayRequests.clear()
            queueFetchesByDevice.clear()
            ackedEnvelopeIds.clear()
            devicesById.clear()
            queuesByMailbox.clear()
        }

        fun clearTransportObservations() {
            relayRequests.clear()
            queueFetchesByDevice.clear()
            ackedEnvelopeIds.clear()
        }

        fun queueForDevice(deviceId: String): List<QueuedEnvelope> {
            val mailbox = devicesById[deviceId]?.mailboxToken ?: return emptyList()
            return queuesByMailbox[mailbox].orEmpty()
        }

        fun mailboxForDevice(deviceId: String): String =
            devicesById[deviceId]?.mailboxToken ?: error("unknown mailbox device=$deviceId")

        fun injectEnvelopeForDevice(deviceId: String, envelope: QueuedEnvelope) {
            queuesByMailbox.getOrPut(mailboxForDevice(deviceId)) { mutableListOf() } += envelope
        }

        fun serializedRelayRequests(): String =
            relayRequests.joinToString(separator = "\n") {
                json.encodeToString(RelayMessageRequest.serializer(), it)
            }

        fun engine(): MockEngine = MockEngine { request ->
            fun respondJson(content: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
                content = content,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )

            when {
                request.url.encodedPath.endsWith("/api/auth/login") ||
                    request.url.encodedPath.endsWith("/api/auth/refresh") -> {
                    val username = Regex(""""username"\s*:\s*"([^"]+)"""")
                        .find(request.bodyText())
                        ?.groupValues
                        ?.get(1)
                        ?.lowercase()
                    val user = when (username) {
                        "marco" -> MARCO
                        "polo" -> POLO
                        else -> error("unexpected login username=$username")
                    }
                    respondJson(
                        json.encodeToString(
                            AuthResponse.serializer(),
                            AuthResponse(
                                token = tokenFor(user),
                                refresh_token = refreshTokenFor(user),
                                expires_in = 3_600,
                                user = user,
                            ),
                        ),
                    )
                }
                request.url.encodedPath.endsWith("/api/hello") -> respondJson(
                    """{"message":"test","server_id":"dm-acceptance-test"}""",
                )
                request.url.encodedPath.endsWith("/api/devices") && request.method == HttpMethod.Post -> {
                    val user = userFromToken(request)
                    val registration = json.decodeFromString(RegisterDeviceRequest.serializer(), request.bodyText())
                    val mailbox = mailboxFor(registration.device_id)
                    devicesById[registration.device_id] = RegisteredDevice(user, registration, mailbox)
                    queuesByMailbox.getOrPut(mailbox) { mutableListOf() }
                    respondJson(
                        json.encodeToString(
                            RegisterDeviceResponse.serializer(),
                            RegisterDeviceResponse(
                                device_id = registration.device_id,
                                prekeys_stored = registration.one_time_prekeys.size,
                                one_time_prekey_ids = registration.one_time_prekeys.map { it.id },
                            ),
                        ),
                        HttpStatusCode.Created,
                    )
                }
                request.url.encodedPath.endsWith("/api/chats") -> respondJson(
                    json.encodeToString(ListSerializer(Chat.serializer()), listOf(CHAT)),
                )
                request.url.encodedPath.contains("/api/users/") &&
                    request.url.encodedPath.endsWith("/devices") -> {
                    val userId = request.url.encodedPath
                        .substringAfter("/api/users/")
                        .substringBefore("/devices")
                    val devices = devicesById.values
                        .filter { it.user.id == userId }
                        .map { it.toUserDevice() }
                    respondJson(json.encodeToString(ListSerializer(UserDevice.serializer()), devices))
                }
                request.url.encodedPath.endsWith("/bundle") -> {
                    val deviceId = request.url.encodedPath.removeSuffix("/bundle").substringAfterLast("/")
                    val registered = devicesById[deviceId] ?: error("unknown bundle device=$deviceId")
                    respondJson(json.encodeToString(DeviceKeyBundle.serializer(), registered.toBundle()))
                }
                request.url.encodedPath.endsWith("/api/messages/relay") -> {
                    val relay = json.decodeFromString(RelayMessageRequest.serializer(), request.bodyText())
                    relayRequests += relay
                    val ids = relay.envelopes.mapIndexed { index, envelope ->
                        val id = "env-${relayRequests.size}-$index"
                        queuesByMailbox.getOrPut(envelope.mailbox_token) { mutableListOf() } += QueuedEnvelope(
                            envelope_id = id,
                            mailbox_token = envelope.mailbox_token,
                            envelope_type = envelope.envelope_type,
                            ciphertext = envelope.ciphertext,
                            size_bucket = envelope.ciphertext.length,
                            created_at = "2026-07-27T00:00:00Z",
                        )
                        id
                    }
                    respondJson(
                        json.encodeToString(
                            RelayMessageResponse.serializer(),
                            RelayMessageResponse(enqueued = relay.envelopes.size, envelope_ids = ids),
                        ),
                        HttpStatusCode.Created,
                    )
                }
                request.url.encodedPath.endsWith("/api/messages/queue") -> {
                    val deviceId = request.headers["X-Device-Id"] ?: error("queue fetch without X-Device-Id")
                    val mailbox = devicesById[deviceId]?.mailboxToken ?: error("unknown queue device=$deviceId")
                    val envelopes = queuesByMailbox[mailbox].orEmpty()
                    queueFetchesByDevice.getOrPut(deviceId) { mutableListOf() } += envelopes.map { it.envelope_id }
                    respondJson(
                        json.encodeToString(
                            MessageQueueResponse.serializer(),
                            MessageQueueResponse(envelopes = envelopes),
                        ),
                    )
                }
                request.url.encodedPath.endsWith("/api/messages/queue/ack") -> {
                    val ack = json.decodeFromString(AckMessageQueueRequest.serializer(), request.bodyText())
                    ackedEnvelopeIds += ack.envelope_ids
                    queuesByMailbox.values.forEach { queue ->
                        queue.removeAll { it.envelope_id in ack.envelope_ids }
                    }
                    respondJson(
                        json.encodeToString(
                            AckMessageQueueResponse.serializer(),
                            AckMessageQueueResponse(deleted = ack.envelope_ids.size),
                        ),
                    )
                }
                request.url.encodedPath.endsWith("/api/mailbox/rotate") -> {
                    val deviceId = request.headers["X-Device-Id"] ?: "dev"
                    respondJson(
                        json.encodeToString(
                            RotateMailboxResponse.serializer(),
                            RotateMailboxResponse(device_id = deviceId, mailbox_token = mailboxFor(deviceId)),
                        ),
                    )
                }
                request.url.encodedPath.endsWith("/api/devices") && request.method == HttpMethod.Get -> {
                    val user = userFromToken(request)
                    val devices = devicesById.values
                        .filter { it.user.id == user.id }
                        .map { it.toUserDevice() }
                    respondJson(json.encodeToString(ListSerializer(UserDevice.serializer()), devices))
                }
                else -> respondJson("{}")
            }
        }

        private fun RegisteredDevice.toUserDevice(): UserDevice = UserDevice(
            device_id = request.device_id,
            mailbox_token = mailboxToken,
            registration_id = request.registration_id,
            identity_public_key = request.identity_public_key,
            device_status = "active",
        )

        private fun RegisteredDevice.toBundle(): DeviceKeyBundle = DeviceKeyBundle(
            device_id = request.device_id,
            account_id = user.id,
            registration_id = request.registration_id,
            identity_public_key = request.identity_public_key,
            signed_prekey = request.signed_prekey,
            pq_prekey = request.pq_prekey,
            one_time_prekey = request.one_time_prekeys.firstOrNull()
                ?: OneTimePreKeyMaterial(0, request.identity_public_key),
        )

        private fun userFromToken(request: HttpRequestData): User {
            val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
            return when (token) {
                tokenFor(MARCO) -> MARCO
                tokenFor(POLO) -> POLO
                else -> error("unexpected token=$token")
            }
        }

        private fun mailboxFor(deviceId: String): String = "mailbox-$deviceId"

        companion object {
            val MARCO = User(id = "user-marco", username = "Marco", email = null)
            val POLO = User(id = "user-polo", username = "Polo", email = null)
            const val CHAT_ID = "chat-marco-polo"
            val CHAT = Chat(
                id = CHAT_ID,
                title = "Polo",
                type = ChatType.DIRECT,
                member_ids = listOf(MARCO.id, POLO.id),
            )

            fun tokenFor(user: User): String = "tok-${user.id}"
            fun refreshTokenFor(user: User): String = "refresh-${user.id}"
        }
    }
}

private fun HttpRequestData.bodyText(): String =
    (body as? io.ktor.http.content.TextContent)?.text ?: ""
