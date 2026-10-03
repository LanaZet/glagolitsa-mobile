// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Client update matrix (Mattermost min/latest + Signal/WhatsApp hard floor patterns).
 *
 * Industry unit-test focus (no UI / no network):
 * - pure semver / build compare
 * - NONE / SOFT / HARD decision table
 * - platform isolation
 * - install URL preference (store over download)
 * - settings badge presentation
 * - feature flags defaults
 * - JSON policy decode (wire contract)
 */
class ClientUpdatePolicyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val androidIdentity = ClientAppIdentity(
        versionName = "0.1.0",
        versionCode = 1,
        platform = ClientPlatform.ANDROID,
    )

    // --- Semver (Mattermost ClientVersions / mobile checkVersion) ---

    @Test
    fun compareSemver_ordersNumericParts_notLexicographic() {
        assertTrue(ClientUpdateEvaluator.compareSemver("1.9.0", "1.10.0") < 0)
        assertTrue(ClientUpdateEvaluator.compareSemver("1.10.0", "1.9.0") > 0)
        assertEquals(0, ClientUpdateEvaluator.compareSemver("1.2.3", "1.2.3"))
        assertTrue(ClientUpdateEvaluator.compareSemver("1.2", "1.2.1") < 0)
        assertTrue(ClientUpdateEvaluator.compareSemver("1.2.1", "1.2") > 0)
    }

    @Test
    fun compareSemver_stripsPrefixAndPrerelease() {
        assertEquals(0, ClientUpdateEvaluator.compareSemver("v1.2.3", "1.2.3"))
        assertEquals(0, ClientUpdateEvaluator.compareSemver("1.2.3-beta", "1.2.3"))
        assertEquals(0, ClientUpdateEvaluator.compareSemver("1.2.3+build", "1.2.3"))
        assertTrue(ClientUpdateEvaluator.compareSemver("v0.9.0-rc1", "1.0.0") < 0)
    }

    @Test
    fun compareSemver_handlesWhitespaceAndEmpty() {
        assertEquals(0, ClientUpdateEvaluator.compareSemver(" 1.0.0 ", "1.0.0"))
        assertEquals(0, ClientUpdateEvaluator.compareSemver("", ""))
        assertTrue(ClientUpdateEvaluator.compareSemver("", "0.0.1") < 0)
        assertTrue(ClientUpdateEvaluator.compareSemver("1", "1.0.0") == 0)
    }

    @Test
    fun compareSemver_ignoresNonNumericSuffixInPart() {
        // "1.2x" → 1.2
        assertEquals(0, ClientUpdateEvaluator.compareSemver("1.2x", "1.2"))
    }

    // --- Decision table: NONE / SOFT / HARD ---

    @Test
    fun none_whenAtOrAboveLatest_versionAndBuild() {
        val policy = ClientUpdatePolicy(
            android = PlatformUpdatePolicy(
                min_version = "0.1.0",
                latest_version = "0.1.0",
                min_build = 1,
                latest_build = 1,
            ),
        )
        val d = ClientUpdateEvaluator.evaluate(androidIdentity, policy)
        assertEquals(UpdateDecisionKind.NONE, d.kind)
        assertFalse(d.shouldPrompt)
        assertFalse(d.isBlocking)
        assertFalse(d.canInstall) // no store/download urls
    }

    @Test
    fun none_whenAboveLatest() {
        val policy = ClientUpdatePolicy(
            android = PlatformUpdatePolicy(
                min_version = "0.1.0",
                latest_version = "0.1.0",
                min_build = 1,
                latest_build = 1,
            ),
        )
        val d = ClientUpdateEvaluator.evaluate(
            androidIdentity.copy(versionName = "9.9.9", versionCode = 999),
            policy,
        )
        assertEquals(UpdateDecisionKind.NONE, d.kind)
    }

    @Test
    fun soft_whenBelowLatestButAboveMin() {
        val policy = ClientUpdatePolicy(
            android = PlatformUpdatePolicy(
                min_version = "0.1.0",
                latest_version = "0.2.0",
                min_build = 1,
                latest_build = 20,
                store_url = "https://play.google.com/store/apps/details?id=com.glagolitsa.mobile",
            ),
            soft_title = "Доступна новая версия",
        )
        val d = ClientUpdateEvaluator.evaluate(androidIdentity, policy)
        assertEquals(UpdateDecisionKind.SOFT, d.kind)
        assertTrue(d.shouldPrompt)
        assertFalse(d.isBlocking)
        assertTrue(d.canInstall)
        assertEquals("Доступна новая версия", d.title)
        assertEquals("0.1.0", d.minVersion)
        assertEquals("0.2.0", d.latestVersion)
    }

    @Test
    fun soft_whenOnlyBelowLatestBuild() {
        val policy = ClientUpdatePolicy(
            android = PlatformUpdatePolicy(
                min_version = "0.1.0",
                latest_version = "0.1.0",
                min_build = 1,
                latest_build = 50,
                download_url = "https://cdn.example/app.apk",
            ),
        )
        val d = ClientUpdateEvaluator.evaluate(
            androidIdentity.copy(versionCode = 10),
            policy,
        )
        assertEquals(UpdateDecisionKind.SOFT, d.kind)
        assertEquals("https://cdn.example/app.apk", d.installUrl)
    }

    @Test
    fun none_whenLocalBuildAlreadyAtOrAboveLatestEvenIfVersionNameLags() {
        // Real case: adb debug 0.1.3/code=6 vs published policy 0.1.4/build=5.
        // Must not prompt installing an older APK just because name is lower.
        val policy = ClientUpdatePolicy(
            android = PlatformUpdatePolicy(
                min_version = "0.1.0",
                latest_version = "0.1.4",
                min_build = 1,
                latest_build = 5,
                download_url = "https://api.example/downloads/glagolitsa-0.1.4-5.apk",
            ),
        )
        val d = ClientUpdateEvaluator.evaluate(
            androidIdentity.copy(versionName = "0.1.3", versionCode = 6),
            policy,
        )
        assertEquals(UpdateDecisionKind.NONE, d.kind)
        assertFalse(d.shouldPrompt)
    }

    @Test
    fun hard_whenBelowMinVersion() {
        val policy = ClientUpdatePolicy(
            android = PlatformUpdatePolicy(
                min_version = "1.0.0",
                latest_version = "1.2.0",
                store_url = "https://example.com/app",
                download_url = "https://example.com/app.apk",
            ),
            hard_title = "Требуется обновление",
        )
        val d = ClientUpdateEvaluator.evaluate(androidIdentity, policy)
        assertEquals(UpdateDecisionKind.HARD, d.kind)
        assertTrue(d.isBlocking)
        assertTrue(d.shouldPrompt)
        assertEquals("Требуется обновление", d.title)
        // direct APK wins over store (sideload-first for testers)
        assertEquals("https://example.com/app.apk", d.installUrl)
        assertEquals("https://example.com/app", d.storeUrl)
        assertEquals("https://example.com/app.apk", d.downloadUrl)
    }

    @Test
    fun hard_whenBelowMinBuild_evenIfVersionLooksOk() {
        val policy = ClientUpdatePolicy(
            android = PlatformUpdatePolicy(
                min_version = "0.1.0",
                latest_version = "0.2.0",
                min_build = 50,
                latest_build = 60,
                download_url = "https://cdn.example/glagolitsa.apk",
            ),
        )
        val d = ClientUpdateEvaluator.evaluate(androidIdentity, policy)
        assertEquals(UpdateDecisionKind.HARD, d.kind)
        assertEquals("https://cdn.example/glagolitsa.apk", d.installUrl)
    }

    @Test
    fun hard_takesPrecedenceOverSoft_whenBelowBoth() {
        val policy = ClientUpdatePolicy(
            android = PlatformUpdatePolicy(
                min_version = "0.5.0",
                latest_version = "1.0.0",
                store_url = "https://store",
            ),
        )
        val d = ClientUpdateEvaluator.evaluate(androidIdentity, policy)
        assertEquals(UpdateDecisionKind.HARD, d.kind)
    }

    @Test
    fun killSwitch_alwaysHard_evenIfVersionIsNewest() {
        val policy = ClientUpdatePolicy(
            kill_switch = true,
            android = PlatformUpdatePolicy(
                min_version = "0.0.1",
                latest_version = "0.0.1",
                store_url = "https://store",
            ),
            hard_body = "Экстренное обновление",
        )
        val d = ClientUpdateEvaluator.evaluate(
            androidIdentity.copy(versionName = "99.0.0", versionCode = 999),
            policy,
        )
        assertEquals(UpdateDecisionKind.HARD, d.kind)
        assertEquals("Экстренное обновление", d.body)
        assertTrue(d.canInstall)
    }

    // --- Empty gates (no force / no soft) ---

    @Test
    fun emptyMinAndLatest_meansNoGate() {
        val policy = ClientUpdatePolicy(
            android = PlatformUpdatePolicy(
                min_version = "",
                latest_version = "",
                min_build = 0,
                latest_build = 0,
            ),
        )
        val d = ClientUpdateEvaluator.evaluate(
            ClientAppIdentity("0.0.1", 1, ClientPlatform.ANDROID),
            policy,
        )
        assertEquals(UpdateDecisionKind.NONE, d.kind)
    }

    @Test
    fun blankLocalVersion_doesNotForceOnVersionGateOnly() {
        // versionName blank + min_version set → isBelowMin version branch skipped when blank
        // but min_build still applies
        val policy = ClientUpdatePolicy(
            android = PlatformUpdatePolicy(min_version = "1.0.0", min_build = 0),
        )
        val d = ClientUpdateEvaluator.evaluate(
            ClientAppIdentity(versionName = "", versionCode = 0, platform = ClientPlatform.ANDROID),
            policy,
        )
        assertEquals(UpdateDecisionKind.NONE, d.kind)
    }

    // --- Platform isolation ---

    @Test
    fun desktopPolicy_selectedByPlatform_notAndroid() {
        val policy = ClientUpdatePolicy(
            desktop = PlatformUpdatePolicy(
                min_version = "0.2.0",
                download_url = "https://cdn.example/desktop.dmg",
            ),
            android = PlatformUpdatePolicy(min_version = "9.9.9"),
        )
        val d = ClientUpdateEvaluator.evaluate(
            ClientAppIdentity("0.1.0", 1, ClientPlatform.DESKTOP),
            policy,
        )
        assertEquals(UpdateDecisionKind.HARD, d.kind)
        assertEquals("https://cdn.example/desktop.dmg", d.installUrl)
    }

    @Test
    fun iosPolicy_selectedIndependently() {
        val policy = ClientUpdatePolicy(
            ios = PlatformUpdatePolicy(
                min_version = "2.0.0",
                store_url = "https://apps.apple.com/app/id1",
            ),
            android = PlatformUpdatePolicy(min_version = "0.0.1"),
        )
        val iosHard = ClientUpdateEvaluator.evaluate(
            ClientAppIdentity("1.0.0", 1, ClientPlatform.IOS),
            policy,
        )
        val androidOk = ClientUpdateEvaluator.evaluate(
            ClientAppIdentity("0.1.0", 1, ClientPlatform.ANDROID),
            policy,
        )
        assertEquals(UpdateDecisionKind.HARD, iosHard.kind)
        assertEquals(UpdateDecisionKind.NONE, androidOk.kind)
    }

    @Test
    fun clientPlatform_fromId_aliases() {
        assertEquals(ClientPlatform.ANDROID, ClientPlatform.fromId("android"))
        assertEquals(ClientPlatform.IOS, ClientPlatform.fromId("iOS"))
        assertEquals(ClientPlatform.IOS, ClientPlatform.fromId("iphone"))
        assertEquals(ClientPlatform.DESKTOP, ClientPlatform.fromId("macos"))
        assertEquals(ClientPlatform.DESKTOP, ClientPlatform.fromId("windows"))
        assertEquals(ClientPlatform.DESKTOP, ClientPlatform.fromId(null))
        assertEquals(ClientPlatform.DESKTOP, ClientPlatform.fromId("unknown"))
    }

    // --- Install URL preference ---

    @Test
    fun preferredInstallUrl_downloadOverStore() {
        val p = PlatformUpdatePolicy(
            store_url = "https://store",
            download_url = "https://cdn/app.apk",
        )
        assertEquals("https://cdn/app.apk", ClientUpdateEvaluator.preferredInstallUrl(p))
    }

    @Test
    fun preferredInstallUrl_storeWhenNoDownload() {
        val p = PlatformUpdatePolicy(store_url = "https://store")
        assertEquals("https://store", ClientUpdateEvaluator.preferredInstallUrl(p))
    }

    @Test
    fun preferredInstallUrl_downloadWhenNoStore() {
        val p = PlatformUpdatePolicy(download_url = "https://cdn/app.apk")
        assertEquals("https://cdn/app.apk", ClientUpdateEvaluator.preferredInstallUrl(p))
    }

    @Test
    fun preferredInstallUrl_nullWhenBothEmpty() {
        assertNull(ClientUpdateEvaluator.preferredInstallUrl(PlatformUpdatePolicy()))
    }

    // --- Settings badge (UI presentation pure) ---

    @Test
    fun settingsBadge_none_whenNoUpdate() {
        val badge = ClientUpdateEvaluator.settingsBadge(
            ClientUpdateEvaluator.evaluate(
                androidIdentity,
                ClientUpdatePolicy(
                    android = PlatformUpdatePolicy(min_version = "0.1.0", latest_version = "0.1.0"),
                ),
            ),
        )
        assertNull(badge.label)
        assertFalse(badge.highlight)
    }

    @Test
    fun settingsBadge_soft_showsNovoe() {
        val d = ClientUpdateEvaluator.evaluate(
            androidIdentity,
            ClientUpdatePolicy(
                android = PlatformUpdatePolicy(
                    min_version = "0.1.0",
                    latest_version = "0.2.0",
                ),
            ),
        )
        val badge = ClientUpdateEvaluator.settingsBadge(d)
        assertEquals("Новое", badge.label)
        assertTrue(badge.highlight)
    }

    @Test
    fun settingsBadge_hard_showsNuzhno() {
        val d = ClientUpdateEvaluator.evaluate(
            androidIdentity,
            ClientUpdatePolicy(
                android = PlatformUpdatePolicy(min_version = "1.0.0"),
            ),
        )
        val badge = ClientUpdateEvaluator.settingsBadge(d)
        assertEquals("Нужно", badge.label)
        assertTrue(badge.highlight)
    }

    @Test
    fun settingsBadge_nullDecision() {
        val badge = ClientUpdateEvaluator.settingsBadge(null)
        assertNull(badge.label)
        assertFalse(badge.highlight)
    }

    // --- Feature flags ---

    @Test
    fun features_passThroughAndDefaults() {
        val policy = ClientUpdatePolicy(
            features = mapOf("channels_public_discover" to true, "calls_video" to false),
        )
        val d = ClientUpdateEvaluator.evaluate(androidIdentity, policy)
        assertTrue(ClientUpdateEvaluator.isFeatureEnabled(policy, "channels_public_discover"))
        assertFalse(ClientUpdateEvaluator.isFeatureEnabled(policy, "calls_video"))
        assertFalse(ClientUpdateEvaluator.isFeatureEnabled(policy, "missing", default = false))
        assertTrue(ClientUpdateEvaluator.isFeatureEnabled(policy, "missing", default = true))
        assertEquals(true, d.features["channels_public_discover"])
    }

    // --- Wire contract: JSON decode (API payload) ---

    @Test
    fun policy_decodesFromServerJson() {
        val raw = """
            {
              "android": {
                "min_version": "0.1.0",
                "latest_version": "0.2.0",
                "min_build": 1,
                "latest_build": 20,
                "store_url": "https://play.google.com/store/apps/details?id=com.glagolitsa.mobile",
                "download_url": ""
              },
              "ios": { "min_version": "0.1.0", "latest_version": "0.1.0" },
              "desktop": { "min_version": "0.1.0", "latest_version": "0.1.0" },
              "kill_switch": false,
              "soft_title": "Доступна новая версия",
              "hard_title": "Требуется обновление",
              "features": { "calls_audio": true, "calls_video": false }
            }
        """.trimIndent()
        val policy = json.decodeFromString(ClientUpdatePolicy.serializer(), raw)
        assertEquals("0.2.0", policy.android.latest_version)
        assertEquals(20, policy.android.latest_build)
        assertFalse(policy.kill_switch)
        val d = ClientUpdateEvaluator.evaluate(androidIdentity, policy)
        assertEquals(UpdateDecisionKind.SOFT, d.kind)
        assertTrue(d.canInstall)
    }

    @Test
    fun policy_ignoresUnknownJsonKeys() {
        val raw = """{"android":{"min_version":"2.0.0"},"future_field":true}"""
        val policy = json.decodeFromString(ClientUpdatePolicy.serializer(), raw)
        val d = ClientUpdateEvaluator.evaluate(androidIdentity, policy)
        assertEquals(UpdateDecisionKind.HARD, d.kind)
    }

    // --- Multi-user / multi-device matrix (same public channel discover spirit) ---

    @Test
    fun samePolicy_evaluatesIndependentlyPerDeviceVersion() {
        val policy = ClientUpdatePolicy(
            android = PlatformUpdatePolicy(
                min_version = "1.0.0",
                latest_version = "1.5.0",
                store_url = "https://store",
            ),
        )
        val devices = listOf(
            ClientAppIdentity("0.9.0", 9, ClientPlatform.ANDROID) to UpdateDecisionKind.HARD,
            ClientAppIdentity("1.0.0", 10, ClientPlatform.ANDROID) to UpdateDecisionKind.SOFT,
            ClientAppIdentity("1.5.0", 15, ClientPlatform.ANDROID) to UpdateDecisionKind.NONE,
            ClientAppIdentity("2.0.0", 20, ClientPlatform.ANDROID) to UpdateDecisionKind.NONE,
        )
        devices.forEach { (identity, expected) ->
            assertEquals(
                expected,
                ClientUpdateEvaluator.evaluate(identity, policy).kind,
                "device ${identity.versionName}/${identity.versionCode}",
            )
        }
    }
}
