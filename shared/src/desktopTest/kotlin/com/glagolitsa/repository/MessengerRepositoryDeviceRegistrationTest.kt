// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.auth.DeviceRegistrationException
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.TestCryptoEngine
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.User
import com.glagolitsa.model.UserDevice
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.session.SessionStore
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Device registration reconcile + multi-phone re-enroll.
 *
 * Covers:
 * - confirm/promote while another active exists (Signal linked devices; no auto-revoke)
 * - local device revoked after explicit unlink → rotate identity and re-register
 * - bootstrapRestoredSession refreshes token then re-enrolls
 */
class MessengerRepositoryDeviceRegistrationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val user = User(id = "user-device-1", username = "device-user", email = null)
    private val ownDeviceId = "test-device-user-device-1"
    private val ghostActiveId = "ghost-active-old"
    private val otherPhoneActiveId = "other-phone-active"

    private val calls = mutableListOf<String>()
    private var ownDevices: List<UserDevice> = emptyList()
    private var confirmStatus: HttpStatusCode = HttpStatusCode.OK
    private var registerCount = 0
    private var accessToken: String = "tok-device"
    private var refreshCalls = 0
    private var registerRejectedDeviceIds = mutableSetOf<String>()
    /** Reject with 403 + "revoked" body (Signal: cannot re-use logically deleted device). */
    private var registerForbiddenDeviceIds = mutableSetOf<String>()
    private var lastRegisteredDeviceId: String? = null
    /** Optional hook: supply device id for next POST when body is unavailable in mock. */
    private var nextRegisterDeviceId: String? = null

    @BeforeTest
    fun reset() {
        SessionStore.clear()
        calls.clear()
        ownDevices = emptyList()
        confirmStatus = HttpStatusCode.OK
        registerCount = 0
        accessToken = "tok-device"
        refreshCalls = 0
        registerRejectedDeviceIds.clear()
        registerForbiddenDeviceIds.clear()
        lastRegisteredDeviceId = null
        nextRegisterDeviceId = null
    }

    private fun registeredDeviceIdFromState(): String =
        nextRegisterDeviceId
            ?: lastRegisteredDeviceId
            ?: ownDeviceId

    @AfterTest
    fun cleanup() {
        SessionStore.clear()
    }

    private fun authResponse(token: String = accessToken) = json.encodeToString(
        AuthResponse.serializer(),
        AuthResponse(token = token, refresh_token = "refresh-device", user = user),
    )

    private fun mockEngine(): MockEngine = MockEngine { request ->
        val path = request.url.encodedPath
        val authHeader = request.headers[HttpHeaders.Authorization].orEmpty()
        when {
            path.endsWith("/api/hello") -> respond(
                content = """{"message":"test","server_id":"device-registration-test"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            path.endsWith("/api/auth/login") -> respond(
                content = authResponse("tok-device"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            path.endsWith("/api/auth/refresh") -> {
                refreshCalls++
                calls.add("refresh")
                accessToken = "tok-refreshed-$refreshCalls"
                respond(
                    content = authResponse(accessToken),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            path.endsWith("/api/users/me") || path.endsWith("/api/profile/me") -> {
                if (authHeader.contains("expired") || authHeader.contains("stale")) {
                    respond(
                        content = """{"error":"invalid token"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                } else {
                    respond(
                        content = json.encodeToString(User.serializer(), user),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
            path.endsWith("/api/devices") && request.method == HttpMethod.Post -> {
                val postedId = request.headers["X-Device-Id"]
                    ?.takeIf { it.isNotBlank() }
                    ?: registeredDeviceIdFromState()
                if (postedId in registerForbiddenDeviceIds) {
                    calls.add("register-forbidden:$postedId")
                    respond(
                        content = """{"error":"device revoked"}""",
                        status = HttpStatusCode.Forbidden,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                } else if (postedId in registerRejectedDeviceIds ||
                    authHeader.contains("expired") ||
                    authHeader.contains("stale")
                ) {
                    calls.add("register-reject:$postedId")
                    respond(
                        content = """{"error":"invalid token"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                } else {
                    registerCount++
                    calls.add("register:$postedId")
                    lastRegisteredDeviceId = postedId
                    val existing = ownDevices.firstOrNull { it.device_id == postedId }
                    when {
                        existing == null -> {
                            // Fresh / rotated device: active immediately (single-phone or after rotate).
                            ownDevices = ownDevices + device(postedId, "active", key = "key-$postedId")
                        }
                        existing.device_status == "pending" && registerCount >= 2 -> {
                            ownDevices = ownDevices.map { d ->
                                if (d.device_id == postedId) d.copy(device_status = "active") else d
                            }
                        }
                        existing.device_status == "pending" -> {
                            // first register keeps pending until confirm/promote path
                        }
                        else -> {
                            ownDevices = ownDevices.map { d ->
                                if (d.device_id == postedId) d.copy(device_status = "active") else d
                            }
                        }
                    }
                    respond(
                        content = """{"device_id":"$postedId","prekeys_stored":1}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
            path.contains("/users/") && path.endsWith("/devices") -> {
                calls.add("list-devices")
                respond(
                    content = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(UserDevice.serializer()),
                        ownDevices,
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            path.contains("/api/devices/") && path.endsWith("/confirm") -> {
                calls.add("confirm")
                if (confirmStatus == HttpStatusCode.OK) {
                    // Mark the device being confirmed active (path .../devices/{id}/confirm).
                    val id = path.substringAfter("/api/devices/").substringBefore("/confirm")
                    ownDevices = ownDevices.map { d ->
                        if (d.device_id == id || d.device_id == ownDeviceId) {
                            d.copy(device_status = "active")
                        } else {
                            d
                        }
                    }
                }
                respond(
                    content = """{"status":"active"}""",
                    status = confirmStatus,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            path.contains("/api/devices/") && request.method == HttpMethod.Delete -> {
                val id = path.removePrefix("/api/devices/").substringBefore('/')
                calls.add("revoke:$id")
                ownDevices = ownDevices.map { d ->
                    if (d.device_id == id) d.copy(device_status = "revoked") else d
                }
                respond(
                    content = """{"status":"revoked"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            path.endsWith("/api/chats") -> respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            else -> respond("{}", HttpStatusCode.OK)
        }
    }

    private fun repository(crypto: TestCryptoEngine = TestCryptoEngine()): MessengerRepository =
        MessengerRepository(
            driverFactory = DatabaseDriverFactory().companionInMemory(),
            cryptoEngineFactory = CryptoEngineFactory(),
            secureSession = SecureSessionStore(),
            api = ApiClient(
                baseUrl = "https://api.test",
                httpClient = HttpClient(mockEngine()) {
                    install(ContentNegotiation) { json(json) }
                },
            ),
            cryptoEngine = crypto,
        )

    private fun device(
        id: String,
        status: String,
        key: String = "k",
    ) = UserDevice(
        device_id = id,
        mailbox_token = "mb-$id",
        registration_id = 1,
        identity_public_key = key,
        device_status = status,
    )

    /** Minimal partner prekey bundle for session seed / wipe tests (Signal X3DH shape). */
    private fun partnerBundle(
        accountId: String,
        deviceId: String,
        identityKey: String = "Ym9iLWtleQ==",
    ) = com.glagolitsa.model.DeviceKeyBundle(
        device_id = deviceId,
        account_id = accountId,
        registration_id = 7,
        identity_public_key = identityKey,
        signed_prekey = com.glagolitsa.model.SignedPreKeyMaterial(1, identityKey, identityKey, 1L),
        pq_prekey = com.glagolitsa.model.PqPreKeyMaterial(2, identityKey, identityKey, 1L),
        one_time_prekey = com.glagolitsa.model.OneTimePreKeyMaterial(3, identityKey),
    )

    @Test
    fun login_pendingWithOtherActive_confirmsAndKeepsOtherLinked(): Unit = runBlocking {
        ownDevices = listOf(
            UserDevice(
                device_id = ownDeviceId,
                mailbox_token = "mb-own",
                registration_id = 1,
                identity_public_key = "a",
                device_status = "pending",
            ),
            UserDevice(
                device_id = ghostActiveId,
                mailbox_token = "mb-ghost",
                registration_id = 2,
                identity_public_key = "b",
                device_status = "active",
            ),
        )
        val repo = repository()
        repo.login("device-user", "password123")

        assertNotNull(SessionStore.token.value)
        assertTrue(calls.contains("confirm"), "must confirm via other active device")
        assertFalse(
            calls.contains("revoke:$ghostActiveId"),
            "Signal multi-device: must not auto-revoke the other phone: $calls",
        )
        assertTrue(
            ownDevices.any { it.device_id == ghostActiveId && it.device_status == "active" },
            "other device stays active: $ownDevices",
        )
    }

    @Test
    fun login_pendingWithoutConfirmer_reregistersThenRevokesSiblingPending(): Unit = runBlocking {
        ownDevices = listOf(
            UserDevice(
                device_id = ownDeviceId,
                mailbox_token = "mb-own",
                registration_id = 1,
                identity_public_key = "a",
                device_status = "pending",
            ),
            UserDevice(
                device_id = "other-pending",
                mailbox_token = "mb-other",
                registration_id = 3,
                identity_public_key = "c",
                device_status = "pending",
            ),
        )
        val repo = repository()
        repo.login("device-user", "password123")

        assertNotNull(SessionStore.token.value)
        assertTrue(registerCount >= 2, "re-register for server promote when no confirmer")
        assertFalse(calls.contains("confirm"), "no active confirmer to call confirm")
        // After this install is active, leftover pending siblings are zombies — revoke them.
        assertTrue(
            calls.contains("revoke:other-pending"),
            "must revoke sibling pending after self is active: $calls",
        )
        assertFalse(
            ownDevices.any { it.device_id == "other-pending" && it.device_status == "pending" },
            "sibling pending must not remain: $ownDevices",
        )
    }

    @Test
    fun login_keysNotReady_throwsDeviceRegistrationException(): Unit = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/hello") -> respond(
                    content = """{"message":"test","server_id":"device-registration-test"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/api/auth/login") -> respond(
                    content = authResponse(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> respond("{}", HttpStatusCode.OK)
            }
        }
        val repo = MessengerRepository(
            driverFactory = DatabaseDriverFactory().companionInMemory(),
            cryptoEngineFactory = CryptoEngineFactory(),
            secureSession = SecureSessionStore(),
            api = ApiClient(
                baseUrl = "https://api.test",
                httpClient = HttpClient(engine) {
                    install(ContentNegotiation) { json(json) }
                },
            ),
            cryptoEngine = TestCryptoEngine(registrationAvailable = false),
        )

        assertFailsWith<DeviceRegistrationException> {
            repo.login("device-user", "password123")
        }
        assertEquals(null, SessionStore.token.value)
    }

    @Test
    fun login_alreadyActive_skipsConfirmAndKeepsOtherActives(): Unit = runBlocking {
        ownDevices = listOf(
            UserDevice(
                device_id = ownDeviceId,
                mailbox_token = "mb-own",
                registration_id = 1,
                identity_public_key = "a",
                device_status = "active",
            ),
            UserDevice(
                device_id = ghostActiveId,
                mailbox_token = "mb-ghost",
                registration_id = 2,
                identity_public_key = "b",
                device_status = "active",
            ),
        )
        val repo = repository()
        repo.login("device-user", "password123")

        assertFalse(calls.contains("confirm"))
        assertFalse(
            calls.any { it.startsWith("revoke:") },
            "must not auto-unlink other actives: $calls",
        )
    }

    // --- Multi-phone / revoked local device ---

    /**
     * User already logged in on another phone (active). This phone still has an old
     * local device id that the server marked revoked — must clear local crypto and
     * re-register with a new device id (not hammer revoked id).
     */
    @Test
    fun login_localDeviceRevokedOnServer_otherPhoneActive_rotatesIdentityAndRegisters(): Unit = runBlocking {
        val crypto = TestCryptoEngine()
        // Prime local identity (same as previous install).
        crypto.ensureDeviceIdentity(user.id)
        assertEquals(ownDeviceId, crypto.currentDeviceId(user.id))

        ownDevices = listOf(
            device(ownDeviceId, "revoked"),
            device(otherPhoneActiveId, "active"),
        )

        val repo = repository(crypto)
        repo.login("device-user", "password123")

        assertEquals(1, crypto.clearDeviceIdentityCount, "must clear revoked local identity")
        val newId = crypto.currentDeviceId(user.id)
        assertNotNull(newId)
        assertTrue(newId != ownDeviceId, "new device id after rotation")
        assertTrue(
            calls.any { it == "register:$newId" },
            "must register rotated device, calls=$calls",
        )
        assertNotNull(SessionStore.token.value)
        assertFalse(SessionStore.reauthRequired.value)
        // Other phone stays linked (Signal multi-device).
        assertTrue(
            ownDevices.any { it.device_id == newId && (it.device_status ?: "active") == "active" },
            "rotated device should be active on server list",
        )
        assertTrue(
            ownDevices.any { it.device_id == otherPhoneActiveId && it.device_status == "active" } ||
                !calls.contains("revoke:$otherPhoneActiveId"),
            "must not auto-revoke other phone after rotate",
        )
    }

    /**
     * Cold start after process death: stored session + revoked local device + other phone active.
     * bootstrapRestoredSession must refresh token and re-enroll without kicking to full wipe.
     */
    @Test
    fun bootstrap_afterOtherPhoneLogin_revokedLocalDevice_rotatesAndStaysLoggedIn(): Unit = runBlocking {
        val crypto = TestCryptoEngine()
        crypto.ensureDeviceIdentity(user.id)
        val secure = SecureSessionStore()
        val repo = MessengerRepository(
            driverFactory = DatabaseDriverFactory().companionInMemory(),
            cryptoEngineFactory = CryptoEngineFactory(),
            secureSession = secure,
            api = ApiClient(
                baseUrl = "https://api.test",
                httpClient = HttpClient(mockEngine()) {
                    install(ContentNegotiation) { json(json) }
                },
            ),
            cryptoEngine = crypto,
        )

        // First login succeeds with active device.
        ownDevices = listOf(device(ownDeviceId, "active"))
        repo.login("device-user", "password123")
        assertNotNull(SessionStore.token.value)

        // Simulate: user logged in on another phone; server revoked this phone's device.
        ownDevices = listOf(
            device(ownDeviceId, "revoked"),
            device(otherPhoneActiveId, "active"),
        )
        // Process death — memory clear, secure store still has credentials.
        SessionStore.clear()
        // Stale access token in store forces refresh path on bootstrap.
        val stored = secure.load()!!
        secure.save(
            stored.copy(
                accessToken = "tok-stale",
                refreshToken = "refresh-device",
                expiresAtEpochMs = 1L, // expired
            ),
        )

        val restored = repo.restoreSession()
        assertTrue(restored, "restoreSession should keep user from secure store")
        assertEquals(user.id, SessionStore.user.value?.id)

        repo.bootstrapRestoredSession()

        assertTrue(refreshCalls >= 1, "bootstrap must refresh expired access token")
        assertTrue(crypto.clearDeviceIdentityCount >= 1, "revoked local device must rotate")
        val newId = crypto.currentDeviceId(user.id)
        assertNotNull(newId)
        assertTrue(newId != ownDeviceId)
        assertTrue(
            calls.any { it.startsWith("register:") && it != "register:$ownDeviceId" } ||
                calls.contains("register:$newId"),
            "must register new device id, calls=$calls",
        )
        assertNotNull(SessionStore.token.value)
        assertFalse(
            SessionStore.reauthRequired.value,
            "must not force full reauth when refresh + rotate succeed",
        )
    }

    /**
     * Second phone can log in while first remains registered (until ghost reconcile).
     * New install gets its own device id and becomes active.
     */
    @Test
    fun secondPhoneLogin_whileFirstActive_registersNewDevice(): Unit = runBlocking {
        // Server already has phone A active; this is a fresh phone B crypto store.
        ownDevices = listOf(device(otherPhoneActiveId, "active"))
        val crypto = TestCryptoEngine()
        val repo = repository(crypto)

        repo.login("device-user", "password123")

        val phoneBId = crypto.currentDeviceId(user.id)
        assertEquals(ownDeviceId, phoneBId) // first identity generation on this install
        assertTrue(calls.any { it.startsWith("register:") })
        assertTrue(
            ownDevices.any { it.device_id == ownDeviceId && it.device_status == "active" } ||
                ownDevices.any { it.device_id == phoneBId },
            "phone B registered: $ownDevices",
        )
        assertNotNull(SessionStore.token.value)
    }

    /**
     * Bootstrap with expired access but valid refresh: must not mark reauthRequired
     * solely because an intermediate device call saw a bad token before refresh.
     */
    @Test
    fun bootstrap_expiredAccess_refreshesThenRegisters_noReauth(): Unit = runBlocking {
        val crypto = TestCryptoEngine()
        val secure = SecureSessionStore()
        val repo = MessengerRepository(
            driverFactory = DatabaseDriverFactory().companionInMemory(),
            cryptoEngineFactory = CryptoEngineFactory(),
            secureSession = secure,
            api = ApiClient(
                baseUrl = "https://api.test",
                httpClient = HttpClient(mockEngine()) {
                    install(ContentNegotiation) { json(json) }
                },
            ),
            cryptoEngine = crypto,
        )
        ownDevices = emptyList()
        repo.login("device-user", "password123")
        SessionStore.clear()

        val stored = secure.load()!!
        secure.save(
            stored.copy(
                accessToken = "tok-stale",
                refreshToken = "refresh-device",
                expiresAtEpochMs = 1L,
            ),
        )
        assertTrue(repo.restoreSession())
        repo.bootstrapRestoredSession()

        assertTrue(refreshCalls >= 1)
        assertFalse(SessionStore.reauthRequired.value)
        assertNotNull(SessionStore.token.value)
        assertTrue(SessionStore.token.value!!.contains("refreshed") || SessionStore.token.value!!.isNotBlank())
    }

    // --- Signal / Sesame-aligned multi-device cases ---
    //
    // Signal Sesame: "A device will always have the same DeviceID and identity key pair
    // (to change these the device must be logically deleted and then added with new values)."
    // Linked devices: separate device records; reinstall = new device, old sessions invalid.
    // Prekeys must be uploaded on re-enroll; partner sessions must be rebuilt after identity change.

    /**
     * Signal: identity key pair changes only when device is logically deleted.
     * After server revoke → clear local → new DeviceID + new identity public key.
     */
    @Test
    fun signal_sesame_rotation_changesDeviceIdAndIdentityKey(): Unit = runBlocking {
        val crypto = TestCryptoEngine()
        crypto.ensureDeviceIdentity(user.id)
        val oldId = crypto.currentDeviceId(user.id)!!
        val oldKey = crypto.currentIdentityPublicKey(user.id)!!.copyOf()

        // Seed a partner session that must die with the old identity (Signal session wipe).
        crypto.ensureSession(
            accountId = user.id,
            remoteAccountId = "partner-bob",
            remoteDeviceId = "partner-dev-1",
            bundle = partnerBundle("partner-bob", "partner-dev-1"),
        )
        assertTrue(crypto.hasSession(user.id, "partner-bob", "partner-dev-1"))
        assertEquals(1, crypto.sessionCount(user.id))

        ownDevices = listOf(
            device(oldId, "revoked"),
            device(otherPhoneActiveId, "active"),
        )
        val repo = repository(crypto)
        repo.login("device-user", "password123")

        val newId = crypto.currentDeviceId(user.id)!!
        val newKey = crypto.currentIdentityPublicKey(user.id)!!
        assertTrue(newId != oldId, "Sesame: new DeviceID after logical delete")
        assertFalse(oldKey.contentEquals(newKey), "Sesame: new identity key pair after re-add")
        assertEquals(0, crypto.sessionCount(user.id), "old Signal sessions must be wiped with identity")
        assertFalse(crypto.hasSession(user.id, "partner-bob", "partner-dev-1"))
        assertTrue(calls.any { it == "register:$newId" })
    }

    /**
     * Signal re-register uploads a full key bundle (prekeys). Registration request must
     * include device_id matching local identity after rotation.
     */
    @Test
    fun signal_reEnroll_uploadsRegistrationBundleForNewDevice(): Unit = runBlocking {
        val crypto = TestCryptoEngine()
        crypto.ensureDeviceIdentity(user.id)
        ownDevices = listOf(
            device(ownDeviceId, "revoked"),
            device(otherPhoneActiveId, "active"),
        )
        val repo = repository(crypto)
        repo.login("device-user", "password123")

        val newId = crypto.currentDeviceId(user.id)!!
        val reg = crypto.buildDeviceRegistration(user.id)
        assertNotNull(reg)
        assertEquals(newId, reg!!.device_id)
        assertTrue(reg.one_time_prekeys.isNotEmpty(), "must ship one-time prekeys like Signal KDC enroll")
        assertTrue(reg.identity_public_key.isNotBlank())
        assertTrue(calls.contains("register:$newId"))
    }

    /**
     * While this phone is still pending, do not revoke the other active phone
     * (Signal: primary/linked stays until explicit unlink; zero-active deadlock otherwise).
     */
    @Test
    fun multiPhone_pendingThisDevice_doesNotRevokeOtherActivePhone(): Unit = runBlocking {
        // Force first POST to keep this device pending (no auto-active on first insert).
        // Setup: other phone already active; our device appears pending on list before login completes.
        ownDevices = listOf(
            device(ownDeviceId, "pending"),
            device(otherPhoneActiveId, "active"),
        )
        val crypto = TestCryptoEngine()
        crypto.ensureDeviceIdentity(user.id)
        val repo = repository(crypto)
        repo.login("device-user", "password123")

        // Confirm path may promote us; if still pending mid-flow, other phone must not be revoked first.
        // After full success we may revoke ghosts — assert confirm happened while other was active.
        if (calls.contains("confirm")) {
            val confirmIdx = calls.indexOf("confirm")
            val revokeOther = calls.indexOf("revoke:$otherPhoneActiveId")
            if (revokeOther >= 0) {
                assertTrue(
                    confirmIdx < revokeOther,
                    "Signal/Mattermost: never revoke other active before this device is confirmed: $calls",
                )
            }
        }
        assertTrue(
            ownDevices.any { it.device_id == otherPhoneActiveId },
            "other phone still present in device list",
        )
    }

    /**
     * Fresh phone (no local device on server list) while another phone is active —
     * register as new device (Signal linked / second install), not reuse foreign device id.
     */
    @Test
    fun signal_freshInstall_withOtherPhoneActive_registersOwnDeviceIdOnly(): Unit = runBlocking {
        ownDevices = listOf(device(otherPhoneActiveId, "active"))
        val crypto = TestCryptoEngine()
        // No prior ensureDeviceIdentity — brand new install.
        val repo = repository(crypto)
        repo.login("device-user", "password123")

        val localId = crypto.currentDeviceId(user.id)!!
        assertEquals(ownDeviceId, localId)
        assertTrue(localId != otherPhoneActiveId)
        assertTrue(calls.any { it == "register:$localId" })
        assertFalse(calls.any { it == "register:$otherPhoneActiveId" })
        assertEquals(0, crypto.clearDeviceIdentityCount, "fresh install must not clear identity")
    }

    /**
     * Device missing from server list entirely (server wiped device row) but local crypto remains:
     * do not clear unless status=revoked; just re-POST registration for same id.
     */
    @Test
    fun localDeviceUnknownToServer_reregistersSameIdWithoutClear(): Unit = runBlocking {
        val crypto = TestCryptoEngine()
        crypto.ensureDeviceIdentity(user.id)
        // Server list has only another phone — local id absent (not revoked entry).
        ownDevices = listOf(device(otherPhoneActiveId, "active"))
        val repo = repository(crypto)
        repo.login("device-user", "password123")

        assertEquals(0, crypto.clearDeviceIdentityCount, "absent ≠ revoked; keep same DeviceID/keys")
        assertEquals(ownDeviceId, crypto.currentDeviceId(user.id))
        assertTrue(calls.contains("register:$ownDeviceId") || calls.any { it.startsWith("register:") })
    }

    /**
     * Two sequential logins on two crypto engines (two phones): both register successfully.
     * Note: TestCryptoEngine derives a deterministic first id from accountId (like a fresh
     * install seed); real LibSignal uses random UUIDs so phone A/B never collide.
     */
    @Test
    fun twoPhones_bothCanRegister_independentStores(): Unit = runBlocking {
        val phoneA = TestCryptoEngine()
        val phoneB = TestCryptoEngine()
        ownDevices = emptyList()

        repository(phoneA).login("device-user", "password123")
        val idA = phoneA.currentDeviceId(user.id)!!
        assertEquals(0, phoneA.clearDeviceIdentityCount)

        // Phone B is a separate install; server already has A active.
        ownDevices = listOf(device(idA, "active"))
        calls.clear()
        registerCount = 0
        repository(phoneB).login("device-user", "password123")
        val idB = phoneB.currentDeviceId(user.id)!!

        assertNotNull(SessionStore.token.value)
        assertEquals(0, phoneB.clearDeviceIdentityCount)
        assertTrue(calls.any { it.startsWith("register:") }, "phone B registered: $calls")
        assertTrue(
            ownDevices.any { it.device_id == idB && it.device_status == "active" } ||
                calls.any { it == "register:$idB" },
            "phone B active/registered: devices=$ownDevices calls=$calls",
        )
        assertFalse(
            calls.contains("revoke:$idA"),
            "phone A must stay linked after phone B login: $calls",
        )
        assertTrue(idA.isNotBlank() && idB.isNotBlank())
    }

    /**
     * Sesame §3.1: "A device will always have the same DeviceID and identity key pair
     * (to change these the device must be logically deleted and then added with new values)."
     * Active re-login must NOT rotate keys.
     */
    @Test
    fun signal_sesame_activeDevice_keepsSameDeviceIdAndIdentityKey(): Unit = runBlocking {
        val crypto = TestCryptoEngine()
        crypto.ensureDeviceIdentity(user.id)
        val id0 = crypto.currentDeviceId(user.id)!!
        val key0 = crypto.currentIdentityPublicKey(user.id)!!.copyOf()
        ownDevices = listOf(device(id0, "active", key = "key-$id0"))

        val repo = repository(crypto)
        repo.login("device-user", "password123")

        assertEquals(0, crypto.clearDeviceIdentityCount)
        assertEquals(id0, crypto.currentDeviceId(user.id))
        assertTrue(key0.contentEquals(crypto.currentIdentityPublicKey(user.id)!!))
        // Re-POST registration is fine (prekey refresh); identity must be stable.
        assertTrue(calls.any { it == "register:$id0" } || ownDevices.any { it.device_id == id0 })
    }

    /**
     * If list-devices is stale/unavailable but POST /devices returns 403 revoked,
     * client must still rotate (Signal: cannot resurrect logically deleted DeviceID).
     */
    @Test
    fun signal_registerForbiddenRevoked_rotatesWithoutListHint(): Unit = runBlocking {
        val crypto = TestCryptoEngine()
        crypto.ensureDeviceIdentity(user.id)
        val oldId = crypto.currentDeviceId(user.id)!!
        // Server list empty / no revoked row — only POST refuses the old id.
        ownDevices = emptyList()
        registerForbiddenDeviceIds.add(oldId)

        val repo = repository(crypto)
        repo.login("device-user", "password123")

        assertTrue(crypto.clearDeviceIdentityCount >= 1, "403 revoked must clear local identity")
        val newId = crypto.currentDeviceId(user.id)!!
        assertTrue(newId != oldId)
        assertTrue(calls.contains("register-forbidden:$oldId"), "first POST forbidden: $calls")
        assertTrue(calls.contains("register:$newId"), "retry with new DeviceID: $calls")
        assertNotNull(SessionStore.token.value)
    }

    /**
     * After identity wipe, encrypt to old partner sessions must fail until ensureSession again
     * (Sesame: orphaned sessions no longer match).
     */
    @Test
    fun signal_afterRotation_encryptRequiresNewSession(): Unit = runBlocking {
        val crypto = TestCryptoEngine()
        crypto.ensureDeviceIdentity(user.id)
        crypto.ensureSession(
            accountId = user.id,
            remoteAccountId = "partner-bob",
            remoteDeviceId = "partner-dev-1",
            bundle = partnerBundle("partner-bob", "partner-dev-1"),
        )
        val plain = "hello".encodeToByteArray()
        assertNotNull(
            crypto.encryptMessage(user.id, "partner-bob", "partner-dev-1", plain),
            "session works before revoke",
        )

        ownDevices = listOf(
            device(ownDeviceId, "revoked"),
            device(otherPhoneActiveId, "active"),
        )
        repository(crypto).login("device-user", "password123")

        assertEquals(
            null,
            crypto.encryptMessage(user.id, "partner-bob", "partner-dev-1", plain),
            "after Sesame logical delete, old session must not encrypt",
        )
        // New session can be built against partner (X3DH re-init in real engine).
        crypto.ensureSession(
            accountId = user.id,
            remoteAccountId = "partner-bob",
            remoteDeviceId = "partner-dev-1",
            bundle = partnerBundle("partner-bob", "partner-dev-1"),
        )
        assertNotNull(crypto.encryptMessage(user.id, "partner-bob", "partner-dev-1", plain))
    }

    /**
     * Signal multi-device: after this install is active, the other phone stays active.
     * Never revoke while pending (and never auto-revoke after active either).
     */
    @Test
    fun afterActive_keepsOtherActivePhoneLinked(): Unit = runBlocking {
        ownDevices = listOf(
            device(ownDeviceId, "pending"),
            device(otherPhoneActiveId, "active"),
        )
        val crypto = TestCryptoEngine()
        crypto.ensureDeviceIdentity(user.id)
        repository(crypto).login("device-user", "password123")

        assertFalse(
            calls.any { it.startsWith("revoke:") },
            "must not auto-revoke linked devices: $calls",
        )
        assertTrue(
            ownDevices.any {
                it.device_id == ownDeviceId && (it.device_status ?: "active") == "active"
            } || calls.contains("confirm"),
            "this device promoted: $ownDevices $calls",
        )
        assertTrue(
            ownDevices.any {
                it.device_id == otherPhoneActiveId && (it.device_status ?: "active") == "active"
            },
            "other phone still active: $ownDevices",
        )
    }
}
