// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import android.content.Context
import android.provider.Settings

actual fun installationSeed(): String {
    val context = AppLifecycle.applicationContextOrNull()
    return installationSeed(context)
}

fun installationSeed(context: Context?): String {
    if (context == null) return "android-unknown"
    val androidId = runCatching {
        Settings.Secure.getString(
            context.applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID,
        )
    }.getOrNull()
    return androidId?.takeIf { it.isNotBlank() } ?: "android-unknown"
}
