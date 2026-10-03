// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.NSObjectProtocol
import kotlin.concurrent.AtomicInt

actual object AppLifecycle {
    private val foreground = AtomicInt(1)
    private var observers: List<NSObjectProtocol> = emptyList()

    fun init() {
        if (observers.isNotEmpty()) return
        val center = NSNotificationCenter.defaultCenter
        val o1 = center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, null) { _ ->
            foreground.value = 1
        }
        val o2 = center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, null) { _ ->
            foreground.value = 1
        }
        val o3 = center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, null) { _ ->
            foreground.value = 0
        }
        observers = listOf(o1, o2, o3)
    }

    actual fun restart() {
        // iOS apps are not programmatically restarted; user relaunches.
    }

    actual fun isInForeground(): Boolean = foreground.value == 1
}
