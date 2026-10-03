// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import android.content.Context
import com.glagolitsa.log.AppLog
import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.OneTimePreKeyMaterial
import com.glagolitsa.model.RegisterDeviceRequest
import com.glagolitsa.model.RotateSignedPreKeyRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.util.UUID

internal class LibSignalCryptoEngine(
    context: Context,
) : CryptoEngine {
    private val appContext = context.applicationContext
    private val identityStore = AndroidIdentityKeyStore(appContext)
    private val persistence = AndroidSignalStorePersistence(appContext)
    private val stores = mutableMapOf<String, PersistingSignalProtocolStore>()
    private val mutex = Mutex()

    override suspend fun ensureDeviceIdentity(accountId: String): DeviceIdentity = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: identityStore.createNew(accountId).also {
            identityStore.save(accountId, it)
        }
        storeFor(accountId, stored)
        DeviceIdentity(
            accountId = accountId,
            deviceId = stored.deviceId,
            registrationId = stored.registrationId,
            identityPublicKey = stored.identityPublicKey,
        )
    }

    override suspend fun clearDeviceIdentity(accountId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            stores.remove(accountId)
        }
        identityStore.clear(accountId)
        persistence.clear(accountId)
    }

    override suspend fun buildDeviceRegistration(accountId: String): RegisterDeviceRequest? = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: return@withContext null
        val store = storeFor(accountId, stored)
        SignalBundleFactory.buildRegistrationRequest(
            deviceId = stored.deviceId,
            registrationId = stored.registrationId,
            store = store,
            attestation = AndroidIntegritySignals.deviceAttestation(),
        )
    }

    override suspend fun ensureSession(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        bundle: DeviceKeyBundle,
    ): Boolean = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: return@withContext false
        val store = storeFor(accountId, stored)
        val remoteAddress = remoteAddress(remoteAccountId, remoteDeviceId)
        val localAddress = localAddress(accountId, stored.deviceId)
        if (store.containsSession(remoteAddress)) {
            return@withContext true
        }
        runCatching {
            SessionBuilder(store, remoteAddress, localAddress)
                .process(SignalBundleFactory.toPreKeyBundle(bundle))
            true
        }.onFailure {
            AppLog.debug(
                "ensureSession failed remote=$remoteDeviceId " +
                    "error=${it::class.simpleName}:${it.message.orEmpty()}",
            )
        }.getOrDefault(false)
    }

    override suspend fun resetSession(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
    ): Unit = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: return@withContext
        val store = storeFor(accountId, stored)
        store.deleteSession(remoteAddress(remoteAccountId, remoteDeviceId))
    }

    override suspend fun encryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        plaintext: ByteArray,
    ): EncryptedPayload? = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: return@withContext null
        val store = storeFor(accountId, stored)
        val remoteAddress = remoteAddress(remoteAccountId, remoteDeviceId)
        if (!store.containsSession(remoteAddress)) {
            return@withContext null
        }
        runCatching {
            val cipher = SessionCipher(store, localAddress(accountId, stored.deviceId), remoteAddress)
            val encrypted = cipher.encrypt(plaintext)
            EncryptedPayload(
                envelopeType = apiEnvelopeType(encrypted.type),
                ciphertext = encrypted.serialize(),
            )
        }.onFailure {
            AppLog.debug(
                "encryptMessage failed remote=$remoteDeviceId " +
                    "error=${it::class.simpleName}:${it.message.orEmpty()}",
            )
        }.getOrNull()
    }

    override suspend fun trustRemoteIdentity(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: return@withContext false
        val store = storeFor(accountId, stored)
        store.trustIdentity(remoteAddress(remoteAccountId, remoteDeviceId))
        true
    }

    override suspend fun decryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceUuid: String,
        envelopeType: Int,
        ciphertext: ByteArray,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: return@withContext null
        val store = storeFor(accountId, stored)
        val remoteAddress = remoteAddress(remoteAccountId, remoteDeviceUuid)
        runCatching {
            val cipher = SessionCipher(store, localAddress(accountId, stored.deviceId), remoteAddress)
            when (libSignalEnvelopeType(envelopeType)) {
                CiphertextMessage.PREKEY_TYPE ->
                    cipher.decrypt(PreKeySignalMessage(ciphertext))
                CiphertextMessage.WHISPER_TYPE ->
                    cipher.decrypt(SignalMessage(ciphertext))
                else -> error("Unexpected ciphertext type: $envelopeType")
            }
        }.onFailure {
            AppLog.debug(
                "decryptMessage failed remote=$remoteDeviceUuid " +
                    "type=$envelopeType error=${it::class.simpleName}:${it.message.orEmpty()}",
            )
        }.getOrNull()
    }

    override suspend fun createSenderKeyDistribution(
        accountId: String,
        deviceId: String,
        chatId: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: return@withContext null
        val store = storeFor(accountId, stored)
        runCatching {
            val senderAddress = groupSenderAddress(accountId, deviceId)
            val distributionId = distributionIdForChat(chatId)
            val distribution = GroupSessionBuilder(store).create(senderAddress, distributionId)
            distribution.serialize()
        }.onFailure {
            AppLog.debug(
                "createSenderKeyDistribution failed chat=$chatId " +
                    "error=${it::class.simpleName}:${it.message.orEmpty()}",
            )
        }.getOrNull()
    }

    override suspend fun processSenderKeyDistribution(
        accountId: String,
        senderAccountId: String,
        senderDeviceId: String,
        chatId: String,
        distributionBytes: ByteArray,
    ): Boolean = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: return@withContext false
        val store = storeFor(accountId, stored)
        runCatching {
            val senderAddress = groupSenderAddress(senderAccountId, senderDeviceId)
            val distributionId = distributionIdForChat(chatId)
            GroupSessionBuilder(store).process(
                senderAddress,
                SenderKeyDistributionMessage(distributionBytes),
            )
            true
        }.onFailure {
            AppLog.debug(
                "processSenderKeyDistribution failed chat=$chatId sender=$senderDeviceId " +
                    "error=${it::class.simpleName}:${it.message.orEmpty()}",
            )
        }.getOrDefault(false)
    }

    override suspend fun encryptGroupMessage(
        accountId: String,
        deviceId: String,
        chatId: String,
        plaintext: ByteArray,
    ): EncryptedPayload? = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: return@withContext null
        val store = storeFor(accountId, stored)
        runCatching {
            val senderAddress = groupSenderAddress(accountId, deviceId)
            val distributionId = distributionIdForChat(chatId)
            val encrypted = GroupCipher(store, senderAddress).encrypt(distributionId, plaintext)
            EncryptedPayload(
                envelopeType = SIGNAL_ENVELOPE_SENDER_KEY,
                ciphertext = encrypted.serialize(),
            )
        }.onFailure {
            AppLog.debug(
                "encryptGroupMessage failed chat=$chatId " +
                    "error=${it::class.simpleName}:${it.message.orEmpty()}",
            )
        }.getOrNull()
    }

    override suspend fun decryptGroupMessage(
        accountId: String,
        senderAccountId: String,
        senderDeviceId: String,
        chatId: String,
        ciphertext: ByteArray,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: return@withContext null
        val store = storeFor(accountId, stored)
        runCatching {
            val senderAddress = groupSenderAddress(senderAccountId, senderDeviceId)
            GroupCipher(store, senderAddress).decrypt(ciphertext)
        }.onFailure {
            AppLog.debug(
                "decryptGroupMessage failed chat=$chatId sender=$senderDeviceId " +
                    "error=${it::class.simpleName}:${it.message.orEmpty()}",
            )
        }.getOrNull()
    }

    override suspend fun safetyNumber(
        accountId: String,
        remoteAccountId: String,
        remoteIdentityPublicKey: ByteArray,
        remoteRegistrationId: Int,
    ): SafetyNumberInfo? = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: return@withContext null
        val localIdentity = IdentityKey(stored.identityPublicKey, 0)
        val remoteIdentity = IdentityKey(remoteIdentityPublicKey, 0)
        runCatching {
            val fingerprint = NumericFingerprintGenerator(FINGERPRINT_ITERATIONS).createFor(
                FINGERPRINT_VERSION,
                accountId.toByteArray(Charsets.UTF_8),
                localIdentity,
                remoteAccountId.toByteArray(Charsets.UTF_8),
                remoteIdentity,
            )
            SafetyNumberInfo(
                displayText = fingerprint.displayableFingerprint.displayText,
                qrPayload = fingerprint.scannableFingerprint.serialized,
            )
        }.getOrNull()
    }

    override fun runSelfTest(): CryptoSelfTestResult {
        val roundtripOk = LibSignalSelfTest.run()
        val identityGenerated = runCatching {
            org.signal.libsignal.protocol.IdentityKeyPair.generate()
                .publicKey.serialize().isNotEmpty()
        }.getOrDefault(false)
        return CryptoSelfTestResult(
            identityGenerated = identityGenerated,
            roundtripOk = roundtripOk,
        )
    }

    private suspend fun storeFor(
        accountId: String,
        stored: AndroidIdentityKeyStore.StoredIdentity,
    ): PersistingSignalProtocolStore = mutex.withLock {
        stores.getOrPut(accountId) {
            PersistingSignalProtocolStore(
                identityKeyPair = stored.identityKeyPair,
                registrationId = stored.registrationId,
                accountId = accountId,
                persistence = persistence,
            )
        }
    }

    private fun localAddress(accountId: String, deviceId: String): SignalProtocolAddress =
        SignalProtocolAddress("$accountId#$deviceId", SignalBundleFactory.SIGNAL_DEVICE_ID)

    override suspend fun buildPrekeyReplenishment(
        accountId: String,
        count: Int,
    ): List<OneTimePreKeyMaterial>? = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: return@withContext null
        val store = storeFor(accountId, stored)
        SignalBundleFactory.buildOneTimePrekeys(store, count)
    }

    override suspend fun buildSignedPreKeyRotation(
        accountId: String,
    ): RotateSignedPreKeyRequest? = withContext(Dispatchers.IO) {
        val stored = identityStore.load(accountId) ?: return@withContext null
        val store = storeFor(accountId, stored)
        SignalBundleFactory.buildSignedPreKeyRotation(store)
    }

    private fun remoteAddress(accountId: String, deviceId: String): SignalProtocolAddress =
        SignalProtocolAddress("$accountId#$deviceId", SignalBundleFactory.SIGNAL_DEVICE_ID)

    private fun groupSenderAddress(accountId: String, deviceId: String): SignalProtocolAddress =
        SignalProtocolAddress("$accountId#$deviceId", SignalBundleFactory.SIGNAL_DEVICE_ID)

    private fun distributionIdForChat(chatId: String): UUID = UUID.fromString(chatId)

    private fun apiEnvelopeType(libSignalType: Int): Int =
        when (libSignalType) {
            CiphertextMessage.WHISPER_TYPE -> API_SIGNAL_MESSAGE_TYPE
            CiphertextMessage.PREKEY_TYPE -> API_PREKEY_MESSAGE_TYPE
            else -> libSignalType
        }

    private fun libSignalEnvelopeType(apiType: Int): Int =
        when (apiType) {
            API_SIGNAL_MESSAGE_TYPE -> CiphertextMessage.WHISPER_TYPE
            API_PREKEY_MESSAGE_TYPE -> CiphertextMessage.PREKEY_TYPE
            else -> apiType
        }

    private companion object {
        const val FINGERPRINT_ITERATIONS = 5200
        const val FINGERPRINT_VERSION = 0
        const val API_SIGNAL_MESSAGE_TYPE = 1
        const val API_PREKEY_MESSAGE_TYPE = 3
    }
}
