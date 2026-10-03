// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure outbox backoff / attempt limits (Signal-style job policy). */
class OutboxRetryPolicyTest {
    @Test
    fun outboxRetryDelay_growsExponentiallyAndCaps() {
        val first = outboxRetryDelayMs(1)
        val second = outboxRetryDelayMs(2)
        assertTrue(second > first)
        assertEquals(30 * 60 * 1000L, outboxRetryDelayMs(20))
    }

    @Test
    fun waitingForRecipient_isNotASendFailure() {
        assertTrue(OutboxRecoverableErrors.isWaitingForRecipient(OutboxRecoverableErrors.NO_DEVICES))
        assertTrue(OutboxRecoverableErrors.isWaitingForRecipient("у контакта нет зарегистрированных устройств"))
        assertFalse(OutboxRecoverableErrors.isWaitingForRecipient(OutboxRecoverableErrors.NO_ENVELOPES))
        assertFalse(OutboxRecoverableErrors.isWaitingForRecipient("Failed to connect"))
        assertFalse(OutboxRecoverableErrors.isWaitingForRecipient(null))
    }

    @Test
    fun outboxMaxAttempts_isFiniteAndPositive() {
        assertTrue(OUTBOX_MAX_ATTEMPTS in 2..50)
    }

    @Test
    fun outboxProcessingResult_combinesAttemptsAndKeepsLastError() {
        val first = OutboxProcessingResult(
            attempted = 1,
            transientFailures = 1,
            lastError = "Failed to connect",
        )
        val second = OutboxProcessingResult(
            attempted = 2,
            sent = 2,
        )

        val combined = first + second

        assertEquals(3, combined.attempted)
        assertEquals(2, combined.sent)
        assertEquals(1, combined.transientFailures)
        assertEquals("Failed to connect", combined.lastError)
        assertTrue(combined.hasFailures)
    }
}
