// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID

actual fun installationSeed(): String {
    val defaults = NSUserDefaults.standardUserDefaults
    val key = "glagolitsa.installation_id"
    val existing = defaults.stringForKey(key)
    if (!existing.isNullOrBlank()) return existing
    val created = NSUUID().UUIDString
    defaults.setObject(created, forKey = key)
    return created
}
