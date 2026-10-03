// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import com.glagolitsa.security.LocalAuthAvailability

data class BiometricUnlockCandidate(
    val userId: String,
    val username: String?,
)

object BiometricUnlockPolicy {
    fun shouldGateColdStart(
        hasStoredSession: Boolean,
        biometricUnlockEnabled: Boolean,
        reauthRequired: Boolean,
    ): Boolean = hasStoredSession && biometricUnlockEnabled && !reauthRequired

    fun shouldOfferUnlockButton(
        candidate: BiometricUnlockCandidate?,
        availability: LocalAuthAvailability,
    ): Boolean = candidate != null && availability == LocalAuthAvailability.Available
}
