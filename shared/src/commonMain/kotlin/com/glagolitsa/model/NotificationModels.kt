// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

@Serializable
data class RegisterPushTokenRequest(
    val platform: String,
    val token: String,
    val device_id: String,
)

@Serializable
data class RegisterPushTokenResponse(
    val id: String,
    val platform: String,
    val device_id: String,
    val status: String,
)

@Serializable
data class NotificationPreferences(
    val user_id: String,
    val messages_enabled: Boolean = true,
    val calls_enabled: Boolean = true,
    val new_device_enabled: Boolean = true,
    val show_sender_name: Boolean = false,
    val show_message_preview: Boolean = false,
    val badge_enabled: Boolean = true,
)

@Serializable
data class UpdateNotificationPreferencesRequest(
    val messages_enabled: Boolean? = null,
    val calls_enabled: Boolean? = null,
    val new_device_enabled: Boolean? = null,
    val show_sender_name: Boolean? = null,
    val show_message_preview: Boolean? = null,
    val badge_enabled: Boolean? = null,
)