// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.glagolitsa.platform.installationSeed
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * Хранит сериализованную identity key pair в EncryptedSharedPreferences.
 */
internal class AndroidIdentityKeyStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        PREFS_NAME,
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(accountId: String): StoredIdentity? {
        val prefix = keyPrefix(accountId)
        val deviceId = prefs.getString("${prefix}device_id", null) ?: return null
        val registrationId = prefs.getInt("${prefix}registration_id", -1).takeIf { it >= 0 } ?: return null
        val identityBlob = prefs.getString("${prefix}identity_blob", null)?.let(::decodeHex) ?: return null
        val identityKeyPair = IdentityKeyPair(identityBlob)
        return StoredIdentity(
            deviceId = deviceId,
            registrationId = registrationId,
            identityKeyPair = identityKeyPair,
            identityPublicKey = identityKeyPair.publicKey.serialize(),
        )
    }

    fun save(accountId: String, stored: StoredIdentity) {
        val prefix = keyPrefix(accountId)
        prefs.edit()
            .putString("${prefix}device_id", stored.deviceId)
            .putInt("${prefix}registration_id", stored.registrationId)
            .putString("${prefix}identity_blob", encodeHex(stored.identityKeyPair.serialize()))
            .apply()
    }

    fun clear(accountId: String) {
        val prefix = keyPrefix(accountId)
        prefs.edit()
            .remove("${prefix}device_id")
            .remove("${prefix}registration_id")
            .remove("${prefix}identity_blob")
            .apply()
    }

    fun createNew(accountId: String): StoredIdentity {
        val identityKeyPair = IdentityKeyPair.generate()
        return StoredIdentity(
            deviceId = stableDeviceId(accountId, installationSeed(appContext)),
            registrationId = KeyHelper.generateRegistrationId(false),
            identityKeyPair = identityKeyPair,
            identityPublicKey = identityKeyPair.publicKey.serialize(),
        )
    }

    data class StoredIdentity(
        val deviceId: String,
        val registrationId: Int,
        val identityKeyPair: IdentityKeyPair,
        val identityPublicKey: ByteArray,
    )

    private fun keyPrefix(accountId: String): String = "acct_${accountId}_"

    private companion object {
        const val PREFS_NAME = "glagolitsa_crypto_identity"
    }
}