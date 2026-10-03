// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exhaustive matrix for APK sideload / update install routing and UI labels.
 * Keeps Android FileProvider / network out of unit tests.
 */
class ClientUpdateInstallLogicTest {

    // ── normalizeUrl ──────────────────────────────────────────────────────

    @Test
    fun normalizeUrl_nullAndBlank() {
        assertNull(ClientUpdateInstallLogic.normalizeUrl(null))
        assertNull(ClientUpdateInstallLogic.normalizeUrl(""))
        assertNull(ClientUpdateInstallLogic.normalizeUrl("   "))
        assertNull(ClientUpdateInstallLogic.normalizeUrl("\t\n"))
    }

    @Test
    fun normalizeUrl_trims() {
        assertEquals(
            "https://api.glagolit.me/downloads/a.apk",
            ClientUpdateInstallLogic.normalizeUrl("  https://api.glagolit.me/downloads/a.apk  "),
        )
    }

    // ── looksLikeApkUrl ───────────────────────────────────────────────────

    @Test
    fun looksLikeApkUrl_plainApk() {
        assertTrue(ClientUpdateInstallLogic.looksLikeApkUrl("https://cdn.example/app.apk"))
        assertTrue(ClientUpdateInstallLogic.looksLikeApkUrl("https://api.glagolit.me/downloads/glagolitsa-0.1.3-4.apk"))
        assertTrue(ClientUpdateInstallLogic.looksLikeApkUrl("file:///tmp/x.apk"))
        assertTrue(ClientUpdateInstallLogic.looksLikeApkUrl("app.apk"))
    }

    @Test
    fun looksLikeApkUrl_caseInsensitive() {
        assertTrue(ClientUpdateInstallLogic.looksLikeApkUrl("https://cdn.example/App.APK"))
        assertTrue(ClientUpdateInstallLogic.looksLikeApkUrl("https://cdn.example/App.Apk"))
    }

    @Test
    fun looksLikeApkUrl_ignoresQueryAndFragment() {
        assertTrue(
            ClientUpdateInstallLogic.looksLikeApkUrl(
                "https://cdn.example/app.apk?token=abc&x=1",
            ),
        )
        assertTrue(
            ClientUpdateInstallLogic.looksLikeApkUrl(
                "https://cdn.example/app.apk#section",
            ),
        )
        assertTrue(
            ClientUpdateInstallLogic.looksLikeApkUrl(
                "https://cdn.example/app.apk?v=1#top",
            ),
        )
    }

    @Test
    fun looksLikeApkUrl_rejectsNonApk() {
        assertFalse(ClientUpdateInstallLogic.looksLikeApkUrl("https://play.google.com/store/apps/details?id=com.glagolitsa.mobile"))
        assertFalse(ClientUpdateInstallLogic.looksLikeApkUrl("https://cdn.example/app.ipa"))
        assertFalse(ClientUpdateInstallLogic.looksLikeApkUrl("https://cdn.example/app.apk.exe"))
        assertFalse(ClientUpdateInstallLogic.looksLikeApkUrl("https://cdn.example/apk"))
        assertFalse(ClientUpdateInstallLogic.looksLikeApkUrl("https://cdn.example/app.apk/manifest"))
        assertFalse(ClientUpdateInstallLogic.looksLikeApkUrl(""))
        assertFalse(ClientUpdateInstallLogic.looksLikeApkUrl("   "))
    }

    @Test
    fun looksLikeApkUrl_trimsBeforeCheck() {
        assertTrue(ClientUpdateInstallLogic.looksLikeApkUrl("  https://cdn.example/app.apk  "))
    }

    // ── routeFor ──────────────────────────────────────────────────────────

    @Test
    fun routeFor_blankIsNoop() {
        assertEquals(ClientUpdateInstallLogic.Route.NOOP, ClientUpdateInstallLogic.routeFor(null))
        assertEquals(ClientUpdateInstallLogic.Route.NOOP, ClientUpdateInstallLogic.routeFor(""))
        assertEquals(ClientUpdateInstallLogic.Route.NOOP, ClientUpdateInstallLogic.routeFor("  "))
    }

    @Test
    fun routeFor_storeOrHttpIsOpenUrl() {
        assertEquals(
            ClientUpdateInstallLogic.Route.OPEN_URL,
            ClientUpdateInstallLogic.routeFor("https://play.google.com/store/apps/details?id=x"),
        )
        assertEquals(
            ClientUpdateInstallLogic.Route.OPEN_URL,
            ClientUpdateInstallLogic.routeFor("https://apps.apple.com/app/id123"),
        )
        assertEquals(
            ClientUpdateInstallLogic.Route.OPEN_URL,
            ClientUpdateInstallLogic.routeFor("https://example.com/release-notes"),
        )
    }

