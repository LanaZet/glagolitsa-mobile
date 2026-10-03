// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

@Serializable
data class PowChallengeResponse(
    val challenge_id: String,
    val challenge: String,
    val difficulty: Int,
    val expires_at: String,
)