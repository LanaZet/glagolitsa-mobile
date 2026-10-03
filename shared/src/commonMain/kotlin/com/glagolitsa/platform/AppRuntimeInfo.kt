// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import com.glagolitsa.model.ClientAppIdentity
import com.glagolitsa.model.ClientPlatform

/** Runtime app identity for update policy evaluation (platform expect/actual). */
expect object AppRuntimeInfo {
    val versionName: String
    val versionCode: Int
    val platform: ClientPlatform
    val devShortcutsEnabled: Boolean

    fun openUrl(url: String)

    /**
     * Download an APK update into the app cache. Does **not** open the package
     * installer — call [launchPendingUpdateInstaller] after the user confirms.
     *
     * [onProgress] reports bytes read and optional Content-Length (null if unknown).
     * Non-APK URLs open externally and complete immediately.
     *
     * Throws on download / IO failures so UI can show a message.
     */
    suspend fun downloadUpdate(
        url: String,
        onProgress: (bytesRead: Long, contentLength: Long?) -> Unit = { _, _ -> },
    )

    /** True if a valid cached APK from [downloadUpdate] is ready to install. */
    fun hasPendingUpdateApk(): Boolean

    /**
     * Source URL of the cached APK (sidecar written after a successful download),
     * or null if missing / unknown.
     */
    fun pendingUpdateSourceUrl(): String?

    /**
     * Delete a cached update APK (and URL sidecar) when policy no longer needs it
     * or the user discards the prompt permanently.
     */
    fun clearPendingUpdateApk()

    /**
     * Open the system package installer for the cached APK.
     * Throws if the file is missing/corrupt or install permission is denied.
     */
    fun launchPendingUpdateInstaller()

}

fun AppRuntimeInfo.toClientAppIdentity(): ClientAppIdentity =
    ClientAppIdentity(
        versionName = versionName,
        versionCode = versionCode,
        platform = platform,
    )