    @Test
    fun routeFor_apkIsDownloadApk() {
        assertEquals(
            ClientUpdateInstallLogic.Route.DOWNLOAD_APK,
            ClientUpdateInstallLogic.routeFor("https://api.glagolit.me/downloads/glagolitsa-0.1.3-4.apk"),
        )
        assertEquals(
            ClientUpdateInstallLogic.Route.DOWNLOAD_APK,
            ClientUpdateInstallLogic.routeFor("  https://cdn/x.apk?sig=1  "),
        )
    }

    // ── primaryInstallUrl / hasDirectApk ──────────────────────────────────

    @Test
    fun primaryInstallUrl_prefersDownloadOverInstallUrl() {
        val d = decision(
            installUrl = "https://play.google.com/store/apps/details?id=x",
            storeUrl = "https://play.google.com/store/apps/details?id=x",
            downloadUrl = "https://cdn.example/app.apk",
        )
        assertEquals(
            "https://cdn.example/app.apk",
            ClientUpdateInstallLogic.primaryInstallUrl(d),
        )
    }

    @Test
    fun primaryInstallUrl_fallsBackToInstallUrlWhenNoDownload() {
        val d = decision(
            installUrl = "https://play.google.com/store/apps/details?id=x",
            storeUrl = "https://play.google.com/store/apps/details?id=x",
            downloadUrl = null,
        )
        assertEquals(
            "https://play.google.com/store/apps/details?id=x",
            ClientUpdateInstallLogic.primaryInstallUrl(d),
        )
    }

    @Test
    fun primaryInstallUrl_usesDownloadEvenIfInstallUrlNull() {
        val d = decision(
            installUrl = null,
            storeUrl = null,
            downloadUrl = "https://cdn.example/app.apk",
        )
        assertEquals(
            "https://cdn.example/app.apk",
            ClientUpdateInstallLogic.primaryInstallUrl(d),
        )
    }

    @Test
    fun primaryInstallUrl_blankDownloadFallsBack() {
        val d = decision(
            installUrl = "https://store",
            storeUrl = "https://store",
            downloadUrl = "   ",
        )
        assertEquals("https://store", ClientUpdateInstallLogic.primaryInstallUrl(d))
    }

    @Test
    fun primaryInstallUrl_allBlankIsNull() {
        val d = decision(installUrl = null, storeUrl = null, downloadUrl = null)
        assertNull(ClientUpdateInstallLogic.primaryInstallUrl(d))
        val blank = decision(installUrl = " ", storeUrl = "", downloadUrl = "\t")
        assertNull(ClientUpdateInstallLogic.primaryInstallUrl(blank))
    }

    @Test
    fun hasDirectApk_trueOnlyForApkUrls() {
        assertTrue(
            ClientUpdateInstallLogic.hasDirectApk(
                decision(downloadUrl = "https://cdn/app.apk"),
            ),
        )
        assertFalse(
            ClientUpdateInstallLogic.hasDirectApk(
                decision(downloadUrl = "https://cdn/app.zip"),
            ),
        )
        assertFalse(
            ClientUpdateInstallLogic.hasDirectApk(
                decision(downloadUrl = null),
            ),
        )
    }

    @Test
    fun secondaryStoreButtonVisible_hiddenWhenApkPresent() {
        // Sideload-only phase: never show Play next to a direct APK.
        assertFalse(
            ClientUpdateInstallLogic.secondaryStoreButtonVisible(
                decision(
                    installUrl = "https://store",
                    storeUrl = "https://store",
                    downloadUrl = "https://cdn/a.apk",
                ),
            ),
        )
        assertFalse(
            ClientUpdateInstallLogic.secondaryStoreButtonVisible(
                decision(
                    installUrl = "https://cdn/a.apk",
                    storeUrl = null,
                    downloadUrl = "https://cdn/a.apk",
                ),
            ),
        )
        assertTrue(
            ClientUpdateInstallLogic.secondaryStoreButtonVisible(
                decision(
                    installUrl = "https://store",
                    storeUrl = "https://store",
                    downloadUrl = null,
                ),
            ),
        )
    }

    // ── button labels ─────────────────────────────────────────────────────

