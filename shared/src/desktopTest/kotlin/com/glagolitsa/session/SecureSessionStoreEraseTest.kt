// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Credential erase / multi-account isolation for SecureSessionStore (desktop file backend).
 */
class SecureSessionStoreEraseTest {
    private var previousHome: String? = null

    @BeforeTest
    fun isolateHome() {
        previousHome = System.getProperty("user.home")
        val tmp = kotlin.io.path.createTempDirectory("glag-session-erase").toFile()
        System.setProperty("user.home", tmp.absolutePath)
    }

    @AfterTest
    fun restoreHome() {
        previousHome?.let { System.setProperty("user.home", it) }
        previousHome = null
    }

    private fun creds(
        userId: String,
        username: String,
        access: String = "access-$userId",
        refresh: String? = "refresh-$userId",
    ) = StoredCredentials(
        accessToken = access,
        refreshToken = refresh,
        expiresAtEpochMs = System.currentTimeMillis() + 60_000,
        userId = userId,
        username = username,
    )

    @Test
    fun saveLoad_roundTripForActiveUser() = runBlocking {
        val store = SecureSessionStore()
        store.save(creds("u1", "alice"))
        val loaded = store.load()
        assertEquals("u1", loaded?.userId)
        assertEquals("access-u1", loaded?.accessToken)
        assertEquals("refresh-u1", loaded?.refreshToken, "refresh must persist for stay-logged-in")
        assertEquals("alice", loaded?.username)
        assertEquals("u1", store.activeUserId())
    }

    @Test
    fun clearUser_removesOnlyThatAccount() = runBlocking {
        val store = SecureSessionStore()
        store.save(creds("u1", "alice"))
        store.save(creds("u2", "bob"))
        assertEquals(2, store.loadAll().size)

        store.clearUser("u1")

        assertNull(store.loadForUser("u1"))
        assertEquals("u2", store.loadForUser("u2")?.userId)
        assertEquals(1, store.loadAll().size)
        assertEquals("u2", store.activeUserId())
    }

    @Test
    fun clear_wipesAllAccounts() = runBlocking {
        val store = SecureSessionStore()
        store.save(creds("u1", "alice"))
        store.save(creds("u2", "bob"))
        store.clear()
        assertTrue(store.loadAll().isEmpty())
        assertNull(store.load())
        assertNull(store.activeUserId())
    }

    @Test
    fun setActiveUser_nullDetachesWithoutDeletingCredentials() = runBlocking {
        val store = SecureSessionStore()
        store.save(creds("u1", "alice"))
        store.setActiveUser(null)
        assertNull(store.activeUserId())
        assertEquals("u1", store.loadForUser("u1")?.userId)
    }
}
