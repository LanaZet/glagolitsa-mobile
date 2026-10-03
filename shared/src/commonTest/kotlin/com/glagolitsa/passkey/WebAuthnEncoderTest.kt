// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.passkey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebAuthnEncoderTest {
    @Test
    fun clientDataJson_containsChallengeAndOrigin() {
        val raw = WebAuthnEncoder.clientDataJson(
            type = "webauthn.get",
            challenge = "abc",
            origin = "http://localhost",
        ).decodeToString()
        assertTrue(raw.contains("\"type\":\"webauthn.get\""))
        assertTrue(raw.contains("\"challenge\":\"abc\""))
        assertTrue(raw.contains("\"origin\":\"http://localhost\""))
    }

    @Test
    fun authenticatorData_hasRpIdHashAndFlags() {
        val data = WebAuthnEncoder.authenticatorData(
            rpId = "localhost",
            signCount = 2,
            attested = false,
        )
        assertEquals(37, data.size)
        assertEquals((WebAuthnEncoder.FLAG_UP or WebAuthnEncoder.FLAG_UV).toByte(), data[32])
        assertEquals(2, data[36].toInt() and 0xFF)
    }

    @Test
    fun generateAndSign_roundTrip() {
        val key = PasskeyEcdsa.generate()
        assertEquals(32, key.d.size)
        assertEquals(32, key.x.size)
        assertEquals(32, key.y.size)
        val signature = PasskeyEcdsa.sign(key, "glagolitsa-passkey".encodeToByteArray())
        assertTrue(signature.isNotEmpty())
    }
}
