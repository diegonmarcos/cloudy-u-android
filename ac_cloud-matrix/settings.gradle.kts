/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

pluginManagement {
    repositories {
        includeBuild("plugins")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("https://www.jitpack.io")
            content {
                includeModule("com.github.matrix-org", "matrix-analytics-events")
                // Required transitively by androidx.media3:media3-exoplayer-midi for MIDI playback.
                includeModule("com.github.philburk", "jsyn")
            }
        }
        google()
        mavenCentral()
        maven {
            url = uri("https://repo1.maven.org/maven2/")
        }
        flatDir {
            dirs("libraries/matrix/libs")
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "ElementX"
include(":app")
include(":appnav")
include(":appconfig")
include(":appicon:element")
include(":appicon:enterprise")
include(":tests:detekt-rules")
include(":tests:konsist")
include(":tests:uitests")
include(":tests:testutils")
include(":annotations")
include(":codegen")

fun includeProjects(directory: File, path: String, maxDepth: Int = 1) {
    directory.listFiles().orEmpty().also { it.sort() }.forEach { file ->
        if (file.isDirectory) {
            val newPath = "$path:${file.name}"
            val buildFile = File(file, "build.gradle.kts")
            if (buildFile.exists()) {
                include(newPath)
                logger.lifecycle("Included project: $newPath")
            } else if (maxDepth > 0) {
                includeProjects(file, newPath, maxDepth - 1)
            }
        }
    }
}

includeProjects(File(rootDir, "enterprise"), ":enterprise", maxDepth = 2)
includeProjects(File(rootDir, "features"), ":features")
includeProjects(File(rootDir, "libraries"), ":libraries")
includeProjects(File(rootDir, "services"), ":services")

// Fleet mesh membership: libs:core manifest-merges the CONSTELLATION_DATA
// signature permission and, through libs:devtools, FleetTokenProvider +
// FleetMemberReceiver + the MESH_MEMBER <queries> intent — without them the
// SuperApp and every sibling cannot see or reach this app. devtools is not
// optional: core declares `api project(':libs:devtools')`, which resolves
// against THIS settings file. Shared BY REFERENCE, same pair as media-center.
include(":libs:core")
project(":libs:core").projectDir = file("../ab_cloud-libs-shared/libs/core")
include(":libs:devtools")
project(":libs:devtools").projectDir = file("../ab_cloud-libs-shared/libs/devtools")
// #873 libs:core declares `api project(':libs:fleetconfig-model')` (the setup contract): the include is not optional.
include(":libs:fleetconfig-model")
project(":libs:fleetconfig-model").projectDir = file("../ab_cloud-libs-shared/libs/fleetconfig-model")
// #1122 libs:core declares `api project(':libs:crash')` (the default crash handler and screen): the include is not optional.
include(":libs:crash")
project(":libs:crash").projectDir = file("../ab_cloud-libs-shared/libs/crash")
// #1352 Configs ▸ General / ▸ About in Settings ▸ Manage app (libs:ui-nav-bars) and its deps.
include(":libs:ui-nav-bars")
project(":libs:ui-nav-bars").projectDir = file("../ab_cloud-libs-shared/libs/ui-nav-bars")
include(":libs:fleetconfigs-agents")
project(":libs:fleetconfigs-agents").projectDir = file("../ab_cloud-libs-shared/libs/fleetconfigs-agents")
include(":libs:fleetconfigs-logcat")
project(":libs:fleetconfigs-logcat").projectDir = file("../ab_cloud-libs-shared/libs/fleetconfigs-logcat")
include(":libs:fleetconfigs-xray")
project(":libs:fleetconfigs-xray").projectDir = file("../ab_cloud-libs-shared/libs/fleetconfigs-xray")
include(":libs:bottomnav")
project(":libs:bottomnav").projectDir = file("../ab_cloud-libs-shared/libs/bottomnav")
include(":libs:ui-icons")
project(":libs:ui-icons").projectDir = file("../ab_cloud-libs-shared/libs/ui-icons")
include(":libs:theme-background")
project(":libs:theme-background").projectDir = file("../ab_cloud-libs-shared/libs/theme-background")
include(":libs:perms")
project(":libs:perms").projectDir = file("../ab_cloud-libs-shared/libs/perms")
include(":libs:fleet-data-client")
project(":libs:fleet-data-client").projectDir = file("../ab_cloud-libs-shared/libs/fleet-data-client")
// ':libs:<x>' implicitly declares a ':libs' container whose default projectDir
// is <root>/libs, which this fork does not have — and Gradle 9 fails outright
// on a project directory that does not exist. Point it at the shared root.
project(":libs").projectDir = file("../ab_cloud-libs-shared/libs")

// Uncomment to include the compound-android module as a local dependency so you can work on it locally.
// You will also need to clone it in the specified folder.
// includeBuild("checkouts/compound-android") {
//    dependencySubstitution {
//        // substitute remote dependency with local module
//        substitute(module("io.element.android:compound-android")).using(project(":compound"))
//    }
// }
