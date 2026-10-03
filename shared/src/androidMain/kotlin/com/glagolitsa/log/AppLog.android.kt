// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.log

import android.util.Log
import com.glagolitsa.shared.BuildConfig

actual object AppLog {
    private const val TAG = "Glagolitsa"

    actual fun debug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    actual fun warning(message: String) {
        Log.w(TAG, message)
    }
}
