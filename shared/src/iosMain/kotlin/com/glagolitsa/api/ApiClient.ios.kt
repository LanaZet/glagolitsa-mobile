// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSProcessInfo

/**
 * API base URL resolution for Apple:
 * 1. Env `GLAGOLITSA_API_BASE_URL` (Xcode scheme / simctl spawn)
 * 2. iOS **Simulator** → local Go from [run-and-debug.sh] (`http://127.0.0.1:8080`)
 *    — same Docker/Postgres + Go as Android, host loopback (not 10.0.2.2)
 * 3. Physical device → public HTTPS unless env override (LAN IP for local)
 *
 * Darwin engine is process-wide; REST and WS still use separate [HttpClient]
 * instances in [ApiClient] so timeout/plugin config does not collide.
 */
actual fun createHttpEngine(): HttpClientEngineFactory<*> = Darwin

actual fun createWebSocketHttpEngine(): HttpClientEngineFactory<*> = Darwin

actual fun defaultBaseUrl(): String {
    val env = NSProcessInfo.processInfo.environment
    val configured = (env["GLAGOLITSA_API_BASE_URL"] as? String)?.trim().orEmpty()
    if (configured.isNotBlank()) return configured
    // Simulator shares the Mac network stack → host localhost is the local Go API.
    val onSimulator = env["SIMULATOR_DEVICE_NAME"] != null ||
        env["SIMULATOR_UDID"] != null ||
        env["SIMULATOR_ROOT"] != null
    if (onSimulator) {
        return "http://127.0.0.1:8080"
    }
    return "https://api.glagolit.me"
}
