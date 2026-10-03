// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire normalize + monotonic transitions (stage 3).
 */
class MessageStatusTest {
    @Test
    fun normalize_matrix() {
        val cases = listOf(
            null to MessageStatus.SENT,
            "" to MessageStatus.SENT,
            "   " to MessageStatus.SENT,
            MessageStatus.SENT to MessageStatus.SENT,
            MessageStatus.DELIVERED to MessageStatus.SENT,
            MessageStatus.SENDING to MessageStatus.SENDING,
            MessageStatus.READ to MessageStatus.READ,
            MessageStatus.FAILED to MessageStatus.FAILED,
            "server_weird_status" to MessageStatus.SENT,
            " sent " to MessageStatus.SENT,
            " delivered " to MessageStatus.SENT,
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, MessageStatus.normalize(raw), "raw=$raw")
        }
    }

    @Test
    fun deliveryStatus_usesNormalize() {
        val message = Message(id = "m1", chat_id = "c1", sender_id = "u1", body = "x")
        assertEquals(MessageStatus.SENT, message.copy(status = null).deliveryStatus())
        assertEquals(MessageStatus.SENT, message.copy(status = MessageStatus.DELIVERED).deliveryStatus())
        assertEquals(MessageStatus.SENDING, message.copy(status = MessageStatus.SENDING).deliveryStatus())
        assertEquals(MessageStatus.FAILED, message.copy(status = MessageStatus.FAILED).deliveryStatus())
        assertEquals(MessageStatus.READ, message.copy(status = MessageStatus.READ).deliveryStatus())
    }

    @Test
    fun isKnownDeliveryStatus_acceptsOnlyDeliveryWireValues() {
        assertTrue(MessageStatus.isKnownDeliveryStatus(MessageStatus.SENDING))
        assertTrue(MessageStatus.isKnownDeliveryStatus(" delivered "))
        assertFalse(MessageStatus.isKnownDeliveryStatus(null))
        assertFalse(MessageStatus.isKnownDeliveryStatus(""))
        assertFalse(MessageStatus.isKnownDeliveryStatus("на встрече"))
        assertFalse(MessageStatus.isKnownDeliveryStatus("Пара слов о себе"))
    }

    @Test
    fun canTransition_allowsForwardAndRetryOnly() {
        // Forward ladder
        assertTrue(MessageStatus.canTransition(MessageStatus.SENDING, MessageStatus.SENT))
        assertTrue(MessageStatus.canTransition(MessageStatus.SENDING, MessageStatus.FAILED))
        assertTrue(MessageStatus.canTransition(MessageStatus.SENT, MessageStatus.READ))
        // Retry / recovery from failed
        assertTrue(MessageStatus.canTransition(MessageStatus.FAILED, MessageStatus.SENDING))
        assertTrue(MessageStatus.canTransition(MessageStatus.FAILED, MessageStatus.SENT))
        // Idempotent
        assertTrue(MessageStatus.canTransition(MessageStatus.SENT, MessageStatus.SENT))
        assertTrue(MessageStatus.canTransition(MessageStatus.READ, MessageStatus.READ))
        // Downgrades blocked
        assertFalse(MessageStatus.canTransition(MessageStatus.READ, MessageStatus.SENT))
        assertFalse(MessageStatus.canTransition(MessageStatus.READ, MessageStatus.SENDING))
        assertFalse(MessageStatus.canTransition(MessageStatus.READ, MessageStatus.FAILED))
        assertFalse(MessageStatus.canTransition(MessageStatus.SENT, MessageStatus.SENDING))
        assertFalse(MessageStatus.canTransition(MessageStatus.SENT, MessageStatus.FAILED))
        assertFalse(MessageStatus.canTransition(MessageStatus.SENDING, MessageStatus.READ)) // skip SENT
    }

    @Test
    fun applyTransition_keepsCurrentWhenBlocked() {
        assertEquals(
            MessageStatus.READ,
            MessageStatus.applyTransition(MessageStatus.READ, MessageStatus.SENT),
        )
        assertEquals(
            MessageStatus.SENT,
            MessageStatus.applyTransition(MessageStatus.SENT, MessageStatus.SENDING),
        )
        assertEquals(
            MessageStatus.SENT,
            MessageStatus.applyTransition(MessageStatus.SENDING, MessageStatus.SENT),
        )
        assertEquals(
            MessageStatus.READ,
            MessageStatus.applyTransition(MessageStatus.SENT, MessageStatus.READ),
        )
        // Legacy delivered current → treated as SENT
        assertEquals(
            MessageStatus.READ,
            MessageStatus.applyTransition(MessageStatus.DELIVERED, MessageStatus.READ),
        )
    }

}
