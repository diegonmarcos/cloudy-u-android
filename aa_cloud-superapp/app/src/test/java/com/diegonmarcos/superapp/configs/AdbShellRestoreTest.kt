package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.adbdebug.ChannelAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1550 regression: #1522 replaced the SuperApp's Configs > ADB Shell with six broker sections and the full channel console
 * (libs:shizuku-adb-debug-tools AdbShellPage) stopped being reachable. Every element of that console is asserted here, and so is
 * every #1522 addition, so neither can be dropped again.
 */
class AdbShellRestoreTest {
    private fun code(f: File) = f.readText().replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("//[^\\n]*"), "")
    private val superapp = File("src/main/java/com/diegonmarcos/superapp/configs")
    private val lib = File("../../ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/AdbShellPage.kt")

    @Test fun `the SuperApp page hosts the full channel console`() {
        val page = code(File(superapp, "AdbShellBrokerPage.kt"))
        assertTrue("the stateful page passes AdbShellPage as the console", page.contains("console = { AdbShellPage() }"))
        assertTrue("the CONSOLE section draws it", page.contains("AdbShellModel.Section.CONSOLE ->") && page.contains("console()"))
        val sec = AdbShellModel.ORDER.first { it.id == "console" }
        assertTrue("the console starts open", !sec.collapsed)
    }

    @Test fun `every console section is present`() {
        val t = code(lib)
        for (s in listOf("1  STATUS", "2  CONNECT", "3  ACTIVE CONNECTION", "4  LOGS", "5  DECLARED NEEDS", "6  SETUP", "7  KEEP WIRELESS DEBUGGING ON"))
            assertTrue("console section $s", t.contains("Section(\"$s\")"))
    }

    @Test fun `every console control and fact is present`() {
        val t = code(lib)
        val need = mapOf(
            "header connection name" to "Connection name: ",
            "header live round-trip line" to "Live check (echo round trip",
            "header wireless debugging button" to "DenseButton(\"Wireless debugging\"",
            "header refresh" to "DenseButton(\"Refresh\"",
            "layer table" to "ChannelState.rows(",
            "layer probe time" to "Dim(\"Probed\"",
            "channel mode bar" to "ChannelModeBar {",
            "server launcher choice" to "LaunchVia.values()",
            "connect method plan" to "ConnectPlan.methods(",
            "connect step runner" to "for (s in steps)",
            "connect outcome" to "ConnectStatus.WAITING_FOR_USER",
            "keep armed toggle" to "Keep armed: ON",
            "disabled-action reasons" to "ChannelActions.enablement(",
            "test output" to "testOut",
            "log copy" to "DenseButton(\"Copy\"",
            "log clear" to "DenseButton(\"Clear\"",
            "declared needs" to "DeclaredNeeds.current()",
            "own local server line" to "own local server 127.0.0.1:",
            "setup checklist" to "SetupChecklist.items(",
            "keeper chips" to "WdKeeperStatus.chips(",
            "keeper permission fix" to "Fix in Perms",
            "keeper battery prompt" to "Battery: allow unrestricted",
            "keeper change table" to "\"Re-armed by\"",
        )
        val missing = need.filterValues { !t.contains(it) }.keys
        assertTrue("console elements missing: $missing", missing.isEmpty())
    }

    @Test fun `every channel action is offered`() {
        assertEquals(listOf("Disconnect", "Reconnect", "Restart server", "Stop server", "Test round-trip"), ChannelAction.values().map { it.label })
        val t = code(lib)
        for (a in ChannelAction.values()) assertTrue("action ${a.name} is wired", t.contains("ChannelAction.${a.name} ->"))
    }

    @Test fun `the 1522 additions stay`() {
        assertEquals(listOf("status", "actions", "keeper", "callers", "audit", "keeper_log"), AdbShellModel.ORDER.map { it.id }.filter { it != "console" })
        val page = code(File(superapp, "AdbShellBrokerPage.kt"))
        for (s in listOf("\"Re-arm\"", "\"Pair\"", "\"Wireless debugging settings\"", "\"Test now\"", "\"WRITE_SECURE_SETTINGS\"", "\"Last re-arm\"", "adb:caller", "adb:audit", "adb:keeper_log"))
            assertTrue("#1522 element $s", page.contains(s))
    }
}