    @Test
    fun primarySettingsButtonLabel_matrix() {
        val storeOnly = decision(
            installUrl = "https://store",
            storeUrl = "https://store",
            downloadUrl = null,
        )
        val apkOnly = decision(
            installUrl = "https://cdn/a.apk",
            storeUrl = null,
            downloadUrl = "https://cdn/a.apk",
        )
        val both = decision(
            installUrl = "https://cdn/a.apk",
            storeUrl = "https://store",
            downloadUrl = "https://cdn/a.apk",
        )

        assertEquals(
            ClientUpdateInstallLogic.LABEL_STORE,
            ClientUpdateInstallLogic.primarySettingsButtonLabel(downloading = false, decision = storeOnly),
        )
        assertEquals(
            ClientUpdateInstallLogic.LABEL_DOWNLOAD,
            ClientUpdateInstallLogic.primarySettingsButtonLabel(downloading = false, decision = apkOnly),
        )
        assertEquals(
            ClientUpdateInstallLogic.LABEL_DOWNLOAD,
            ClientUpdateInstallLogic.primarySettingsButtonLabel(downloading = false, decision = both),
        )
        assertEquals(
            ClientUpdateInstallLogic.LABEL_DOWNLOADING,
            ClientUpdateInstallLogic.primarySettingsButtonLabel(downloading = true, decision = both),
        )
        assertEquals(
            "Скачиваем… 42%",
            ClientUpdateInstallLogic.primarySettingsButtonLabel(
                downloading = true,
                decision = both,
                progressPercent = 42,
            ),
        )
    }

    @Test
    fun softInstallButtonLabel_idleAndLoading() {
        assertEquals(
            ClientUpdateInstallLogic.LABEL_UPDATE_SOFT,
            ClientUpdateInstallLogic.softInstallButtonLabel(downloading = false),
        )
        assertEquals(
            ClientUpdateInstallLogic.LABEL_DOWNLOADING,
            ClientUpdateInstallLogic.softInstallButtonLabel(downloading = true),
        )
        assertEquals(
            "Скачиваем… 10%",
            ClientUpdateInstallLogic.softInstallButtonLabel(downloading = true, progressPercent = 10),
        )
    }

    @Test
    fun hardPrimaryButtonLabel_prefersApkDownload() {
        val storeOnly = decision(
            installUrl = "https://store",
            storeUrl = "https://store",
            downloadUrl = null,
        )
        val apkOnly = decision(
            installUrl = "https://cdn/a.apk",
            storeUrl = null,
            downloadUrl = "https://cdn/a.apk",
        )
        val both = decision(
            installUrl = "https://cdn/a.apk",
            storeUrl = "https://store",
            downloadUrl = "https://cdn/a.apk",
        )

        assertEquals(
            ClientUpdateInstallLogic.LABEL_OPEN_STORE,
            ClientUpdateInstallLogic.hardPrimaryButtonLabel(downloading = false, decision = storeOnly),
        )
        assertEquals(
            ClientUpdateInstallLogic.LABEL_DOWNLOAD,
            ClientUpdateInstallLogic.hardPrimaryButtonLabel(downloading = false, decision = apkOnly),
        )
        assertEquals(
            ClientUpdateInstallLogic.LABEL_DOWNLOAD,
            ClientUpdateInstallLogic.hardPrimaryButtonLabel(downloading = false, decision = both),
        )
        assertEquals(
            ClientUpdateInstallLogic.LABEL_DOWNLOADING,
            ClientUpdateInstallLogic.hardPrimaryButtonLabel(downloading = true, decision = both),
        )
    }

    // ── canShowInstallActions / primaryInstallUrl ────────────────────────

    @Test
    fun canShowInstallActions_requiresPromptAndPrimaryUrl() {
        assertFalse(ClientUpdateInstallLogic.canShowInstallActions(null))
        assertFalse(
            ClientUpdateInstallLogic.canShowInstallActions(
                decision(kind = UpdateDecisionKind.NONE, installUrl = "https://x"),
            ),
        )
        assertFalse(
            ClientUpdateInstallLogic.canShowInstallActions(
                decision(kind = UpdateDecisionKind.SOFT, installUrl = null, downloadUrl = null),
            ),
        )
        assertTrue(
            ClientUpdateInstallLogic.canShowInstallActions(
                decision(kind = UpdateDecisionKind.SOFT, downloadUrl = "https://cdn/a.apk"),
            ),
        )
        assertTrue(
            ClientUpdateInstallLogic.canShowInstallActions(
                decision(kind = UpdateDecisionKind.HARD, installUrl = "https://store"),
            ),
        )
    }

    @Test
    fun primaryInstallUrl_prefersApk() {
        val d = decision(
            installUrl = "https://store",
            storeUrl = "https://store",
            downloadUrl = "https://cdn/a.apk",
        )
        assertEquals("https://cdn/a.apk", ClientUpdateInstallLogic.primaryInstallUrl(d))
        assertEquals(
            "https://cdn/a.apk",
            ClientUpdateInstallLogic.primaryInstallUrl(
                decision(installUrl = null, storeUrl = null, downloadUrl = "https://cdn/a.apk"),
            ),
        )
        assertNull(
            ClientUpdateInstallLogic.primaryInstallUrl(
                decision(installUrl = "  ", storeUrl = null, downloadUrl = null),
            ),
        )
    }

    // ── download validation / progress / errors ───────────────────────────

    @Test
    fun isHttpSuccess_range() {
        assertTrue(ClientUpdateInstallLogic.isHttpSuccess(200))
        assertTrue(ClientUpdateInstallLogic.isHttpSuccess(201))
        assertTrue(ClientUpdateInstallLogic.isHttpSuccess(204))
        assertTrue(ClientUpdateInstallLogic.isHttpSuccess(299))
        assertFalse(ClientUpdateInstallLogic.isHttpSuccess(199))
        assertFalse(ClientUpdateInstallLogic.isHttpSuccess(300))
        assertFalse(ClientUpdateInstallLogic.isHttpSuccess(301))
        assertFalse(ClientUpdateInstallLogic.isHttpSuccess(404))
        assertFalse(ClientUpdateInstallLogic.isHttpSuccess(500))
        assertFalse(ClientUpdateInstallLogic.isHttpSuccess(0))
        assertFalse(ClientUpdateInstallLogic.isHttpSuccess(-1))
    }

    @Test
    fun isDownloadedApkValid_minSize() {
        assertFalse(ClientUpdateInstallLogic.isDownloadedApkValid(0))
        assertFalse(ClientUpdateInstallLogic.isDownloadedApkValid(1))
        assertFalse(ClientUpdateInstallLogic.isDownloadedApkValid(1_023))
        assertTrue(ClientUpdateInstallLogic.isDownloadedApkValid(1_024))
        assertTrue(ClientUpdateInstallLogic.isDownloadedApkValid(20_000_000))
    }

    @Test
    fun progressFraction_andPercent() {
        assertNull(ClientUpdateInstallLogic.progressFraction(10, null))
        assertNull(ClientUpdateInstallLogic.progressFraction(10, 0))
        assertEquals(0f, ClientUpdateInstallLogic.progressFraction(0, 100))
        assertEquals(0.5f, ClientUpdateInstallLogic.progressFraction(50, 100))
        assertEquals(1f, ClientUpdateInstallLogic.progressFraction(100, 100))
        assertEquals(1f, ClientUpdateInstallLogic.progressFraction(150, 100))
        assertEquals(50, ClientUpdateInstallLogic.progressPercent(50, 100))
        assertNull(ClientUpdateInstallLogic.progressPercent(50, null))
    }

    @Test
    fun errorMessages_stableCopy() {
        assertEquals(
            "Не удалось скачать APK (HTTP 404)",
            ClientUpdateInstallLogic.errorMessageHttp(404),
        )
        assertEquals(
            "Не удалось скачать APK (HTTP 500)",
            ClientUpdateInstallLogic.errorMessageHttp(500),
        )
        assertEquals(
            "boom",
            ClientUpdateInstallLogic.errorMessageFromThrowable(IllegalStateException("boom")),
        )
        assertEquals(
            ClientUpdateInstallLogic.ERROR_GENERIC,
            ClientUpdateInstallLogic.errorMessageFromThrowable(null),
        )
        assertEquals(
            ClientUpdateInstallLogic.ERROR_GENERIC,
            ClientUpdateInstallLogic.errorMessageFromThrowable(RuntimeException("  ")),
        )
        assertEquals(
            ClientUpdateInstallLogic.ERROR_GENERIC,
            ClientUpdateInstallLogic.errorMessageFromThrowable(RuntimeException("")),
        )
    }

    // ── end-to-end decision matrix (settings primary CTA) ─────────────────

    @Test
    fun settingsPrimaryCta_apkSideloadRouteWhenDownloadPresent() {
        val d = decision(
            kind = UpdateDecisionKind.SOFT,
            installUrl = "https://api.glagolit.me/downloads/glagolitsa-0.1.3-4.apk",
            storeUrl = "https://play.google.com/store/apps/details?id=com.glagolitsa.mobile",
            downloadUrl = "https://api.glagolit.me/downloads/glagolitsa-0.1.3-4.apk",
        )
        assertTrue(ClientUpdateInstallLogic.canShowInstallActions(d))
        val url = ClientUpdateInstallLogic.primaryInstallUrl(d)
        assertEquals(
            "https://api.glagolit.me/downloads/glagolitsa-0.1.3-4.apk",
            url,
        )
        assertEquals(
            ClientUpdateInstallLogic.Route.DOWNLOAD_APK,
            ClientUpdateInstallLogic.routeFor(url),
        )
        assertEquals(
            ClientUpdateInstallLogic.LABEL_DOWNLOAD,
            ClientUpdateInstallLogic.primarySettingsButtonLabel(downloading = false, decision = d),
        )
        assertFalse(ClientUpdateInstallLogic.secondaryStoreButtonVisible(d))
    }

