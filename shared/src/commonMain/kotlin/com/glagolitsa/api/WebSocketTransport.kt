// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

/**
 * Shared realtime transport knobs.
 * Idle public WSS without pings + short read timeouts flaps «Переподключаемся…»
 * while HTTP presence still reports «в сети».
 */
object WebSocketTransport {
    const val PING_INTERVAL_MS: Long = 20_000L
    const val CONNECT_TIMEOUT_MS: Long = 12_000L
}
