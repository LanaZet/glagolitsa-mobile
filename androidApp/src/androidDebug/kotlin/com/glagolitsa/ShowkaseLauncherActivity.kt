// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.airbnb.android.showkase.models.Showkase
import com.glagolitsa.showkase.getBrowserIntent

/** Debug: отдельная иконка «Glagolitsa UI» на лаунчере → каталог Showkase. */
class ShowkaseLauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Showkase.getBrowserIntent(this))
        finish()
    }
}