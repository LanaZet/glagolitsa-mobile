// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import com.glagolitsa.model.ClientPlatform
import java.awt.Desktop
import java.net.URI

actual object AppRuntimeInfo {
    actual val versionName: String = System.getProperty("glagolitsa.versionName", "0.1.0")
    actual val versionCode: Int =
        System.getProperty("glagolitsa.versionCode", "1").toIntOrNull() ?: 1
    actual val platform: ClientPlatform = ClientPlatform.DESKTOP
    actual val devShortcutsEnabled: Boolean = true

    actual fun openUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        }
    }

    actual suspend fun downloadUpdate(
        url: String,
        onProgress: (bytesRead: Long, contentLength: Long?) -> Unit,
    ) {
        // Desktop has no in-app APK installer; open the URL (browser / download manager).
        onProgress(0L, null)
        openUrl(url)
        onProgress(1L, 1L)
    }

    actual fun hasPendingUpdateApk(): Boolean = false

    actual fun pendingUpdateSourceUrl(): String? = null

    actual fun clearPendingUpdateApk() = Unit

    actual fun launchPendingUpdateInstaller() {
        // no-op on desktop
    }

}
