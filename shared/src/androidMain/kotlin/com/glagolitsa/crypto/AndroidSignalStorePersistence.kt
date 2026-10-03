// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import java.util.UUID
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/**
 * Персистентность libsignal store: только serialize()/deserialize() записей libsignal.
 */
internal class AndroidSignalStorePersistence(
    context: Context,
) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun loadInto(accountId: String, store: InMemorySignalProtocolStore) {
        val prefix = keyPrefix(accountId)
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(prefix) || value !is String) return@forEach
            val blob = decodeHex(value)
            when {
                key.startsWith("${prefix}session_") -> {
                    val address = parseAddress(key.removePrefix("${prefix}session_"))
                    store.storeSession(address, SessionRecord(blob))
                }
                key.startsWith("${prefix}prekey_") -> {
                    val id = key.removePrefix("${prefix}prekey_").toInt()
                    store.storePreKey(id, PreKeyRecord(blob))
                }
                key.startsWith("${prefix}signed_prekey_") -> {
                    val id = key.removePrefix("${prefix}signed_prekey_").toInt()
                    store.storeSignedPreKey(id, SignedPreKeyRecord(blob))
                }
                key.startsWith("${prefix}kyber_prekey_") -> {
                    val id = key.removePrefix("${prefix}kyber_prekey_").toInt()
                    store.storeKyberPreKey(id, KyberPreKeyRecord(blob))
                }
                key.startsWith("${prefix}identity_") -> {
                    val address = parseAddress(key.removePrefix("${prefix}identity_"))
                    store.saveIdentity(address, IdentityKey(blob, 0))
                }
                key.startsWith("${prefix}trusted_identity_") -> {
                    // loaded into PersistingSignalProtocolStore.trustedIdentities
                }
                key.startsWith("${prefix}sender_key_") -> {
                    val (address, distributionId) = parseSenderKeyKey(key.removePrefix("${prefix}sender_key_"))
                    store.storeSenderKey(address, distributionId, SenderKeyRecord(blob))
                }
            }
        }
    }

    fun saveSession(accountId: String, address: SignalProtocolAddress, record: SessionRecord) {
        prefs.edit()
            .putString(sessionKey(accountId, address), encodeHex(record.serialize()))
            .apply()
    }

    fun savePreKey(accountId: String, id: Int, record: PreKeyRecord) {
        prefs.edit()
            .putString("${keyPrefix(accountId)}prekey_$id", encodeHex(record.serialize()))
            .apply()
    }

    fun saveSignedPreKey(accountId: String, id: Int, record: SignedPreKeyRecord) {
        prefs.edit()
            .putString("${keyPrefix(accountId)}signed_prekey_$id", encodeHex(record.serialize()))
            .apply()
    }

    fun saveKyberPreKey(accountId: String, id: Int, record: KyberPreKeyRecord) {
        prefs.edit()
            .putString("${keyPrefix(accountId)}kyber_prekey_$id", encodeHex(record.serialize()))
            .apply()
    }

    fun saveIdentity(accountId: String, address: SignalProtocolAddress, key: IdentityKey) {
        prefs.edit()
            .putString(identityKey(accountId, address), encodeHex(key.serialize()))
            .apply()
    }

    fun loadTrustedIdentities(accountId: String): Set<String> =
        prefs.all
            .filterKeys { it.startsWith("${keyPrefix(accountId)}trusted_identity_") }
            .filterValues { it == true }
            .keys
            .map { it.removePrefix("${keyPrefix(accountId)}trusted_identity_") }
            .toSet()

    fun setIdentityTrusted(accountId: String, address: SignalProtocolAddress, trusted: Boolean) {
        val key = trustedIdentityKey(accountId, address)
        prefs.edit().apply {
            if (trusted) putBoolean(key, true) else remove(key)
        }.apply()
    }

    fun saveSenderKey(
        accountId: String,
        address: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord,
    ) {
        prefs.edit()
            .putString(senderKeyKey(accountId, address, distributionId), encodeHex(record.serialize()))
            .apply()
    }

    fun clear(accountId: String) {
        val prefix = keyPrefix(accountId)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    private fun keyPrefix(accountId: String): String = "acct_${accountId}_signal_"

    private fun sessionKey(accountId: String, address: SignalProtocolAddress): String =
        "${keyPrefix(accountId)}session_${address.name}_${address.deviceId}"

    private fun identityKey(accountId: String, address: SignalProtocolAddress): String =
        "${keyPrefix(accountId)}identity_${address.name}_${address.deviceId}"

    private fun trustedIdentityKey(accountId: String, address: SignalProtocolAddress): String =
        "${keyPrefix(accountId)}trusted_identity_${address.name}_${address.deviceId}"

    private fun senderKeyKey(
        accountId: String,
        address: SignalProtocolAddress,
        distributionId: UUID,
    ): String = "${keyPrefix(accountId)}sender_key_${address.name}_${address.deviceId}::$distributionId"

    private fun parseSenderKeyKey(raw: String): Pair<SignalProtocolAddress, UUID> {
        val separator = raw.lastIndexOf("::")
        require(separator > 0) { "Invalid sender key key: $raw" }
        val distributionId = UUID.fromString(raw.substring(separator + 2))
        val addressPart = raw.substring(0, separator)
        return parseAddress(addressPart) to distributionId
    }

    private fun parseAddress(raw: String): SignalProtocolAddress {
        val separator = raw.lastIndexOf('_')
        require(separator > 0) { "Invalid address key: $raw" }
        val name = raw.substring(0, separator)
        val deviceId = raw.substring(separator + 1).toInt()
        return SignalProtocolAddress(name, deviceId)
    }

    private companion object {
        const val PREFS_NAME = "glagolitsa_crypto_signal_store"
    }
}