// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.audio

/**
 * Sticky 50/50 A/B for call-time RNNoise-class enhancement.
 * Assignment is derived from the install seed so a device stays on one arm.
 */
object CallNsExperiment {
    const val VERSION = "call_ns_v1"

    fun assignArm(installationSeed: String, persisted: CallNsArm? = null): CallNsArm {
        if (persisted != null) return persisted
        val hash = stableHash("$VERSION:$installationSeed")
        return if ((hash and 1) == 0) CallNsArm.Base else CallNsArm.Enhanced
    }

    internal fun stableHash(value: String): Int {
        var hash = 5381
        for (char in value) {
            hash = (hash * 33) xor char.code
        }
        return hash
    }
}
