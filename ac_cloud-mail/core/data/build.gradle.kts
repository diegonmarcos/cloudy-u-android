plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.sterna.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core:jmap"))
    api(project(":core:imap"))
    // #1837 the Rust mail core (libmail_core.so + its Kotlin facade): the JMAP sync engine and store of record.
    implementation(project(":libs:mail-core-rs"))
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    api(libs.androidx.paging.runtime)

    implementation(libs.androidx.datastore.preferences)

    // The one-click unsubscribe POST (RFC 8058) lives here, not in :core:jmap — it is a request to
    // a third-party domain that uses no JMAP at all, and reusing that module's client would put it
    // one careless refactor away from the code path that attaches an Authorization header
    // (decision D1). OkHttp is already a project dependency; this exposes it to one more module.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    // A local HTTP server, to prove what the unsubscribe POST puts on the wire — and what it
    // refuses to do when the sender's server answers with a redirect.
    testImplementation(libs.okhttp.mockwebserver)
    // The XmlPullParser implementation the unit tests parse RSS/Atom with. Android ships this
    // library under the hood; the JVM test classpath needs it explicitly because there is no
    // platform parser off-device. See core/data rss.
    testImplementation(libs.kxml2)
    // Virtual time, to unit-test the outbox badge without waiting out real undo windows.
    testImplementation(libs.kotlinx.coroutines.test)
    // Real SQLite engine for unit-testing the conversation-grouping SQL on the JVM.
    testImplementation("org.xerial:sqlite-jdbc:3.45.3.0")
}
