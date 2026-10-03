// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import com.glagolitsa.util.sha256Hex

/**
 * Deterministic device_id for an account on this install.
 * Survives app reinstalls that wipe encrypted prefs when the installation seed
 * (ANDROID_ID / desktop installation file) stays the same — so the server does
 * not accumulate zombie active devices.
 */
fun stableDeviceId(accountId: String, installationSeed: String): String {
    val material = "glagolitsa-device-v1|$installationSeed|$accountId"
    val digest = sha256Hex(material.encodeToByteArray())
    // UUID-shaped 8-4-4-4-12 from first 32 hex chars.
    return buildString(36) {
        append(digest, 0, 8)
        append('-')
        append(digest, 8, 12)
        append('-')
        append(digest, 12, 16)
        append('-')
        append(digest, 16, 20)
        append('-')
        append(digest, 20, 32)
    }
}
