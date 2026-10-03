// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.push

/** Platform push token (FCM / APNs / Web Push). */
expect class PushTokenProvider() {
    suspend fun currentToken(): String?
    val platform: String
}