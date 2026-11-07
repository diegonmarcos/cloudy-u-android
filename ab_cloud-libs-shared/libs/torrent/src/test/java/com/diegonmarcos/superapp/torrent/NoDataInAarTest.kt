package com.diegonmarcos.superapp.torrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** libs:torrent is engine code only: nothing baked into the AAR (no assets, no raw or other resources, no BuildConfig data, no gradle injection). */
class NoDataInAarTest {
    private val module: File = File(System.getProperty("user.dir"))
    private val main = File(module, "src/main")

    @Test fun noAssetsOrResourcesInTheMainSourceSet() {
        assertFalse("assets/", File(main, "assets").exists())
        assertFalse("res/", File(main, "res").exists())
        assertFalse("resources/", File(main, "resources").exists())
        assertFalse("jniLibs/ (the native half comes from the jlibtorrent artifacts)", File(main, "jniLibs").exists())
    }

    @Test fun onlyKotlinAndTheManifestLiveInMain() {
        val stray = main.walkTopDown().filter { it.isFile }.filter { !it.name.endsWith(".kt") && it.name != "AndroidManifest.xml" }.toList()
        assertTrue("unexpected files in src/main: $stray", stray.isEmpty())
    }

    @Test fun gradleBakesNothing() {
        val g = File(module, "build.gradle").readText().lines().filter { !it.trim().startsWith("//") }.joinToString("\n")
        assertFalse(g.contains("buildConfigField")); assertFalse(g.contains("resValue")); assertFalse(g.contains("assets.srcDir")); assertFalse(g.contains("into('assets')"))
    }
}
