// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageDeliveryIndicatorTest {
    @Test
    fun sendErrorLabel_mapsCommonOutboxErrorsToCompactRussianLabels() {
        assertEquals("Нет соединения", sendErrorLabel("Failed to connect to /10.0.2.2:18443"))
        assertEquals("Нет соединения", sendErrorLabel("Connection refused"))
        assertEquals("Таймаут", sendErrorLabel("Request timeout has expired"))
        assertNull(sendErrorLabel("Partner has no registered devices"))
        assertEquals("Не отправлено", sendErrorLabel("No encrypted envelopes produced"))
        assertEquals("Нужен вход", sendErrorLabel("auth pending"))
        assertEquals("Проверьте ключ", sendErrorLabel("Сначала подтвердите новый ключ безопасности собеседника"))
    }

    @Test
    fun sendErrorLabel_keepsSpecificUnknownErrorsAndIgnoresBlank() {
        assertEquals("Не отправлено", sendErrorLabel(" server exploded "))
        assertNull(sendErrorLabel(null))
        assertNull(sendErrorLabel("   "))
    }
}
