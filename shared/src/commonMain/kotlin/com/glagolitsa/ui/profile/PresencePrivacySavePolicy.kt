// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import com.glagolitsa.model.PresencePrivacySettings
import com.glagolitsa.model.ProfileAboutPolicy
import com.glagolitsa.model.UpdatePresencePrivacyRequest
import com.glagolitsa.ui.chat.TransientNetworkErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val PRIVACY_SAVE_ATTEMPTS = 2
private const val PRIVACY_SAVE_RETRY_DELAY_MS = 700L

data class PresencePrivacySaveOutcome(
    val settings: PresencePrivacySettings? = null,
    val error: Throwable? = null,
) {
    val isSuccess: Boolean get() = settings != null
}

suspend fun savePresencePrivacyWithRetry(
    request: UpdatePresencePrivacyRequest,
    update: suspend (UpdatePresencePrivacyRequest) -> PresencePrivacySettings,
): PresencePrivacySaveOutcome {
    var lastError: Throwable? = null
    repeat(PRIVACY_SAVE_ATTEMPTS) { attempt ->
        try {
            return PresencePrivacySaveOutcome(settings = update(request))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            lastError = t
            if (!isPresencePrivacyTransient(t) || attempt == PRIVACY_SAVE_ATTEMPTS - 1) {
                return PresencePrivacySaveOutcome(error = t)
            }
            delay(PRIVACY_SAVE_RETRY_DELAY_MS)
        }
    }
    return PresencePrivacySaveOutcome(error = lastError)
}

fun isSameNetworkPrivacy(
    current: PresencePrivacySettings,
    expected: PresencePrivacySettings,
): Boolean =
    current.online_visibility == expected.online_visibility &&
        current.last_seen_visibility == expected.last_seen_visibility

fun isPresencePrivacyTransient(error: Throwable?): Boolean =
    error != null && (
        TransientNetworkErrors.isTransient(error) ||
            ProfileAboutPolicy.isTransientSyncFailure(error.message)
        )

fun presencePrivacyUserMessage(error: Throwable?): String {
    val raw = error?.message.orEmpty()
    if (isPresencePrivacyTransient(error)) {
        return "Сервер не ответил вовремя. Попробуйте ещё раз."
    }
    val cleaned = raw
        .lineSequence()
        .firstOrNull()
        ?.substringBefore("[url=")
        ?.trim()
        .orEmpty()
    return cleaned.takeIf { it.isNotBlank() && !it.contains("request_timeout", ignoreCase = true) }
        ?: "Не удалось сохранить видимость статуса"
}
