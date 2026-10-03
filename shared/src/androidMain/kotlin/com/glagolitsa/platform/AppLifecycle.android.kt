// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import android.app.Activity
import android.app.Application
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

actual object AppLifecycle {
    private var application: Application? = null
    private var activity: Activity? = null
    private val foreground = AtomicBoolean(false)

    /** Call from Application.onCreate so FCM can notify with no Activity. */
    fun initApplication(app: Application) {
        application = app
    }

    fun init(host: Activity) {
        activity = host
        if (application == null) {
            application = host.application
        }
    }

    fun setForeground(value: Boolean) {
        foreground.set(value)
    }

    fun applicationContextOrNull(): Context? =
        application?.applicationContext ?: activity?.applicationContext

    fun activityOrNull(): Activity? = activity

    actual fun restart() {
        activity?.recreate()
    }

    actual fun isInForeground(): Boolean = foreground.get()
}