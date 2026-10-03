// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Delivery footer contract:
 * pending = fast icon · sent = one ✓ · read = two ✓✓ · failed = retry alert (no tick).
 */
class MessageDeliverySemanticsTest {
    private val base = Message(id = "m1", chat_id = "dm", sender_id = "me", body = "hi")

    @Test
    fun presentation_matrix_tickOrAlertExclusively() {
        val cases = listOf(
            null to MessageDeliveryPresentation(MessageDeliveryTick.SENT, null),
            "" to MessageDeliveryPresentation(MessageDeliveryTick.SENT, null),
            MessageStatus.SENDING to MessageDeliveryPresentation(MessageDeliveryTick.PENDING, null),
            MessageStatus.SENT to MessageDeliveryPresentation(MessageDeliveryTick.SENT, null),
            MessageStatus.DELIVERED to MessageDeliveryPresentation(MessageDeliveryTick.SENT, null),
            MessageStatus.READ to MessageDeliveryPresentation(MessageDeliveryTick.READ, null),
            MessageStatus.FAILED to MessageDeliveryPresentation(null, MessageSendAlert.FAILED),
            "server_weird" to MessageDeliveryPresentation(MessageDeliveryTick.SENT, null),
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, MessageDeliverySemantics.presentation(raw), "raw=$raw")
            assertEquals(
                expected,
                MessageDeliverySemantics.presentation(base.copy(status = raw)),
                "message status=$raw",
            )
            // Never both tick and alert.
            val p = MessageDeliverySemantics.presentation(raw)
            assertTrue(p.tick == null || p.alert == null, "raw=$raw must be exclusive")
        }
    }

    @Test
    fun failed_hasAlertNoTick() {
        val p = MessageDeliverySemantics.presentation(MessageStatus.FAILED)
        assertNull(p.tick)
        assertEquals(MessageSendAlert.FAILED, p.alert)
    }

    @Test
    fun predicates_areMutuallyExclusivePerStatus() {
        data class Predicates(
            val sending: Boolean,
            val failed: Boolean,
            val deliveredTick: Boolean,
            val read: Boolean,
        )

        fun Message.predicates() = Predicates(
            sending = isSending(),
            failed = isFailed(),
            deliveredTick = isDelivered(),
            read = isRead(),
        )

        val expected = mapOf(
            MessageStatus.SENDING to Predicates(true, false, false, false),
            MessageStatus.FAILED to Predicates(false, true, false, false),
            MessageStatus.SENT to Predicates(false, false, true, false),
            MessageStatus.DELIVERED to Predicates(false, false, true, false),
            MessageStatus.READ to Predicates(false, false, false, true),
            null to Predicates(false, false, true, false),
        )
        for ((status, want) in expected) {
            val got = base.copy(status = status).predicates()
            assertEquals(want, got, "status=$status")
            assertEquals(1, listOf(got.sending, got.failed, got.deliveredTick, got.read).count { it })
        }
    }

    @Test
    fun showsSingleCheck_onlySentTick() {
        assertTrue(MessageDeliverySemantics.showsSingleCheck(MessageStatus.SENT))
        assertTrue(MessageDeliverySemantics.showsSingleCheck(MessageStatus.DELIVERED))
        assertTrue(MessageDeliverySemantics.showsSingleCheck(null))
        assertFalse(MessageDeliverySemantics.showsSingleCheck(MessageStatus.SENDING))
        assertFalse(MessageDeliverySemantics.showsSingleCheck(MessageStatus.FAILED))
        assertFalse(MessageDeliverySemantics.showsSingleCheck(MessageStatus.READ))
    }

    @Test
    fun showsDoubleCheck_onlyReadTick() {
        assertTrue(MessageDeliverySemantics.showsDoubleCheck(MessageStatus.READ))
        assertFalse(MessageDeliverySemantics.showsDoubleCheck(MessageStatus.SENT))
        assertFalse(MessageDeliverySemantics.showsDoubleCheck(MessageStatus.SENDING))
        assertFalse(MessageDeliverySemantics.showsDoubleCheck(MessageStatus.FAILED))
    }

    @Test
    fun transition_sendingToSentToRead() {
        val outbox = base.copy(status = MessageStatus.SENDING)
        assertEquals(MessageDeliveryTick.PENDING, MessageDeliverySemantics.presentation(outbox).tick)

        val accepted = outbox.copy(status = MessageDeliverySemantics.AFTER_RELAY_ACCEPT_STATUS)
        assertEquals(MessageDeliveryTick.SENT, MessageDeliverySemantics.presentation(accepted).tick)
        assertTrue(accepted.isDelivered())
        assertFalse(UndeliveredMessagePolicy.isOwnUndelivered(accepted, "me"))

        val read = accepted.copy(status = MessageDeliverySemantics.AFTER_PEER_READ_RECEIPT_STATUS)
        assertEquals(MessageDeliveryTick.READ, MessageDeliverySemantics.presentation(read).tick)
        assertTrue(read.isRead())
    }

    @Test
    fun transition_sendingToFailed_thenRetry() {
        val failed = base.copy(status = MessageStatus.FAILED)
        assertEquals(MessageSendAlert.FAILED, MessageDeliverySemantics.presentation(failed).alert)
        assertTrue(UndeliveredMessagePolicy.isOwnUndelivered(failed, "me"))

        assertTrue(MessageStatus.canTransition(MessageStatus.FAILED, MessageStatus.SENDING))
        assertTrue(MessageStatus.canTransition(MessageStatus.FAILED, MessageStatus.SENT))
    }

    @Test
    fun afterRelayAccept_constants() {
        assertEquals(MessageStatus.SENT, MessageDeliverySemantics.AFTER_RELAY_ACCEPT_STATUS)
        assertEquals(MessageStatus.READ, MessageDeliverySemantics.AFTER_PEER_READ_RECEIPT_STATUS)
    }
}
