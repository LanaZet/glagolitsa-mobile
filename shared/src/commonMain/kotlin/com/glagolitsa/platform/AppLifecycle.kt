// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

expect object AppLifecycle {
    fun restart()

    /** True while the main activity/window is resumed (suppress duplicate banners). */
    fun isInForeground(): Boolean
}