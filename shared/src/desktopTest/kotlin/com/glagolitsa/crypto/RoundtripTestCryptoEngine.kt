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

/**
 * Test double: deterministic encrypt/decrypt without libsignal.
 * Enough to exercise MessengerRepository DM queue/send paths in desktop tests.
 */
class RoundtripTestCryptoEngine(
    /** Distinguishes multi-phone installs sharing the same accountId. */
    private val installLabel: String = "",
) : CryptoEngine {
    private val identities = mutableMapOf<String, DeviceIdentity>()

    override suspend fun ensureDeviceIdentity(accountId: String): DeviceIdentity =
        identities.getOrPut(accountId) {
            val label = installLabel.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
            DeviceIdentity(
                accountId = accountId,
                deviceId = "test-device-$accountId$label",
                registrationId = 42,
                identityPublicKey = ByteArray(32) { 9 },
            )
        }

    override suspend fun clearDeviceIdentity(accountId: String) {
        identities.remove(accountId)
    }

    override suspend fun buildDeviceRegistration(accountId: String): RegisterDeviceRequest? {
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
    ): Boolean = true

    override suspend fun resetSession(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
    ) = Unit

    override suspend fun encryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        plaintext: ByteArray,
    ): EncryptedPayload = EncryptedPayload(envelopeType = 3, ciphertext = xorObfuscate(plaintext))

    override suspend fun decryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceUuid: String,
        envelopeType: Int,
        ciphertext: ByteArray,
    ): ByteArray? = xorObfuscate(ciphertext)

    private fun xorObfuscate(bytes: ByteArray): ByteArray =
        ByteArray(bytes.size) { index -> (bytes[index].toInt() xor 0xAA).toByte() }

    override suspend fun trustRemoteIdentity(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
    ): Boolean = true

    override suspend fun buildPrekeyReplenishment(accountId: String, count: Int): List<OneTimePreKeyMaterial>? = null

    override suspend fun buildSignedPreKeyRotation(accountId: String): RotateSignedPreKeyRequest? = null

    override suspend fun createSenderKeyDistribution(
        accountId: String,
        deviceId: String,
        chatId: String,
    ): ByteArray? = byteArrayOf(0x01, 0x02)

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
    ): EncryptedPayload = EncryptedPayload(envelopeType = 4, ciphertext = plaintext)

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
}