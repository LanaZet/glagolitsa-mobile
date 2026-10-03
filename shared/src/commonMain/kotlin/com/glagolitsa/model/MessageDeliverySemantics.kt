// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Own-message footer contract.
 *
 * ## Ticks (ladder)
 * | Wire | Tick | Glyph |
 * |------|------|--------|
 * | sending | [MessageDeliveryTick.PENDING] | clock (not a check) |
 * | sent / delivered(legacy) / null | [MessageDeliveryTick.SENT] | one ✓ |
 * | read | [MessageDeliveryTick.READ] | two ✓✓ |
 *
 * ## Alert (outside the ladder — Signal-style)
 * | Wire | Alert |
 * |------|--------|
 * | failed | [MessageSendAlert.FAILED] — retry, no tick |
 *
 * Single ✓ is relay-accept only; peer decrypt is invisible until read.
 */
object MessageDeliverySemantics {
    const val AFTER_RELAY_ACCEPT_STATUS: String = MessageStatus.SENT
    const val AFTER_PEER_READ_RECEIPT_STATUS: String = MessageStatus.READ

    /**
     * Split presentation: either a delivery tick **or** a send alert (never both).
     */
    fun presentation(status: String?): MessageDeliveryPresentation =
        when (MessageStatus.normalize(status)) {
            MessageStatus.FAILED -> MessageDeliveryPresentation(
                tick = null,
                alert = MessageSendAlert.FAILED,
            )
            MessageStatus.SENDING -> MessageDeliveryPresentation(
                tick = MessageDeliveryTick.PENDING,
                alert = null,
            )
            MessageStatus.READ -> MessageDeliveryPresentation(
                tick = MessageDeliveryTick.READ,
                alert = null,
            )
            else -> MessageDeliveryPresentation(
                tick = MessageDeliveryTick.SENT,
                alert = null,
            )
        }

    fun presentation(message: Message): MessageDeliveryPresentation =
        presentation(message.status)

    fun showsSingleCheck(status: String?): Boolean =
        presentation(status).tick == MessageDeliveryTick.SENT

    fun showsDoubleCheck(status: String?): Boolean =
        presentation(status).tick == MessageDeliveryTick.READ
}

/** Delivery ticks only — PENDING / SENT / READ. */
enum class MessageDeliveryTick {
    /** Outbox still working — non-check icon. */
    PENDING,

    /** Relay accepted — one check. */
    SENT,

    /** Peer read receipt — two checks. */
    READ,
}

/** Send failure alert — not part of the tick ladder. */
enum class MessageSendAlert {
    FAILED,
}

/**
 * Footer model: exactly one of [tick] or [alert] is non-null for own messages
 * (or both null for edge cases).
 */
data class MessageDeliveryPresentation(
    val tick: MessageDeliveryTick?,
    val alert: MessageSendAlert?,
) {
    init {
        require(tick == null || alert == null) {
            "tick and alert are mutually exclusive"
        }
    }
}
