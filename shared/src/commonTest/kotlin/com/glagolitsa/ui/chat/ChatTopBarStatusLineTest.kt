// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatTopBarStatusLineTest {
    @Test
    fun connectionPlate_replacesPartnerPresence() {
        val (line, isPlate) = resolveChatTopBarStatusLine(
            isDirectMessage = true,
            groupSubtitle = null,
            partnerPresenceStatus = "в сети",
            connectionStatus = "Переподключаемся…",
        )
        assertEquals("Переподключаемся…", line)
        assertTrue(isPlate)
    }

    @Test
    fun healthy_showsPartnerPresence() {
        val (line, isPlate) = resolveChatTopBarStatusLine(
            isDirectMessage = true,
            groupSubtitle = null,
            partnerPresenceStatus = "в сети",
            connectionStatus = null,
        )
        assertEquals("в сети", line)
        assertFalse(isPlate)
    }

    @Test
    fun groupSubtitle_winsOverConnection() {
        val (line, isPlate) = resolveChatTopBarStatusLine(
            isDirectMessage = false,
            groupSubtitle = "3 участников, 1 в сети",
            partnerPresenceStatus = null,
            connectionStatus = "Переподключаемся…",
        )
        assertEquals("3 участников, 1 в сети", line)
        assertFalse(isPlate)
    }

    @Test
    fun dmWithoutPresence_defaultsOffline() {
        val (line, isPlate) = resolveChatTopBarStatusLine(
            isDirectMessage = true,
            groupSubtitle = null,
            partnerPresenceStatus = null,
            connectionStatus = null,
        )
        assertEquals("не в сети", line)
        assertFalse(isPlate)
    }

    @Test
    fun topBar_neverShowsCustomUserStatusAsPresence() {
        // Top bar second line is network presence (or connection plate), not «на встрече».
        val (line, isPlate) = resolveChatTopBarStatusLine(
            isDirectMessage = true,
            groupSubtitle = null,
            partnerPresenceStatus = "в сети",
            connectionStatus = null,
        )
        assertEquals("в сети", line)
        assertFalse(isPlate)
        assertFalse(line!!.contains("встреч"))
        assertFalse(line.contains("Статус не указан"))
    }

    @Test
    fun connectionPlate_doesNotLeakPartnerCustomStatus() {
        val (line, isPlate) = resolveChatTopBarStatusLine(
            isDirectMessage = true,
            groupSubtitle = null,
            partnerPresenceStatus = "на встрече", // mis-wired input would still be replaced by plate
            connectionStatus = "Обновляем соединение…",
        )
        assertEquals("Обновляем соединение…", line)
        assertTrue(isPlate)
    }
}
