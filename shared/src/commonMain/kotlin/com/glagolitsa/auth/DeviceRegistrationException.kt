// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.auth

/**
 * Аккаунт и сессия могут уже существовать, но [POST /api/devices] не прошёл —
 * на сервере остаётся account_status=inactive без device_id.
 */
class DeviceRegistrationException(
    message: String,
    val deviceId: String? = null,
    override val cause: Throwable? = null,
) : Exception(message, cause)