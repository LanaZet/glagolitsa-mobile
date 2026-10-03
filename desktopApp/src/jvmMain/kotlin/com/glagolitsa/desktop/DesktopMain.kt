// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.security.LocalAuthenticator
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.ui.MessengerApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        alwaysOnTop = true,
        title = "Glagolitsa UI (Hot Reload)",
    ) {
        MessengerApp(
            driverFactory = DatabaseDriverFactory(),
            cryptoEngineFactory = CryptoEngineFactory(),
            secureSessionStore = SecureSessionStore(),
            localAuthenticator = LocalAuthenticator(),
        )
    }
}
