// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import com.glagolitsa.api.ApiException
import com.glagolitsa.currentTimeMillis
import com.glagolitsa.model.User
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single credential authority (Signal-style AccountManager).
 *
 * All API work goes through [activeToken]/[withAuth]. There is no separate “memory token”
 * gate for outbox: disk credentials are published into [SessionStore] so UI and jobs agree.
 *
 * Refresh policy (Signal/Telegram-like):
 * - proactive refresh near expiry
 * - 401 → one refresh retry then [SessionExpiredException]
 * - network / other refresh errors → keep using current access token (job retries later)
 * - hard reauth ([SessionStore.reauthRequired]) → refuse to mint tokens until password login
 */
class SessionTokenProvider(
    private val secureSession: SecureSessionStore,
    private val refreshTokens: suspend () -> String,
) {
    private val refreshMutex = Mutex()
    private var refreshBlockedUntilMs = 0L

    suspend fun activeToken(): String {
        if (SessionStore.reauthRequired.value) {
            throw SessionExpiredException()
        }
        val stored = secureSession.load()
        if (stored?.shouldRefresh() == true) {
            if (currentTimeMillis() < refreshBlockedUntilMs) {
                return publishAccessToken(stored)
            }
            return refreshMutex.withLock {
                if (SessionStore.reauthRequired.value) {
                    throw SessionExpiredException()
                }
                if (currentTimeMillis() < refreshBlockedUntilMs) {
                    return@withLock publishAccessToken(secureSession.load())
                }
                try {
                    refreshTokens()
                } catch (e: SessionExpiredException) {
                    throw e
                } catch (e: NotAuthenticatedException) {
                    throw e
                } catch (e: ApiException) {
                    when (e.status) {
                        HttpStatusCode.Unauthorized -> {
                            val current = secureSession.load()
                            if (current?.hasUsableAccessToken() == true) {
                                publishAccessToken(current)
                            } else {
                                throw SessionExpiredException()
                            }
                        }
                        HttpStatusCode.TooManyRequests -> {
                            refreshBlockedUntilMs = currentTimeMillis() + REFRESH_RATE_LIMIT_BACKOFF_MS
                            publishAccessToken(secureSession.load())
                        }
                        // Other API errors: fall back to existing access (jobs retry).
                        else -> publishAccessToken(secureSession.load())
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Transport timeout etc. — do not wipe session; send path can still try.
                    publishAccessToken(secureSession.load())
                }
            }
        }
        return publishAccessToken(stored)
    }

    suspend fun <T> withAuth(block: suspend (String) -> T): T {
        var token = activeToken()
        return try {
            block(token)
        } catch (e: ApiException) {
            if (e.status != HttpStatusCode.Unauthorized) throw e
            if (currentTimeMillis() < refreshBlockedUntilMs) throw e
            if (SessionStore.reauthRequired.value) throw SessionExpiredException()
            token = refreshMutex.withLock {
                try {
                    refreshTokens()
                } catch (refresh: ApiException) {
                    if (refresh.status == HttpStatusCode.TooManyRequests) {
                        refreshBlockedUntilMs = currentTimeMillis() + REFRESH_RATE_LIMIT_BACKOFF_MS
                    }
                    if (refresh.status == HttpStatusCode.Unauthorized) {
                        throw SessionExpiredException()
                    }
                    throw refresh
                }
            }
            try {
                block(token)
            } catch (retry: ApiException) {
                if (retry.status == HttpStatusCode.Unauthorized) throw SessionExpiredException()
                throw retry
            }
        }
    }

    /**
     * Force a refresh (WS 401 / reconnect). Still Signal-safe: does not wipe device crypto.
     * Rate-limited via the same mutex + 429 backoff as [activeToken].
     */
    suspend fun forceRefreshAccessToken(): String {
        if (SessionStore.reauthRequired.value) {
            throw SessionExpiredException()
        }
        if (currentTimeMillis() < refreshBlockedUntilMs) {
            return activeToken()
        }
        return refreshMutex.withLock {
            if (SessionStore.reauthRequired.value) {
                throw SessionExpiredException()
            }
            if (currentTimeMillis() < refreshBlockedUntilMs) {
                return@withLock publishAccessToken(secureSession.load())
            }
            try {
                refreshTokens()
            } catch (e: ApiException) {
                if (e.status == HttpStatusCode.TooManyRequests) {
                    refreshBlockedUntilMs = currentTimeMillis() + REFRESH_RATE_LIMIT_BACKOFF_MS
                }
                if (e.status == HttpStatusCode.Unauthorized) {
                    throw SessionExpiredException()
                }
                throw e
            }
        }
    }

    /**
     * One source of truth: whatever we use as Authorization is also what [SessionStore] exposes.
     * Avoids Signal anti-pattern of “API works via disk fallback, outbox thinks there is no session”.
     */
    private fun publishAccessToken(stored: StoredCredentials?): String {
        if (SessionStore.reauthRequired.value) {
            throw SessionExpiredException()
        }
        SessionStore.token.value?.takeIf { it.isNotBlank() }?.let { return it }
        val token = stored?.accessToken?.takeIf { it.isNotBlank() }
            ?: throw NotAuthenticatedException()
        val user = SessionStore.user.value
            ?: stored.userId?.takeIf { it.isNotBlank() }?.let { id ->
                User(id = id, username = stored.username?.takeIf { it.isNotBlank() } ?: id)
            }
        if (user != null) {
            SessionStore.setSession(token, user, stored.refreshToken)
        } else {
            // No user id yet — still return token for rare early-boot paths.
            SessionStore.clearReauthRequired()
        }
        return token
    }

    companion object {
        const val REFRESH_RATE_LIMIT_BACKOFF_MS = 30_000L
    }
}

fun StoredCredentials.isExpired(skewMs: Long = 60_000L): Boolean =
    accessToken.isBlank() || (expiresAtEpochMs > 0L && currentTimeMillis() >= expiresAtEpochMs - skewMs)

fun StoredCredentials.hasUsableAccessToken(skewMs: Long = 0L): Boolean =
    accessToken.isNotBlank() && (expiresAtEpochMs <= 0L || currentTimeMillis() < expiresAtEpochMs - skewMs)

fun StoredCredentials.requiresRefresh(skewMs: Long = 60_000L): Boolean =
    refreshToken != null && isExpired(skewMs)

private fun StoredCredentials.shouldRefresh(skewMs: Long = 60_000L): Boolean =
    isExpired(skewMs) && (refreshToken != null || SessionStore.refreshToken.value != null)

/** Внутренний сигнал: refresh недоступен, нужен повторный вход. Не показывать пользователю как есть. */
class SessionExpiredException : Exception("auth_refresh_unavailable")

/** No credentials in memory or secure store (Signal: not registered). */
class NotAuthenticatedException : IllegalStateException("Not authenticated")

fun Throwable.isAuthRefreshFailure(): Boolean =
    this is SessionExpiredException ||
        this is NotAuthenticatedException ||
        (this is ApiException && status == HttpStatusCode.Unauthorized)
