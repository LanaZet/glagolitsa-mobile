// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Pure decision helpers for client update install / APK sideload.
 *
 * UI and Android platform code call these so behavior stays unit-testable
 * without a device, network, or FileProvider.
 *
 * Flow for APK (primary path while Play is empty):
 *   1) user taps download → in-app download with progress
 *   2) when ready → ask user to install
 *   3) user confirms → open system package installer
 */
object ClientUpdateInstallLogic {
    const val MIN_APK_BYTES: Long = 1_024L
    const val UPDATES_CACHE_DIR: String = "updates"
    const val APK_CACHE_FILE_NAME: String = "glagolitsa-update.apk"
    /** Sidecar next to [APK_CACHE_FILE_NAME] with the source download URL. */
    const val APK_CACHE_URL_FILE_NAME: String = "glagolitsa-update.apk.url"
    const val APK_MIME_TYPE: String = "application/vnd.android.package-archive"

    const val LABEL_DOWNLOAD: String = "Скачать обновление"
    const val LABEL_DOWNLOADING: String = "Скачиваем…"
    const val LABEL_DOWNLOADING_PROGRESS: String = "Скачиваем… %d%%"
    const val LABEL_READY_TITLE: String = "Обновление скачано"
    const val LABEL_READY_BODY: String = "Файл готов. Установить сейчас?"
    const val LABEL_INSTALL: String = "Установить"
    const val LABEL_LATER: String = "Позже"
    const val LABEL_STORE: String = "Скачать из магазина"
    const val LABEL_OPEN_STORE: String = "Открыть магазин"
    const val LABEL_UPDATE_SOFT: String = "Обновить"

    const val ERROR_PERMISSION: String =
        "Разрешите установку из этого источника и нажмите «Установить» ещё раз"
    const val ERROR_NO_CONTEXT: String = "Application context unavailable"
    const val ERROR_CORRUPT_APK: String = "Скачанный APK пустой или повреждён"
    const val ERROR_NO_PENDING_APK: String = "APK ещё не скачан"
    const val ERROR_GENERIC: String = "Не удалось скачать или установить обновление"

    /** How update URLs should be handled. */
    enum class Route {
        /** Blank / missing URL — do nothing. */
        NOOP,
        /** Non-APK (Play/App Store/https page) — open externally. */
        OPEN_URL,
        /** `.apk` link — download into cache; install only after user confirms. */
        DOWNLOAD_APK,
    }

    fun normalizeUrl(url: String?): String? =
        url?.trim()?.takeIf { it.isNotEmpty() }

    fun looksLikeApkUrl(url: String): Boolean {
        val path = url
            .trim()
            .substringBefore('#')
            .substringBefore('?')
            .lowercase()
        return path.endsWith(".apk")
    }

    fun routeFor(url: String?): Route {
        val normalized = normalizeUrl(url) ?: return Route.NOOP
        return if (looksLikeApkUrl(normalized)) Route.DOWNLOAD_APK else Route.OPEN_URL
    }

    /**
     * Primary CTA target: always prefer direct APK when present so we never
     * send testers to an empty Play listing.
     */
    fun primaryInstallUrl(decision: UpdateDecision): String? {
        val download = normalizeUrl(decision.downloadUrl)
        if (download != null) return download
        return normalizeUrl(decision.installUrl)
    }

    /** True when policy has a real `.apk` download link. */
    fun hasDirectApk(decision: UpdateDecision): Boolean {
        val download = normalizeUrl(decision.downloadUrl) ?: return false
        return looksLikeApkUrl(download)
    }

    /**
     * Store secondary button: only when there is a store URL and **no** APK.
     * While we ship sideload-only builds, never show Play next to a direct APK.
     */
    fun secondaryStoreButtonVisible(decision: UpdateDecision): Boolean {
        if (hasDirectApk(decision)) return false
        return normalizeUrl(decision.storeUrl) != null
    }

    fun primarySettingsButtonLabel(
        downloading: Boolean,
        decision: UpdateDecision,
        progressPercent: Int? = null,
        readyToInstall: Boolean = false,
    ): String {
        if (readyToInstall) return LABEL_INSTALL
        if (downloading) {
            return if (progressPercent != null) {
                downloadingProgressLabel(progressPercent)
            } else {
                LABEL_DOWNLOADING
            }
        }
        return if (hasDirectApk(decision) || normalizeUrl(decision.storeUrl) == null) {
            LABEL_DOWNLOAD
        } else {
            LABEL_STORE
        }
    }

    fun softInstallButtonLabel(downloading: Boolean, progressPercent: Int? = null): String =
        when {
            downloading && progressPercent != null ->
                downloadingProgressLabel(progressPercent)
            downloading -> LABEL_DOWNLOADING
            else -> LABEL_UPDATE_SOFT
        }

    fun hardPrimaryButtonLabel(
        downloading: Boolean,
        decision: UpdateDecision,
        progressPercent: Int? = null,
    ): String {
        if (downloading) {
            return if (progressPercent != null) {
                downloadingProgressLabel(progressPercent)
            } else {
                LABEL_DOWNLOADING
            }
        }
        return when {
            hasDirectApk(decision) -> LABEL_DOWNLOAD
            normalizeUrl(decision.storeUrl) != null -> LABEL_OPEN_STORE
            else -> LABEL_DOWNLOAD
        }
    }

    fun isHttpSuccess(code: Int): Boolean = code in 200..299

    fun isDownloadedApkValid(sizeBytes: Long): Boolean =
        sizeBytes >= MIN_APK_BYTES

    /**
     * @return 0f..1f when [contentLength] is known, otherwise null (indeterminate).
     */
    fun progressFraction(bytesRead: Long, contentLength: Long?): Float? {
        if (contentLength == null || contentLength <= 0L) return null
        if (bytesRead <= 0L) return 0f
        return (bytesRead.toDouble() / contentLength.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

    fun progressPercent(bytesRead: Long, contentLength: Long?): Int? {
        val fraction = progressFraction(bytesRead, contentLength) ?: return null
        return (fraction * 100f).toInt().coerceIn(0, 100)
    }

    fun errorMessageHttp(code: Int): String =
        "Не удалось скачать APK (HTTP $code)"

    fun errorMessageFromThrowable(error: Throwable?): String =
        error?.message?.takeIf { it.isNotBlank() } ?: ERROR_GENERIC

    /**
     * Prefer install only when policy still says this client is behind.
     * A leftover cache must not surface "Установить" on an already-current build.
     */
    fun shouldOfferCachedInstall(decision: UpdateDecision?, hasPendingApk: Boolean): Boolean =
        hasPendingApk && canShowInstallActions(decision)

    /** Whether Settings → Обновления should show install/download actions. */
    fun canShowInstallActions(decision: UpdateDecision?): Boolean {
        if (decision == null || !decision.shouldPrompt) return false
        return primaryInstallUrl(decision) != null
    }

    private fun downloadingProgressLabel(progressPercent: Int): String =
        LABEL_DOWNLOADING_PROGRESS.replace("%d%%", "${progressPercent.coerceIn(0, 100)}%")
}
