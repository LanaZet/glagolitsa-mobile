// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.call

/**
 * Client-side media key lifecycle for E2EE calls.
 *
 * Keys are generated on device and distributed as encrypted per-device offers
 * via the Go API (ciphertext only). This manager does not talk to LiveKit
 * directly; [CallMediaEngine] consumes the active key bytes.
 */
class CallMediaKeyManager {
    private var activeKey: ByteArray? = null

    /** Generate a new 32-byte media key for the call (rekey on join/leave). */
    fun generateKey(random: (Int) -> ByteArray = { size -> ByteArray(size) { 0 } }): ByteArray {
        val key = random(32)
        activeKey = key.copyOf()
        return key.copyOf()
    }

    fun setActiveKey(key: ByteArray) {
        activeKey = key.copyOf()
    }

    fun activeKeyOrNull(): ByteArray? = activeKey?.copyOf()

    fun clear() {
        activeKey?.fill(0)
        activeKey = null
    }

    /**
     * Whether [targetUserId]/[targetDeviceId] should receive a key offer.
     * Mirrors server KeyOfferEligible: only accepted/joined devices.
     */
    fun isEligibleTarget(inviteState: String?, left: Boolean): Boolean {
        if (left) return false
        return inviteState == "joined" || inviteState == "accepted"
    }
}
