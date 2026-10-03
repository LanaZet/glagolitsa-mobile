// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.OneTimePreKeyMaterial
import com.glagolitsa.model.RegisterDeviceRequest
import com.glagolitsa.model.RotateSignedPreKeyRequest

/**
 * Платформенный крипто-движок (libsignal на Android).
 * Этап 1: identity. Этап 3: encrypt/decrypt + сессии Double Ratchet.
 */
interface CryptoEngine {
    suspend fun ensureDeviceIdentity(accountId: String): DeviceIdentity

    suspend fun clearDeviceIdentity(accountId: String)

    suspend fun buildDeviceRegistration(accountId: String): RegisterDeviceRequest?

    suspend fun ensureSession(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        bundle: DeviceKeyBundle,
    ): Boolean

    /** Сбрасывает локальную Signal-сессию с устройством собеседника перед повторной установкой. */
    suspend fun resetSession(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
    )

    suspend fun encryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        plaintext: ByteArray,
    ): EncryptedPayload?

    suspend fun buildPrekeyReplenishment(accountId: String, count: Int = 20): List<OneTimePreKeyMaterial>?

    suspend fun buildSignedPreKeyRotation(accountId: String): RotateSignedPreKeyRequest?

    suspend fun decryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceUuid: String,
        envelopeType: Int,
        ciphertext: ByteArray,
    ): ByteArray?

    /** Подтверждение смены identity собеседника (диалог «Принять»). */
    suspend fun trustRemoteIdentity(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
    ): Boolean

    /** Sender Key distribution message (opaque bytes) for a group chat. */
    suspend fun createSenderKeyDistribution(
        accountId: String,
        deviceId: String,
        chatId: String,
    ): ByteArray?

    suspend fun processSenderKeyDistribution(
        accountId: String,
        senderAccountId: String,
        senderDeviceId: String,
        chatId: String,
        distributionBytes: ByteArray,
    ): Boolean

    suspend fun encryptGroupMessage(
        accountId: String,
        deviceId: String,
        chatId: String,
        plaintext: ByteArray,
    ): EncryptedPayload?

    suspend fun decryptGroupMessage(
        accountId: String,
        senderAccountId: String,
        senderDeviceId: String,
        chatId: String,
        ciphertext: ByteArray,
    ): ByteArray?

    suspend fun safetyNumber(
        accountId: String,
        remoteAccountId: String,
        remoteIdentityPublicKey: ByteArray,
        remoteRegistrationId: Int,
    ): SafetyNumberInfo?

    fun runSelfTest(): CryptoSelfTestResult
}

expect class CryptoEngineFactory {
    fun create(): CryptoEngine
}