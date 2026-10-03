// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

/**
 * Mattermost-style client update policy (server is source of truth).
 *
 * - [PlatformUpdatePolicy.minVersion] / [PlatformUpdatePolicy.minBuild] → HARD
 * - [PlatformUpdatePolicy.latestVersion] / [PlatformUpdatePolicy.latestBuild] → SOFT when below
 * - [killSwitch] → HARD regardless of version
 */
@Serializable
data class ClientUpdatePolicy(
    val android: PlatformUpdatePolicy = PlatformUpdatePolicy(),
    val ios: PlatformUpdatePolicy = PlatformUpdatePolicy(),
    val desktop: PlatformUpdatePolicy = PlatformUpdatePolicy(),
    val kill_switch: Boolean = false,
    val soft_title: String = "Доступна новая версия",
    val soft_body: String = "Обновите приложение, чтобы получить исправления и новые возможности.",
    val hard_title: String = "Требуется обновление",
    val hard_body: String = "Эта версия больше не поддерживается. Установите новую из магазина или по ссылке.",
    val features: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class PlatformUpdatePolicy(
    /** Minimum allowed marketing version (semver-ish). Empty = no min gate. */
    val min_version: String = "",
    /** Latest recommended marketing version. Empty = no soft recommend. */
    val latest_version: String = "",
    /** Minimum allowed build/versionCode. 0 = ignore build for min. */
    val min_build: Int = 0,
    /** Latest recommended build. 0 = ignore build for soft. */
    val latest_build: Int = 0,
    /** Installable package URL (Play / App Store / APK / desktop download). */
    val store_url: String = "",
    /** Optional direct installable artifact (APK / DMG) when store is unavailable. */
    val download_url: String = "",
)

enum class ClientPlatform {
    ANDROID,
    IOS,
    DESKTOP,
    ;

    companion object {
        fun fromId(raw: String?): ClientPlatform =
            when (raw?.trim()?.lowercase()) {
                "android" -> ANDROID
                "ios", "iphone", "ipad" -> IOS
                "desktop", "jvm", "macos", "windows", "linux" -> DESKTOP
                else -> DESKTOP
            }
    }
}

enum class UpdateDecisionKind {
    NONE,
    SOFT,
    HARD,
}

data class ClientAppIdentity(
    val versionName: String,
    val versionCode: Int,
    val platform: ClientPlatform,
)

data class UpdateDecision(
    val kind: UpdateDecisionKind,
    val title: String,
    val body: String,
    /** Preferred install link (direct APK first, then store). */
    val installUrl: String?,
    val storeUrl: String?,
    val downloadUrl: String?,
    val minVersion: String?,
    val latestVersion: String?,
    val features: Map<String, Boolean> = emptyMap(),
) {
    val isBlocking: Boolean get() = kind == UpdateDecisionKind.HARD
    val shouldPrompt: Boolean get() = kind == UpdateDecisionKind.SOFT || kind == UpdateDecisionKind.HARD
    val canInstall: Boolean get() = !installUrl.isNullOrBlank()
}

/** Badge on Settings → Обновления (Mattermost-style soft/hard cue). */
data class ClientUpdateSettingsBadge(
    val label: String?,
    val highlight: Boolean,
)

object ClientUpdateEvaluator {

    fun platformPolicy(policy: ClientUpdatePolicy, platform: ClientPlatform): PlatformUpdatePolicy =
        when (platform) {
            ClientPlatform.ANDROID -> policy.android
            ClientPlatform.IOS -> policy.ios
            ClientPlatform.DESKTOP -> policy.desktop
        }

    fun evaluate(local: ClientAppIdentity, policy: ClientUpdatePolicy): UpdateDecision {
        val platform = platformPolicy(policy, local.platform)
        val installUrl = preferredInstallUrl(platform)
        val storeUrl = platform.store_url.takeIf { it.isNotBlank() }
        val downloadUrl = platform.download_url.takeIf { it.isNotBlank() }

        if (policy.kill_switch) {
            return UpdateDecision(
                kind = UpdateDecisionKind.HARD,
                title = policy.hard_title,
                body = policy.hard_body,
                installUrl = installUrl,
                storeUrl = storeUrl,
                downloadUrl = downloadUrl,
                minVersion = platform.min_version.ifBlank { null },
                latestVersion = platform.latest_version.ifBlank { null },
                features = policy.features,
            )
        }

        val belowMin = isBelowMin(local, platform)
        if (belowMin) {
            return UpdateDecision(
                kind = UpdateDecisionKind.HARD,
                title = policy.hard_title,
                body = policy.hard_body,
                installUrl = installUrl,
                storeUrl = storeUrl,
                downloadUrl = downloadUrl,
                minVersion = platform.min_version.ifBlank { null },
                latestVersion = platform.latest_version.ifBlank { null },
                features = policy.features,
            )
        }

        val belowLatest = isBelowLatest(local, platform)
        if (belowLatest) {
            return UpdateDecision(
                kind = UpdateDecisionKind.SOFT,
                title = policy.soft_title,
                body = policy.soft_body,
                installUrl = installUrl,
                storeUrl = storeUrl,
                downloadUrl = downloadUrl,
                minVersion = platform.min_version.ifBlank { null },
                latestVersion = platform.latest_version.ifBlank { null },
                features = policy.features,
            )
        }

        return UpdateDecision(
            kind = UpdateDecisionKind.NONE,
            title = "",
            body = "",
            installUrl = installUrl,
            storeUrl = storeUrl,
            downloadUrl = downloadUrl,
            minVersion = platform.min_version.ifBlank { null },
            latestVersion = platform.latest_version.ifBlank { null },
            features = policy.features,
        )
    }

    fun isFeatureEnabled(policy: ClientUpdatePolicy, key: String, default: Boolean = false): Boolean =
        policy.features[key] ?: default

    /**
     * Settings-row presentation (Mattermost/Element: badge when update available).
     * - HARD → "Нужно" (blocking)
     * - SOFT → "Новое" (recommended)
     * - NONE / null → no badge
     */
    fun settingsBadge(decision: UpdateDecision?): ClientUpdateSettingsBadge {
        if (decision == null || !decision.shouldPrompt) {
            return ClientUpdateSettingsBadge(label = null, highlight = false)
        }
        return when (decision.kind) {
            UpdateDecisionKind.HARD -> ClientUpdateSettingsBadge(label = "Нужно", highlight = true)
            UpdateDecisionKind.SOFT -> ClientUpdateSettingsBadge(label = "Новое", highlight = true)
            UpdateDecisionKind.NONE -> ClientUpdateSettingsBadge(label = null, highlight = false)
        }
    }

    /**
     * Prefer direct download (APK) when present so testers are not sent to an
     * empty Play listing. Fall back to store_url only when there is no download.
     */
    fun preferredInstallUrl(platform: PlatformUpdatePolicy): String? =
        platform.download_url.takeIf { it.isNotBlank() }
            ?: platform.store_url.takeIf { it.isNotBlank() }

    /**
     * Compare marketing versions: "1.2.3" style, missing parts = 0.
     * Returns negative if a < b, 0 if equal, positive if a > b.
     */
    fun compareSemver(a: String, b: String): Int {
        val pa = parseSemver(a)
        val pb = parseSemver(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val av = pa.getOrElse(i) { 0 }
            val bv = pb.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun isBelowMin(local: ClientAppIdentity, platform: PlatformUpdatePolicy): Boolean {
        // versionCode is the monotonic install identity when both sides publish it.
        if (platform.min_build > 0 && local.versionCode > 0) {
            return local.versionCode < platform.min_build
        }
        if (platform.min_version.isNotBlank() && local.versionName.isNotBlank()) {
            return compareSemver(local.versionName, platform.min_version) < 0
        }
        return false
    }

    private fun isBelowLatest(local: ClientAppIdentity, platform: PlatformUpdatePolicy): Boolean {
        // Prefer versionCode over marketing versionName when both are present.
        // Debug/adb installs often bump versionCode while versionName lags the
        // published policy (e.g. local 0.1.3/code=6 vs policy 0.1.4/build=5).
        // Failing on name alone would prompt installing an *older* APK.
        if (platform.latest_build > 0 && local.versionCode > 0) {
            return local.versionCode < platform.latest_build
        }
        if (platform.latest_version.isNotBlank() && local.versionName.isNotBlank()) {
            return compareSemver(local.versionName, platform.latest_version) < 0
        }
        return false
    }

    private fun parseSemver(raw: String): List<Int> {
        val core = raw.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        if (core.isBlank()) return listOf(0)
        return core.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }
}
