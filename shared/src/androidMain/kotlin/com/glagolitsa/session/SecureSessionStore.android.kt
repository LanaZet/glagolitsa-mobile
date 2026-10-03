// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.glagolitsa.log.AppLog
import com.glagolitsa.shared.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual class SecureSessionStore(
    private val context: Context,
) {
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    actual suspend fun save(credentials: StoredCredentials) = withContext(Dispatchers.IO) {
        val userId = credentials.userId
            ?: prefs.getString(KEY_ACTIVE_USER, null)
            ?: debugFallbackPrefs().getString(KEY_ACTIVE_USER, null)
        val encryptedSaved = runCatching {
            val editor = prefs.edit()
            if (userId.isNullOrBlank()) {
                editor
                    .putString(KEY_ACCESS, credentials.accessToken)
                    .putString(KEY_REFRESH, credentials.refreshToken)
                    .putLong(KEY_EXPIRES, credentials.expiresAtEpochMs)
            } else {
                saveShellSync(StoredSessionShell(userId = userId))
                val prefix = userPrefix(userId)
                editor
                    .putString("$prefix$KEY_ACCESS", credentials.accessToken)
                    .putString("$prefix$KEY_REFRESH", credentials.refreshToken)
                    .putLong("$prefix$KEY_EXPIRES", credentials.expiresAtEpochMs)
                    .putString("$prefix$KEY_USER_ID", userId)
                    .putString("$prefix$KEY_USERNAME", credentials.username ?: prefs.getString("$prefix$KEY_USERNAME", null))
                    .putString("$prefix$KEY_API_BASE_URL", credentials.apiBaseUrl)
                    .putString("$prefix$KEY_SERVER_ID", credentials.serverId)
                    .putLong("$prefix$KEY_SERVER_CONFIRMED_AT", credentials.serverConfirmedAtEpochMs)
                    .putString(KEY_ACTIVE_USER, userId)
                    .putStringSet(KEY_ACCOUNT_IDS, accountIds(prefs) + userId)
            }
            editor.commit()
        }.onFailure {
            AppLog.warning("secure session encrypted save failed: ${it::class.simpleName}")
        }.getOrDefault(false)
        saveDebugFallback(credentials)
        if (!encryptedSaved) {
            AppLog.warning("secure session encrypted save returned false")
        }
    }

    actual suspend fun load(): StoredCredentials? = withContext(Dispatchers.IO) {
        activeUserId()?.let { userId ->
            loadForUser(userId)?.let { return@withContext it }
        }
        val encrypted = runCatching {
            val access = prefs.getString(KEY_ACCESS, null) ?: return@runCatching null
            StoredCredentials(
                accessToken = access,
                refreshToken = prefs.getString(KEY_REFRESH, null),
                expiresAtEpochMs = prefs.getLong(KEY_EXPIRES, 0L),
            )
        }.onFailure {
            AppLog.warning("secure session encrypted load failed: ${it::class.simpleName}")
        }.getOrNull()
        if (encrypted != null) {
            saveDebugFallback(encrypted)
        }
        encrypted ?: loadDebugFallback()
    }

    actual suspend fun saveShell(shell: StoredSessionShell) = withContext(Dispatchers.IO) {
        saveShellSync(shell)
    }

    actual suspend fun loadShell(): StoredSessionShell? = withContext(Dispatchers.IO) {
        val prefs = shellPrefs()
        prefs.getString(KEY_SHELL_USER_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { userId ->
                StoredSessionShell(userId = userId)
            }
    }

    actual suspend fun clearShell() = withContext(Dispatchers.IO) {
        clearShellSync()
        Unit
    }

    actual suspend fun loadForUser(userId: String): StoredCredentials? = withContext(Dispatchers.IO) {
        val encrypted = runCatching {
            loadFromPrefs(prefs, userId)
        }.onFailure {
            AppLog.warning("secure session encrypted user load failed: ${it::class.simpleName}")
        }.getOrNull()
        encrypted ?: loadDebugFallback(userId)
    }

    actual suspend fun loadAll(): List<StoredCredentials> = withContext(Dispatchers.IO) {
        val ids = accountIds(prefs) + accountIds(debugFallbackPrefs())
        ids.mapNotNull { loadForUser(it) }
            .distinctBy { it.userId }
    }

    actual suspend fun setActiveUser(userId: String?) = withContext(Dispatchers.IO) {
        val encryptedEditor = prefs.edit()
        val debugEditor = debugFallbackPrefs().edit()
        if (userId.isNullOrBlank()) {
            encryptedEditor.remove(KEY_ACTIVE_USER)
            debugEditor.remove(KEY_ACTIVE_USER)
            clearShellSync()
        } else {
            encryptedEditor.putString(KEY_ACTIVE_USER, userId)
            debugEditor.putString(KEY_ACTIVE_USER, userId)
            saveShellSync(StoredSessionShell(userId = userId))
        }
        encryptedEditor.commit()
        debugEditor.commit()
        Unit
    }

    actual suspend fun activeUserId(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_ACTIVE_USER, null)
            ?: debugFallbackPrefs().getString(KEY_ACTIVE_USER, null)
    }

    actual suspend fun setBiometricUnlockEnabled(userId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val key = "${userPrefix(userId)}$KEY_BIOMETRIC_UNLOCK_ENABLED"
        val encryptedEditor = prefs.edit()
        val debugEditor = debugFallbackPrefs().edit()
        if (enabled) {
            encryptedEditor.putBoolean(key, true)
            debugEditor.putBoolean(key, true)
        } else {
            encryptedEditor.remove(key)
            debugEditor.remove(key)
        }
        encryptedEditor.commit()
        debugEditor.commit()
        Unit
    }

    actual suspend fun isBiometricUnlockEnabled(userId: String): Boolean = withContext(Dispatchers.IO) {
        val key = "${userPrefix(userId)}$KEY_BIOMETRIC_UNLOCK_ENABLED"
        prefs.getBoolean(key, false) || debugFallbackPrefs().getBoolean(key, false)
    }

    actual suspend fun clearUser(userId: String) = withContext(Dispatchers.IO) {
        clearUserFromPrefs(prefs, userId)
        clearUserFromPrefs(debugFallbackPrefs(), userId)
    }

    actual suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { prefs.edit().clear().commit() }
        debugFallbackPrefs().edit().clear().commit()
        clearShellSync()
        Unit
    }

    private fun saveDebugFallback(credentials: StoredCredentials) {
        if (!BuildConfig.DEBUG) return
        val fallback = debugFallbackPrefs()
        val userId = credentials.userId
            ?: fallback.getString(KEY_ACTIVE_USER, null)
        val editor = fallback.edit()
        if (userId.isNullOrBlank()) {
            editor
                .putString(KEY_ACCESS, credentials.accessToken)
                .putString(KEY_REFRESH, credentials.refreshToken)
                .putLong(KEY_EXPIRES, credentials.expiresAtEpochMs)
        } else {
            val resolvedUserId = userId
            val prefix = userPrefix(resolvedUserId)
            editor
                .putString("$prefix$KEY_ACCESS", credentials.accessToken)
                .putString("$prefix$KEY_REFRESH", credentials.refreshToken)
                .putLong("$prefix$KEY_EXPIRES", credentials.expiresAtEpochMs)
                .putString("$prefix$KEY_USER_ID", resolvedUserId)
                .putString("$prefix$KEY_USERNAME", credentials.username ?: fallback.getString("$prefix$KEY_USERNAME", null))
                .putString("$prefix$KEY_API_BASE_URL", credentials.apiBaseUrl)
                .putString("$prefix$KEY_SERVER_ID", credentials.serverId)
                .putLong("$prefix$KEY_SERVER_CONFIRMED_AT", credentials.serverConfirmedAtEpochMs)
                .putString(KEY_ACTIVE_USER, resolvedUserId)
                .putStringSet(KEY_ACCOUNT_IDS, accountIds(fallback) + resolvedUserId)
            saveShellSync(StoredSessionShell(userId = resolvedUserId))
        }
        editor.commit()
    }

    private fun loadDebugFallback(userId: String? = null): StoredCredentials? {
        if (!BuildConfig.DEBUG) return null
        val fallback = debugFallbackPrefs()
        if (!userId.isNullOrBlank()) {
            return loadFromPrefs(fallback, userId)
        }
        val access = fallback.getString(KEY_ACCESS, null) ?: return null
        return StoredCredentials(
            accessToken = access,
            refreshToken = fallback.getString(KEY_REFRESH, null),
            expiresAtEpochMs = fallback.getLong(KEY_EXPIRES, 0L),
        )
    }

    private fun debugFallbackPrefs() = context.getSharedPreferences(DEBUG_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)

    private fun shellPrefs() = context.getSharedPreferences(SHELL_PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveShellSync(shell: StoredSessionShell) {
        if (shell.userId.isBlank()) return
        val editor = shellPrefs()
            .edit()
            .putString(KEY_SHELL_USER_ID, shell.userId)
            .remove(KEY_SHELL_USERNAME)
        editor.commit()
    }

    private fun clearShellSync() {
        shellPrefs().edit().clear().commit()
    }

    private fun loadFromPrefs(source: android.content.SharedPreferences, userId: String): StoredCredentials? {
        val prefix = userPrefix(userId)
        val access = source.getString("$prefix$KEY_ACCESS", null) ?: return null
        return StoredCredentials(
            accessToken = access,
            refreshToken = source.getString("$prefix$KEY_REFRESH", null),
            expiresAtEpochMs = source.getLong("$prefix$KEY_EXPIRES", 0L),
            userId = source.getString("$prefix$KEY_USER_ID", null) ?: userId,
            username = source.getString("$prefix$KEY_USERNAME", null),
            apiBaseUrl = source.getString("$prefix$KEY_API_BASE_URL", null),
            serverId = source.getString("$prefix$KEY_SERVER_ID", null),
            serverConfirmedAtEpochMs = source.getLong("$prefix$KEY_SERVER_CONFIRMED_AT", 0L),
        )
    }

    private fun accountIds(source: android.content.SharedPreferences): Set<String> =
        source.getStringSet(KEY_ACCOUNT_IDS, emptySet()).orEmpty()

    private fun clearUserFromPrefs(source: android.content.SharedPreferences, userId: String) {
        val active = source.getString(KEY_ACTIVE_USER, null)
        val ids = accountIds(source) - userId
        val prefix = userPrefix(userId)
        val editor = source.edit()
            .remove("$prefix$KEY_ACCESS")
            .remove("$prefix$KEY_REFRESH")
            .remove("$prefix$KEY_EXPIRES")
            .remove("$prefix$KEY_USER_ID")
            .remove("$prefix$KEY_USERNAME")
            .remove("$prefix$KEY_API_BASE_URL")
            .remove("$prefix$KEY_SERVER_ID")
            .remove("$prefix$KEY_SERVER_CONFIRMED_AT")
            .remove("$prefix$KEY_BIOMETRIC_UNLOCK_ENABLED")
            .putStringSet(KEY_ACCOUNT_IDS, ids)
        if (active == userId) {
            editor.remove(KEY_ACTIVE_USER)
            clearShellSync()
        }
        editor.commit()
    }

    private fun userPrefix(userId: String) = "user.$userId."

    private companion object {
        const val PREFS_NAME = "glagolitsa_secure_session"
        const val DEBUG_FALLBACK_PREFS_NAME = "glagolitsa_debug_session_fallback"
        const val SHELL_PREFS_NAME = "glagolitsa_session_shell"
        const val KEY_ACTIVE_USER = "active_user_id"
        const val KEY_ACCOUNT_IDS = "account_ids"
        const val KEY_SHELL_USER_ID = "user_id"
        const val KEY_SHELL_USERNAME = "username"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at_ms"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_API_BASE_URL = "api_base_url"
        const val KEY_SERVER_ID = "server_id"
        const val KEY_SERVER_CONFIRMED_AT = "server_confirmed_at_ms"
        const val KEY_BIOMETRIC_UNLOCK_ENABLED = "biometric_unlock_enabled"
    }
}
