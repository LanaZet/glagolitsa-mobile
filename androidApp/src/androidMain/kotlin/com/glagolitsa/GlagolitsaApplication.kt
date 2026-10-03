// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa

import android.app.Application
import com.glagolitsa.platform.AppLifecycle
import com.glagolitsa.push.LocalMessageNotifier

class GlagolitsaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLifecycle.initApplication(this)
        LocalMessageNotifier.ensureChannels()
    }
}