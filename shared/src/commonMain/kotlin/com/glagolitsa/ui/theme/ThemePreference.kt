// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.theme

/**
 * Device-scoped theme key. Account SQLDelight is the wrong home: cold start
 * opens the default namespace before [switchAccount], so a profile-only
 * write is invisible after restart.
 */
internal expect fun loadPersistedThemeKey(): String?

internal expect fun persistThemeKey(value: String)
