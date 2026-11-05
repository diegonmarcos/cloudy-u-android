package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.adbdebug.AdbShellHost
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
 * Configs > ADB Shell INSIDE the shell, and that page is the SuperApp's AdbShellBrokerPage (#1522).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdbShellInFrameTest {
    private val java = File(System.getProperty("user.dir"), "src/main/java/com/diegonmarcos/superapp")

    @Test fun `the shell is the host of the page for every chip`() {
        assertTrue(AdbShellHost::class.java.isAssignableFrom(ShellActivity::class.java))
        val src = File(java, "ShellActivity.kt").readText()
        assertTrue("it opens Configs > ADB Shell", Regex("override fun openAdbShellInFrame\\(\\)[^\\n]*openSectionPage\\(\"config\", \"adb-shell\"\\)").containsMatchIn(src))
    }

    @Test fun `Configs ADB Shell is the broker page and nothing else`() {
        val page = SectionPages.pagesFor("config", includeHidden = true).first { it.id == "adb-shell" }.factory()
        assertEquals(AdbShellFragment::class.java, page.javaClass)
        val code = File(java, "configs/AdbShellFragment.kt").readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
        assertTrue("hosts AdbShellBrokerPage", code.contains("AdbShellBrokerPage()"))
        assertTrue("draws no page, theme or scroll of its own", !code.contains("AdbShellScreen") && !code.contains("verticalScroll") && !code.contains("MaterialTheme"))
    }
}
