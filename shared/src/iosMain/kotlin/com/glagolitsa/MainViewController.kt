// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa

import androidx.compose.ui.window.ComposeUIViewController
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.platform.AppLifecycle
import com.glagolitsa.platform.NetworkPathMonitor
import com.glagolitsa.security.LocalAuthenticator
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.ui.MessengerApp
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
fun MainViewController(): UIViewController {
    setUnhandledExceptionHook { throwable ->
        // println only — NSLog + Kotlin String can abort (see AppLog.ios).
        println(
            "UNCAUGHT ${throwable::class.simpleName}: ${throwable.message}\n" +
                throwable.stackTraceToString(),
        )
    }

    AppLifecycle.init()
    NetworkPathMonitor.init()

    // Factories outside composition (same pattern as Android MainActivity).
    val driverFactory = DatabaseDriverFactory()
    val cryptoFactory = CryptoEngineFactory()
    val sessionStore = SecureSessionStore()
    val localAuth = LocalAuthenticator()

    return ComposeUIViewController {
        MessengerApp(
            driverFactory = driverFactory,
            cryptoEngineFactory = cryptoFactory,
            secureSessionStore = sessionStore,
            localAuthenticator = localAuth,
        )
    }
}
