// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.glagolitsa.ui.preview.DesignCatalog

/** Desktop-песочница для дизайна: hot reload без сервера и эмулятора. */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(width = 960.dp, height = 980.dp),
        title = "Glagolitsa Design (Hot Reload)",
    ) {
        DesignCatalog()
    }
}