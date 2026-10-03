// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.security

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.Timer
import java.util.TimerTask

actual object SecureClipboard {
    actual fun copyWithAutoClear(label: String, text: String, clearAfterMs: Long) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
        Timer("secure-clipboard", true).schedule(
            object : TimerTask() {
                override fun run() {
                    runCatching {
                        val current = clipboard.getContents(null)?.getTransferData(DataFlavor.stringFlavor)
                        if (current == text) {
                            clipboard.setContents(StringSelection(""), null)
                        }
                    }
                }
            },
            clearAfterMs,
        )
    }
}