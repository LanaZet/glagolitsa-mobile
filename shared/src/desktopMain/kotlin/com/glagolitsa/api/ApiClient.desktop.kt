// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol

private object DesktopRestOkHttpEngineFactory : HttpClientEngineFactory<OkHttpConfig> {
    override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine =
        OkHttp.create {
            block()
            preconfigured = OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectionPool(ConnectionPool(8, 2, TimeUnit.MINUTES))
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
}

private object DesktopWebSocketOkHttpEngineFactory : HttpClientEngineFactory<OkHttpConfig> {
    override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine =
        OkHttp.create {
            block()
            preconfigured = OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
                .connectTimeout(WebSocketTransport.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(WebSocketTransport.PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
}

actual fun createHttpEngine(): HttpClientEngineFactory<*> = DesktopRestOkHttpEngineFactory

actual fun createWebSocketHttpEngine(): HttpClientEngineFactory<*> = DesktopWebSocketOkHttpEngineFactory

actual fun defaultBaseUrl(): String = "http://127.0.0.1:8080"
