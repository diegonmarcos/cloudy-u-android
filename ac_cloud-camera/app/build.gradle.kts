import java.io.FileInputStream
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val useKeystoreProperties = keystorePropertiesFile.canRead()
val keystoreProperties = Properties()
if (useKeystoreProperties) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    alias(libs.plugins.android.application)
}

// #461a: the voice-shutter trigger word is DECLARED in build.json and baked
// here into BuildConfig, exactly like libs/voice does for the model registry.
// Keeping the trigger in build.json — not a Kotlin literal — is the point: the
// shutter's hot word can change without an edit to application code, and it is
// data in the same file as the rest of the voice configuration.
val appMetadataJson = groovy.json.JsonSlurper().parse(rootProject.file("build.json")) as Map<*, *>
val voiceSection = appMetadataJson["voice"] as? Map<*, *>
// #772 the image recognition debug group, declared in build.json::debug_api.
val debugImageGroup = ((appMetadataJson["debug_api"] as? Map<*, *>)?.get("image_group") as? String)
    ?: error("build.json::debug_api.image_group is required")
// #798 the sound identification debug group, declared in the same place.
val debugSoundGroup = ((appMetadataJson["debug_api"] as? Map<*, *>)?.get("sound_group") as? String)
    ?: error("build.json::debug_api.sound_group is required")
// #868 the nav declaration (build.json::ui): the island's ids, the sections and their pages, the default.
val uiBlock = appMetadataJson["ui"] as? Map<*, *>
val uiBottomNav = ((uiBlock?.get("bottom_nav") as? List<*>) ?: emptyList<Any>()).joinToString(",")
val uiSectionsB64: String = Base64.getEncoder().encodeToString(
    groovy.json.JsonOutput.toJson(uiBlock?.get("sections") ?: emptyList<Any>()).toByteArray()
)
val uiDefaultSection = (uiBlock?.get("default_section") as? String).orEmpty()
val voiceShutterTrigger =
    (voiceSection?.get("shutter_trigger_word") as? String).orEmpty().ifBlank { "capture" }
