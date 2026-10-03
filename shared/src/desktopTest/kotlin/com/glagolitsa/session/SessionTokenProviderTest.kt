// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import com.glagolitsa.api.ApiException
import com.glagolitsa.currentTimeMillis
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTokenProviderTest {
    private val secureSession = SecureSessionStore()

    @BeforeTest
    fun setup() = runTest {
        SessionStore.clear()
        secureSession.clear()
    }

    @AfterTest
    fun cleanup() = runTest {
        SessionStore.clear()
        secureSession.clear()
    }

    @Test
    fun activeToken_on429_returnsCachedTokenAndBacksOff() = runTest {
        val expired = StoredCredentials(
            accessToken = "access-stale",
            refreshToken = "refresh-1",
            expiresAtEpochMs = currentTimeMillis() - 1_000L,
        )
        secureSession.save(expired)
        SessionStore.setSession("access-stale", com.glagolitsa.model.User("u1", "alice", null), "refresh-1")

        var refreshCalls = 0
        val provider = SessionTokenProvider(secureSession) {
            refreshCalls++
            throw ApiException(HttpStatusCode.TooManyRequests, "rate limit exceeded")
        }

        val first = provider.activeToken()
        val second = provider.activeToken()

        assertEquals("access-stale", first)
        assertEquals("access-stale", second)
        assertEquals(1, refreshCalls, "second call must not hammer refresh during backoff")
    }
}