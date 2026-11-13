package com.diegonmarcos.superapp.shellclient

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** #1521 "Arm in SuperApp": ONE helper, an explicit intent to a declared, exported SuperApp entry; a clear answer when it is missing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OpenArmPageTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun installSuperApp(exported: Boolean = true) {
        val act = ActivityInfo().apply { name = ShellWire.ARM_ACTIVITY; packageName = ShellWire.BROKER_PKG; this.exported = exported
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = ShellWire.BROKER_PKG } }
        val pi = PackageInfo().apply { packageName = ShellWire.BROKER_PKG; applicationInfo = act.applicationInfo; activities = arrayOf(act) }
        shadowOf(app.packageManager).installPackage(pi)
    }

    @Test fun theIntentResolvesToTheSuperAppsArmActivity() {
        installSuperApp()
        val ri = app.packageManager.resolveActivity(ShellClient.armIntent(), 0)
        assertNotNull(ri)
        assertEquals(ShellWire.BROKER_PKG, ri!!.activityInfo.packageName)
        assertEquals(ShellWire.ARM_ACTIVITY, ri.activityInfo.name)
    }

    @Test fun theIntentIsExplicit() {
        val i = ShellClient.armIntent()
        assertEquals(ShellWire.BROKER_PKG, i.component?.packageName)
        assertEquals(ShellWire.ARM_ACTIVITY, i.component?.className)
    }

    @Test fun openFromANonActivityContextStartsItWithNewTask() {
        installSuperApp()
        assertTrue(ShellClient.openArmPage(app))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(ShellWire.ARM_ACTIVITY, started.component?.className)
        assertTrue(started.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun aMissingSuperAppIsReportedNotSilent() {
        assertFalse(ShellClient.openArmPage(app))
        assertEquals(null, shadowOf(app).nextStartedActivity)
        assertFalse(ShellClient.status(app).armed)
        assertTrue(ShellClient.status(app).reason.contains("not installed"))
    }

    // The <queries> guard: Android 11+ hides the SuperApp from an app that does not declare it, so every app that links
    // shell-client must merge the library's <queries> and none may remove it.
    @Test fun theLibraryDeclaresTheSuperAppInQueries() {
        val m = File("src/main/AndroidManifest.xml").readText()
        // #1682: the owner is the app 1_cicd/src/data/adb-owner.json names (the SuperApp until the P3 flip, the Cloud Webserver after it)
        var root: File? = File("").absoluteFile
        while (root != null && !File(root, "1_cicd/src/data/adb-owner.json").exists()) root = root.parentFile
        assertNotNull("repo root", root)
        val owner = Regex("\"package\"\\s*:\\s*\"([^\"]+)\"").find(File(root!!, "1_cicd/src/data/adb-owner.json").readText())!!.groupValues[1]
        assertTrue(m.contains("<queries>") && m.contains("""<package android:name="$owner" />"""))
    }

    @Test fun noAppThatUsesShellClientRemovesTheQueries() {
        var root: File? = File("").absoluteFile
        while (root != null && !File(root, "build.sh").exists()) root = root.parentFile
        assertNotNull("repo root", root)
        val users = root!!.listFiles { f -> f.isDirectory && f.name.startsWith("a") }.orEmpty()
            .flatMap { listOf(File(it, "app/build.gradle"), File(it, "build.gradle")) }
            .filter { it.isFile && it.readText().contains("libs:shell-client") }
        for (g in users) {
            val man = File(g.parentFile, "src/main/AndroidManifest.xml")
            if (man.isFile) assertFalse("${man.path} removes <queries>", Regex("""<queries[^>]*node="remove"""").containsMatchIn(man.readText()))
        }
    }

    @Test fun theArmActivityIsDeclaredExportedWithTheActionFilter() {
        val m = File("../shell-broker/src/main/AndroidManifest.xml").readText()
        val a = m.substringAfter("ShellArmActivity").substringBefore("</activity>")
        assertTrue(a.contains("""android:exported="true"""") && a.contains(ShellWire.ACTION_ARM))
    }
}
