// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

// WebSocketTransport is same package
import android.os.Build
import com.glagolitsa.shared.BuildConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol

/** Public API hostname (TLS SNI / cert). */
internal const val PRODUCTION_API_HOST = "api.glagolit.me"
/** Host loopback as seen from emulator (phone-USB SSH tunnel on Mac). */
internal const val EMULATOR_HOST_LOOPBACK = "10.0.2.2"

/**
 * Last-resort origin address if public DNS for [PRODUCTION_API_HOST] fails.
 * Empty unless the local build sets `apiFallbackIp` (gitignored). A public
 * checkout must not ship a machine address.
 */
internal fun productionApiFallbackIp(): String = BuildConfig.API_FALLBACK_IP.trim()

internal fun lookupProductionApi(
    hostname: String,
    systemLookup: (String) -> List<InetAddress>,
    fallbackIp: String = "",
    forceLoopback: Boolean = false,
): List<InetAddress> {
    if (hostname != PRODUCTION_API_HOST) {
        return systemLookup(hostname)
    }
    if (forceLoopback) {
        return listOf(InetAddress.getByName(EMULATOR_HOST_LOOPBACK))
    }
    val resolved = try {
        systemLookup(hostname)
    } catch (_: Exception) {
        emptyList()
    }
    if (resolved.isNotEmpty()) {
        return resolved
    }
    if (fallbackIp.isBlank()) {
        return emptyList()
    }
    return listOf(InetAddress.getByName(fallbackIp))
}

private val productionApiDns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        lookupProductionApi(
            hostname = hostname,
            systemLookup = { Dns.SYSTEM.lookup(it) },
            fallbackIp = productionApiFallbackIp(),
            forceLoopback = BuildConfig.EMULATOR_PHONE_TUNNEL && isAndroidEmulator(),
        )
}

/**
 * REST engine: HTTP/1.1 only + dedicated pool.
 * Isolates API from half-open H2 multiplex / WS ping stalls that previously
 * made every call hang for the full Ktor 15s request timeout.
 */
private object AndroidRestOkHttpEngineFactory : HttpClientEngineFactory<OkHttpConfig> {
    override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine =
        OkHttp.create {
            block()
            preconfigured = OkHttpClient.Builder()
                .dns(productionApiDns)
                .protocols(listOf(Protocol.HTTP_1_1))
                // Short-lived pool: avoid reusing half-open sockets that hang until Ktor 15s.
                .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addNetworkInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Connection", "close")
                            .build(),
                    )
                }
                .build()
        }
}

/**
 * WebSocket engine: separate OkHttp client/pool so control-plane ping/pong
 * cannot starve REST (Signal-style channel separation).
 */
private object AndroidWebSocketOkHttpEngineFactory : HttpClientEngineFactory<OkHttpConfig> {
    override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine =
        OkHttp.create {
            block()
            preconfigured = OkHttpClient.Builder()
                .dns(productionApiDns)
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
                .connectTimeout(WebSocketTransport.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                // Ktor WebSockets owns heartbeat. A second OkHttp ping loop can
                // close healthy mobile WSS with "sent ping but didn't receive pong".
                .pingInterval(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
}

actual fun createHttpEngine(): HttpClientEngineFactory<*> = AndroidRestOkHttpEngineFactory

actual fun createWebSocketHttpEngine(): HttpClientEngineFactory<*> = AndroidWebSocketOkHttpEngineFactory

/**
 * Базовый URL API:
 * - Gradle `-PapiBaseUrl=...` / BuildConfig.API_BASE_URL — **истина** (public или local)
 * - Если URL не зашит: эмулятор → 10.0.2.2, USB-телефон → 127.0.0.1 (adb reverse)
 *
 * Важно: никогда не подменять зашитый public URL на 10.0.2.2.
 * Иначе «Сунь Укун / public» показывает public API, а ходит на локальный docker.
 */
actual fun defaultBaseUrl(): String {
    val configured = BuildConfig.API_BASE_URL.trim()
    if (configured.isNotBlank()) {
        return configured
    }
    return if (isAndroidEmulator()) {
        "http://10.0.2.2:8080"
    } else {
        "http://127.0.0.1:8080"
    }
}

/** Эвристика: отличаем эмулятор от реального устройства для выбора хоста. */
private fun isAndroidEmulator(): Boolean {
    return Build.FINGERPRINT.startsWith("generic")
        || Build.FINGERPRINT.startsWith("unknown")
        || Build.MODEL.contains("google_sdk")
        || Build.MODEL.contains("Emulator")
        || Build.MODEL.contains("Android SDK built for x86")
        || Build.MANUFACTURER.contains("Genymotion")
        || Build.HARDWARE.contains("goldfish")
        || Build.HARDWARE.contains("ranchu")
        || Build.PRODUCT.contains("sdk_gphone")
        || Build.PRODUCT.contains("emulator")
        || Build.PRODUCT.contains("simulator")
}
