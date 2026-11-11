rootProject.name = "Phone"
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://www.jitpack.io") }
        mavenLocal()
    }
}
include(":app")

// Shared BY REFERENCE from ab_cloud-libs-shared/ — projectDir points at the one
// copy rather than a vendored duplicate, so the module can never drift per app.
// ':libs:analytics' implicitly declares an intermediate ':libs' project, whose
// default projectDir is <root>/libs. This app has no local libs/, and Gradle 9
// fails the build outright on a project directory that does not exist. Map
// ':libs' onto the shared libs root so the container resolves to a real
// directory. Apps that DO have their own libs/ (nav, media-center) must not do
// this — it would repoint their local modules.
include(":libs:analytics")
project(":libs").projectDir = file("../ab_cloud-libs-shared/libs")
project(":libs:analytics").projectDir = file("../ab_cloud-libs-shared/libs/analytics")

// Fleet mesh membership: libs:core manifest-merges the CONSTELLATION_DATA
// signature permission and, through libs:devtools, FleetTokenProvider +
// FleetMemberReceiver + the MESH_MEMBER <queries> intent — without them the
// SuperApp and every sibling cannot see or reach this app. devtools is not
// optional: core declares `api project(':libs:devtools')`, which resolves
// against THIS settings file. The ':libs' mapping above already covers both.
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
// 2026-10-16 owner decision: all apps must be Compose. The fleet's Compose kit (CloudKitTheme + KitPalette):
// ScreeningLogActivity and CallProviderChooserSheet paint with it.
include(":libs:ui-kit")
project(":libs:ui-kit").projectDir = file("../ab_cloud-libs-shared/libs/ui-kit")
include(":libs:bottomnav")
project(":libs:bottomnav").projectDir = file("../ab_cloud-libs-shared/libs/bottomnav")
// #1160 the nav icon catalog libs:bottomnav draws every glyph from.
include(":libs:ui-icons")
project(":libs:ui-icons").projectDir = file("../ab_cloud-libs-shared/libs/ui-icons")
// #1164 the shared navigation: first row (Profile -> Cloud Account), second row, bottom nav, page background.
include(":libs:ui-nav-bars")
project(":libs:ui-nav-bars").projectDir = file("../ab_cloud-libs-shared/libs/ui-nav-bars")
include(":libs:fleetconfigs-agents")
project(":libs:fleetconfigs-agents").projectDir = file("../ab_cloud-libs-shared/libs/fleetconfigs-agents")
include(":libs:fleetconfigs-logcat")
project(":libs:fleetconfigs-logcat").projectDir = file("../ab_cloud-libs-shared/libs/fleetconfigs-logcat")
include(":libs:fleetconfigs-xray")
project(":libs:fleetconfigs-xray").projectDir = file("../ab_cloud-libs-shared/libs/fleetconfigs-xray")
include(":libs:theme-background")
project(":libs:theme-background").projectDir = file("../ab_cloud-libs-shared/libs/theme-background")
// #1360 libs:ui-nav-bars draws Configs > General > Perms with libs:perms (api dependency).
include(":libs:perms")
project(":libs:perms").projectDir = file("../ab_cloud-libs-shared/libs/perms")
include(":libs:fleet-data-client")
project(":libs:fleet-data-client").projectDir = file("../ab_cloud-libs-shared/libs/fleet-data-client")
