package com.diegonmarcos.superapp.launcher

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1153 (supersedes #1095) Cloud > Apps > Lnktree > SuperApp (moved from Configs, owner 2026-10-30, where it
 * now LEADS the row) launches the real app as its own task, so split screen can keep the Cloud > Apps page open beside it. The tile's target is the `app://<package>` grammar
 * ShellActivity.handleTarget resolves to the package's launcher intent with NEW_TASK | LAUNCH_ADJACENT, and
 * the activity it lands on is resizeable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfigsTileDeepLinkTest {

    private fun superAppTile(): Sections.AggTile {
        val row = Sections.byId("cloud")!!.tileGroups.first { it.title == "Lnktree" }
        assertEquals("SuperApp must lead the Lnktree row", "cloud-superapp", row.tiles.first().id)
        return row.tiles.first { it.id == "cloud-superapp" }
    }

    @Test
    fun `C1 the SuperApp tile leading the Lnktree row launches the app package as its own task`() {
        assertEquals("app://com.diegonmarcos.superapp", superAppTile().target)
    }

    @Test
    fun `C2 the shell launches app targets with NEW_TASK and LAUNCH_ADJACENT, onto a resizeable activity`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .first { java.io.File(it, "aa_cloud-superapp/app/src/main/AndroidManifest.xml").exists() }
        val shell = java.io.File(root, "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt").readText()
        assertTrue("app: targets lose NEW_TASK", "FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT" in shell)
        val manifest = java.io.File(root, "aa_cloud-superapp/app/src/main/AndroidManifest.xml").readText()
        val main = manifest.substringAfter("android:name=\".MainActivity\"").substringBefore(">")
        assertTrue("MainActivity must be resizeable for split screen", "android:resizeableActivity=\"true\"" in main)
    }

    @Test
    fun `C3 the Configs row has no app launch tile any more, its tiles open pages or constellation apps`() {
        val row = Sections.byId("cloud")!!.tileGroups.first { it.title == "Setup" }.tiles.filter { !it.separator }
        assertEquals(emptyList<String>(), row.filter { it.target.startsWith("app://") }.map { it.id })
        assertTrue("a Configs tile has an empty target: ${row.filter { it.target.isBlank() }.map { it.id }}", row.none { it.target.isBlank() })
    }
}
