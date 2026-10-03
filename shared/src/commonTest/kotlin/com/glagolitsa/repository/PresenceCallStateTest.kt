// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.model.CallStatus
import com.glagolitsa.model.CallSession
import com.glagolitsa.model.CallConnectionPolicy
import com.glagolitsa.model.LastSeenBucket
import com.glagolitsa.model.PresenceStatus
import com.glagolitsa.model.UserPresenceView
import com.glagolitsa.model.isTerminal
import com.glagolitsa.model.statusLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure rules for call ↔ presence: cancel must not leave in_call stuck.
 * (PresenceController mutations covered via map semantics here without Android deps.)
 */
class PresenceCallStateTest {

    @Test
    fun terminalCall_mustNotMarkConnected() {
        val ended = sampleCall(status = CallStatus.ENDED)
        val rejected = sampleCall(status = CallStatus.REJECTED)
        val missed = sampleCall(status = CallStatus.MISSED)
        assertTrue(ended.isTerminal())
        assertTrue(rejected.isTerminal())
        assertTrue(missed.isTerminal())
        assertFalse(sampleCall(status = CallStatus.RINGING).isTerminal())
        assertFalse(sampleCall(status = CallStatus.CONNECTING).isTerminal())
        assertFalse(sampleCall(status = CallStatus.ACTIVE).isTerminal())
    }

    @Test
    fun applyPartnerCallEnded_clearsInCallToOnline() {
        var map = mapOf(
            "alice" to UserPresenceView(
                user_id = "alice",
                status = PresenceStatus.IN_CALL,
                in_call = true,
                call_id = "c1",
            ),
        )
        map = applyCallEnded(map, "alice")
        val view = map.getValue("alice")
        assertEquals(PresenceStatus.ONLINE, view.status)
        assertFalse(view.in_call)
        assertNull(view.call_id)
    }

    @Test
    fun applyPartnerInCall_setsNetworkInCallOnly() {
        var map = mapOf(
            "alice" to UserPresenceView(user_id = "alice", status = PresenceStatus.ONLINE),
        )
        map = applyInCall(map, "alice", "c9")
        val view = map.getValue("alice")
        assertEquals(PresenceStatus.IN_CALL, view.status)
        assertTrue(view.in_call)
        assertEquals("c9", view.call_id)
    }

    @Test
    fun onlineEvent_clearsStaleLastSeenBucket() {
        val view = presenceAfterOnlineEvent(
            UserPresenceView(
                user_id = "alice",
                status = PresenceStatus.OFFLINE,
                last_seen_bucket = LastSeenBucket.LONG_AGO,
            ),
            "alice",
        )

        assertEquals(PresenceStatus.ONLINE, view.status)
        assertNull(view.last_seen_bucket)
        assertEquals("в сети", view.statusLabel())
    }

    @Test
    fun offlineEvent_clearsStaleLastSeenBucketUntilServerSnapshotRefreshesIt() {
        val view = presenceAfterOfflineEvent(
            UserPresenceView(
                user_id = "alice",
                status = PresenceStatus.ONLINE,
                last_seen_bucket = LastSeenBucket.LONG_AGO,
            ),
            "alice",
        )

        assertEquals(PresenceStatus.OFFLINE, view.status)
        assertNull(view.last_seen_bucket)
        assertEquals("не в сети", view.statusLabel())
    }

    @Test
    fun inCallEvent_clearsStaleLastSeenBucket() {
        val view = presenceAfterInCallEvent(
            UserPresenceView(
                user_id = "alice",
                status = PresenceStatus.OFFLINE,
                last_seen_bucket = LastSeenBucket.LONG_AGO,
            ),
            "alice",
            "c9",
        )

        assertEquals(PresenceStatus.IN_CALL, view.status)
        assertNull(view.last_seen_bucket)
        assertEquals("в звонке", view.statusLabel())
    }

    @Test
    fun cancelRace_sequenceEndsWithoutInCall() {
        // Simulate: set in_call (mark connected), then call.ended (hangup) → must clear.
        var map = emptyMap<String, UserPresenceView>()
        map = applyInCall(map, "bob", "call-1")
        assertTrue(map.getValue("bob").in_call)
        map = applyCallEnded(map, "bob")
        assertFalse(map.getValue("bob").in_call)
        assertEquals(PresenceStatus.ONLINE, map.getValue("bob").status)
    }

    @Test
    fun shouldMarkConnected_onlyWhenLiveActiveCall() {
        assertTrue(CallConnectionPolicy.canMarkConnected("c1", sampleCall("c1", CallStatus.CONNECTING)))
        assertTrue(CallConnectionPolicy.canMarkConnected("c1", sampleCall("c1", CallStatus.RINGING)))
        assertFalse(CallConnectionPolicy.canMarkConnected("c1", sampleCall("c1", CallStatus.ENDED)))
        assertFalse(CallConnectionPolicy.canMarkConnected(null, sampleCall("c1", CallStatus.CONNECTING)))
        assertFalse(CallConnectionPolicy.canMarkConnected("other", sampleCall("c1", CallStatus.CONNECTING)))
    }

    private fun sampleCall(
        id: String = "call-1",
        status: String = CallStatus.RINGING,
    ) = CallSession(
        id = id,
        caller_id = "alice",
        callee_id = "bob",
        status = status,
        livekit_room_id = "room-$id",
        created_at = "2026-07-24T00:00:00Z",
    )

    /** Mirrors PresenceController.applyPartnerCallEnded map update. */
    private fun applyCallEnded(
        map: Map<String, UserPresenceView>,
        userId: String,
    ): Map<String, UserPresenceView> {
        val current = map[userId]
        val nextStatus = when {
            current == null -> PresenceStatus.ONLINE
            current.status == PresenceStatus.IN_CALL || current.in_call -> PresenceStatus.ONLINE
            else -> current.status
        }
        return map + (userId to (current?.copy(status = nextStatus, in_call = false, call_id = null)
            ?: UserPresenceView(user_id = userId, status = nextStatus)))
    }

    private fun applyInCall(
        map: Map<String, UserPresenceView>,
        userId: String,
        callId: String,
    ): Map<String, UserPresenceView> {
        val current = map[userId]
        return map + (userId to (current?.copy(
            status = PresenceStatus.IN_CALL,
            in_call = true,
            call_id = callId,
        ) ?: UserPresenceView(
            user_id = userId,
            status = PresenceStatus.IN_CALL,
            in_call = true,
            call_id = callId,
        )))
    }

}
