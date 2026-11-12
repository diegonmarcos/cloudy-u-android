import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // #1682 P1 the UI is native Compose (libs:ui-nav-bars, libs:bottomnav, libs:web-core), no Tauri webview.
    id("org.jetbrains.kotlin.plugin.compose")
}

// cloud-c3-webserver (#1682 P1): the native Kotlin / Compose app over the Rust core (../../../core, a cdylib build.sh
// cross-compiles with the NDK into src/main/jniLibs/<abi>/ before gradle runs). The directory keeps its Tauri-era path
// (src-tauri/gen/android) because other apps' builds and tests read this module's AndroidManifest.xml there. Every value
// the fleet owns (identity, SDK levels, version, launcher label, the nav declaration, the admin port) is read from the
// app's ONE build.json (four levels up: gen/android/app -> ac_cloud-c3-webserver).
@Suppress("UNCHECKED_CAST")
val buildJson = groovy.json.JsonSlurper().parse(file("../../../../build.json")) as Map<String, Any?>
@Suppress("UNCHECKED_CAST")
val androidJson = buildJson["android"] as Map<String, Any?>

// #868 build.json::ui is THE navigation declaration (libs:bottomnav NavDecl), decoded once by Decl.kt.
@Suppress("UNCHECKED_CAST")
val uiJson = (buildJson["ui"] as? Map<String, Any?>) ?: throw GradleException("build.json::ui missing: the bottom nav would draw nothing (#868)")
val uiBottomNav: String = (uiJson["bottom_nav"] as? List<*>)?.joinToString(",") ?: throw GradleException("build.json::ui.bottom_nav missing")
// ui.sections is NOT baked (runtime-data D3: no new BuildConfig base64 field): the Rust core hands it to the UI (Core.nativeUiSections).
if (uiJson["sections"] !is List<*>) throw GradleException("build.json::ui.sections missing")
val uiDefaultSection: String = (uiJson["default_section"] as? String) ?: uiBottomNav.substringBefore(',')
// The loopback admin API the Compose UI reads: build.json::runtime.port, the port the Rust core binds (core/build.rs reads the same key).
@Suppress("UNCHECKED_CAST")
val adminPort: Int = (((buildJson["runtime"] as Map<String, Any?>)["port"]) as Number).toInt()
@Suppress("UNCHECKED_CAST")
val adbShellJson = buildJson["adb_shell"] as Map<String, Any?>

