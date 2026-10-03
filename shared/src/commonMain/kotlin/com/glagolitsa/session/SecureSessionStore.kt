// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

expect class SecureSessionStore {
    suspend fun save(credentials: StoredCredentials)
    suspend fun load(): StoredCredentials?
    suspend fun saveShell(shell: StoredSessionShell)
    suspend fun loadShell(): StoredSessionShell?
    suspend fun clearShell()
    suspend fun loadForUser(userId: String): StoredCredentials?
    suspend fun loadAll(): List<StoredCredentials>
    suspend fun setActiveUser(userId: String?)
    suspend fun activeUserId(): String?
    suspend fun setBiometricUnlockEnabled(userId: String, enabled: Boolean)
    suspend fun isBiometricUnlockEnabled(userId: String): Boolean
    suspend fun clearUser(userId: String)
    suspend fun clear()
}
