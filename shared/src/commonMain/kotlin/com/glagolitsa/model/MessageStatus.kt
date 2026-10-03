// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

object MessageStatus {
    const val SENDING = "sending"
    const val SENT = "sent"
    const val DELIVERED = "delivered"
    const val READ = "read"
    const val FAILED = "failed"

    private val KNOWN_DELIVERY_STATUSES = setOf(SENDING, SENT, DELIVERED, READ, FAILED)

    fun isKnownDeliveryStatus(raw: String?): Boolean =
        raw?.trim() in KNOWN_DELIVERY_STATUSES

    /**
     * Backward-compatible normalization:
     * - null/blank legacy rows mean server-accepted outgoing message.
     * - old "delivered" rows are treated as server-accepted single-check status.
     */
    fun normalize(raw: String?): String =
        when (raw?.trim().orEmpty()) {
            "", SENT, DELIVERED -> SENT
            SENDING -> SENDING
            READ -> READ
            FAILED -> FAILED
            else -> SENT
        }

    /**
     * Whether a stored status may move to [proposed].
     *
     * Allowed:
     * - SENDING → SENT | FAILED
     * - FAILED → SENDING | SENT (retry / recovery)
     * - SENT → READ
     * - same status (idempotent)
     *
     * Forbidden: any downgrade (e.g. READ→SENT, SENT→SENDING, READ→FAILED).
     */
    fun canTransition(current: String?, proposed: String?): Boolean {
        val from = normalize(current)
        val to = normalize(proposed)
        if (from == to) return true
        return when (from) {
            SENDING -> to == SENT || to == FAILED
            FAILED -> to == SENDING || to == SENT
            SENT -> to == READ
            READ -> false
            else -> false
        }
    }

    /**
     * Apply [proposed] only if [canTransition]; otherwise keep [current] (normalized).
     * Returns the status that should be persisted.
     */
    fun applyTransition(current: String?, proposed: String): String {
        val from = normalize(current)
        val to = normalize(proposed)
        return if (canTransition(from, to)) to else from
    }
}

fun Message.deliveryStatus(): String = MessageStatus.normalize(status)

fun Message.isSending(): Boolean = deliveryStatus() == MessageStatus.SENDING

fun Message.isFailed(): Boolean = deliveryStatus() == MessageStatus.FAILED

fun Message.isDelivered(): Boolean = MessageDeliverySemantics.showsSingleCheck(status)

fun Message.isRead(): Boolean = MessageDeliverySemantics.showsDoubleCheck(status)