// versionCode: minutes since 2026-01-01 UTC over a 3,000,000 base, the fleet's
// encoding, so it only ever moves forward whichever workflow built it.
// COMMS_BUILD_TIMESTAMP (yyyyMMdd.HHmmss) wins when CI sets it. (java.time is
// imported above: inside a build script a bare `java.` resolves to the
// project's `java` extension, not the package.)
val fleetVersionCode: Int = run {
    val base = LocalDateTime.of(2026, 1, 1, 0, 0)
    var built = LocalDateTime.now(ZoneOffset.UTC)
    val stamp = System.getenv("COMMS_BUILD_TIMESTAMP")
    if (!stamp.isNullOrBlank()) {
        runCatching {
            built = LocalDateTime.parse(stamp, DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss"))
        }
    }
    val mins = Duration.between(base, built).toMinutes()
    if (mins > 0) (3000000L + mins).toInt() else 3000000
}

// #830 the build commit, baked into versionName "(sha-xxxxxxxx)" + BuildConfig.GIT_SHORT_SHA so
// Store Details (libs/appstore BuiltFrom) and About can name the installed build. Same
// derivation as the rest of the fleet (#826): GITHUB_SHA first, `git rev-parse` locally, 8 hex.
// versionCode is untouched.
val gitShortSha: String = (System.getenv("GITHUB_SHA")?.takeIf { it.isNotBlank() }
    ?: providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim())
    .take(8)

android {
    compileSdk = (androidJson["compile_sdk"] as Number).toInt()
    namespace = androidJson["application_id"] as String
    defaultConfig {
        // "true" in every build type, not only debug: the UI's one HTTP
        // destination is the in-process server on http://127.0.0.1.
        manifestPlaceholders["usesCleartextTraffic"] = "true"
        applicationId = androidJson["application_id"] as String
        minSdk = (androidJson["min_sdk"] as Number).toInt()
        targetSdk = (androidJson["target_sdk"] as Number).toInt()
        versionCode = fleetVersionCode
        versionName = "${androidJson["version_name"] as String} (sha-$gitShortSha)"
        buildConfigField("String", "GIT_SHORT_SHA", "\"$gitShortSha\"")
        buildConfigField("String", "UI_BOTTOM_NAV", "\"$uiBottomNav\"")
        buildConfigField("String", "UI_DEFAULT_SECTION", "\"$uiDefaultSection\"")
        buildConfigField("int", "ADMIN_PORT", "$adminPort")
        buildConfigField("String", "ADB_SHELL_LABEL", "\"${adbShellJson["label"]}\"")
        ndk {
            // The ABI build.sh compiled the Rust core for (CLOUDNAV_VARIANT -> build.json::release.variants), else build.json::android.abi_filters.
            val abiOverride = System.getenv("C3_WEBSERVER_ABIS")?.trim().orEmpty()
            @Suppress("UNCHECKED_CAST")
            val abis = if (abiOverride.isNotEmpty()) abiOverride.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                else (androidJson["abi_filters"] as List<String>)
            abiFilters += abis
        }
        // Launcher label = build.json::name, the application's one name (#351).
        resValue("string", "app_name", buildJson["display_name"] as String)
        resValue("string", "main_activity_title", buildJson["display_name"] as String)
    }
    // English is the fleet's base language: drops the ~80 locales appcompat
    // and material would otherwise merge in.
    androidResources {
        localeFilters += listOf("en")
    }
    buildTypes {
        getByName("debug") {
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            packaging {
                jniLibs.keepDebugSymbols.add("*/arm64-v8a/*.so")
                jniLibs.keepDebugSymbols.add("*/armeabi-v7a/*.so")
                jniLibs.keepDebugSymbols.add("*/x86/*.so")
                jniLibs.keepDebugSymbols.add("*/x86_64/*.so")
            }
        }
        getByName("release") {
            optimization {
               enable = true
            }
            proguardFiles(
                *fileTree(".") {
                  include("**/*.pro")
                  exclude("build/**")
                }.files.toTypedArray()
            )
        }
    }
    // The Rust library is the bulk of the APK: stored compressed it is the
    // download size budget, stored uncompressed (the default at minSdk >= 23)
    // it would be its full unpacked size.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    lint {
        // targetSdk 28 is the storage model, see build.json::android._doc_target_sdk.
        disable.add("ExpiredTargetSdkVersion")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        buildConfig = true
        resValues = true
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

// #1682 P2b ADB owner side (settings.gradle sets gradle.ext.adbOwnerSide from adb-owner.json, or the CI verify): src/owner
// holds the real owner (lib ADB Shell page, readiness, Connect, Tester, Logs); src/dormant the inactive stand-in the shipped
// build compiles while another app owns ADB. Exactly one of the two is compiled.
val adbOwnerSide: Boolean = (gradle as ExtensionAware).extensions.extraProperties.let { it.has("adbOwnerSide") && it.get("adbOwnerSide") == true }
android.sourceSets.getByName("main").java.srcDir(if (adbOwnerSide) "src/owner/java" else "src/dormant/java")

dependencies {
    // ADB-OWNER-SIDE BEGIN (test-shell-broker-guards.sh B1 reads the shipped variant: this block only links when adbOwnerSide)
    if (adbOwnerSide) {
        implementation(project(":libs:shizuku-adb-debug-tools"))
        implementation(project(":libs:shell-broker"))
        implementation(project(":libs:adb-shell-core"))
    }
    // ADB-OWNER-SIDE END
    // #1682 P1 the native UI: Compose (the fleet BOM) + the fleet's nav chrome, web view and perms libs.
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(project(":libs:bottomnav"))
    implementation(project(":libs:theme-background"))
    implementation(project(":libs:web-core"))   // #1371 the fleet's one web view: the Pages browser frame and the File Explorer preview
    implementation(project(":libs:perms"))
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    // cloud-c3-webserver: fleet mesh member. libs:core (+ devtools via `api`)
    // merges the fleet provider, receiver, <queries> and the signature
    // permission into this APK; nothing here calls it (settings.gradle).
    implementation(project(":libs:core"))
    implementation(project(":libs:fleet-notify"))   // #1660 FleetNotify + KeepAliveStatus (left libs:core): the keep-alive / data-widget notifications
    // #1352 Configs ▸ General / ▸ About, reached through the launcher shortcuts the lib publishes (no native menu here)
    implementation(project(":libs:ui-nav-bars"))
    // #1630 fleet-default Configs tail: Agents, Logcat, X-Ray App register themselves on the shared strip (the web Configs strip opens them natively).
    implementation(project(":libs:fleetconfigs-agents"))
    implementation(project(":libs:fleetconfigs-logcat"))
    implementation(project(":libs:fleetconfigs-xray"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}

