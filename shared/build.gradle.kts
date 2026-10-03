// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.composeHotReload)
}

sqldelight {
    databases {
        create("GlagolitsaDatabase") {
            packageName.set("com.glagolitsa.db")
        }
    }
}

@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.work.runtime)
            implementation(libs.firebase.messaging)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android)
            implementation(libs.sqlcipher.android)
            implementation(libs.libsignal.android)
            implementation(libs.androidx.security.crypto)
            implementation(libs.androidx.biometric)
            implementation(libs.zxing.core)
            implementation(libs.livekit.android)
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.sqlite)
                implementation(libs.zxing.core)
            }
        }

        val iosMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.native)
            }
        }

        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.uiTest)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }

        val desktopTest by getting {
            dependsOn(commonTest.get())
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        androidUnitTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.junit)
            implementation(libs.libsignal.client)
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val localBuildProps = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.glagolitsa.shared"
    compileSdk = libs.versions.compileSdk.get().toInt()

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // Prefer root -P flags (assemble from androidApp still sees rootProject properties).
        fun prop(name: String): String? =
            localBuildProps.getProperty(name)?.takeIf { it.isNotBlank() }
                ?: (project.findProperty(name) as String?)
                ?: (rootProject.findProperty(name) as String?)

        val apiBaseUrl = prop("apiBaseUrl") ?: ""
        val apiFallbackIp = prop("apiFallbackIp") ?: ""
        // Default OFF. Never bake dev shortcut usernames unless explicitly enabled.
        val devShortcutsEnabled = prop("devShortcutsEnabled")
            ?.toBooleanStrictOrNull()
            ?: false
        val rawDevPrimaryUsername = prop("devPrimaryUsername").orEmpty()
        val rawDevPrimaryPassword = prop("devPrimaryPassword").orEmpty()
        val rawDevPrimaryLabel = prop("devPrimaryLabel").orEmpty().ifBlank { rawDevPrimaryUsername }
        val rawDevSecondaryUsername = prop("devSecondaryUsername").orEmpty()
        val rawDevSecondaryPassword = prop("devSecondaryPassword").orEmpty()
        val rawDevSecondaryLabel = prop("devSecondaryLabel").orEmpty().ifBlank { rawDevSecondaryUsername }
        val shortcutsOn = devShortcutsEnabled &&
            rawDevPrimaryUsername.isNotBlank() &&
            rawDevPrimaryPassword.isNotBlank()
        val emulatorPhoneTunnel = prop("emulatorPhoneTunnel")
            ?.toBooleanStrictOrNull()
            ?: false
        val devPrimaryUsername = if (shortcutsOn) rawDevPrimaryUsername else ""
        val devPrimaryPassword = if (shortcutsOn) rawDevPrimaryPassword else ""
        val devPrimaryLabel = if (shortcutsOn) rawDevPrimaryLabel else ""
        val devSecondaryUsername =
            if (shortcutsOn && rawDevSecondaryUsername.isNotBlank() && rawDevSecondaryPassword.isNotBlank()) {
                rawDevSecondaryUsername
            } else {
                ""
            }
        val devSecondaryPassword = if (devSecondaryUsername.isNotEmpty()) rawDevSecondaryPassword else ""
        val devSecondaryLabel = if (devSecondaryUsername.isNotEmpty()) rawDevSecondaryLabel else ""
        logger.lifecycle(
            "shared BuildConfig: API_BASE_URL=$apiBaseUrl DEV_SHORTCUTS_ENABLED=$shortcutsOn EMULATOR_PHONE_TUNNEL=$emulatorPhoneTunnel",
        )
        buildConfigField("String", "API_BASE_URL", apiBaseUrl.asBuildConfigString())
        buildConfigField("String", "API_FALLBACK_IP", apiFallbackIp.asBuildConfigString())
        buildConfigField("Boolean", "DEV_SHORTCUTS_ENABLED", shortcutsOn.toString())
        buildConfigField("Boolean", "EMULATOR_PHONE_TUNNEL", emulatorPhoneTunnel.toString())
        buildConfigField("String", "DEV_PRIMARY_USERNAME", devPrimaryUsername.asBuildConfigString())
        buildConfigField("String", "DEV_PRIMARY_PASSWORD", devPrimaryPassword.asBuildConfigString())
        buildConfigField("String", "DEV_PRIMARY_LABEL", devPrimaryLabel.asBuildConfigString())
        buildConfigField("String", "DEV_SECONDARY_USERNAME", devSecondaryUsername.asBuildConfigString())
        buildConfigField("String", "DEV_SECONDARY_PASSWORD", devSecondaryPassword.asBuildConfigString())
        buildConfigField("String", "DEV_SECONDARY_LABEL", devSecondaryLabel.asBuildConfigString())
        buildConfigField("Boolean", "DEBUG_BUILD", "false")
        // Defaults must match androidApp; prefer gradle.properties appVersion*.
        val appVersionName = (project.findProperty("appVersionName") as String?) ?: "0.1.5"
        val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 7
        buildConfigField("String", "APP_VERSION_NAME", appVersionName.asBuildConfigString())
        buildConfigField("int", "APP_VERSION_CODE", appVersionCode.toString())
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("Boolean", "DEBUG_BUILD", "true")
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
