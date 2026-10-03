// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.passkey

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.AlgorithmParameters
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

internal actual object PasskeyEcdsa {
    actual fun generate(): PasskeyEcdsaKey {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val publicKey = pair.public as ECPublicKey
        val privateKey = pair.private as ECPrivateKey
        return PasskeyEcdsaKey(
            d = pad32(privateKey.s.toByteArray()),
            x = pad32(publicKey.w.affineX.toByteArray()),
            y = pad32(publicKey.w.affineY.toByteArray()),
        )
    }

    actual fun sign(key: PasskeyEcdsaKey, data: ByteArray): ByteArray {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        val spec = parameters.getParameterSpec(ECParameterSpec::class.java)
        val privateKey = KeyFactory.getInstance("EC")
            .generatePrivate(ECPrivateKeySpec(BigInteger(1, key.d), spec))
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    actual fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun pad32(raw: ByteArray): ByteArray {
        if (raw.size == 32) return raw
        if (raw.size > 32) return raw.copyOfRange(raw.size - 32, raw.size)
        return ByteArray(32 - raw.size) + raw
    }
}
