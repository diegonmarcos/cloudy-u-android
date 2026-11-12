buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.3.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
        // #1352 libs:ui-nav-bars (Configs ▸ General / ▸ About) is Compose: its compiler plugin, same Kotlin
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.10")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        // #1682 P2b the owner side's libadb-android (com.github.MuntashirAkon) is on JitPack; only when the owner side is built.
        if ((gradle as ExtensionAware).extensions.extraProperties.let { it.has("adbOwnerSide") && it.get("adbOwnerSide") == true }) {
            maven { url = uri("https://jitpack.io") }
        }
    }
}

tasks.register("clean").configure {
    delete("build")
}
