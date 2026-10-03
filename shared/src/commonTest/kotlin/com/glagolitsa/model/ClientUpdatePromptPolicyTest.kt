// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Soft update must not cover an open chat. Hard gate stays blocking everywhere.
 */
class ClientUpdatePromptPolicyTest {

    @Test
    fun softBanner_onlyOnMainShellWhenNotDismissed() {
        assertTrue(
            ClientUpdatePromptPolicy.shouldShowSoftBanner(
                kind = UpdateDecisionKind.SOFT,
                dismissed = false,
                onMainShell = true,
            ),
        )
        assertFalse(
            ClientUpdatePromptPolicy.shouldShowSoftBanner(
                kind = UpdateDecisionKind.SOFT,
                dismissed = false,
                onMainShell = false,
            ),
        )
        assertFalse(
            ClientUpdatePromptPolicy.shouldShowSoftBanner(
                kind = UpdateDecisionKind.SOFT,
                dismissed = true,
                onMainShell = true,
            ),
        )
    }

    @Test
    fun softBanner_hiddenForHardNoneAndMissing() {
        assertFalse(
            ClientUpdatePromptPolicy.shouldShowSoftBanner(
                kind = UpdateDecisionKind.HARD,
                dismissed = false,
                onMainShell = true,
            ),
        )
        assertFalse(
            ClientUpdatePromptPolicy.shouldShowSoftBanner(
                kind = UpdateDecisionKind.NONE,
                dismissed = false,
                onMainShell = true,
            ),
        )
        assertFalse(
            ClientUpdatePromptPolicy.shouldShowSoftBanner(
                kind = null,
                dismissed = false,
                onMainShell = true,
            ),
        )
    }

    @Test
    fun hardGate_onlyWhenHard() {
        assertTrue(ClientUpdatePromptPolicy.shouldShowHardGate(UpdateDecisionKind.HARD))
        assertFalse(ClientUpdatePromptPolicy.shouldShowHardGate(UpdateDecisionKind.SOFT))
        assertFalse(ClientUpdatePromptPolicy.shouldShowHardGate(UpdateDecisionKind.NONE))
        assertFalse(ClientUpdatePromptPolicy.shouldShowHardGate(null))
    }
}
