// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.api

import com.glagolitsa.api.ApiException
import com.glagolitsa.auth.DeviceRegistrationException
import com.glagolitsa.auth.RegistrationDeferredException
import com.glagolitsa.auth.RegistrationValidation
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthMessagesTest {
    @Test
    fun registrationUiError_mapsDeferredToUsernameField() {
        val uiError = RegistrationDeferredException("accepted").registrationUiError()
        assertEquals(RegistrationValidation.Field.USERNAME, uiError.field)
        assertTrue(uiError.suggestLogin)
        assertTrue(uiError.bannerHint?.contains("профиле") == true)
    }

    @Test
    fun authLoginMessage_mapsUnauthorized() {
        val message = ApiException(HttpStatusCode.Unauthorized, null).authLoginMessage()
        assertEquals("Неверное имя пользователя или пароль", message)
    }

    @Test
    fun authRegistrationMessage_mapsConnectionFailure() {
        val message = Exception("Failed to connect to /10.0.2.2:8080").authRegistrationMessage()
        assertTrue(message.contains("Не удалось подключиться к серверу"))
        assertTrue(message.contains("VPN"))
    }

    @Test
    fun registrationUiError_mapsInvalidUsernameToRussianHint() {
        val uiError = ApiException(HttpStatusCode.BadRequest, "invalid username").registrationUiError()
        assertEquals(RegistrationValidation.Field.USERNAME, uiError.field)
        assertTrue(uiError.message.contains("английские буквы"))
    }

    @Test
    fun authLoginMessage_mapsDeviceRegistrationFailure() {
        val message = DeviceRegistrationException("timeout", deviceId = "dev-1").authLoginMessage()
        assertEquals("timeout", message)
    }

    @Test
    fun registrationUiError_mapsDeviceRegistrationToLoginHint() {
        val uiError = DeviceRegistrationException("server error").registrationUiError()
        assertTrue(uiError.suggestLogin)
        assertTrue(uiError.bannerHint?.contains("device_id") == true)
    }
}
