import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.properties.Properties
import java.io.FileInputStream
import java.util.Base64

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.detekt)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun hasSigningVars(): Boolean {
    return providers.environmentVariable("SIGNING_KEY_ALIAS").orNull != null
            && providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull != null
            && providers.environmentVariable("SIGNING_STORE_FILE").orNull != null
            && providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull != null
}

// #868 build.json::ui is THE navigation declaration (libs:bottomnav's NavDecl). It is baked here as
// UI_SECTIONS_B64 / UI_BOTTOM_NAV / UI_DEFAULT_SECTION and decoded once by DialerNav; no section id is
// spelled in Kotlin. nav-shape-guard (N1-N9) holds this app to it.
val dialerUi = (groovy.json.JsonSlurper().parse(rootProject.file("build.json")) as Map<*, *>)["ui"] as Map<*, *>
fun base64Json(value: Any?): String =
    Base64.getEncoder().encodeToString(groovy.json.JsonOutput.toJson(value).toByteArray())

// #826 the build commit, baked into versionName "(sha-xxxxxxxx)" + BuildConfig.GIT_SHORT_SHA so
// Store Details (libs/appstore BuiltFrom) and About can name the installed build. Same
// derivation as the rest of the fleet: GITHUB_SHA first, `git rev-parse` locally, 8 hex.
// versionCode is untouched (#789: identity stays sha-based, not code-based).
val gitShortSha: String = (System.getenv("GITHUB_SHA")?.takeIf { it.isNotBlank() }
    ?: providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim())
    .take(8)

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        // Cloud Dialer: the INSTALLED package id. Defaults to APP_ID (stock
        // org.fossify.phone) but the engine passes -PAPPLICATION_ID=
        // com.diegonmarcos.comms.dialer (build.json::forks.dialer.build.gradle_props)
        // so our fork has its OWN unique package — it never collides with a
        // stock Fossify Phone install, which is what let Android grant it the
        // default-Phone-app (ROLE_DIALER) role. The `namespace` (R/BuildConfig
        // package) intentionally stays APP_ID = org.fossify.phone (the code pkg).
        applicationId = (project.findProperty("APPLICATION_ID") ?: project.property("APP_ID")).toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        versionName = "${project.property("VERSION_NAME")} (sha-$gitShortSha)"
        versionCode = project.property("VERSION_CODE").toString().toInt()
        setProperty("archivesBaseName", "phone-$versionCode")

        // ---- Cloud Dialer self-contained updater (GHCR/WorkManager) ----
        // All values are data-driven: supplied via gradle -P properties by the
        // ea_cloud-comms engine (forks.dialer.build.gradle_props in build.json),
        // each with a safe in-repo default for standalone builds.
        buildConfigField("String", "GHCR_REGISTRY", "\"${project.findProperty("GHCR_REGISTRY") ?: "ghcr.io"}\"")
        buildConfigField("String", "GHCR_NAMESPACE", "\"${project.findProperty("GHCR_NAMESPACE") ?: "diegonmarcos"}\"")
        buildConfigField("String", "GHCR_IMAGE", "\"${project.findProperty("GHCR_IMAGE") ?: "cloud-comms-dialer"}\"")
        buildConfigField("String", "AUTO_UPDATE_TAG", "\"${project.findProperty("AUTO_UPDATE_TAG") ?: "latest"}\"")
        buildConfigField("String", "AUTO_UPDATE_TAG_MAP", "\"${project.findProperty("AUTO_UPDATE_TAG_MAP") ?: ""}\"")
        buildConfigField("String", "GIT_SHORT_SHA", "\"$gitShortSha\"")
        buildConfigField("String", "UI_SECTIONS_B64", "\"${base64Json(dialerUi["sections"])}\"")
        buildConfigField("String", "UI_BOTTOM_NAV", "\"${(dialerUi["bottom_nav"] as List<*>).joinToString(",")}\"")
        buildConfigField("String", "UI_DEFAULT_SECTION", "\"${dialerUi["default_section"]}\"")
        buildConfigField("boolean", "AUTO_UPDATE_ENABLED", "${project.findProperty("AUTO_UPDATE_ENABLED") ?: "false"}")
        buildConfigField("boolean", "AU_REQUIRE_UNMETERED", "${project.findProperty("AU_REQUIRE_UNMETERED") ?: "true"}")
        buildConfigField("boolean", "AU_REQUIRE_CHARGING", "${project.findProperty("AU_REQUIRE_CHARGING") ?: "false"}")
        buildConfigField("long", "AUTO_UPDATE_INTERVAL_HOURS", "${project.findProperty("AUTO_UPDATE_INTERVAL_HOURS") ?: "24"}L")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            register("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        } else if (hasSigningVars()) {
            register("release") {
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
                storeFile = file(providers.environmentVariable("SIGNING_STORE_FILE").get())
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            }
        } else {
            logger.warn("Warning: No signing config found. Build will be unsigned.")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists() || hasSigningVars()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    flavorDimensions.add("variants")
    productFlavors {
        register("core")
        register("foss")
        register("gplay")
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }

    compileOptions {
        val currentJavaVersionFromLibs =
            JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    dependenciesInfo {
        includeInApk = false
    }

    testOptions {
        unitTests {
            // The Compose tests render the real screens, which read this app's string resources.
            isIncludeAndroidResources = true
        }
    }

    androidResources {
        // FLEET RULE (#299): English is the fleet's base language.
        localeFilters += listOf("en")
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    namespace = project.property("APP_ID").toString()

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = rootProject.file("lint.xml")
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

detekt {
    baseline = file("detekt-baseline.xml")
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

dependencies {
    implementation(project(":libs:analytics"))
    // Fleet mesh member: libs:core (+ devtools via `api`) merges the fleet
    // provider, receiver, <queries> and the signature permission into this APK.
    implementation(project(":libs:core"))
    // 2026-10-16 owner decision: all apps must be Compose. Our additions (screened-call history, provider
    // chooser sheet) are composables under CloudKitTheme; upstream Fossify screens stay Views.
    implementation(project(":libs:ui-kit"))
    // #868 the bottom nav: one island for the whole fleet, declared in build.json::ui.
    implementation(project(":libs:bottomnav"))
    // #1164 the shared navigation: first row (Profile -> Cloud Account), second row, bottom nav, page background.
    implementation(project(":libs:ui-nav-bars"))
    implementation(project(":libs:theme-background"))
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(libs.fossify.commons)
    implementation(libs.indicator.fast.scroll)
    implementation(libs.autofit.text.view)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.eventbus)
    implementation(libs.libphonenumber)
    implementation(libs.geocoder)
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    testImplementation("junit:junit:4.13.2")
    // Compose UI tests of the SMS screens run on the JVM under Robolectric (same stack as libs:bottomnav's tests).
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.11.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    detektPlugins(libs.compose.detekt)
}
