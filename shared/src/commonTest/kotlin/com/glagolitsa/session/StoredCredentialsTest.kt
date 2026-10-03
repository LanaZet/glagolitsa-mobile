// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import com.glagolitsa.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoredCredentialsTest {
    @Test
    fun isExpired_returnsTrueWithinSkewWindow() {
        val credentials = StoredCredentials(
            accessToken = "token",
            refreshToken = "refresh",
            expiresAtEpochMs = currentTimeMillis() + 30_000L,
        )
        assertTrue(credentials.isExpired(skewMs = 60_000L))
    }

    @Test
    fun isExpired_returnsFalseWhenFarFromExpiry() {
        val credentials = StoredCredentials(
            accessToken = "token",
            refreshToken = "refresh",
            expiresAtEpochMs = currentTimeMillis() + 600_000L,
        )
        assertFalse(credentials.isExpired(skewMs = 60_000L))
    }

    @Test
    fun isExpired_returnsFalseWhenExpiryIsUnset() {
        val credentials = StoredCredentials(
            accessToken = "token",
            refreshToken = null,
            expiresAtEpochMs = 0L,
        )
        assertFalse(credentials.isExpired(skewMs = 60_000L))
    }

    @Test
    fun requiresRefresh_returnsFalseForExpiredTokenWithoutRefreshToken() {
        val credentials = StoredCredentials(
            accessToken = "token",
            refreshToken = null,
            expiresAtEpochMs = currentTimeMillis() - 1_000L,
        )
        assertFalse(credentials.requiresRefresh(skewMs = 60_000L))
    }

    @Test
    fun proactiveSkew_doesNotTreatFreshFiveMinuteTokenAsExpired() {
        // Server access TTL is ~300s; skew must leave headroom after issue.
        val credentials = StoredCredentials(
            accessToken = "token",
            refreshToken = "refresh",
            expiresAtEpochMs = currentTimeMillis() + 300_000L,
        )
        assertFalse(credentials.isExpired(skewMs = SESSION_PROACTIVE_REFRESH_SKEW_MS))
        assertFalse(credentials.requiresRefresh(skewMs = SESSION_PROACTIVE_REFRESH_SKEW_MS))
    }
}
