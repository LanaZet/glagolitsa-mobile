// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val time: String,
)

@Serializable
data class HelloResponse(
    val message: String,
    val server_id: String = "",
)
