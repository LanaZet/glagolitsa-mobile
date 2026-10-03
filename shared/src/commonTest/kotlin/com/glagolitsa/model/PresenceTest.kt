// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresenceTest {
    @Test
    fun statusLabel_prefersVisibleCallState() {
        val view = UserPresenceView(
            user_id = "u1",
            status = PresenceStatus.ONLINE,
            in_call = true,
            call_id = "call-1",
        )

        assertEquals("в звонке", view.statusLabel())
        assertTrue(view.isVisibleInCall())
        assertTrue(view.isOnlineLike())
    }

    @Test
    fun statusLabel_usesLastSeenBucketsForOfflineStates() {
        assertEquals(
            "был(а) сегодня",
            UserPresenceView(
                user_id = "u1",
                status = PresenceStatus.OFFLINE,
                last_seen_bucket = LastSeenBucket.TODAY,
            ).statusLabel(),
        )
        assertEquals(
            "не в сети",
            UserPresenceView(user_id = "u1", status = PresenceStatus.OFFLINE).statusLabel(),
        )
    }

    @Test
    fun hiddenPresenceDoesNotLeakOnlineOrCallState() {
        val view = UserPresenceView(
            user_id = "u1",
            status = PresenceStatus.HIDDEN,
            last_seen_bucket = LastSeenBucket.RECENTLY,
            in_call = true,
            call_id = "call-1",
        )

        assertEquals("был(а) недавно", view.statusLabel())
        assertFalse(view.isVisibleInCall())
        assertFalse(view.isOnlineLike())
    }

    @Test
    fun inCallStatusCountsAsOnlineLike() {
        val view = UserPresenceView(user_id = "u1", status = PresenceStatus.IN_CALL)

        assertTrue(view.isVisibleInCall())
        assertTrue(view.isOnlineLike())
    }

    @Test
    fun statusLabel_typingAndRecordingAreNetworkChannel() {
        assertEquals(
            "печатает…",
            UserPresenceView(user_id = "u1", status = PresenceStatus.TYPING).statusLabel(),
        )
        assertEquals(
            "записывает голосовое…",
            UserPresenceView(user_id = "u1", status = PresenceStatus.RECORDING_VOICE).statusLabel(),
        )
        assertEquals(
            "записывает видео…",
            UserPresenceView(user_id = "u1", status = PresenceStatus.RECORDING_VIDEO).statusLabel(),
        )
    }

    @Test
    fun statusLabel_isNeverCustomUserText() {
        // Presence view has no field for custom status; labels stay network-only.
        val view = UserPresenceView(user_id = "u1", status = PresenceStatus.ONLINE)
        assertEquals("в сети", view.statusLabel())
        assertFalse(view.statusLabel().contains("встреч"))
    }
}
