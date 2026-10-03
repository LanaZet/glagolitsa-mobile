// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.OneTimePreKeyMaterial
import com.glagolitsa.model.RegisterDeviceRequest
import com.glagolitsa.model.RotateSignedPreKeyRequest

/**
 * Swift-side backends installed from [iosApp] before [MainViewController] builds the graph.
 *
 * CryptoKit (AES-GCM) and LibSignalClient cannot be called from Kotlin/Native directly,
 * so the Xcode shell implements these interfaces and injects them at launch.
 */
object IosCryptoBackends {
    var aesGcm: IosAesGcmBackend? = null
    var signal: IosSignalCryptoHost? = null
}

interface IosAesGcmBackend {
    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray?
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray?
}

interface IosSignalCryptoHost {
    fun loadIdentity(accountId: String): DeviceIdentity?
    fun createIdentity(accountId: String, deviceId: String): DeviceIdentity?
    fun clearIdentity(accountId: String)
    fun buildDeviceRegistration(accountId: String): RegisterDeviceRequest?
    fun ensureSession(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        bundle: DeviceKeyBundle,
    ): Boolean
    fun resetSession(accountId: String, remoteAccountId: String, remoteDeviceId: String)
    fun encryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        plaintext: ByteArray,
    ): EncryptedPayload?
    fun decryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceUuid: String,
        envelopeType: Int,
        ciphertext: ByteArray,
    ): ByteArray?
    fun buildPrekeyReplenishment(accountId: String, count: Int): List<OneTimePreKeyMaterial>?
    fun buildSignedPreKeyRotation(accountId: String): RotateSignedPreKeyRequest?
    fun trustRemoteIdentity(accountId: String, remoteAccountId: String, remoteDeviceId: String): Boolean
    fun createSenderKeyDistribution(accountId: String, deviceId: String, chatId: String): ByteArray?
    fun processSenderKeyDistribution(
        accountId: String,
        senderAccountId: String,
        senderDeviceId: String,
        chatId: String,
        distributionBytes: ByteArray,
    ): Boolean
    fun encryptGroupMessage(
        accountId: String,
        deviceId: String,
        chatId: String,
        plaintext: ByteArray,
    ): EncryptedPayload?
    fun decryptGroupMessage(
        accountId: String,
        senderAccountId: String,
        senderDeviceId: String,
        chatId: String,
        ciphertext: ByteArray,
    ): ByteArray?
    fun safetyNumber(
        accountId: String,
        remoteAccountId: String,
        remoteIdentityPublicKey: ByteArray,
        remoteRegistrationId: Int,
    ): SafetyNumberInfo?
    fun runSignalSelfTest(): Boolean
}

internal fun requireAesGcm(): IosAesGcmBackend =
    IosCryptoBackends.aesGcm
        ?: error("iOS AES-GCM backend is not installed")

internal fun requireSignalHost(): IosSignalCryptoHost =
    IosCryptoBackends.signal
        ?: error("iOS libsignal backend is not installed")
