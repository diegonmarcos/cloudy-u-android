package com.diegonmarcos.cloudaccount

import com.diegonmarcos.cloudaccount.hub.HubNav
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1682 Account's three ADB entries open the owner's (Cloud C3 Webserver) ADB Shell page; the broker status panel keeps every line. */
class AdbOwnerSitesTest {
    private val main = File("src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt").readText()
    private val panel = File("src/main/java/com/diegonmarcos/cloudaccount/ShellBrokerPanel.kt").readText()
    private val perms = File("../../ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/PermsPage.kt").readText()

    @Test fun adbShellPageOpensTheOwnerAndKeepsTheStatusPanel() {
        val line = main.substring(main.indexOf("\"adb-shell\" ->"))
        val block = line.substring(0, line.indexOf("\"drive\" ->"))
        assertTrue(block.contains("ShellBrokerPanelActions.openOwnerAdbPage"))
        assertTrue("status panel kept inline", block.contains("ShellBrokerPanel()"))
        assertTrue(panel.contains("ShellClient.openArmPage(ctx)"))
        assertTrue("panel buttons kept", panel.contains("\"Re-check\"") && panel.contains("Open ADB Shell"))
    }

    @Test fun everyOldAdbIdLandsOnTheOneOwnerOpeningPage() {
        assertEquals("connect" to "adb-shell", HubNav.ADB_PAGE)
        assertEquals(HubNav.ADB_PAGE.let { it.first to it.second }, HubNav.resolve("setup", "adb-shell").let { it.first to it.second })
        assertEquals("connect" to "adb-shell", HubNav.resolve("configs", "adb-shell").let { it.first to it.second })
        assertEquals("connect" to "adb-shell", HubNav.resolve("connect", "adb-shell").let { it.first to it.second })
    }

    @Test fun permsPageOpenAdbShellButtonOpensTheOwner() {
        assertTrue(perms.contains("KitAction(\"Open ADB Shell\", PermsPageTags.NO_CHANNEL) { com.diegonmarcos.superapp.shellclient.ShellClient.openArmPage(ctx) }"))
        assertTrue("no in-app route left", !perms.contains("go(\"setup\", \"adb-shell\")"))
    }
}
