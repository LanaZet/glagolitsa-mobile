// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.passkey

import com.glagolitsa.util.sha256
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal object WebAuthnEncoder {
    const val FLAG_UP: Int = 0x01
    const val FLAG_UV: Int = 0x04
    const val FLAG_AT: Int = 0x40

    fun clientDataJson(type: String, challenge: String, origin: String): ByteArray =
        """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}"""
            .encodeToByteArray()

    fun authenticatorData(
        rpId: String,
        signCount: Int,
        attested: Boolean,
        credentialId: ByteArray? = null,
        key: PasskeyEcdsaKey? = null,
    ): ByteArray {
        val flags = FLAG_UP or FLAG_UV or if (attested) FLAG_AT else 0
        val prefix = sha256(rpId.encodeToByteArray()) +
            byteArrayOf(flags.toByte()) +
            uint32(signCount)
        if (!attested) return prefix
        require(credentialId != null && key != null)
        return prefix +
            ByteArray(16) +
            uint16(credentialId.size) +
            credentialId +
            coseEc2(key)
    }

    fun attestationObject(authData: ByteArray): ByteArray =
        CborMapBuilder().apply {
            text("fmt", "none")
            map("attStmt", cborEmptyMap())
            bytes("authData", authData)
        }.build()

    fun publicKeyCredential(
        credentialId: ByteArray,
        clientData: ByteArray,
        attestationObject: ByteArray? = null,
        authenticatorData: ByteArray? = null,
        signature: ByteArray? = null,
        userHandle: ByteArray? = null,
    ): JsonObject = buildJsonObject {
        val id = Base64Url.encode(credentialId)
        put("id", JsonPrimitive(id))
        put("rawId", JsonPrimitive(id))
        put("type", JsonPrimitive("public-key"))
        put(
            "response",
            buildJsonObject {
                put("clientDataJSON", JsonPrimitive(Base64Url.encode(clientData)))
                attestationObject?.let {
                    put("attestationObject", JsonPrimitive(Base64Url.encode(it)))
                }
                authenticatorData?.let {
                    put("authenticatorData", JsonPrimitive(Base64Url.encode(it)))
                }
                signature?.let { put("signature", JsonPrimitive(Base64Url.encode(it))) }
                userHandle?.let { put("userHandle", JsonPrimitive(Base64Url.encode(it))) }
            },
        )
    }

    fun coseEc2(key: PasskeyEcdsaKey): ByteArray =
        CborMapBuilder().apply {
            unsigned(1, 2)
            unsigned(3, -7)
            unsigned(-1, 1)
            bytes(-2, pad32(key.x))
            bytes(-3, pad32(key.y))
        }.build()

    private fun uint32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun uint16(value: Int): ByteArray = byteArrayOf(
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun pad32(raw: ByteArray): ByteArray {
        if (raw.size == 32) return raw
        if (raw.size > 32) return raw.copyOfRange(raw.size - 32, raw.size)
        return ByteArray(32 - raw.size) + raw
    }
}
