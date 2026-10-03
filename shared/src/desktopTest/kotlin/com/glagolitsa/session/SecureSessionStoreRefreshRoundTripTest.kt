// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression: desktop Properties store used `setProperty(...) ?: remove`, but
 * [java.util.Properties.setProperty] returns null when the key is new — so refresh
 * (and username) were deleted immediately after first save. That forced reauth on
 * access-token expiry even with a valid refresh token (Mattermost/Telegram "stay logged in").
 */
class SecureSessionStoreRefreshRoundTripTest {
    private var previousHome: String? = null

    @BeforeTest
    fun isolateHome() {
        previousHome = System.getProperty("user.home")
        val tmp = kotlin.io.path.createTempDirectory("glag-session-rt").toFile()
        System.setProperty("user.home", tmp.absolutePath)
    }

    @AfterTest
    fun restoreHome() {
        previousHome?.let { System.setProperty("user.home", it) }
        previousHome = null
    }

    @Test
    fun refreshTokenAndUsername_roundTripWithUserId() = runBlocking {
        val store = SecureSessionStore()
        store.save(
            StoredCredentials(
                accessToken = "access-1",
                refreshToken = "refresh-1",
                expiresAtEpochMs = 123L,
                userId = "u1",
                username = "alice",
            ),
        )
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals("access-1", loaded.accessToken)
        assertEquals("refresh-1", loaded.refreshToken)
        assertEquals("alice", loaded.username)
        assertEquals(123L, loaded.expiresAtEpochMs)
        assertEquals("u1", loaded.userId)
    }

    @Test
    fun refreshToken_canBeClearedExplicitly() = runBlocking {
        val store = SecureSessionStore()
        store.save(
            StoredCredentials(
                accessToken = "access-1",
                refreshToken = "refresh-1",
                expiresAtEpochMs = 123L,
                userId = "u1",
                username = "alice",
            ),
        )
        store.save(
            StoredCredentials(
                accessToken = "access-2",
                refreshToken = null,
                expiresAtEpochMs = 0L,
                userId = "u1",
                username = "alice",
            ),
        )
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals("access-2", loaded.accessToken)
        assertNull(loaded.refreshToken)
        assertEquals("alice", loaded.username)
    }

    @Test
    fun refreshToken_roundTripLegacyKeysWithoutUserId() = runBlocking {
        val store = SecureSessionStore()
        store.save(
            StoredCredentials(
                accessToken = "access-legacy",
                refreshToken = "refresh-legacy",
                expiresAtEpochMs = 99L,
            ),
        )
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals("access-legacy", loaded.accessToken)
        assertEquals("refresh-legacy", loaded.refreshToken)
    }
}
