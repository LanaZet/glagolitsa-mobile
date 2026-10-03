// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

@Serializable
data class AuthResponse(
    val token: String,
    val access_token: String? = null,
    val refresh_token: String? = null,
    val token_id: String? = null,
    val session_id: String? = null,
    val expires_in: Int? = null,
    val user: User,
)

@Serializable
data class RegisterRequest(
    val username: String,
    val email: String? = null,
    val password: String,
    val device_id: String? = null,
    val pow_challenge_id: String? = null,
    val pow_solution: String? = null,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val device_id: String? = null,
)

@Serializable
data class RefreshRequest(
    val refresh_token: String,
    val device_id: String? = null,
)

@Serializable
data class RecoverySetupRequest(
    val recovery_key: String,
)

@Serializable
data class RecoveryVerifyRequest(
    val username: String,
    val recovery_key: String,
)

@Serializable
data class RecoveryCompleteRequest(
    val recovery_token: String,
    val new_password: String,
)

@Serializable
data class RecoveryTicketResponse(
    val recovery_token: String? = null,
    val username: String? = null,
    val expires_in: Int? = null,
    val status: String? = null,
)

@Serializable
data class RecoveryCompleteResponse(
    val status: String,
    val username: String? = null,
)

@Serializable
data class RecoveryStatusResponse(
    val recovery_key_set: Boolean = false,
    val recovery_key_hint: String? = null,
    val trusted_device_count: Int = 0,
    val passkey_ready: Boolean = false,
)

@Serializable
data class TrustedRecoveryStartRequest(
    val username: String,
)

@Serializable
data class TrustedRecoveryStartResponse(
    val challenge_id: String,
    val expires_in: Int? = null,
    val status: String? = null,
)

@Serializable
data class TrustedRecoveryApproveRequest(
    val challenge_id: String,
)

@Serializable
data class TrustedRecoveryPollRequest(
    val challenge_id: String,
)

@Serializable
data class TrustedRecoveryPendingItem(
    val challenge_id: String,
    val expires_at: String? = null,
)

@Serializable
data class TrustedRecoveryPendingResponse(
    val challenges: List<TrustedRecoveryPendingItem> = emptyList(),
)

@Serializable
data class WebAuthnBeginRequest(
    val username: String? = null,
)

@Serializable
data class WebAuthnBeginResponse(
    val session_id: String,
    val rp_id: String,
    val origin: String,
    val challenge: String,
    val timeout_ms: Int? = null,
    val user_id: String? = null,
    val user_name: String? = null,
    val exclude_credential_ids: List<String>? = null,
    val allow_credential_ids: List<String>? = null,
)

@Serializable
data class WebAuthnFinishRequest(
    val session_id: String,
    val username: String? = null,
    val credential: kotlinx.serialization.json.JsonObject,
)
