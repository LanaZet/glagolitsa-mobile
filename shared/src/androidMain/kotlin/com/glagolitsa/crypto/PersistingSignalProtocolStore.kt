// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.util.UUID

/**
 * Обёртка над [InMemorySignalProtocolStore] с персистентностью через libsignal serialize().
 */
internal class PersistingSignalProtocolStore(
    identityKeyPair: IdentityKeyPair,
    registrationId: Int,
    private val accountId: String,
    private val persistence: AndroidSignalStorePersistence,
) : SignalProtocolStore {
    private val delegate = InMemorySignalProtocolStore(identityKeyPair, registrationId)
    private val trustedIdentities = persistence.loadTrustedIdentities(accountId).toMutableSet()

    init {
        persistence.loadInto(accountId, delegate)
    }

    override fun getIdentityKeyPair(): IdentityKeyPair = delegate.identityKeyPair

    override fun getLocalRegistrationId(): Int = delegate.localRegistrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val change = delegate.saveIdentity(address, identityKey)
        persistence.saveIdentity(accountId, address, identityKey)
        return change
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        if (trustedIdentities.contains(addressKey(address))) return true
        return delegate.isTrustedIdentity(address, identityKey, direction)
    }

    fun trustIdentity(address: SignalProtocolAddress) {
        val key = addressKey(address)
        trustedIdentities.add(key)
        persistence.setIdentityTrusted(accountId, address, trusted = true)
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = delegate.getIdentity(address)

    override fun loadPreKey(preKeyId: Int): PreKeyRecord = delegate.loadPreKey(preKeyId)

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        delegate.storePreKey(preKeyId, record)
        persistence.savePreKey(accountId, preKeyId, record)
    }

    override fun containsPreKey(preKeyId: Int): Boolean = delegate.containsPreKey(preKeyId)

    override fun removePreKey(preKeyId: Int) {
        delegate.removePreKey(preKeyId)
    }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        delegate.loadSession(address) ?: SessionRecord()

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> =
        delegate.loadExistingSessions(addresses)

    override fun getSubDeviceSessions(name: String): MutableList<Int> = delegate.getSubDeviceSessions(name)

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        delegate.storeSession(address, record)
        persistence.saveSession(accountId, address, record)
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = delegate.containsSession(address)

    override fun deleteSession(address: SignalProtocolAddress) {
        delegate.deleteSession(address)
    }

    override fun deleteAllSessions(name: String) {
        delegate.deleteAllSessions(name)
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord = delegate.loadSignedPreKey(signedPreKeyId)

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> = delegate.loadSignedPreKeys()

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        delegate.storeSignedPreKey(signedPreKeyId, record)
        persistence.saveSignedPreKey(accountId, signedPreKeyId, record)
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = delegate.containsSignedPreKey(signedPreKeyId)

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        delegate.removeSignedPreKey(signedPreKeyId)
    }

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        delegate.storeSenderKey(sender, distributionId, record)
        persistence.saveSenderKey(accountId, sender, distributionId, record)
    }

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord =
        delegate.loadSenderKey(sender, distributionId) ?: missingSignalRecord()

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord = delegate.loadKyberPreKey(kyberPreKeyId)

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> = delegate.loadKyberPreKeys()

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        delegate.storeKyberPreKey(kyberPreKeyId, record)
        persistence.saveKyberPreKey(accountId, kyberPreKeyId, record)
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = delegate.containsKyberPreKey(kyberPreKeyId)

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        delegate.markKyberPreKeyUsed(kyberPreKeyId, signedPreKeyId, baseKey)
    }

    private fun addressKey(address: SignalProtocolAddress): String =
        "${address.name}_${address.deviceId}"

    @Suppress("UNCHECKED_CAST")
    private fun <T> missingSignalRecord(): T = null as T

}
