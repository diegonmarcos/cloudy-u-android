package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.launcher.SectionPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import android.app.Application

/**
 * #1080 every chip that opens the ADB Shell page (the embedded Store's bar, Permissions, Dev control) lands on SuperApp's own
 * Configs > ADB Shell INSIDE the shell. The page is a strip over four tabs (Status, Shell, Perms, All); All is the SuperApp's AdbShellBrokerPage (#1522).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdbShellInFrameTest {
    private val java = File(System.getProperty("user.dir"), "src/main/java/com/diegonmarcos/superapp")

    @Test fun `the shell is the host of the page for every chip`() {
        // #1682 P3 step 1: the host is a hook the Application installs, the activity implements no ADB interface.
        val shell = File(java, "ShellActivity.kt").readText()
        assertTrue("ShellActivity implements no ADB class", !shell.contains("adbdebug") && !shell.contains("openAdbShellInFrame"))
        val app = File(java, "App.kt").readText()
        val side = File(System.getProperty("user.dir"), "src/owner/java/com/diegonmarcos/superapp/adbside/AdbSide.kt").readText()
        assertTrue("the owner AdbSide installs the lib's in-frame opener", side.contains("AdbShellLink.hostOpener = inFrame") && side.contains("AdbReadiness.hostOpener = inFrame"))
        assertTrue("App hands the facade its opener", app.contains("AdbSide.install(this)"))
        assertTrue("it opens Configs > ADB Shell", Regex("shell\\.openSectionPage\\(\"config\", \"adb-shell\"\\)").containsMatchIn(app))
    }

    @Test fun `Configs ADB Shell is a strip over four tabs, each an AdbShellFragment, and All is the broker page`() {
        val pages = SectionPages.pagesFor("config", includeHidden = true)
        assertEquals(listOf("adb-status", "adb-term", "adb-perms", "adb-all"), AdbCockpitModel.Tab.values().map { it.pageId })
        for (t in AdbCockpitModel.Tab.values()) assertEquals(t.pageId, AdbShellFragment::class.java, pages.first { it.id == t.pageId }.factory().javaClass)
        assertTrue("the adb-shell page itself is the strip (it declares tabs)", pages.first { it.id == "adb-shell" }.factory().javaClass != AdbShellFragment::class.java)
        val code = File(java, "configs/AdbShellFragment.kt").readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
        assertTrue("hosts AdbShellBrokerPage", code.contains("AdbShellBrokerPage()"))
        assertTrue("draws no page, theme or scroll of its own", !code.contains("AdbShellScreen") && !code.contains("verticalScroll") && !code.contains("MaterialTheme"))
    }
}
