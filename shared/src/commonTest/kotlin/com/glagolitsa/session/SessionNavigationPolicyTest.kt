// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stay-in-chat / session shell invariants.
 *
 * Mirrors the matrix used by Element X session routing, Mattermost "stay logged in",
 * and Signal soft session recovery: process death / token refresh must not look like logout.
 */
class SessionNavigationPolicyTest {

    // --- Cold start (Mattermost / Element: restore → main) ---

    @Test
    fun initialRoute_whenRestored_isMainNotLogin() {
        assertTrue(SessionNavigationPolicy.initialRouteIsMain(sessionRestored = true))
        assertFalse(SessionNavigationPolicy.initialRouteIsMain(sessionRestored = false))
    }

    // --- Soft reauth (Element: reauthRequired + identity kept) ---

    @Test
    fun reauthLogin_onlyWhenReauthRequiredAndUserKnown() {
        assertTrue(SessionNavigationPolicy.shouldRouteToReauthLogin(reauthRequired = true, hasUser = true))
        assertFalse(SessionNavigationPolicy.shouldRouteToReauthLogin(reauthRequired = true, hasUser = false))
        assertFalse(SessionNavigationPolicy.shouldRouteToReauthLogin(reauthRequired = false, hasUser = true))
        assertFalse(SessionNavigationPolicy.shouldRouteToReauthLogin(reauthRequired = false, hasUser = false))
    }

    // --- Full logout only when both token and user gone ---

    @Test
    fun loggedOutLogin_requiresBothTokenAndUserMissing() {
        assertTrue(
            SessionNavigationPolicy.shouldRouteToLoggedOutLogin(
                hasAccessToken = false,
                hasUser = false,
                alreadyOnAuthScreen = false,
            ),
        )
        assertFalse(
            SessionNavigationPolicy.shouldRouteToLoggedOutLogin(
                hasAccessToken = false,
                hasUser = true, // mid-refresh / offline shell
                alreadyOnAuthScreen = false,
            ),
            "token null + user present must not kick to login",
        )
        assertFalse(
            SessionNavigationPolicy.shouldRouteToLoggedOutLogin(
                hasAccessToken = true,
                hasUser = false,
                alreadyOnAuthScreen = false,
            ),
        )
        assertFalse(
            SessionNavigationPolicy.shouldRouteToLoggedOutLogin(
                hasAccessToken = false,
                hasUser = false,
                alreadyOnAuthScreen = true,
            ),
        )
    }

    // --- Stay in chat shell (Telegram: temporary access loss ≠ logout) ---

    @Test
    fun keepAuthenticatedShell_whileUserBoundAndNoHardReauth() {
        assertTrue(SessionNavigationPolicy.shouldKeepAuthenticatedShell(hasUser = true, reauthRequired = false))
        assertFalse(SessionNavigationPolicy.shouldKeepAuthenticatedShell(hasUser = true, reauthRequired = true))
        assertFalse(SessionNavigationPolicy.shouldKeepAuthenticatedShell(hasUser = false, reauthRequired = false))
    }

    @Test
    fun attemptRecovery_onlyWhenUserPresentTokenMissingNoReauth() {
        assertTrue(
            SessionNavigationPolicy.shouldAttemptSessionRecovery(
                hasUser = true,
                hasAccessToken = false,
                reauthRequired = false,
            ),
        )
        assertFalse(
            SessionNavigationPolicy.shouldAttemptSessionRecovery(
                hasUser = true,
                hasAccessToken = true,
                reauthRequired = false,
            ),
        )
        assertFalse(
            SessionNavigationPolicy.shouldAttemptSessionRecovery(
                hasUser = true,
                hasAccessToken = false,
                reauthRequired = true,
            ),
        )
        assertFalse(
            SessionNavigationPolicy.shouldAttemptSessionRecovery(
                hasUser = false,
                hasAccessToken = false,
                reauthRequired = false,
            ),
        )
    }

    // --- Explicit leave-chat matrix (user-visible "kicked out of chat") ---

    @Test
    fun leaveChat_whenTransientTokenLoss_staysInChat() {
        assertFalse(
            SessionNavigationPolicy.shouldLeaveChatForAuth(
                hasUser = true,
                hasAccessToken = false,
                reauthRequired = false,
            ),
            "clearAccessToken / 401 recovery must not leave ChatDetail",
        )
    }

    @Test
    fun leaveChat_whenOnlineSessionHealthy_staysInChat() {
        assertFalse(
            SessionNavigationPolicy.shouldLeaveChatForAuth(
                hasUser = true,
                hasAccessToken = true,
                reauthRequired = false,
            ),
        )
    }

    @Test
    fun leaveChat_whenSoftReauthRequired_leavesChat() {
        assertTrue(
            SessionNavigationPolicy.shouldLeaveChatForAuth(
                hasUser = true,
                hasAccessToken = false,
                reauthRequired = true,
            ),
        )
    }

    @Test
    fun leaveChat_whenFullLogout_leavesChat() {
        assertTrue(
            SessionNavigationPolicy.shouldLeaveChatForAuth(
                hasUser = false,
                hasAccessToken = false,
                reauthRequired = false,
            ),
        )
    }
}
