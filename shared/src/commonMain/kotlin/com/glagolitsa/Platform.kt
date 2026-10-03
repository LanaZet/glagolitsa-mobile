// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform