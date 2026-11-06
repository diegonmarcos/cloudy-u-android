pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Camera"
include(":app")

// Task #461 — the ONE shared image-scan engine (ZXing barcode decode + ML Kit
// OCR + typed-payload parsing), shared BY REFERENCE from ab_cloud-libs-shared.
// Cloud-drive and cloud-gallery compile in the SAME directory; this app
// now does too — one engine, no private copy to drift (#170/#261). projectDir
// is mandatory: the default would resolve to ./libs/ml-l-image, which
// does not exist in this app. Same AGP-9 consumption path gallery uses.
include(":libs:ml-l-image")
project(":libs:ml-l-image").projectDir = file("../ab_cloud-libs-shared/libs/ml-l-image")
// #798 sound identification's contract, by reference the same way (the engine is a Store lib).
include(":libs:ml-l-sound")
project(":libs:ml-l-sound").projectDir = file("../ab_cloud-libs-shared/libs/ml-l-sound")

// Fleet mesh membership: libs:core manifest-merges the CONSTELLATION_DATA
// signature permission and, through libs:devtools, FleetTokenProvider +
// FleetMemberReceiver + the MESH_MEMBER <queries> intent — without them the
// SuperApp and every sibling cannot see or reach this app. devtools is not
// optional: core declares `api project(':libs:devtools')`, which resolves
// against THIS settings file. Same pair, same reason as gallery.
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
// #1533 the shared on-device vision toolkit (detection, frame mapping, analyzer lifecycle), by reference.
include(":libs:ml-l-vision-mlkit")
project(":libs:ml-l-vision-mlkit").projectDir = file("../ab_cloud-libs-shared/libs/ml-l-vision-mlkit")
// #868 the fleet's bottom island + page-tab strips + NavDecl: ui/ModeNav.kt is the mode switcher.
include(":libs:bottomnav")
project(":libs:bottomnav").projectDir = file("../ab_cloud-libs-shared/libs/bottomnav")
// #1160 the nav icon catalog libs:bottomnav draws every glyph from.
include(":libs:ui-icons")
project(":libs:ui-icons").projectDir = file("../ab_cloud-libs-shared/libs/ui-icons")
// #1352 Configs ▸ General / ▸ About (libs:ui-nav-bars, opened from More settings); its `api` deps theme-background and perms.
include(":libs:ui-nav-bars")
project(":libs:ui-nav-bars").projectDir = file("../ab_cloud-libs-shared/libs/ui-nav-bars")
include(":libs:perms")
project(":libs:perms").projectDir = file("../ab_cloud-libs-shared/libs/perms")
include(":libs:fleet-data-client")
project(":libs:fleet-data-client").projectDir = file("../ab_cloud-libs-shared/libs/fleet-data-client")
include(":libs:theme-background")
project(":libs:theme-background").projectDir = file("../ab_cloud-libs-shared/libs/theme-background")
// Gradle 9 fails outright on a project directory that does not exist, and the
// implicit ':libs' container resolves to <root>/libs which this app does not
// have. Point it at the shared root, exactly as gallery does; the leaf
// above already names its own projectDir, so this only gives the container
// something real to point at.
project(":libs").projectDir = file("../ab_cloud-libs-shared/libs")
