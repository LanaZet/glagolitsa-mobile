// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.security

enum class LocalAuthAvailability {
    Available,
    Unsupported,
    NoBiometricEnrolled,
    HardwareUnavailable,
    SecurityUpdateRequired,
    Unknown,
}

sealed interface LocalAuthResult {
    data object Success : LocalAuthResult
    data object Cancelled : LocalAuthResult
    data class Failed(val message: String? = null) : LocalAuthResult
}

expect class LocalAuthenticator {
    suspend fun availability(): LocalAuthAvailability
    suspend fun authenticate(reason: String): LocalAuthResult
}
