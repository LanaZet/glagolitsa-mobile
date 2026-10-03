// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.ui.chatScreenErrorMessage
import com.glagolitsa.ui.chatErrorLogDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransientNetworkErrorsTest {
    @Test
    fun mapsTunnelAndConnectFailuresToActionableCopy() {
        assertTrue(TransientNetworkErrors.isTransientMessage("Failed to connect to /10.0.2.2:18443"))
        assertEquals(
            TransientNetworkErrors.NETWORK_ERROR,
            chatScreenErrorMessage("Failed to connect to /10.0.2.2:18443"),
        )
        assertEquals(TransientNetworkErrors.NETWORK_ERROR, chatScreenErrorMessage("failed to connect to api.glagolit.me"))
        assertEquals(TransientNetworkErrors.NETWORK_ERROR, chatScreenErrorMessage("Connection refused"))
        assertEquals(TransientNetworkErrors.TIMEOUT_ERROR, chatScreenErrorMessage("Request timeout has expired"))
        assertEquals(TransientNetworkErrors.AUTH_REQUIRED_ERROR, chatScreenErrorMessage("Session expired"))
        assertEquals(TransientNetworkErrors.AUTH_REQUIRED_ERROR, chatScreenErrorMessage("HTTP 401"))
        assertEquals(TransientNetworkErrors.TIMEOUT_ERROR, chatScreenErrorMessage("HTTP 408"))
    }

    @Test
    fun mapsKnownActionFailuresToSpecificCopy() {
        assertEquals(
            TransientNetworkErrors.RECIPIENT_UNAVAILABLE_ERROR,
            chatScreenErrorMessage("Partner has no registered devices"),
        )
        assertEquals(
            TransientNetworkErrors.RECIPIENT_UNAVAILABLE_ERROR,
            TransientNetworkErrors.userVisibleChatError("Partner has no registered devices"),
        )
        assertEquals(
            TransientNetworkErrors.SECURITY_KEY_ERROR,
            chatScreenErrorMessage("Сначала подтвердите новый ключ безопасности собеседника"),
        )
        assertEquals(
            TransientNetworkErrors.ACCESS_DENIED_ERROR,
            chatScreenErrorMessage("forbidden"),
        )
        assertEquals(
            TransientNetworkErrors.RATE_LIMIT_ERROR,
            chatScreenErrorMessage("HTTP 429"),
        )
    }

    @Test
    fun replacesInternalFailuresWithUsefulSafeCopy() {
        assertEquals(
            TransientNetworkErrors.MESSAGE_UNAVAILABLE_ERROR,
            chatScreenErrorMessage("Message not found after enqueue"),
        )
        assertEquals(
            TransientNetworkErrors.SERVER_ERROR,
            chatScreenErrorMessage("object storage is not configured"),
        )
        assertEquals(
            TransientNetworkErrors.SERVER_ERROR,
            chatScreenErrorMessage("HTTP 503"),
        )
        assertEquals(
            TransientNetworkErrors.GENERIC_CHAT_ACTION_ERROR,
            chatScreenErrorMessage("server exploded"),
        )
    }

    @Test
    fun preservesSafeRussianUserFacingMessages() {
        assertEquals(
            "Текст не изменился",
            chatScreenErrorMessage("Текст не изменился"),
        )
        assertEquals(
            TransientNetworkErrors.CHAT_REFRESH_ERROR,
            chatScreenErrorMessage("Не удалось обновить чат. Проверьте соединение"),
        )
    }

    @Test
    fun redactsSensitiveLogDetails() {
        val detail = chatErrorLogDetail(
            "Authorization: Bearer abc.def.ghi password=secret access_token=raw-token-value failed to connect localhost:8080",
        )
        assertFalse(detail.contains("abc.def.ghi"))
        assertFalse(detail.contains("secret"))
        assertFalse(detail.contains("raw-token-value"))
        assertFalse(detail.contains("localhost"))
        assertTrue(detail.contains("<redacted>"))
        assertTrue(detail.contains("<local>"))
    }
}
