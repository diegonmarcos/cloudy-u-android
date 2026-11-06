import java.io.FileInputStream
import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

/**
 * Loads local user-specific build properties that are not checked into source control.
 */
val userProperties = Properties().apply {
    val buildPropertiesFile = File(rootDir, "user.properties")
    if (buildPropertiesFile.exists()) {
        FileInputStream(buildPropertiesFile).use { load(it) }
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages (Bitwarden)"
            url = uri("https://maven.pkg.github.com/bitwarden/sdk")
            credentials {
                username = ""
                password = userProperties["gitHubToken"] as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
        if ((userProperties["localSdk"] as String?).toBoolean()) {
            mavenLocal()
        }
    }
}

rootProject.name = "Bitwarden"
include(
    ":annotation",
    ":app",
    ":authenticator",
    ":authenticatorbridge",
    ":core",
    ":cxf",
    ":data",
    ":network",
    ":testharness",
    ":ui",
)

// Fleet mesh membership: libs:core manifest-merges the CONSTELLATION_DATA
// signature permission and, through libs:devtools, FleetTokenProvider +
// FleetMemberReceiver + the MESH_MEMBER <queries> intent — without them the
// SuperApp and every sibling cannot see or reach this app. Shared BY REFERENCE;
// devtools is not optional, core's `api project(':libs:devtools')` resolves
// against THIS settings file. Distinct from Bitwarden's own ':core' — the
// paths differ, and so do the default groups (Bitwarden vs Bitwarden.libs).
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
// #868 FLEET NAV PATCH: the shared bottom island (BottomNavIsland + NavDecl). Drawn by
// ui/platform/feature/vaultunlockednavbar/FleetNavScaffold.kt in place of the M3 bottom bar.
include(":libs:bottomnav")
project(":libs:bottomnav").projectDir = file("../ab_cloud-libs-shared/libs/bottomnav")
// #1160 the nav icon catalog libs:bottomnav draws every glyph from.
include(":libs:ui-icons")
project(":libs:ui-icons").projectDir = file("../ab_cloud-libs-shared/libs/ui-icons")
// #1352 Configs ▸ General / ▸ About (libs:ui-nav-bars) and its `api` deps theme-background and perms.
include(":libs:ui-nav-bars")
project(":libs:ui-nav-bars").projectDir = file("../ab_cloud-libs-shared/libs/ui-nav-bars")
include(":libs:perms")
project(":libs:perms").projectDir = file("../ab_cloud-libs-shared/libs/perms")
include(":libs:fleet-data-client")
project(":libs:fleet-data-client").projectDir = file("../ab_cloud-libs-shared/libs/fleet-data-client")
include(":libs:theme-background")
project(":libs:theme-background").projectDir = file("../ab_cloud-libs-shared/libs/theme-background")
// Gradle 9 fails on a project directory that does not exist, and the implicit
// ':libs' container defaults to <root>/libs, which Bitwarden does not have.
// Point it at the shared root, exactly as media-center and camera do.
project(":libs").projectDir = file("../ab_cloud-libs-shared/libs")
