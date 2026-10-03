// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.call

/**
 * Incoming call privacy: unknown callers do not get full-screen / high-priority
 * ring until a request is accepted. Push remains wake-only (opaque call_id).
 */
object IncomingCallPrivacyGate {
    enum class RingMode {
        /** Full ring UI for accepted contacts/chats. */
        FULL_SCREEN,
        /** Quiet request row — no full-screen interrupt. */
        REQUEST_ONLY,
        /** Blocked — no UI. */
        BLOCKED,
    }

    data class Decision(
        val mode: RingMode,
        val highPriority: Boolean,
    )

    fun decide(
        isBlocked: Boolean,
        isAcceptedContactOrChat: Boolean,
        unknownRequestGateEnabled: Boolean,
    ): Decision {
        if (isBlocked) {
            return Decision(RingMode.BLOCKED, highPriority = false)
        }
        if (isAcceptedContactOrChat) {
            return Decision(RingMode.FULL_SCREEN, highPriority = true)
        }
        if (unknownRequestGateEnabled) {
            return Decision(RingMode.REQUEST_ONLY, highPriority = false)
        }
        // Gate disabled: still avoid high-priority for unknowns.
        return Decision(RingMode.REQUEST_ONLY, highPriority = false)
    }

    /** Allowed push payload keys (wake-only). */
    val pushAllowlist = setOf("type", "call_id", "collapse_id")
}
