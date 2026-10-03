// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallTest {
    private val call = CallSession(
        id = "call-1",
        caller_id = "caller",
        callee_id = "callee",
        status = CallStatus.RINGING,
        livekit_room_id = "call-call-1",
        created_at = "2026-07-20T10:00:00Z",
    )

    @Test
    fun partnerIdFor_returnsOppositeParticipant() {
        assertEquals("callee", call.partnerIdFor("caller"))
        assertEquals("caller", call.partnerIdFor("callee"))
    }

    @Test
    fun displayAvatarUrl_usesPeerPhotoWhenPresent() {
        val avatars = mapOf("caller" to "data:image/png;base64,aaa", "callee" to "data:image/png;base64,bbb")
        assertEquals(
            "data:image/png;base64,bbb",
            call.displayAvatarUrl("caller", avatars::get),
        )
        assertEquals(
            "data:image/png;base64,aaa",
            call.displayAvatarUrl("callee", avatars::get),
        )
        assertEquals(null, call.displayAvatarUrl("caller", userAvatarUrl = { _ -> null }))
    }

    @Test
    fun displayAvatarUrl_prefersGroupIconThenCaller() {
        val group = call.copy(chat_id = "chat-1", call_scope = CallScope.GROUP, callee_id = "")
        assertEquals(
            "data:image/png;base64,group",
            group.displayAvatarUrl("callee", { "data:image/png;base64,caller" }, chatAvatarUrl = "data:image/png;base64,group"),
        )
        assertEquals(
            "data:image/png;base64,caller",
            group.displayAvatarUrl("callee", { id -> if (id == "caller") "data:image/png;base64,caller" else null }),
        )
    }

    @Test
    fun terminalStatuses_areEndedRejectedAndMissedOnly() {
        assertFalse(call.copy(status = CallStatus.RINGING).isTerminal())
        assertFalse(call.copy(status = CallStatus.CONNECTING).isTerminal())
        assertFalse(call.copy(status = CallStatus.ACTIVE).isTerminal())

        assertTrue(call.copy(status = CallStatus.ENDED).isTerminal())
        assertTrue(call.copy(status = CallStatus.REJECTED).isTerminal())
        assertTrue(call.copy(status = CallStatus.MISSED).isTerminal())
    }

    @Test
    fun historyStatusLabel_mapsCallStateForHistoryRows() {
        assertEquals("Входящий", call.copy(status = CallStatus.RINGING).historyStatusLabel("callee"))
        assertEquals("Исходящий", call.copy(status = CallStatus.RINGING).historyStatusLabel("caller"))
        assertEquals("Подключение", call.copy(status = CallStatus.CONNECTING).historyStatusLabel("caller"))
        assertEquals("Разговор", call.copy(status = CallStatus.ACTIVE).historyStatusLabel("caller"))
        assertEquals("Завершён", call.copy(status = CallStatus.ENDED).historyStatusLabel("caller"))
        assertEquals(
            "Завершён · 42 с",
            call.copy(status = CallStatus.ENDED, duration_sec = 42).historyStatusLabel("caller"),
        )
        assertEquals("Отклонён", call.copy(status = CallStatus.REJECTED).historyStatusLabel("caller"))
        assertEquals("Пропущен", call.copy(status = CallStatus.MISSED).historyStatusLabel("caller"))
    }

    @Test
    fun historyDetailsLabel_includesTypeTimeAndDuration() {
        val timeLabel = formatMessageTime(call.created_at)
        assertEquals(
            "Входящий · Аудио · $timeLabel",
            call.historyDetailsLabel("callee"),
        )
        assertEquals(
            "Исходящий · Видео · $timeLabel",
            call.copy(call_type = CallType.VIDEO).historyDetailsLabel("caller"),
        )
        assertEquals(
            "Завершён · Аудио · $timeLabel · 2 мин 5 с",
            call.copy(status = CallStatus.ENDED, duration_sec = 125).historyDetailsLabel("caller"),
        )
    }

    @Test
    fun activeStatusLabel_mapsCallStateForOverlay() {
        assertEquals(
            "Звоним…",
            CallUiState(call.copy(status = CallStatus.RINGING), "peer", CallRole.Outgoing).activeStatusLabel(),
        )
        assertEquals(
            "Входящий звонок",
            CallUiState(call.copy(status = CallStatus.RINGING), "peer", CallRole.Incoming).activeStatusLabel(),
        )
        assertEquals(
            "Подключение…",
            CallUiState(call.copy(status = CallStatus.CONNECTING), "peer", CallRole.Outgoing).activeStatusLabel(),
        )
        assertEquals(
            "На линии",
            CallUiState(call.copy(status = CallStatus.ACTIVE), "peer", CallRole.Outgoing).activeStatusLabel(),
        )
        assertEquals(
            "Звонок завершён",
            CallUiState(call.copy(status = CallStatus.ENDED), "peer", CallRole.Outgoing).activeStatusLabel(),
        )
        assertEquals(
            "Абонент занят",
            CallUiState(call.copy(status = CallStatus.REJECTED), "peer", CallRole.Outgoing).activeStatusLabel(),
        )
        assertEquals(
            "Звонок отклонён",
            CallUiState(call.copy(status = CallStatus.REJECTED), "peer", CallRole.Incoming).activeStatusLabel(),
        )
        assertEquals(
            "Пропущенный звонок",
            CallUiState(call.copy(status = CallStatus.MISSED), "peer", CallRole.Outgoing).activeStatusLabel(),
        )
    }
}
