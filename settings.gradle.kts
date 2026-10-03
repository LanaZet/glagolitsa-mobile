// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

rootProject.name = "GlagolitsaMobile"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven {
            url = uri("https://build-artifacts.signal.org/libraries/maven")
            content {
                includeGroup("org.signal")
            }
        }
        // LiveKit android transitive (audioswitch).
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.davidliu")
            }
        }
    }
}

include(":shared")
include(":androidApp")
include(":desktopApp")