// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiClientWebSocketTest {
    @Test
    fun webSocketUrl_convertsHttpsToWss() {
        val client = ApiClient(baseUrl = "https://api.glagolit.me")
        assertEquals(
            "wss://api.glagolit.me/api/ws?token=abc",
            client.webSocketUrl("abc"),
        )
    }

    @Test
    fun webSocketUrl_convertsHttpToWs() {
        val client = ApiClient(baseUrl = "http://localhost:8080")
        assertEquals(
            "ws://localhost:8080/api/ws?token=tok",
            client.webSocketUrl("tok"),
        )
    }
}