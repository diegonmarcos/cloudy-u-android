pluginManagement {
    includeBuild("plugins")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
        mavenLocal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        mavenLocal()
    }
}
rootProject.name = "Gallery"
include(":app")
include(":baselineprofile")
include(":libs:gesture")
project(":libs:gesture").projectDir = file("../ab_cloud-libs-shared/libs/gesture")
// Shared BY REFERENCE from ab_cloud-libs-shared/ — one copy, no per-app drift.
// projectDir is mandatory here: the default would resolve to ./libs/analytics,
// which is this fork's own local libs/ tree, not the shared module.
include(":libs:analytics")
project(":libs:analytics").projectDir = file("../ab_cloud-libs-shared/libs/analytics")
include(":libs:cropper")
project(":libs:cropper").projectDir = file("../ab_cloud-libs-shared/libs/cropper")
include(":libs:panoramaviewer")
project(":libs:panoramaviewer").projectDir = file("../ab_cloud-libs-shared/libs/panoramaviewer")
include(":libs:scrollbar")
project(":libs:scrollbar").projectDir = file("../ab_cloud-libs-shared/libs/scrollbar")

// Shared scan engine (task #459/#460): ZXing barcode decode + ML Kit OCR +
// typed-payload parsing. The SAME module cloud-drive compiles in — one engine,
// two apps, no private copy to drift. projectDir mandatory for the same reason
// as analytics above.
include(":libs:ml-l-image")
project(":libs:ml-l-image").projectDir = file("../ab_cloud-libs-shared/libs/ml-l-image")

// Crash telemetry. libs:core carries CoreInitProvider, which installs the
// uncaught-exception handler that POSTs the stack trace plus the tail of this
// process's logcat to c3-infra-api — so a crash here reports itself instead of
// dying on the phone, which is what made the last startup failure in this app
// take a dex teardown to diagnose rather than one launch.
include(":libs:core")
project(":libs:core").projectDir = file("../ab_cloud-libs-shared/libs/core")
// Not optional and not a stray: libs:core declares `api project(':libs:devtools')`,
// and Gradle resolves that path against THIS settings file. Without the include
// the core dependency fails configuration outright.
include(":libs:devtools")
project(":libs:devtools").projectDir = file("../ab_cloud-libs-shared/libs/devtools")
// #873 libs:core declares `api project(':libs:fleetconfig-model')` (the setup contract): the include is not optional.
include(":libs:fleetconfig-model")
project(":libs:fleetconfig-model").projectDir = file("../ab_cloud-libs-shared/libs/fleetconfig-model")
// #1122 libs:core declares `api project(':libs:crash')` (the default crash handler and screen): the include is not optional.
include(":libs:crash")
project(":libs:crash").projectDir = file("../ab_cloud-libs-shared/libs/crash")

// ':libs:<x>' implicitly declares an intermediate ':libs' project whose default
// projectDir is <root>/libs. This app HAD one, so it never needed mapping —
// until its four modules moved into the shared root and the directory went
// away. Gradle 9 fails outright on a project directory that does not exist
// ("Configuring project ':libs' without an existing directory is not allowed"),
// which is what broke ship-cloud-gallery. Map the container at the shared
// root; every leaf above already names its own projectDir, so this only gives
// the container something real to point at.
project(":libs").projectDir = file("../ab_cloud-libs-shared/libs")
// The shared navigation bars (first row with breadcrumb + Profile, second row, bottom nav, theme background), by reference like every
// other shared lib. libs:ui-nav-bars declares `api project(":libs:bottomnav")`, `:libs:ui-icons` and `:libs:theme-background`, so
// each of these includes is required, not optional.
// perms (#1360): libs:ui-nav-bars draws Configs > General > Perms with it (`api project(":libs:perms")`).
for (name in listOf("bottomnav", "ui-icons", "theme-background", "ui-nav-bars", "perms", "fleet-data-client")) {
    include(":libs:$name")
    project(":libs:$name").projectDir = file("../ab_cloud-libs-shared/libs/$name")
}

include(":ml-models")