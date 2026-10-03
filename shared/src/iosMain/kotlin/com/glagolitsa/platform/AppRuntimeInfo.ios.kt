// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import com.glagolitsa.model.ClientPlatform
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual object AppRuntimeInfo {
    actual val versionName: String =
        (NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String) ?: "0.1.0"

    actual val versionCode: Int =
        ((NSBundle.mainBundle.infoDictionary?.get("CFBundleVersion") as? String)?.toIntOrNull()) ?: 1

    actual val platform: ClientPlatform = ClientPlatform.IOS

    actual val devShortcutsEnabled: Boolean = false

    actual fun openUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    actual suspend fun downloadUpdate(
        url: String,
        onProgress: (bytesRead: Long, contentLength: Long?) -> Unit,
    ) {
        // iOS updates go through TestFlight / App Store later; open URL for now.
        onProgress(0L, null)
        openUrl(url)
        onProgress(1L, 1L)
    }

    actual fun hasPendingUpdateApk(): Boolean = false

    actual fun pendingUpdateSourceUrl(): String? = null

    actual fun clearPendingUpdateApk() = Unit

    actual fun launchPendingUpdateInstaller() = Unit
}
