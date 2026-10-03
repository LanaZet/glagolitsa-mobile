// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.platform.NetworkPathKind
import com.glagolitsa.platform.NetworkPathState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionBannerStateTest {
    @Test
    fun healthyConnected_hidesBanner() {
        val banner = reduceConnectionBannerState(
            wsState = RealtimeConnectionState.Connected,
            networkState = NetworkPathState(kind = NetworkPathKind.Available),
            reauthRequired = false,
        )
        assertEquals(ConnectionBannerKind.Hidden, banner.kind)
        assertNull(banner.connectionStatusText())
    }

    @Test
    fun reconnectingWithNetwork_isTransientReconnecting() {
        val banner = reduceConnectionBannerState(
            wsState = RealtimeConnectionState.Reconnecting,
            networkState = NetworkPathState(kind = NetworkPathKind.Available),
            reauthRequired = false,
        )
        assertEquals(ConnectionBannerKind.Reconnecting, banner.kind)
        assertEquals("Переподключаемся…", banner.connectionStatusText())
        assertTrue(banner.kind.isTransientProcess())
        assertIs<ConnectionBannerPresentation.Transient>(presentConnectionBannerSequence(banner))
    }

    @Test
    fun offlinePath_staysHidden_noPermanentPlate() {
        val connectingOffline = reduceConnectionBannerState(
            wsState = RealtimeConnectionState.Connecting,
            networkState = NetworkPathState(kind = NetworkPathKind.Unavailable),
            reauthRequired = false,
        )
        assertEquals(ConnectionBannerKind.Hidden, connectingOffline.kind)
        assertNull(connectingOffline.connectionStatusText())

        val reconnectingOffline = reduceConnectionBannerState(
            wsState = RealtimeConnectionState.Reconnecting,
            networkState = NetworkPathState(kind = NetworkPathKind.Unavailable),
            reauthRequired = false,
        )
        assertEquals(ConnectionBannerKind.Hidden, reconnectingOffline.kind)
    }

    @Test
    fun reauthRequired_isStickyFailed() {
        val banner = reduceConnectionBannerState(
            wsState = RealtimeConnectionState.Connected,
            networkState = NetworkPathState(kind = NetworkPathKind.Available),
            reauthRequired = true,
        )
        assertEquals(ConnectionBannerKind.Failed, banner.kind)
        assertEquals("Требуется вход", banner.connectionStatusText())
        assertTrue(banner.kind.isSticky())
        assertFalse(banner.kind.isTransientProcess())
        assertIs<ConnectionBannerPresentation.Immediate>(presentConnectionBannerSequence(banner))
    }

    @Test
    fun constrainedPath_isTransientDegraded() {
        val banner = reduceConnectionBannerState(
            wsState = RealtimeConnectionState.Connected,
            networkState = NetworkPathState(
                kind = NetworkPathKind.Constrained,
                isConstrained = true,
            ),
            reauthRequired = false,
        )
        assertEquals(ConnectionBannerKind.Degraded, banner.kind)
        assertEquals("Соединение ограничено", banner.connectionStatusText())
        assertTrue(banner.kind.isTransientProcess())
        val presentation = presentConnectionBannerSequence(banner)
        assertIs<ConnectionBannerPresentation.Transient>(presentation)
        assertEquals(CONNECTION_BANNER_DEBOUNCE_MS, presentation.showAfterMs)
        assertEquals(CONNECTION_BANNER_MAX_VISIBLE_MS, presentation.hideAfterMs)
    }

    @Test
    fun connectingWithNetwork_showsConnectingCopy() {
        val banner = reduceConnectionBannerState(
            wsState = RealtimeConnectionState.Connecting,
            networkState = NetworkPathState(kind = NetworkPathKind.Available),
            reauthRequired = false,
        )
        assertEquals(ConnectionBannerKind.Connecting, banner.kind)
        assertEquals("Обновляем соединение…", banner.connectionStatusText())
    }

    @Test
    fun hidden_isImmediate() {
        val presentation = presentConnectionBannerSequence(ConnectionBannerState())
        assertIs<ConnectionBannerPresentation.Immediate>(presentation)
        assertEquals(ConnectionBannerKind.Hidden, presentation.state.kind)
    }
}
