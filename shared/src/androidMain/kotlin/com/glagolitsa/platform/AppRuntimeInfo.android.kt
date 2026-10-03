// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.glagolitsa.model.ClientPlatform
import com.glagolitsa.model.ClientUpdateInstallLogic
import com.glagolitsa.shared.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

actual object AppRuntimeInfo {
    /**
     * Prefer the *installed* package identity over shared BuildConfig defaults.
     * Historically androidApp (versionCode/Name) and shared BuildConfig could
     * diverge — update policy then prompted for an older published APK.
     */
    actual val versionName: String
        get() = installedVersionName()
            ?: BuildConfig.APP_VERSION_NAME.takeIf { it.isNotBlank() }
            ?: "0.1.0"

    actual val versionCode: Int
        get() = installedVersionCode()
            ?: BuildConfig.APP_VERSION_CODE.takeIf { it > 0 }
            ?: 1

    actual val platform: ClientPlatform = ClientPlatform.ANDROID
    actual val devShortcutsEnabled: Boolean = BuildConfig.DEV_SHORTCUTS_ENABLED

    actual fun openUrl(url: String) {
        val host = AppLifecycle.activityOrNull()
            ?: AppLifecycle.applicationContextOrNull()
            ?: return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (host !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            host.startActivity(intent)
        }
    }

    actual suspend fun downloadUpdate(
        url: String,
        onProgress: (bytesRead: Long, contentLength: Long?) -> Unit,
    ) {
        when (ClientUpdateInstallLogic.routeFor(url)) {
            ClientUpdateInstallLogic.Route.NOOP -> return
            ClientUpdateInstallLogic.Route.OPEN_URL -> {
                openUrl(ClientUpdateInstallLogic.normalizeUrl(url) ?: return)
                return
            }
            ClientUpdateInstallLogic.Route.DOWNLOAD_APK -> {
                val normalized = ClientUpdateInstallLogic.normalizeUrl(url) ?: return
                val context = AppLifecycle.applicationContextOrNull()
                    ?: error(ClientUpdateInstallLogic.ERROR_NO_CONTEXT)
                withContext(Dispatchers.IO) {
                    downloadApk(context.cacheDir, normalized, onProgress)
                }
            }
        }
    }

    actual fun hasPendingUpdateApk(): Boolean {
        val context = AppLifecycle.applicationContextOrNull() ?: return false
        val file = pendingApkFile(context.cacheDir)
        return file.isFile && ClientUpdateInstallLogic.isDownloadedApkValid(file.length())
    }

    actual fun pendingUpdateSourceUrl(): String? {
        val context = AppLifecycle.applicationContextOrNull() ?: return null
        if (!hasPendingUpdateApk()) return null
        val meta = pendingApkUrlFile(context.cacheDir)
        if (!meta.isFile) return null
        return runCatching {
            meta.readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    actual fun clearPendingUpdateApk() {
        val context = AppLifecycle.applicationContextOrNull() ?: return
        runCatching { pendingApkFile(context.cacheDir).delete() }
        runCatching { pendingApkUrlFile(context.cacheDir).delete() }
        runCatching {
            File(
                File(context.cacheDir, ClientUpdateInstallLogic.UPDATES_CACHE_DIR),
                "${ClientUpdateInstallLogic.APK_CACHE_FILE_NAME}.part",
            ).delete()
        }
    }

    actual fun launchPendingUpdateInstaller() {
        val context = AppLifecycle.applicationContextOrNull()
            ?: error(ClientUpdateInstallLogic.ERROR_NO_CONTEXT)
        ensureInstallPermission(context)
        val apkFile = pendingApkFile(context.cacheDir)
        if (!apkFile.isFile || !ClientUpdateInstallLogic.isDownloadedApkValid(apkFile.length())) {
            error(ClientUpdateInstallLogic.ERROR_NO_PENDING_APK)
        }
        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, ClientUpdateInstallLogic.APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun installedVersionName(): String? {
        val context = AppLifecycle.applicationContextOrNull() ?: return null
        return runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun installedVersionCode(): Int? {
        val context = AppLifecycle.applicationContextOrNull() ?: return null
        return runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
            code.takeIf { it > 0 }
        }.getOrNull()
    }

    private fun pendingApkFile(cacheDir: File): File =
        File(File(cacheDir, ClientUpdateInstallLogic.UPDATES_CACHE_DIR), ClientUpdateInstallLogic.APK_CACHE_FILE_NAME)

    private fun pendingApkUrlFile(cacheDir: File): File =
        File(
            File(cacheDir, ClientUpdateInstallLogic.UPDATES_CACHE_DIR),
            ClientUpdateInstallLogic.APK_CACHE_URL_FILE_NAME,
        )

    private fun ensureInstallPermission(context: android.content.Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (context.packageManager.canRequestPackageInstalls()) return

        val settings = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(settings)
        error(ClientUpdateInstallLogic.ERROR_PERMISSION)
    }

    private suspend fun downloadApk(
        cacheDir: File,
        url: String,
        onProgress: (bytesRead: Long, contentLength: Long?) -> Unit,
    ): File {
        val dir = File(cacheDir, ClientUpdateInstallLogic.UPDATES_CACHE_DIR).apply { mkdirs() }
        val out = File(dir, ClientUpdateInstallLogic.APK_CACHE_FILE_NAME)
        val urlMeta = File(dir, ClientUpdateInstallLogic.APK_CACHE_URL_FILE_NAME)
        val partial = File(dir, "${ClientUpdateInstallLogic.APK_CACHE_FILE_NAME}.part")
        if (partial.exists()) partial.delete()
        if (out.exists()) out.delete()
        if (urlMeta.exists()) urlMeta.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 120_000
            requestMethod = "GET"
            setRequestProperty("Accept", "${ClientUpdateInstallLogic.APK_MIME_TYPE},*/*")
        }
        try {
            val code = connection.responseCode
            if (!ClientUpdateInstallLogic.isHttpSuccess(code)) {
                error(ClientUpdateInstallLogic.errorMessageHttp(code))
            }
            val contentLength = connection.contentLengthLong.takeIf { it > 0L }
            var bytesRead = 0L
            onProgress(0L, contentLength)
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        bytesRead += n
                        onProgress(bytesRead, contentLength)
                    }
                    output.flush()
                }
            }
            if (!partial.renameTo(out)) {
                partial.copyTo(out, overwrite = true)
                partial.delete()
            }
            urlMeta.writeText(url)
        } catch (t: Throwable) {
            partial.delete()
            out.delete()
            urlMeta.delete()
            throw t
        } finally {
            connection.disconnect()
        }

        if (!out.isFile || !ClientUpdateInstallLogic.isDownloadedApkValid(out.length())) {
            out.delete()
            urlMeta.delete()
            error(ClientUpdateInstallLogic.ERROR_CORRUPT_APK)
        }
        return out
    }
}
