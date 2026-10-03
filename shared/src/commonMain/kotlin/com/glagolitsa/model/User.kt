// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val username: String,
    val email: String? = null,
    val display_name: String? = null,
    val status: String? = null,
    val bio: String? = null,
    val avatar_url: String? = null,
    val presence: String? = null,
    val nickname: String? = null,
    val position: String? = null,
    val created_at: String? = null,
)

@Serializable
data class UpdateProfileRequest(
    val display_name: String? = null,
    val status: String? = null,
    val bio: String? = null,
    val avatar_url: String? = null,
    val presence: String? = null,
    val nickname: String? = null,
    val position: String? = null,
)

/** Mattermost-style batch user cards request (avatars for chat list). */
@Serializable
data class UsersByIdsRequest(
    val ids: List<String>,
)

/** Имя для отображения в списках и шапках: display_name или @username. */
fun User.displayLabel(): String =
    display_name?.takeIf { it.isNotBlank() } ?: username

fun User.avatarLabel(): String =
    displayLabel().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

fun User.presenceState(): UserPresence = UserPresence.fromApi(presence)