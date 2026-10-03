// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.log

expect object AppLog {
    fun debug(message: String)
    fun warning(message: String)
}