val voiceShutterEnabledByDefault =
    (voiceSection?.get("shutter_enabled_by_default") as? Boolean) ?: false

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// #789 imported, not java.time.*: in this script `java` is the java {} extension.
val cloudVersionCode: Int = run {
    val base = LocalDateTime.of(2026, 1, 1, 0, 0)
    val built: LocalDateTime = System.getenv("COMMS_BUILD_TIMESTAMP")?.let { stamp ->
        runCatching { LocalDateTime.parse(stamp, DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")) }.getOrNull()
    } ?: LocalDateTime.now(ZoneOffset.UTC)
    val mins = Duration.between(base, built).toMinutes()
    if (mins > 0) (3_000_000L + mins).toInt() else 3_000_000
}

// #826 the build commit, baked into versionName "(sha-xxxxxxxx)" + BuildConfig.GIT_SHORT_SHA so
// Store Details (libs/appstore BuiltFrom) and About can name the installed build. Same
// derivation as the rest of the fleet: GITHUB_SHA first, `git rev-parse` locally, 8 hex.
val gitShortSha: String = (System.getenv("GITHUB_SHA")?.takeIf { it.isNotBlank() }
    ?: providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim())
    .take(8)

android {
    if (useKeystoreProperties) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV4Signing = true
            }

            create("play") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["uploadKeyAlias"] as String
                keyPassword = keystoreProperties["uploadKeyPassword"] as String
            }
        }
    }

    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"

    namespace = "cld.camera"

    defaultConfig {
        applicationId = "cld.camera"
        minSdk = 29
        targetSdk = 37
        // #789 the fleet's build stamp: 3,000,000 + minutes since 2026-01-01
        // (COMMS_BUILD_TIMESTAMP when CI gives one, else build time), the same
        // encoding as every first-party app. 93 — upstream's release tag — never
        // moved across our builds, so Android and the owner saw no progress; it
        // stays the versionName, read from build.json::android.version_name.
        versionCode = cloudVersionCode
        versionName = "${(appMetadataJson["android"] as Map<*, *>)["version_name"]} (sha-$gitShortSha)"
        buildConfigField("String", "GIT_SHORT_SHA", "\"$gitShortSha\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Baked from build.json::voice (#461a): the shutter's hot word and the
        // opt-in default. Kept off by default — a camera that listens by default
        // is not acceptable.
        buildConfigField("String", "VOICE_SHUTTER_TRIGGER_WORD", "\"$voiceShutterTrigger\"")
        buildConfigField("Boolean", "VOICE_SHUTTER_ENABLED_BY_DEFAULT", voiceShutterEnabledByDefault.toString())
        buildConfigField("String", "UI_BOTTOM_NAV", "\"$uiBottomNav\"")
        buildConfigField("String", "UI_SECTIONS_B64", "\"$uiSectionsB64\"")
        buildConfigField("String", "UI_DEFAULT_SECTION", "\"$uiDefaultSection\"")
        buildConfigField("String", "DEBUG_API_IMAGE_GROUP", "\"$debugImageGroup\"")
        buildConfigField("String", "DEBUG_API_SOUND_GROUP", "\"$debugSoundGroup\"")
    }

    buildTypes {
        getByName("release") {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (useKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
            resValue("string", "app_name", appMetadataJson["display_name"] as String)
        }

        getByName("debug") {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", (appMetadataJson["display_name"] as String) + " Dev")
            // isDebuggable = false
        }

        create("play") {
            initWith(getByName("release"))
            applicationIdSuffix = ".play"
            if (useKeystoreProperties) {
                signingConfig = signingConfigs.getByName("play")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    androidResources {
        // FLEET RULE (#299): English is the fleet's base language. This list is a
        // HARD FILTER applied at package time: a locale missing here is compiled
        // and then dropped from resources.arsc, so any values-es/ this module or
        // one of its libraries ships is an empty promise and every label renders
        // in English — even on the owner's Spanish phone, and with a green build
        // and a green i18n guard, because both only look at the source tree.
        //
        // Verified against the published APK on 2026-09-11: with "en" alone, the
        // values-es strings were absent from resources.arsc while their values/
        // counterparts were present.
        localeFilters += listOf("en")
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)

    // Task #461 — the ONE shared image-scan engine, consumed by reference the
    // same way cloud-drive and cloud-gallery consume it. One engine, no
    // private copy to drift (#170/#261).
    implementation(project(":libs:ml-l-image"))
    // #798 the CONTRACT half of sound identification (Identify's Sound mode, /api/sound/classify);
    // the engine (YAMNet) is Cloud-Lib-Ml-L-Sound-Yamnet.apk, bound after a handshake, never compiled.
    implementation(project(":libs:ml-l-sound"))

    // #1533 Objects (and VR): detection types, frame-to-view mapping, the analyzer lifecycle, the overlay.
    implementation(project(":libs:ml-l-vision-mlkit"))

    // #1547 the VR filter declaration is read at RUNTIME from Cloud Store's provider, never baked into the APK (code only, no data).
    implementation(project(":libs:fleet-data-client"))

    // Fleet mesh member: libs:core (+ devtools via `api`) merges the fleet
    // provider, receiver, <queries> and the signature permission into this APK.
    implementation(project(":libs:core"))

    // #868 the mode switcher is libs:bottomnav's island + page strip (ui/ModeNav.kt). Both are
    // Compose hosts: their supertype and the WindowInsets / ColorScheme they expose must be on this
    // module's compile classpath, though nothing here is written in Compose.
    implementation(project(":libs:bottomnav"))
    // #1352 Configs ▸ General / ▸ About, two buttons at the top of More settings (FleetConfigsEntries.addButtons)
    implementation(project(":libs:ui-nav-bars"))
    // #1630 fleet-default Configs tail: Agents, Logcat, X-Ray App register themselves on the shared strip.
    implementation(project(":libs:fleetconfigs-agents"))
    implementation(project(":libs:fleetconfigs-logcat"))
    implementation(project(":libs:fleetconfigs-xray"))
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation(libs.bundles.camerax)

    implementation(libs.zxing.core)

    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)

    testImplementation("junit:junit:4.13.2")
    // The real org.json: android.jar's copy is a stub that throws on the JVM.
    testImplementation("org.json:json:20240303")
}
