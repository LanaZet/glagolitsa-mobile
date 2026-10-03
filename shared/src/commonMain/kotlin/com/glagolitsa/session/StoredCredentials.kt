// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

data class StoredCredentials(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMs: Long,
    val userId: String? = null,
    val username: String? = null,
    val apiBaseUrl: String? = null,
    val serverId: String? = null,
    val serverConfirmedAtEpochMs: Long = 0L,
)

/**
 * За сколько до exp пробуем refresh (как в Telegram — заранее, не в момент 401).
 *
 * MUST be well below the server access-token TTL. VPS currently issues ~300s tokens;
 * a 5-minute skew made every freshly issued token look "near expiry" and forced
 * continuous refresh races → rotated refresh invalidated → hard reauth → outbox stuck
 * on SENDING forever.
 */
const val SESSION_PROACTIVE_REFRESH_SKEW_MS = 60_000L
