// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.OneTimePreKeyMaterial
import com.glagolitsa.model.RegisterDeviceRequest
import com.glagolitsa.model.RotateSignedPreKeyRequest

/**
 * Fail-closed iOS crypto placeholder until libsignal-client is packaged.
 */
internal class IosDisabledCryptoEngine : CryptoEngine {
    private val identities = mutableMapOf<String, DeviceIdentity>()

    override suspend fun ensureDeviceIdentity(accountId: String): DeviceIdentity =
        identities.getOrPut(accountId) {
            DeviceIdentity(
                accountId = accountId,
                deviceId = "ios-crypto-disabled",
                registrationId = 0,
                identityPublicKey = ByteArray(0),
            )
        }

    override suspend fun clearDeviceIdentity(accountId: String) {
        identities.remove(accountId)
    }

    override suspend fun buildDeviceRegistration(accountId: String): RegisterDeviceRequest? = null

    override suspend fun ensureSession(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        bundle: DeviceKeyBundle,
    ): Boolean = false

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
    ): EncryptedPayload? = null

    override suspend fun buildPrekeyReplenishment(
        accountId: String,
        count: Int,
    ): List<OneTimePreKeyMaterial>? = null

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
    ): Boolean = false

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
        CryptoSelfTestResult(identityGenerated = false, roundtripOk = false)
}
