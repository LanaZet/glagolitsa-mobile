// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiErrorsTest {
    @Test
    fun profileSaveMessage_hidesDevScriptFor404() {
        val message = ApiException(HttpStatusCode.NotFound, "user not found").profileSaveMessage()
        assertEquals("user not found", message)
    }

    @Test
    fun profileSaveMessage_mapsUnauthorized() {
        val message = ApiException(HttpStatusCode.Unauthorized, null).profileSaveMessage()
        assertEquals("Сессия истекла. Выйдите и войдите снова", message)
    }
}