    @Test
    fun settingsPrimaryCta_storeOnlyOpensUrl() {
        val d = decision(
            kind = UpdateDecisionKind.HARD,
            installUrl = "https://play.google.com/store/apps/details?id=x",
            storeUrl = "https://play.google.com/store/apps/details?id=x",
            downloadUrl = null,
        )
        val url = ClientUpdateInstallLogic.primaryInstallUrl(d)
        assertEquals(
            ClientUpdateInstallLogic.Route.OPEN_URL,
            ClientUpdateInstallLogic.routeFor(url),
        )
        assertEquals(
            ClientUpdateInstallLogic.LABEL_STORE,
            ClientUpdateInstallLogic.primarySettingsButtonLabel(downloading = false, decision = d),
        )
        assertTrue(ClientUpdateInstallLogic.secondaryStoreButtonVisible(d))
    }

    @Test
    fun settingsPrimaryCta_apkOnlyNoSecondaryStore() {
        val d = decision(
            kind = UpdateDecisionKind.SOFT,
            installUrl = "https://api.glagolit.me/downloads/x.apk",
            storeUrl = null,
            downloadUrl = "https://api.glagolit.me/downloads/x.apk",
        )
        assertEquals(
            ClientUpdateInstallLogic.Route.DOWNLOAD_APK,
            ClientUpdateInstallLogic.routeFor(ClientUpdateInstallLogic.primaryInstallUrl(d)),
        )
        assertFalse(ClientUpdateInstallLogic.secondaryStoreButtonVisible(d))
        assertEquals(
            ClientUpdateInstallLogic.LABEL_DOWNLOAD,
            ClientUpdateInstallLogic.primarySettingsButtonLabel(downloading = false, decision = d),
        )
    }

    @Test
    fun hardGate_primaryUsesApkWhenPresent() {
        val d = decision(
            kind = UpdateDecisionKind.HARD,
            installUrl = "https://cdn/app.apk",
            storeUrl = "https://store",
            downloadUrl = "https://cdn/app.apk?token=1",
        )
        assertEquals(
            ClientUpdateInstallLogic.Route.DOWNLOAD_APK,
            ClientUpdateInstallLogic.routeFor(ClientUpdateInstallLogic.primaryInstallUrl(d)),
        )
        assertEquals(
            ClientUpdateInstallLogic.LABEL_DOWNLOAD,
            ClientUpdateInstallLogic.hardPrimaryButtonLabel(downloading = false, decision = d),
        )
        assertFalse(ClientUpdateInstallLogic.secondaryStoreButtonVisible(d))
    }

    @Test
    fun constants_matchPlatformContract() {
        assertEquals(1_024L, ClientUpdateInstallLogic.MIN_APK_BYTES)
        assertEquals("updates", ClientUpdateInstallLogic.UPDATES_CACHE_DIR)
        assertEquals("glagolitsa-update.apk", ClientUpdateInstallLogic.APK_CACHE_FILE_NAME)
        assertEquals(
            "glagolitsa-update.apk.url",
            ClientUpdateInstallLogic.APK_CACHE_URL_FILE_NAME,
        )
        assertEquals(
            "application/vnd.android.package-archive",
            ClientUpdateInstallLogic.APK_MIME_TYPE,
        )
        assertEquals("Скачать обновление", ClientUpdateInstallLogic.LABEL_DOWNLOAD)
        assertEquals("Установить", ClientUpdateInstallLogic.LABEL_INSTALL)
        assertEquals("Обновление скачано", ClientUpdateInstallLogic.LABEL_READY_TITLE)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun decision(
        kind: UpdateDecisionKind = UpdateDecisionKind.SOFT,
        installUrl: String? = null,
        storeUrl: String? = null,
        downloadUrl: String? = null,
    ): UpdateDecision = UpdateDecision(
        kind = kind,
        title = "t",
        body = "b",
        installUrl = installUrl,
        storeUrl = storeUrl,
        downloadUrl = downloadUrl,
        minVersion = null,
        latestVersion = null,
    )
}
