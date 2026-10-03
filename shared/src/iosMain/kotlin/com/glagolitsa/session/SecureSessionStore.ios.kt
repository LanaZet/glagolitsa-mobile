// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSUserDefaults

/**
 * iOS session store.
 *
 * Access/refresh tokens live in the system Keychain (via Swift). NSUserDefaults
 * keeps indexes, expiry, usernames, and other non-secret metadata.
 */
actual class SecureSessionStore {
    private val defaults: NSUserDefaults get() = NSUserDefaults.standardUserDefaults

    actual suspend fun save(credentials: StoredCredentials) = withContext(Dispatchers.Default) {
        val userId = credentials.userId ?: defaults.stringForKey(KEY_ACTIVE_USER)
        if (userId.isNullOrBlank()) {
            keychain.put(KEY_ACCESS, credentials.accessToken)
            keychain.put(KEY_REFRESH, credentials.refreshToken)
            defaults.setObject(credentials.expiresAtEpochMs.toString(), forKey = KEY_EXPIRES)
            removeLegacyDefaults(KEY_ACCESS, KEY_REFRESH)
        } else {
            putShell(StoredSessionShell(userId = userId))
            val prefix = userPrefix(userId)
            keychain.put("$prefix$KEY_ACCESS", credentials.accessToken)
            keychain.put("$prefix$KEY_REFRESH", credentials.refreshToken)
            defaults.setObject(credentials.expiresAtEpochMs.toString(), forKey = "$prefix$KEY_EXPIRES")
            defaults.setObject(userId, forKey = "$prefix$KEY_USER_ID")
            putOrRemove("$prefix$KEY_API_BASE_URL", credentials.apiBaseUrl)
            putOrRemove("$prefix$KEY_SERVER_ID", credentials.serverId)
            defaults.setObject(credentials.serverConfirmedAtEpochMs.toString(), forKey = "$prefix$KEY_SERVER_CONFIRMED_AT")
            val username = credentials.username ?: defaults.stringForKey("$prefix$KEY_USERNAME")
            putOrRemove("$prefix$KEY_USERNAME", username)
            defaults.setObject(userId, forKey = KEY_ACTIVE_USER)
            defaults.setObject((accountIds() + userId).joinToString(","), forKey = KEY_ACCOUNT_IDS)
            removeLegacyDefaults("$prefix$KEY_ACCESS", "$prefix$KEY_REFRESH")
        }
        defaults.synchronize()
        Unit
    }

    actual suspend fun load(): StoredCredentials? = withContext(Dispatchers.Default) {
        defaults.stringForKey(KEY_ACTIVE_USER)?.let { userId ->
            loadFromUser(userId)?.let { return@withContext it }
        }
        val access = keychain.get(KEY_ACCESS)
            ?: defaults.stringForKey(KEY_ACCESS)?.also { migrated ->
                keychain.put(KEY_ACCESS, migrated)
                defaults.removeObjectForKey(KEY_ACCESS)
            }
            ?: return@withContext null
        val refresh = keychain.get(KEY_REFRESH)
            ?: defaults.stringForKey(KEY_REFRESH)?.also { migrated ->
                keychain.put(KEY_REFRESH, migrated)
                defaults.removeObjectForKey(KEY_REFRESH)
            }
        StoredCredentials(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMs = defaults.stringForKey(KEY_EXPIRES)?.toLongOrNull() ?: 0L,
        )
    }

    actual suspend fun saveShell(shell: StoredSessionShell) = withContext(Dispatchers.Default) {
        putShell(shell)
        defaults.synchronize()
        Unit
    }

    actual suspend fun loadShell(): StoredSessionShell? = withContext(Dispatchers.Default) {
        val userId = defaults.stringForKey(KEY_SHELL_USER_ID)?.takeIf { it.isNotBlank() } ?: return@withContext null
        StoredSessionShell(userId = userId)
    }

    actual suspend fun clearShell() = withContext(Dispatchers.Default) {
        defaults.removeObjectForKey(KEY_SHELL_USER_ID)
        defaults.removeObjectForKey(KEY_SHELL_USERNAME)
        defaults.synchronize()
        Unit
    }

    actual suspend fun loadForUser(userId: String): StoredCredentials? = withContext(Dispatchers.Default) {
        loadFromUser(userId)
    }

    actual suspend fun loadAll(): List<StoredCredentials> = withContext(Dispatchers.Default) {
        accountIds().mapNotNull { loadFromUser(it) }
    }

    actual suspend fun setActiveUser(userId: String?) = withContext(Dispatchers.Default) {
        if (userId.isNullOrBlank()) {
            defaults.removeObjectForKey(KEY_ACTIVE_USER)
            defaults.removeObjectForKey(KEY_SHELL_USER_ID)
            defaults.removeObjectForKey(KEY_SHELL_USERNAME)
        } else {
            defaults.setObject(userId, forKey = KEY_ACTIVE_USER)
            putShell(StoredSessionShell(userId = userId))
        }
        defaults.synchronize()
        Unit
    }

    actual suspend fun activeUserId(): String? = withContext(Dispatchers.Default) {
        defaults.stringForKey(KEY_ACTIVE_USER)
    }

    actual suspend fun setBiometricUnlockEnabled(userId: String, enabled: Boolean) =
        withContext(Dispatchers.Default) {
            val key = "${userPrefix(userId)}$KEY_BIOMETRIC_UNLOCK_ENABLED"
            if (enabled) defaults.setObject("true", forKey = key) else defaults.removeObjectForKey(key)
            defaults.synchronize()
            Unit
        }

    actual suspend fun isBiometricUnlockEnabled(userId: String): Boolean =
        withContext(Dispatchers.Default) {
            defaults.stringForKey("${userPrefix(userId)}$KEY_BIOMETRIC_UNLOCK_ENABLED") == "true"
        }

    actual suspend fun clearUser(userId: String) = withContext(Dispatchers.Default) {
        val prefix = userPrefix(userId)
        keychain.delete("$prefix$KEY_ACCESS")
        keychain.delete("$prefix$KEY_REFRESH")
        removeLegacyDefaults("$prefix$KEY_ACCESS", "$prefix$KEY_REFRESH")
        listOf(
            KEY_EXPIRES, KEY_USER_ID, KEY_USERNAME, KEY_API_BASE_URL,
            KEY_SERVER_ID, KEY_SERVER_CONFIRMED_AT, KEY_BIOMETRIC_UNLOCK_ENABLED,
        )
            .forEach { defaults.removeObjectForKey("$prefix$it") }
        defaults.setObject((accountIds() - userId).joinToString(","), forKey = KEY_ACCOUNT_IDS)
        if (defaults.stringForKey(KEY_ACTIVE_USER) == userId) {
            defaults.removeObjectForKey(KEY_ACTIVE_USER)
            defaults.removeObjectForKey(KEY_SHELL_USER_ID)
            defaults.removeObjectForKey(KEY_SHELL_USERNAME)
        }
        defaults.synchronize()
        Unit
    }

    actual suspend fun clear() = withContext(Dispatchers.Default) {
        keychain.delete(KEY_ACCESS)
        keychain.delete(KEY_REFRESH)
        listOf(
            KEY_ACCESS, KEY_REFRESH, KEY_EXPIRES, KEY_ACTIVE_USER, KEY_ACCOUNT_IDS,
            KEY_SHELL_USER_ID, KEY_SHELL_USERNAME,
        ).forEach { defaults.removeObjectForKey(it) }
        accountIds().forEach { id ->
            val prefix = userPrefix(id)
            keychain.delete("$prefix$KEY_ACCESS")
            keychain.delete("$prefix$KEY_REFRESH")
            listOf(
                KEY_ACCESS, KEY_REFRESH, KEY_EXPIRES, KEY_USER_ID, KEY_USERNAME,
                KEY_API_BASE_URL, KEY_SERVER_ID, KEY_SERVER_CONFIRMED_AT, KEY_BIOMETRIC_UNLOCK_ENABLED,
            )
                .forEach { defaults.removeObjectForKey("$prefix$it") }
        }
        defaults.synchronize()
        Unit
    }

    private fun loadFromUser(userId: String): StoredCredentials? {
        val prefix = userPrefix(userId)
        val accessKey = "$prefix$KEY_ACCESS"
        val refreshKey = "$prefix$KEY_REFRESH"
        val access = keychain.get(accessKey)
            ?: defaults.stringForKey(accessKey)?.also { migrated ->
                keychain.put(accessKey, migrated)
                defaults.removeObjectForKey(accessKey)
            }
            ?: return null
        val refresh = keychain.get(refreshKey)
            ?: defaults.stringForKey(refreshKey)?.also { migrated ->
                keychain.put(refreshKey, migrated)
                defaults.removeObjectForKey(refreshKey)
            }
        return StoredCredentials(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMs = defaults.stringForKey("$prefix$KEY_EXPIRES")?.toLongOrNull() ?: 0L,
            userId = defaults.stringForKey("$prefix$KEY_USER_ID") ?: userId,
            username = defaults.stringForKey("$prefix$KEY_USERNAME"),
            apiBaseUrl = defaults.stringForKey("$prefix$KEY_API_BASE_URL"),
            serverId = defaults.stringForKey("$prefix$KEY_SERVER_ID"),
            serverConfirmedAtEpochMs = defaults.stringForKey("$prefix$KEY_SERVER_CONFIRMED_AT")?.toLongOrNull() ?: 0L,
        )
    }

    private fun putShell(shell: StoredSessionShell) {
        if (shell.userId.isBlank()) return
        defaults.setObject(shell.userId, forKey = KEY_SHELL_USER_ID)
        defaults.removeObjectForKey(KEY_SHELL_USERNAME)
    }

    private fun putOrRemove(key: String, value: String?) {
        if (value.isNullOrBlank()) defaults.removeObjectForKey(key)
        else defaults.setObject(value, forKey = key)
    }

    private fun removeLegacyDefaults(vararg keys: String) {
        keys.forEach(defaults::removeObjectForKey)
    }

    private fun accountIds(): Set<String> =
        defaults.stringForKey(KEY_ACCOUNT_IDS)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

    private fun userPrefix(userId: String) = "user.$userId."

    private val keychain = IosSessionKeychain

    private companion object {
        const val KEY_ACTIVE_USER = "active_user_id"
        const val KEY_ACCOUNT_IDS = "account_ids"
        const val KEY_SHELL_USER_ID = "shell_user_id"
        const val KEY_SHELL_USERNAME = "shell_username"
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_EXPIRES = "expires"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_API_BASE_URL = "api_base_url"
        const val KEY_SERVER_ID = "server_id"
        const val KEY_SERVER_CONFIRMED_AT = "server_confirmed_at_ms"
        const val KEY_BIOMETRIC_UNLOCK_ENABLED = "biometric_unlock_enabled"
    }
}

private object IosSessionKeychain {
    private const val LEGACY_DEFAULTS_PREFIX = "glagolitsa.session."

    private fun backend(): IosSessionKeychainBackend =
        IosSessionKeychainHost.backend
            ?: error("iOS Keychain backend is not installed")

    fun put(account: String, value: String?) {
        if (value.isNullOrBlank()) {
            delete(account)
            return
        }
        val status = backend().write(account, value)
        check(status == 0) { "Keychain save failed: $status" }
        NSUserDefaults.standardUserDefaults.removeObjectForKey(LEGACY_DEFAULTS_PREFIX + account)
    }

    fun get(account: String): String? {
        backend().read(account)?.let { return it }
        val legacy = NSUserDefaults.standardUserDefaults.stringForKey(LEGACY_DEFAULTS_PREFIX + account)
            ?: return null
        put(account, legacy)
        return legacy
    }

    fun delete(account: String) {
        backend().remove(account)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(LEGACY_DEFAULTS_PREFIX + account)
    }
}
