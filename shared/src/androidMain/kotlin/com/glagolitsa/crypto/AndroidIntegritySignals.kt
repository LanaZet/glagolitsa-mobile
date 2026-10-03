// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import com.glagolitsa.model.DeviceAttestation

object AndroidIntegritySignals {
    @Volatile
    private var playIntegrityTokenProvider: (suspend () -> String?)? = null

    fun setPlayIntegrityTokenProvider(provider: (suspend () -> String?)?) {
        playIntegrityTokenProvider = provider
    }

    suspend fun deviceAttestation(): DeviceAttestation? {
        val token = playIntegrityTokenProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return DeviceAttestation(
            provider = "play_integrity",
            token = token,
        )
    }
}
