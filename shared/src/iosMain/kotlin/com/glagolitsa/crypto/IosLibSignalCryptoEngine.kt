// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.OneTimePreKeyMaterial
import com.glagolitsa.model.RegisterDeviceRequest
import com.glagolitsa.model.RotateSignedPreKeyRequest
import com.glagolitsa.platform.installationSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class IosLibSignalCryptoEngine(
    private val host: IosSignalCryptoHost,
) : CryptoEngine {
    override suspend fun ensureDeviceIdentity(accountId: String): DeviceIdentity = withContext(Dispatchers.Default) {
        host.loadIdentity(accountId)
            ?: host.createIdentity(accountId, stableDeviceId(accountId, installationSeed()))
            ?: error("Unable to persist iOS Signal identity")
    }

    override suspend fun clearDeviceIdentity(accountId: String) = withContext(Dispatchers.Default) {
        host.clearIdentity(accountId)
    }

    override suspend fun buildDeviceRegistration(accountId: String): RegisterDeviceRequest? =
        withContext(Dispatchers.Default) {
            host.buildDeviceRegistration(accountId)
        }

    override suspend fun ensureSession(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        bundle: DeviceKeyBundle,
    ): Boolean = withContext(Dispatchers.Default) {
        host.ensureSession(accountId, remoteAccountId, remoteDeviceId, bundle)
    }

    override suspend fun resetSession(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
    ) = withContext(Dispatchers.Default) {
        host.resetSession(accountId, remoteAccountId, remoteDeviceId)
    }

    override suspend fun encryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        plaintext: ByteArray,
    ): EncryptedPayload? = withContext(Dispatchers.Default) {
        host.encryptMessage(accountId, remoteAccountId, remoteDeviceId, plaintext)
    }

    override suspend fun decryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceUuid: String,
        envelopeType: Int,
        ciphertext: ByteArray,
    ): ByteArray? = withContext(Dispatchers.Default) {
        host.decryptMessage(accountId, remoteAccountId, remoteDeviceUuid, envelopeType, ciphertext)
    }

    override suspend fun buildPrekeyReplenishment(
        accountId: String,
        count: Int,
    ): List<OneTimePreKeyMaterial>? = withContext(Dispatchers.Default) {
        host.buildPrekeyReplenishment(accountId, count)
    }

    override suspend fun buildSignedPreKeyRotation(accountId: String): RotateSignedPreKeyRequest? =
        withContext(Dispatchers.Default) {
            host.buildSignedPreKeyRotation(accountId)
        }

    override suspend fun trustRemoteIdentity(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
    ): Boolean = withContext(Dispatchers.Default) {
        host.trustRemoteIdentity(accountId, remoteAccountId, remoteDeviceId)
    }

    override suspend fun createSenderKeyDistribution(
        accountId: String,
        deviceId: String,
        chatId: String,
    ): ByteArray? = withContext(Dispatchers.Default) {
        host.createSenderKeyDistribution(accountId, deviceId, chatId)
    }

    override suspend fun processSenderKeyDistribution(
        accountId: String,
        senderAccountId: String,
        senderDeviceId: String,
        chatId: String,
        distributionBytes: ByteArray,
    ): Boolean = withContext(Dispatchers.Default) {
        host.processSenderKeyDistribution(
            accountId,
            senderAccountId,
            senderDeviceId,
            chatId,
            distributionBytes,
        )
    }

    override suspend fun encryptGroupMessage(
        accountId: String,
        deviceId: String,
        chatId: String,
        plaintext: ByteArray,
    ): EncryptedPayload? = withContext(Dispatchers.Default) {
        host.encryptGroupMessage(accountId, deviceId, chatId, plaintext)
    }

    override suspend fun decryptGroupMessage(
        accountId: String,
        senderAccountId: String,
        senderDeviceId: String,
        chatId: String,
        ciphertext: ByteArray,
    ): ByteArray? = withContext(Dispatchers.Default) {
        host.decryptGroupMessage(accountId, senderAccountId, senderDeviceId, chatId, ciphertext)
    }

    override suspend fun safetyNumber(
        accountId: String,
        remoteAccountId: String,
        remoteIdentityPublicKey: ByteArray,
        remoteRegistrationId: Int,
    ): SafetyNumberInfo? = withContext(Dispatchers.Default) {
        host.safetyNumber(accountId, remoteAccountId, remoteIdentityPublicKey, remoteRegistrationId)
    }

    override fun runSelfTest(): CryptoSelfTestResult {
        val identityGenerated = runCatching {
            host.createIdentity("__self-test__", "ios-self-test")?.identityPublicKey?.isNotEmpty() == true
        }.getOrDefault(false)
        runCatching { host.clearIdentity("__self-test__") }
        return CryptoSelfTestResult(
            identityGenerated = identityGenerated,
            roundtripOk = runCatching { host.runSignalSelfTest() }.getOrDefault(false),
        )
    }
}
