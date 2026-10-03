// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetadataTest {
    @Test
    fun pairwiseId_isDeterministicPerAccountPair() {
        val alice = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val bob = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        assertEquals(
            pairwiseIdForPair(alice, bob),
            pairwiseIdForPair(bob, alice),
        )
    }

    @Test
    fun pairwiseId_roundTripsThroughCiphertextPrefix() {
        val id = pairwiseIdForPair("user-a", "user-b")
        val prefixed = prependPairwiseId(id, byteArrayOf(1, 2, 3))
        val (parsedId, payload) = extractPairwiseId(prefixed)!!
        assertEquals(id, parsedId)
        assertEquals(byteArrayOf(1, 2, 3).toList(), payload.toList())
    }

    @Test
    fun sealedDmPayload_encodesAndDecodes() {
        val payload = SealedDmPayload(
            pairwise_id = "pair-1",
            sender_account_id = "sender",
            sender_device_id = "device",
            chat_id = "chat",
            body = "hello",
            client_message_id = "pending-1",
        )
        val decoded = decodeSealedDmPayload(encodeSealedDmPayload(payload))
        assertNotNull(decoded)
        assertEquals(payload, decoded)
    }

    @Test
    fun extractPairwiseId_rejectsShortCiphertext() {
        assertNull(extractPairwiseId(ByteArray(PAIRWISE_ID_BYTE_LENGTH)))
    }
}