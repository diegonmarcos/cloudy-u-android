import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.impl.VariantOutputImpl
import com.android.utils.cxx.io.removeExtensionIfPresent
import com.google.firebase.crashlytics.buildtools.gradle.tasks.InjectMappingFileIdTask
import com.google.firebase.crashlytics.buildtools.gradle.tasks.UploadMappingFileTask
import com.google.gms.googleservices.GoogleServicesTask
import org.gradle.kotlin.dsl.support.uppercaseFirstChar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Base64 // #868 fleet nav patch
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.room)
    // Crashlytics is enabled for all builds initially but removed for FDroid builds in gradle and
    // standardDebug builds in the merged manifest.
    alias(libs.plugins.crashlytics)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
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

/**
 * Loads CI-specific build properties that are not checked into source control.
 */
val ciProperties = Properties().apply {
    val ciPropsFile = File(rootDir, "ci.properties")
    if (ciPropsFile.exists()) {
        FileInputStream(ciPropsFile).use { load(it) }
    }
}

base {
    // Set the base archive name for publishing purposes. This is used to derive the
    // APK and AAB artifact names when uploading to Firebase and Play Store.
    archivesName.set("com.x8bit.bitwarden")
}

room {
    schemaDirectory("$projectDir/schemas")
}

// #826 the build commit, baked into versionName "(sha-xxxxxxxx)" + BuildConfig.GIT_SHORT_SHA so
// Store Details (libs/appstore BuiltFrom) and About can name the installed build. Same
// derivation as the rest of the fleet: GITHUB_SHA first, `git rev-parse` locally, 8 hex.
// versionCode is untouched. The server-facing client version (bitwarden-client-version /
// user-agent) stays the bare upstream version via BuildConfig.CLIENT_VERSION: servers parse it.
val gitShortSha: String = (System.getenv("GITHUB_SHA")?.takeIf { it.isNotBlank() }
    ?: providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim())
    .take(8)

// ── #868 FLEET NAV PATCH (isolated; keep out of upstream hunks) ─────────────────────────────
// The nav declaration (build.json::ui) baked as BuildConfig for libs:bottomnav's NavDecl.
val fleetUi = (groovy.json.JsonSlurper().parse(File(rootDir, "build.json")) as Map<*, *>)["ui"] as? Map<*, *>
val uiBottomNav = ((fleetUi?.get("bottom_nav") as? List<*>) ?: emptyList<Any>()).joinToString(",")
val uiSectionsB64: String = Base64.getEncoder().encodeToString(
    groovy.json.JsonOutput.toJson(fleetUi?.get("sections") ?: emptyList<Any>()).toByteArray(),
)
val uiDefaultSection = (fleetUi?.get("default_section") as? String).orEmpty()
// ── end fleet nav patch ─────────────────────────────────────────────────────────────────────

