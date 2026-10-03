// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.passkey

import com.glagolitsa.model.WebAuthnBeginResponse
import com.glagolitsa.util.sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

interface PasskeyRecordStore {
    suspend fun loadJson(): String
    suspend fun saveJson(value: String)
}

@Serializable
internal data class StoredPasskey(
    val credentialId: String,
    val d: String,
    val x: String,
    val y: String,
    val rpId: String,
    val userHandle: String,
    val signCount: Int,
)

class PasskeyClient(
    private val store: PasskeyRecordStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun hasPasskey(): Boolean = loadAll().isNotEmpty()

    suspend fun create(options: WebAuthnBeginResponse): JsonObject {
        val key = PasskeyEcdsa.generate()
        val credentialId = PasskeyEcdsa.randomBytes(32)
        val clientData = WebAuthnEncoder.clientDataJson(
            type = "webauthn.create",
            challenge = options.challenge,
            origin = options.origin,
        )
        val authData = WebAuthnEncoder.authenticatorData(
            rpId = options.rp_id,
            signCount = 1,
            attested = true,
            credentialId = credentialId,
            key = key,
        )
        val attestation = WebAuthnEncoder.attestationObject(authData)
        saveAll(
            loadAll() + StoredPasskey(
                credentialId = Base64Url.encode(credentialId),
                d = Base64Url.encode(key.d),
                x = Base64Url.encode(key.x),
                y = Base64Url.encode(key.y),
                rpId = options.rp_id,
                userHandle = options.user_id.orEmpty(),
                signCount = 1,
            ),
        )
        return WebAuthnEncoder.publicKeyCredential(
            credentialId = credentialId,
            clientData = clientData,
            attestationObject = attestation,
        )
    }

    suspend fun get(options: WebAuthnBeginResponse): JsonObject {
        val allow = options.allow_credential_ids.orEmpty().toSet()
        val records = loadAll().filter { it.rpId == options.rp_id || it.rpId.isBlank() }
        val match = records.firstOrNull { allow.isEmpty() || it.credentialId in allow }
            ?: error("На этом устройстве нет passkey для этого аккаунта")
        val nextCount = match.signCount + 1
        val key = PasskeyEcdsaKey(
            d = Base64Url.decode(match.d),
            x = Base64Url.decode(match.x),
            y = Base64Url.decode(match.y),
        )
        val credentialId = Base64Url.decode(match.credentialId)
        val clientData = WebAuthnEncoder.clientDataJson(
            type = "webauthn.get",
            challenge = options.challenge,
            origin = options.origin,
        )
        val authData = WebAuthnEncoder.authenticatorData(
            rpId = options.rp_id,
            signCount = nextCount,
            attested = false,
        )
        val signature = PasskeyEcdsa.sign(key, authData + sha256(clientData))
        saveAll(
            loadAll().map { record ->
                if (record.credentialId == match.credentialId) record.copy(signCount = nextCount) else record
            },
        )
        val userHandle = match.userHandle.takeIf { it.isNotBlank() }?.let(Base64Url::decode)
        return WebAuthnEncoder.publicKeyCredential(
            credentialId = credentialId,
            clientData = clientData,
            authenticatorData = authData,
            signature = signature,
            userHandle = userHandle,
        )
    }

    private suspend fun loadAll(): List<StoredPasskey> {
        val raw = store.loadJson()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StoredPasskey.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private suspend fun saveAll(records: List<StoredPasskey>) {
        store.saveJson(json.encodeToString(ListSerializer(StoredPasskey.serializer()), records))
    }
}
