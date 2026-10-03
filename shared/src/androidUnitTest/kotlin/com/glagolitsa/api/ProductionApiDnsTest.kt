// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProductionApiDnsTest {
    private val cloudflare = InetAddress.getByName("104.16.0.1")
    private val fallbackIp = "203.0.113.10"
    private val fallback = InetAddress.getByName(fallbackIp)

    @Test
    fun usesSystemDnsWhenCloudflareResolves() {
        val got = lookupProductionApi(
            hostname = PRODUCTION_API_HOST,
            systemLookup = { listOf(cloudflare) },
        )
        assertEquals(listOf(cloudflare), got)
    }

    @Test
    fun doesNotPinOriginWhenSystemDnsWorks() {
        val got = lookupProductionApi(
            hostname = PRODUCTION_API_HOST,
            systemLookup = { listOf(cloudflare) },
            fallbackIp = fallbackIp,
        )
        assertFalse(got.contains(fallback))
    }

    @Test
    fun fallsBackToOriginOnlyWhenDnsFails() {
        val got = lookupProductionApi(
            hostname = PRODUCTION_API_HOST,
            systemLookup = { error("nxdomain") },
            fallbackIp = fallbackIp,
        )
        assertEquals(listOf(fallback), got)
    }

    @Test
    fun fallsBackWhenSystemDnsReturnsEmpty() {
        val got = lookupProductionApi(
            hostname = PRODUCTION_API_HOST,
            systemLookup = { emptyList() },
            fallbackIp = fallbackIp,
        )
        assertEquals(listOf(fallback), got)
    }

    @Test
    fun dnsFailureWithoutFallbackReturnsEmpty() {
        val got = lookupProductionApi(
            hostname = PRODUCTION_API_HOST,
            systemLookup = { error("nxdomain") },
        )
        assertEquals(emptyList<InetAddress>(), got)
    }

    @Test
    fun otherHostsUseSystemDns() {
        val other = InetAddress.getByName("1.1.1.1")
        val got = lookupProductionApi(
            hostname = "rtc.glagolit.me",
            systemLookup = { listOf(other) },
        )
        assertEquals(listOf(other), got)
    }

    @Test
    fun emulatorTunnelUsesLoopback() {
        val got = lookupProductionApi(
            hostname = PRODUCTION_API_HOST,
            systemLookup = { listOf(cloudflare) },
            forceLoopback = true,
        )
        assertEquals(listOf(InetAddress.getByName(EMULATOR_HOST_LOOPBACK)), got)
    }
}
