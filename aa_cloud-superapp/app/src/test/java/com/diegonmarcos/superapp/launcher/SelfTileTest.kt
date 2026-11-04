package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Bug: in launcher mode the SuperApp's own tile in Cloud ▸ Apps opened Home wearing Android's system status bar, instead
 * of Configs. Cause: the tile fired the app's LAUNCHER intent, i.e. MainActivity (the app door, own task, not the HOME
 * door), where isDefaultLauncher() is false.
 *
 *   S1 the self tile's route is Configs, and only the own package has one
 *   S2 tapping it hands section:config to the running host, never starts an activity; another app is left to launch
 *   S3 the route names a real section, the Configs one
 *   S4 every app-tile launch site (Phone folders, Suite tiles, search, Minimalist list) asks SelfTile first
 *   S6 the Lnktree-row tile (app://<own package>, the one in Cloud ▸ Apps) is routed to Configs in launcher mode BEFORE the
 *      launch intent is built; ConfigsTileDeepLinkTest still pins the app-door behaviour (own task, split screen)
 *   S5 STATUS-BAR GUARD: launcher chrome stays the HOME door's, the system status bar is hidden there, and SelfTile
 *      never starts an activity (so no second door can wear system chrome on this path)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SelfTileTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private class Host(base: Context) : ContextWrapper(base), TileGridFragment.TileClickListener {
        val taps = mutableListOf<String>()
        override fun onTileClicked(tileId: String) { taps += tileId }
    }

    private val main = File(System.getProperty("user.dir"), "src/main/java/com/diegonmarcos/superapp")
    private fun src(rel: String) = File(main, rel).readText()

    @Test
    fun `S1 the self tile routes to Configs and only for the own package`() {
        assertEquals("section:config", SelfTile.routeFor("com.diegonmarcos.superapp", "com.diegonmarcos.superapp"))
        assertEquals(null, SelfTile.routeFor("com.diegonmarcos.superapp", "com.diegonmarcos.cloudcalc"))
        assertEquals(null, SelfTile.routeFor("com.diegonmarcos.superapp", ""))
    }

    @Test
    fun `S2 tapping the self tile sends the host to Configs and starts nothing`() {
        val host = Host(ctx)
        assertTrue(SelfTile.open(host, ctx.packageName))
        assertEquals(listOf("section:config"), host.taps)
        assertFalse(SelfTile.open(host, "com.example.other"))
        assertEquals("another app is not routed", 1, host.taps.size)
        assertFalse("no host on the context chain: the caller launches normally", SelfTile.open(ctx, ctx.packageName))
    }

    @Test
    fun `S3 the route is the Configs section`() {
        val id = SelfTile.TARGET.removePrefix("section:")
        assertNotNull(Sections.byId(id))
        assertEquals("config", Sections.canonicalId(id))
    }

    @Test
    fun `S4 every app-tile launch site asks SelfTile before it launches`() {
        for ((file, launch) in listOf(
            "apps/PhoneAppsUi.kt" to "startMainActivity(",
            "apps/SuitePhoneAppsFragment.kt" to "getLaunchIntentForPackage(",
            "search/SearchHost.kt" to "startMainActivity(",
            "launcher/themes/minimalist/MinimalistBlackFragment.kt" to "startMainActivity(",
        )) {
            val s = src(file)
            val at = s.indexOf(launch)
            assertTrue("$file launches an app", at >= 0)
            val guards = Regex("""SelfTile\.open\(""").findAll(s).count()
            val launches = Regex(Regex.escape(launch)).findAll(s).count()
            assertTrue("$file: $launches launch call(s) but $guards SelfTile.open", guards >= launches)
            assertTrue("$file: SelfTile.open must come before the first launch", s.indexOf("SelfTile.open(") < at)
        }
    }

    @Test
    fun `S5 launcher mode keeps the SuperApp status bar and the self tile never starts an activity`() {
        val shell = src("ShellActivity.kt")
        val door = shell.substring(shell.indexOf("override fun isDefaultLauncher()")).substringBefore("\n    }\n")
        assertTrue("launcher chrome is the HOME door's alone", door.contains("if (this !is HomeActivity) return false"))
        val chrome = shell.substring(shell.indexOf("override fun applyLauncherChrome()")).substringBefore("\n    }\n")
        val launcherBranch = chrome.substring(chrome.indexOf("if (isLauncher) {"), chrome.indexOf("} else {"))
        assertTrue("system status bar hidden in launcher mode", launcherBranch.contains("controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())"))
        assertTrue("the SuperApp strip owns the bar in launcher mode", chrome.contains("it.visibility = View.VISIBLE"))
        val self = src("launcher/SelfTile.kt").substringAfter("object SelfTile")
        assertFalse(self.contains("startActivity"))
        assertFalse(self.contains("getLaunchIntentForPackage"))
        assertFalse(self.contains("startMainActivity"))
        val manifest = File(System.getProperty("user.dir"), "src/main/AndroidManifest.xml").readText()
        assertTrue("HomeActivity is the HOME door", manifest.substringAfter("android:name=\".HomeActivity\"").substringBefore("</activity>").contains("category.HOME"))
        assertFalse("MainActivity is not", manifest.substringAfter("android:name=\".MainActivity\"").substringBefore("</activity>").contains("category.HOME"))
    }

    @Test
    fun `S6 the app-colon own tile is routed to Configs in launcher mode before any launch intent`() {
        val shell = src("ShellActivity.kt")
        val branch = shell.substring(shell.indexOf("if (uri.startsWith(\"app:\")) {"))
        val route = branch.indexOf("SelfTile.routeFor(packageName, pkg)")
        assertTrue("launchUri routes the own package", route >= 0)
        assertTrue("only in launcher mode", branch.substring(0, route).substringAfterLast("if (").startsWith("pkg != null && isDefaultLauncher()"))
        assertTrue("before the launch intent is built", route < branch.indexOf("getLaunchIntentForPackage"))
        val app = Sections.byId("cloud")!!.tileGroups.first { it.title == "Lnktree" }.tiles.first { it.id == "cloud-superapp" }
        assertEquals("app://com.diegonmarcos.superapp", app.target)
    }
}
