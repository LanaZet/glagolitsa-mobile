// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

/**
 * Desktop: credentials в ~/.glagolitsa/session.properties, значения зашифрованы AES-GCM.
 */
actual class SecureSessionStore {
    private val baseDir: File by lazy {
        File(System.getProperty("user.home"), ".glagolitsa").apply { mkdirs() }
    }

    private val file: File by lazy {
        baseDir.resolve("session.properties")
    }

    actual suspend fun save(credentials: StoredCredentials) = withContext(Dispatchers.IO) {
        val props = loadProps()
        val userId = credentials.userId ?: props.getProperty(KEY_ACTIVE_USER)
        if (userId.isNullOrBlank()) {
            props.setProperty(KEY_ACCESS, DesktopCredentialCipher.encrypt(credentials.accessToken, baseDir))
            // Do not use `setProperty(...).let ?: remove` — setProperty returns null for new keys
            // and the Elvis would immediately delete the value we just wrote (session drop on expiry).
            putEncryptedOrRemove(props, KEY_REFRESH, credentials.refreshToken)
            props.setProperty(KEY_EXPIRES, credentials.expiresAtEpochMs.toString())
        } else {
            putShell(props, StoredSessionShell(userId = userId))
            val prefix = userPrefix(userId)
            props.setProperty("$prefix$KEY_ACCESS", DesktopCredentialCipher.encrypt(credentials.accessToken, baseDir))
            putEncryptedOrRemove(props, "$prefix$KEY_REFRESH", credentials.refreshToken)
            props.setProperty("$prefix$KEY_EXPIRES", credentials.expiresAtEpochMs.toString())
            props.setProperty("$prefix$KEY_USER_ID", userId)
            putOrRemove(props, "$prefix$KEY_API_BASE_URL", credentials.apiBaseUrl)
            putOrRemove(props, "$prefix$KEY_SERVER_ID", credentials.serverId)
            props.setProperty("$prefix$KEY_SERVER_CONFIRMED_AT", credentials.serverConfirmedAtEpochMs.toString())
            val username = credentials.username ?: props.getProperty("$prefix$KEY_USERNAME")
            if (!username.isNullOrBlank()) {
                props.setProperty("$prefix$KEY_USERNAME", username)
            } else {
                props.remove("$prefix$KEY_USERNAME")
            }
            props.setProperty(KEY_ACTIVE_USER, userId)
            props.setProperty(KEY_ACCOUNT_IDS, (accountIds(props) + userId).joinToString(","))
        }
        saveProps(props)
    }

    private fun putEncryptedOrRemove(props: Properties, key: String, plain: String?) {
        if (plain.isNullOrBlank()) {
            props.remove(key)
        } else {
            props.setProperty(key, DesktopCredentialCipher.encrypt(plain, baseDir))
        }
    }

    private fun putOrRemove(props: Properties, key: String, value: String?) {
        if (value.isNullOrBlank()) {
            props.remove(key)
        } else {
            props.setProperty(key, value)
        }
    }

    actual suspend fun load(): StoredCredentials? = withContext(Dispatchers.IO) {
        val props = loadProps()
        props.getProperty(KEY_ACTIVE_USER)?.let { userId ->
            loadFromProps(props, userId)?.let { return@withContext it }
        }
        val accessEnc = props.getProperty(KEY_ACCESS) ?: return@withContext null
        val access = runCatching { DesktopCredentialCipher.decrypt(accessEnc, baseDir) }.getOrNull()
            ?: return@withContext null
        StoredCredentials(
            accessToken = access,
            refreshToken = props.getProperty(KEY_REFRESH)?.let { enc ->
                runCatching { DesktopCredentialCipher.decrypt(enc, baseDir) }.getOrNull()
            },
            expiresAtEpochMs = props.getProperty(KEY_EXPIRES)?.toLongOrNull() ?: 0L,
        )
    }

    actual suspend fun saveShell(shell: StoredSessionShell) = withContext(Dispatchers.IO) {
        val props = loadProps()
        putShell(props, shell)
        saveProps(props)
    }

    actual suspend fun loadShell(): StoredSessionShell? = withContext(Dispatchers.IO) {
        loadShell(loadProps())
    }

    actual suspend fun clearShell() = withContext(Dispatchers.IO) {
        val props = loadProps()
        clearShell(props)
        saveProps(props)
    }

    actual suspend fun loadForUser(userId: String): StoredCredentials? = withContext(Dispatchers.IO) {
        loadFromProps(loadProps(), userId)
    }

    actual suspend fun loadAll(): List<StoredCredentials> = withContext(Dispatchers.IO) {
        val props = loadProps()
        accountIds(props).mapNotNull { loadFromProps(props, it) }
    }

    actual suspend fun setActiveUser(userId: String?) = withContext(Dispatchers.IO) {
        val props = loadProps()
        if (userId.isNullOrBlank()) {
            props.remove(KEY_ACTIVE_USER)
            clearShell(props)
        } else {
            props.setProperty(KEY_ACTIVE_USER, userId)
            putShell(props, StoredSessionShell(userId = userId))
        }
        saveProps(props)
    }

    actual suspend fun activeUserId(): String? = withContext(Dispatchers.IO) {
        loadProps().getProperty(KEY_ACTIVE_USER)
    }

    actual suspend fun setBiometricUnlockEnabled(userId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val props = loadProps()
        val key = "${userPrefix(userId)}$KEY_BIOMETRIC_UNLOCK_ENABLED"
        if (enabled) {
            props.setProperty(key, "true")
        } else {
            props.remove(key)
        }
        saveProps(props)
    }

    actual suspend fun isBiometricUnlockEnabled(userId: String): Boolean = withContext(Dispatchers.IO) {
        loadProps().getProperty("${userPrefix(userId)}$KEY_BIOMETRIC_UNLOCK_ENABLED").toBoolean()
    }

    actual suspend fun clearUser(userId: String) = withContext(Dispatchers.IO) {
        val props = loadProps()
        val prefix = userPrefix(userId)
        listOf(
            KEY_ACCESS, KEY_REFRESH, KEY_EXPIRES, KEY_USER_ID, KEY_USERNAME,
            KEY_API_BASE_URL, KEY_SERVER_ID, KEY_SERVER_CONFIRMED_AT, KEY_BIOMETRIC_UNLOCK_ENABLED,
        ).forEach {
            props.remove("$prefix$it")
        }
        props.setProperty(KEY_ACCOUNT_IDS, (accountIds(props) - userId).joinToString(","))
        if (props.getProperty(KEY_ACTIVE_USER) == userId) {
            props.remove(KEY_ACTIVE_USER)
            clearShell(props)
        }
        saveProps(props)
    }

    actual suspend fun clear() = withContext(Dispatchers.IO) {
        if (file.exists()) {
            file.delete()
        }
    }

    private fun loadProps(): Properties {
        val props = Properties()
        if (file.exists()) {
            file.inputStream().use { props.load(it) }
        }
        return props
    }

    private fun saveProps(props: Properties) {
        file.outputStream().use { props.store(it, "glagolitsa session") }
    }

    private fun loadFromProps(props: Properties, userId: String): StoredCredentials? {
        val prefix = userPrefix(userId)
        val accessEnc = props.getProperty("$prefix$KEY_ACCESS") ?: return null
        val access = runCatching { DesktopCredentialCipher.decrypt(accessEnc, baseDir) }.getOrNull()
            ?: return null
        return StoredCredentials(
            accessToken = access,
            refreshToken = props.getProperty("$prefix$KEY_REFRESH")?.let { enc ->
                runCatching { DesktopCredentialCipher.decrypt(enc, baseDir) }.getOrNull()
            },
            expiresAtEpochMs = props.getProperty("$prefix$KEY_EXPIRES")?.toLongOrNull() ?: 0L,
            userId = props.getProperty("$prefix$KEY_USER_ID") ?: userId,
            username = props.getProperty("$prefix$KEY_USERNAME"),
            apiBaseUrl = props.getProperty("$prefix$KEY_API_BASE_URL"),
            serverId = props.getProperty("$prefix$KEY_SERVER_ID"),
            serverConfirmedAtEpochMs = props.getProperty("$prefix$KEY_SERVER_CONFIRMED_AT")?.toLongOrNull() ?: 0L,
        )
    }

    private fun putShell(props: Properties, shell: StoredSessionShell) {
        if (shell.userId.isBlank()) return
        props.setProperty(KEY_SHELL_USER_ID, shell.userId)
        props.remove(KEY_SHELL_USERNAME)
    }

    private fun loadShell(props: Properties): StoredSessionShell? {
        val userId = props.getProperty(KEY_SHELL_USER_ID)?.takeIf { it.isNotBlank() }
            ?: return null
        return StoredSessionShell(userId = userId)
    }

    private fun clearShell(props: Properties) {
        props.remove(KEY_SHELL_USER_ID)
        props.remove(KEY_SHELL_USERNAME)
    }

    private fun accountIds(props: Properties): Set<String> =
        props.getProperty(KEY_ACCOUNT_IDS)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

    private fun userPrefix(userId: String) = "user.$userId."

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
