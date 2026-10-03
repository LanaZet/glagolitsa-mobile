// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import com.glagolitsa.model.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * In-memory session lifecycle.
 *
 * Industry pattern (Signal AccountManager / Element SessionStore / Telegram):
 * - Access token can drop without wiping identity (refresh window).
 * - reauthRequired is the only soft "need password again" flag.
 * - clear() is the hard logout memory wipe.
 */
class SessionStoreTest {
    private val user = User(id = "user-1", username = "alice", email = null)

    @BeforeTest
    fun reset() {
        SessionStore.clear()
    }

    @AfterTest
    fun cleanup() {
        SessionStore.clear()
    }

    @Test
    fun clearAccessToken_keepsUserAndRefreshToken() {
        SessionStore.setSession("access-1", user, "refresh-1")

        SessionStore.clearAccessToken()

        assertNull(SessionStore.token.value)
        assertEquals("refresh-1", SessionStore.refreshToken.value)
        assertEquals(user, SessionStore.user.value)
        assertFalse(SessionStore.reauthRequired.value)
    }

    @Test
    fun clearAccessToken_doesNotMarkReauth_soChatShellCanStay() {
        SessionStore.setSession("access-1", user, "refresh-1")
        SessionStore.clearAccessToken()

        // Precondition for MessengerApp recovery loop + stay-in-chat policy.
        assertTrue(
            SessionNavigationPolicy.shouldKeepAuthenticatedShell(
                hasUser = SessionStore.user.value != null,
                reauthRequired = SessionStore.reauthRequired.value,
            ),
        )
        assertFalse(
            SessionNavigationPolicy.shouldLeaveChatForAuth(
                hasUser = SessionStore.user.value != null,
                hasAccessToken = SessionStore.token.value != null,
                reauthRequired = SessionStore.reauthRequired.value,
            ),
        )
    }

    @Test
    fun markReauthRequired_keepsUserAndClearsAccessToken() {
        SessionStore.setSession("access-1", user, "refresh-1")

        SessionStore.markReauthRequired()

        assertNull(SessionStore.token.value)
        assertEquals(user, SessionStore.user.value)
        assertTrue(SessionStore.reauthRequired.value)
        assertTrue(
            SessionNavigationPolicy.shouldLeaveChatForAuth(
                hasUser = true,
                hasAccessToken = false,
                reauthRequired = true,
            ),
        )
    }

    @Test
    fun setSession_clearsReauthRequired() {
        SessionStore.markReauthRequired()

        SessionStore.setSession("access-2", user, "refresh-2")

        assertFalse(SessionStore.reauthRequired.value)
        assertEquals("access-2", SessionStore.token.value)
    }

    @Test
    fun setUserOnly_bindsIdentityWithoutAccessToken() {
        SessionStore.setUserOnly(user)

        assertEquals(user, SessionStore.user.value)
        assertNull(SessionStore.token.value)
        assertFalse(SessionStore.reauthRequired.value)
        assertTrue(
            SessionNavigationPolicy.shouldAttemptSessionRecovery(
                hasUser = true,
                hasAccessToken = false,
                reauthRequired = false,
            ),
        )
    }

    @Test
    fun clear_wipesIdentityAndForcesLoggedOutRoute() {
        SessionStore.setSession("access-1", user, "refresh-1")
        SessionStore.clear()

        assertNull(SessionStore.token.value)
        assertNull(SessionStore.refreshToken.value)
        assertNull(SessionStore.user.value)
        assertFalse(SessionStore.reauthRequired.value)
        assertTrue(
            SessionNavigationPolicy.shouldLeaveChatForAuth(
                hasUser = false,
                hasAccessToken = false,
                reauthRequired = false,
            ),
        )
    }
}
