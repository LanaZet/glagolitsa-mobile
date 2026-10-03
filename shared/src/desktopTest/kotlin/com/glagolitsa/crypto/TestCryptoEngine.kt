// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.OneTimePreKeyMaterial
import com.glagolitsa.model.RegisterDeviceRequest
import com.glagolitsa.model.RotateSignedPreKeyRequest
import com.glagolitsa.model.SignedPreKeyMaterial
import com.glagolitsa.model.PqPreKeyMaterial
import com.glagolitsa.util.encodeBase64

class TestCryptoEngine(
    private val registrationAvailable: Boolean = true,
    private val registerShouldFail: Boolean = false,
    /** When true, encrypt always fails (session wipe / key-not-ready style). */
    private val encryptAlwaysNull: Boolean = false,
    /**
     * Distinguishes multi-phone installs that share the same [accountId]
     * (real LibSignal uses random UUIDs; tests need stable distinct ids).
     */
    private val installLabel: String = "",
) : CryptoEngine {
    private val identities = mutableMapOf<String, DeviceIdentity>()
    private val sessions = mutableSetOf<String>()
    /** Rotations per account after [clearDeviceIdentity] (second phone / revoked re-enroll). */
    private val identityRotation = mutableMapOf<String, Int>()
    /** How many times [clearDeviceIdentity] was called (hard logout / wipe). */
    var clearDeviceIdentityCount: Int = 0
        private set
    var resetSessionCount: Int = 0
        private set

    /** Current local device id for [accountId], or null if never created. */
    fun currentDeviceId(accountId: String): String? = identities[accountId]?.deviceId

    fun currentIdentityPublicKey(accountId: String): ByteArray? =
        identities[accountId]?.identityPublicKey?.copyOf()

    /** Whether a session exists (Signal Sesame session map). */
    fun hasSession(accountId: String, remoteAccountId: String, remoteDeviceId: String): Boolean =
        sessionKey(accountId, remoteAccountId, remoteDeviceId) in sessions

    fun sessionCount(accountId: String): Int =
        sessions.count { it.startsWith("$accountId:") }

    override suspend fun ensureDeviceIdentity(accountId: String): DeviceIdentity =
        identities.getOrPut(accountId) {
            val rotation = identityRotation[accountId] ?: 0
            // First install: stable id used by existing device-registration tests.
            // After clear (revoked on server / other phone): mint a new id + new keys
            // (Signal Sesame: DeviceID and identity key pair change only when device is
            // logically deleted and re-added).
            val label = installLabel.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
            val deviceId = if (rotation == 0) {
                "test-device-$accountId$label"
            } else {
                "test-device-$accountId$label-rot$rotation"
            }
            DeviceIdentity(
                accountId = accountId,
                deviceId = deviceId,
                registrationId = 42 + rotation,
                identityPublicKey = ByteArray(32) { (9 + rotation).toByte() },
            )
        }

    override suspend fun clearDeviceIdentity(accountId: String) {
        clearDeviceIdentityCount++
        identityRotation[accountId] = (identityRotation[accountId] ?: 0) + 1
        identities.remove(accountId)
        sessions.removeAll { it.startsWith("$accountId:") }
    }

    override suspend fun buildDeviceRegistration(accountId: String): RegisterDeviceRequest? {
        if (!registrationAvailable) return null
        val identity = ensureDeviceIdentity(accountId)
        val key = identity.identityPublicKey.encodeBase64()
        return RegisterDeviceRequest(
            device_id = identity.deviceId,
            registration_id = identity.registrationId,
            identity_public_key = key,
            signed_prekey = SignedPreKeyMaterial(1001, key, key, 1_700_000_000_000),
            pq_prekey = PqPreKeyMaterial(2001, key, key, 1_700_000_000_000),
            one_time_prekeys = listOf(OneTimePreKeyMaterial(301, key)),
        )
    }

    override suspend fun ensureSession(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        bundle: DeviceKeyBundle,
    ): Boolean {
        sessions.add(sessionKey(accountId, remoteAccountId, remoteDeviceId))
        return true
    }

    override suspend fun resetSession(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
    ) {
        resetSessionCount++
        sessions.remove(sessionKey(accountId, remoteAccountId, remoteDeviceId))
    }

    override suspend fun encryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        plaintext: ByteArray,
    ): EncryptedPayload? {
        if (encryptAlwaysNull) return null
        if (sessionKey(accountId, remoteAccountId, remoteDeviceId) !in sessions) return null
        return EncryptedPayload(envelopeType = 3, ciphertext = plaintext)
    }

    private fun sessionKey(accountId: String, remote: String, device: String) =
        "$accountId:$remote:$device"

    override suspend fun buildPrekeyReplenishment(accountId: String, count: Int): List<OneTimePreKeyMaterial>? = null

    override suspend fun buildSignedPreKeyRotation(accountId: String): RotateSignedPreKeyRequest? = null

    override suspend fun decryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceUuid: String,
        envelopeType: Int,
        ciphertext: ByteArray,
    ): ByteArray? = null

    override suspend fun trustRemoteIdentity(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
    ): Boolean = true

    override suspend fun createSenderKeyDistribution(
        accountId: String,
        deviceId: String,
        chatId: String,
    ): ByteArray? = null

    override suspend fun processSenderKeyDistribution(
        accountId: String,
        senderAccountId: String,
        senderDeviceId: String,
        chatId: String,
        distributionBytes: ByteArray,
    ): Boolean = false

    override suspend fun encryptGroupMessage(
        accountId: String,
        deviceId: String,
        chatId: String,
        plaintext: ByteArray,
    ): EncryptedPayload? = null

    override suspend fun decryptGroupMessage(
        accountId: String,
        senderAccountId: String,
        senderDeviceId: String,
        chatId: String,
        ciphertext: ByteArray,
    ): ByteArray? = null

    override suspend fun safetyNumber(
        accountId: String,
        remoteAccountId: String,
        remoteIdentityPublicKey: ByteArray,
        remoteRegistrationId: Int,
    ): SafetyNumberInfo? = null

    override fun runSelfTest(): CryptoSelfTestResult =
        CryptoSelfTestResult(identityGenerated = true, roundtripOk = true)

    val registerShouldFailFlag: Boolean get() = registerShouldFail
}