configure<ApplicationExtension> {
    namespace = "com.x8bit.bitwarden"
    // #1093 the Android halves of the fleet endpoint design (NetworkChangeWatch, FleetEndpointsClient): two files shared with
    // Cloud Account and Cloud Mail, compiled here BY REFERENCE next to the shared JVM tree in :network (no copy, and the
    // strict dependency locks of this build see no new module).
    sourceSets["main"].kotlin.directories.add("../../ab_cloud-libs-shared/libs/fleet-endpoints-android/src/main/java")
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }

    defaultConfig {
        applicationId = "com.diegonmarcos.cloudvault"
        minSdk {
            version = release(libs.versions.minSdk.get().toInt())
        }
        targetSdk {
            version = release(libs.versions.targetSdk.get().toInt())
        }
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = "${libs.versions.appVersionName.get()} (sha-$gitShortSha)"
        buildConfigField("String", "GIT_SHORT_SHA", "\"$gitShortSha\"")
        buildConfigField("String", "CLIENT_VERSION", "\"${libs.versions.appVersionName.get()}\"")
        // #868 fleet nav patch: the declaration NavDecl.fromBuildConfig reads.
        buildConfigField("String", "UI_BOTTOM_NAV", "\"$uiBottomNav\"")
        buildConfigField("String", "UI_SECTIONS_B64", "\"$uiSectionsB64\"")
        buildConfigField("String", "UI_DEFAULT_SECTION", "\"$uiDefaultSection\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            type = "String",
            name = "CI_INFO",
            value = "${ciProperties.getOrDefault("ci.info", "\"\uD83D\uDCBB local\"")}",
        )
        buildConfigField(
            type = "String",
            name = "SDK_VERSION",
            value = "\"${libs.versions.bitwardenSdk.get()}\"",
        )
    }

    androidResources {
        // FLEET RULE (#299): English is the fleet's base language.
        localeFilters += listOf("en")
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    signingConfigs {
        getByName("debug") {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeFile = file("../keystores/debug.keystore")
            storePassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            isMinifyEnabled = false

            buildConfigField(type = "boolean", name = "HAS_DEBUG_MENU", value = "true")
            buildConfigField(type = "boolean", name = "HAS_LOGS_ENABLED", value = "true")
        }

        // Beta and Release variants are identical except beta has a different package name
        create("beta") {
            applicationIdSuffix = ".beta"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            matchingFallbacks += listOf("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            buildConfigField(type = "boolean", name = "HAS_DEBUG_MENU", value = "true")
            buildConfigField(type = "boolean", name = "HAS_LOGS_ENABLED", value = "false")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            buildConfigField(type = "boolean", name = "HAS_DEBUG_MENU", value = "false")
            buildConfigField(type = "boolean", name = "HAS_LOGS_ENABLED", value = "false")
        }
    }

    flavorDimensions += listOf("mode")
    productFlavors {
        create("standard") {
            isDefault = true
            dimension = "mode"
        }
        create("fdroid") {
            dimension = "mode"
        }
    }

    compileOptions {
        sourceCompatibility(libs.versions.jvmTarget.get())
        targetCompatibility(libs.versions.jvmTarget.get())
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        // Required for Robolectric
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    lint {
        disable += listOf(
            "MissingTranslation",
            "ExtraTranslation",
        )
    }
}

androidComponents {
    onVariants { appVariant ->
        val bundlesDir = "${layout.buildDirectory.get()}/outputs/bundle"
        val applicationId = appVariant.applicationId.get()
        val flavorName = appVariant.flavorName
        val variantName = appVariant.name
        val buildType = appVariant.buildType
        appVariant
            .outputs
            .mapNotNull { it as? VariantOutputImpl }
            .forEach { output ->
                val fileNameWithoutExtension = when (flavorName) {
                    "fdroid" -> "$applicationId-$flavorName"
                    "standard" -> applicationId
                    else -> output.outputFileName.get().removeExtensionIfPresent(".apk")
                }

                // Set the APK output filename.
                output.outputFileName.set("$fileNameWithoutExtension.apk")

                val renameTaskName = "rename${variantName.uppercaseFirstChar()}AabFiles"
                tasks.register(renameTaskName) {
                    group = "build"
                    description = "Renames the bundle files for $variantName variant"
                    doLast {
                        val namespace = appVariant.namespace.get()
                        renameFile(
                            "$bundlesDir/$variantName/$namespace-$flavorName-$buildType.aab",
                            "$fileNameWithoutExtension.aab",
                        )
                    }
                }
                // Force renaming task to execute after the variant is built.
                val bundleTaskName = "bundle${variantName.uppercaseFirstChar()}"
                tasks
                    .named { it == bundleTaskName }
                    .configureEach { finalizedBy(renameTaskName) }
            }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

configurations.all {
    resolutionStrategy.dependencySubstitution {
        if ((userProperties["localSdk"] as String?).toBoolean()) {
            substitute(module("com.bitwarden:sdk-android"))
                .using(module("com.bitwarden:sdk-android:LOCAL"))
        }
    }
}

dependencies {
    fun standardImplementation(dependencyNotation: Any) {
        add("standardImplementation", dependencyNotation)
    }

    implementation(project(":authenticatorbridge"))

    implementation(project(":annotation"))
    implementation(project(":core"))
    implementation(project(":cxf"))
    implementation(project(":data"))
    implementation(project(":network"))
    implementation(project(":ui"))

    // Fleet mesh member: libs:core (+ devtools via `api`) merges the fleet
    // provider, receiver, <queries> and the signature permission into this APK.
    implementation(project(":libs:core"))
    // #868 fleet nav patch: the shared bottom island (see FleetNavScaffold.kt).
    implementation(project(":libs:bottomnav"))
    // #1352 Configs ▸ General / ▸ About under the Settings rows (FleetConfigsButtons, libs:ui-nav-bars)
    implementation(project(":libs:ui-nav-bars"))
    // #1630 fleet-default Configs tail: Agents, Logcat, X-Ray App register themselves on the shared strip.
    implementation(project(":libs:fleetconfigs-agents"))
    implementation(project(":libs:fleetconfigs-logcat"))
    implementation(project(":libs:fleetconfigs-xray"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.biometrics)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.providerevents)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.bitwarden.sdk)
    implementation(libs.bumptech.glide)
    implementation(libs.bumptech.glide.okhttp)
    ksp(libs.bumptech.glide.compiler)
    implementation(libs.google.hilt.android)
    ksp(libs.google.hilt.compiler)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization)
    implementation(platform(libs.square.okhttp.bom))
    implementation(libs.square.okhttp)
    implementation(libs.timber)

    // For now we are restricted to running Compose tests for debug builds only
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Standard-specific flavor dependencies
    standardImplementation(libs.google.billing)
    standardImplementation(platform(libs.google.firebase.bom))
    standardImplementation(libs.google.firebase.cloud.messaging)
    standardImplementation(libs.google.firebase.crashlytics)
    standardImplementation(libs.google.mlkit.text.recognition)
    standardImplementation(libs.google.play.review)

    // Pull in test fixtures from other modules
    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":data")))
    testImplementation(testFixtures(project(":network")))
    testImplementation(testFixtures(project(":ui")))

    testImplementation(libs.androidx.compose.ui.test)
    testImplementation(libs.google.hilt.android.testing)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.vintage)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk.mockk)
    testImplementation(libs.robolectric.robolectric)
    testImplementation(libs.square.turbine)
}

afterEvaluate {
    // Disable Fdroid-specific tasks that we want to exclude
    val fdroidTasksToDisable = tasks.withType<GoogleServicesTask>() +
        tasks.withType<InjectMappingFileIdTask>() +
        tasks.withType<UploadMappingFileTask>()
    fdroidTasksToDisable
        .filter { it.name.contains("Fdroid") }
        .forEach { it.enabled = false }
}

private fun renameFile(path: String, newName: String) {
    val originalFile = File(path)
    if (!originalFile.exists()) {
        println("File $originalFile does not exist!")
        return
    }

    val newFile = File(originalFile.parentFile, newName)
    if (originalFile.renameTo(newFile)) {
        println("Renamed $originalFile to $newFile")
    } else {
        @Suppress("TooGenericExceptionThrown")
        throw RuntimeException("Failed to rename $originalFile to $newFile")
    }
}
