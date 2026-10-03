// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import com.glagolitsa.security.LocalAuthAvailability
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BiometricUnlockPolicyTest {
    private val candidate = BiometricUnlockCandidate(userId = "user-1", username = "alice")

    @Test
    fun coldStart_isGatedOnlyForStoredEnabledSessionWithoutHardReauth() {
        assertTrue(
            BiometricUnlockPolicy.shouldGateColdStart(
                hasStoredSession = true,
                biometricUnlockEnabled = true,
                reauthRequired = false,
            ),
        )
        assertFalse(
            BiometricUnlockPolicy.shouldGateColdStart(
                hasStoredSession = false,
                biometricUnlockEnabled = true,
                reauthRequired = false,
            ),
        )
        assertFalse(
            BiometricUnlockPolicy.shouldGateColdStart(
                hasStoredSession = true,
                biometricUnlockEnabled = false,
                reauthRequired = false,
            ),
        )
        assertFalse(
            BiometricUnlockPolicy.shouldGateColdStart(
                hasStoredSession = true,
                biometricUnlockEnabled = true,
                reauthRequired = true,
            ),
        )
    }

    @Test
    fun unlockButton_requiresCandidateAndAvailableAuthenticator() {
        assertTrue(
            BiometricUnlockPolicy.shouldOfferUnlockButton(
                candidate = candidate,
                availability = LocalAuthAvailability.Available,
            ),
        )
        assertFalse(
            BiometricUnlockPolicy.shouldOfferUnlockButton(
                candidate = null,
                availability = LocalAuthAvailability.Available,
            ),
        )
        assertFalse(
            BiometricUnlockPolicy.shouldOfferUnlockButton(
                candidate = candidate,
                availability = LocalAuthAvailability.NoBiometricEnrolled,
            ),
        )
    }
}
