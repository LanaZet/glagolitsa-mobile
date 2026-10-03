// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.DeviceAttestation
import com.glagolitsa.model.OneTimePreKeyMaterial
import com.glagolitsa.model.PqPreKeyMaterial
import com.glagolitsa.model.RegisterDeviceRequest
import com.glagolitsa.model.RotateSignedPreKeyRequest
import com.glagolitsa.model.SignedPreKeyMaterial
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.Medium
import java.util.Random

internal object SignalBundleFactory {
    const val SIGNAL_DEVICE_ID = 1
    private const val ONE_TIME_PREKEY_BATCH = 20

    fun buildRegistrationRequest(
        deviceId: String,
        registrationId: Int,
        store: SignalProtocolStore,
        attestation: DeviceAttestation? = null,
    ): RegisterDeviceRequest {
        val signedPreKeyPair = ECKeyPair.generate()
        val signedPreKeyId = randomId()
        val signedPreKeySignature = store.identityKeyPair.privateKey
            .calculateSignature(signedPreKeyPair.publicKey.serialize())
        val signedPreKeyRecord = SignedPreKeyRecord(
            signedPreKeyId,
            System.currentTimeMillis(),
            signedPreKeyPair,
            signedPreKeySignature,
        )
        store.storeSignedPreKey(signedPreKeyId, signedPreKeyRecord)

        val kyberPreKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberPreKeyId = randomId()
        val kyberPreKeySignature = store.identityKeyPair.privateKey
            .calculateSignature(kyberPreKeyPair.publicKey.serialize())
        val kyberPreKeyRecord = KyberPreKeyRecord(
            kyberPreKeyId,
            System.currentTimeMillis(),
            kyberPreKeyPair,
            kyberPreKeySignature,
        )
        store.storeKyberPreKey(kyberPreKeyId, kyberPreKeyRecord)

        val oneTimePrekeys = buildList {
            repeat(ONE_TIME_PREKEY_BATCH) {
                val preKeyId = randomId()
                val preKeyPair = ECKeyPair.generate()
                store.storePreKey(preKeyId, PreKeyRecord(preKeyId, preKeyPair))
                add(
                    OneTimePreKeyMaterial(
                        id = preKeyId,
                        public_key = preKeyPair.publicKey.serialize().toBase64(),
                    ),
                )
            }
        }

        return RegisterDeviceRequest(
            device_id = deviceId,
            registration_id = registrationId,
            identity_public_key = store.identityKeyPair.publicKey.serialize().toBase64(),
            signed_prekey = SignedPreKeyMaterial(
                id = signedPreKeyId,
                public_key = signedPreKeyPair.publicKey.serialize().toBase64(),
                signature = signedPreKeySignature.toBase64(),
                created_at = System.currentTimeMillis(),
            ),
            pq_prekey = PqPreKeyMaterial(
                id = kyberPreKeyId,
                public_material = kyberPreKeyPair.publicKey.serialize().toBase64(),
                signature = kyberPreKeySignature.toBase64(),
                created_at = System.currentTimeMillis(),
            ),
            one_time_prekeys = oneTimePrekeys,
            attestation = attestation,
        )
    }

    fun toPreKeyBundle(bundle: DeviceKeyBundle): PreKeyBundle {
        // Libsignal 0.96: when the server has no one-time prekey left, both id and public
        // must be "null". Using preKeyId=0 with a null key throws
        // "Must supply both or neither of prekey and prekey_id".
        val oneTime = bundle.one_time_prekey
        val otpKeyBytes = oneTime?.public_key?.takeIf { it.isNotBlank() }?.decodeBase64()
        val preKeyId: Int
        val preKeyPublic: ECPublicKey?
        if (oneTime != null && otpKeyBytes != null && oneTime.id != 0) {
            preKeyId = oneTime.id
            preKeyPublic = ECPublicKey(otpKeyBytes)
        } else {
            preKeyId = PreKeyBundle.NULL_PRE_KEY_ID // -1
            preKeyPublic = null
        }
        return PreKeyBundle(
            bundle.registration_id,
            SIGNAL_DEVICE_ID,
            preKeyId,
            preKeyPublic,
            bundle.signed_prekey.id,
            ECPublicKey(bundle.signed_prekey.public_key.decodeBase64()),
            bundle.signed_prekey.signature.decodeBase64(),
            IdentityKey(bundle.identity_public_key.decodeBase64(), 0),
            bundle.pq_prekey.id,
            KEMPublicKey(bundle.pq_prekey.public_material.decodeBase64()),
            bundle.pq_prekey.signature.decodeBase64(),
        )
    }

    fun buildOneTimePrekeys(
        store: SignalProtocolStore,
        count: Int,
    ): List<OneTimePreKeyMaterial> = buildList {
        repeat(count) {
            val preKeyId = randomId()
            val preKeyPair = ECKeyPair.generate()
            store.storePreKey(preKeyId, PreKeyRecord(preKeyId, preKeyPair))
            add(
                OneTimePreKeyMaterial(
                    id = preKeyId,
                    public_key = preKeyPair.publicKey.serialize().toBase64(),
                ),
            )
        }
    }

    fun buildSignedPreKeyRotation(store: SignalProtocolStore): RotateSignedPreKeyRequest {
        val signedPreKeyPair = ECKeyPair.generate()
        val signedPreKeyId = randomId()
        val signedPreKeySignature = store.identityKeyPair.privateKey
            .calculateSignature(signedPreKeyPair.publicKey.serialize())
        store.storeSignedPreKey(
            signedPreKeyId,
            SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), signedPreKeyPair, signedPreKeySignature),
        )

        val kyberPreKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberPreKeyId = randomId()
        val kyberPreKeySignature = store.identityKeyPair.privateKey
            .calculateSignature(kyberPreKeyPair.publicKey.serialize())
        store.storeKyberPreKey(
            kyberPreKeyId,
            KyberPreKeyRecord(kyberPreKeyId, System.currentTimeMillis(), kyberPreKeyPair, kyberPreKeySignature),
        )

        return RotateSignedPreKeyRequest(
            signed_prekey = SignedPreKeyMaterial(
                id = signedPreKeyId,
                public_key = signedPreKeyPair.publicKey.serialize().toBase64(),
                signature = signedPreKeySignature.toBase64(),
                created_at = System.currentTimeMillis(),
            ),
            pq_prekey = PqPreKeyMaterial(
                id = kyberPreKeyId,
                public_material = kyberPreKeyPair.publicKey.serialize().toBase64(),
                signature = kyberPreKeySignature.toBase64(),
                created_at = System.currentTimeMillis(),
            ),
        )
    }

    private fun randomId(): Int = Random().nextInt(Medium.MAX_VALUE)
}
