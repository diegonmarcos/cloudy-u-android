package com.diegonmarcos.superapp.perms

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1372 the first-run gate is a full-height page whose content clears the system bars: FirstRunGate draws in a Scaffold
 * and its content takes the Scaffold's insets (`padding(bars)`), so the title never sits under the status bar, and its
 * actions sit in a BottomAppBar, which clears the navigation bar. The shell top-inset guard bans naming insets in a lib,
 * so the Scaffold is the one way in. perms-mutation.yml drops the padding and expects this test RED.
 */
class PermsGateLayoutTest {
    private val src = listOf("src/main/java/com/diegonmarcos/superapp/perms/PermsUi.kt", "libs/perms/src/main/java/com/diegonmarcos/superapp/perms/PermsUi.kt")
        .map(::File).first { it.isFile }.readText()
    private val gate = src.substringAfter("internal fun FirstRunGate(").substringBefore("\n}\n")

    @Test fun `the gate is a full-height Scaffold page`() {
        assertTrue("FirstRunGate is not found", gate.isNotEmpty() && gate.length < src.length)
        assertTrue("the gate is not a Scaffold", gate.contains("Scaffold("))
        // #1546 the Scaffold's own modifier fills the screen and carries the SHEET tag (through permsTag, the tag namespace)
        val scaffold = gate.substringAfter("Scaffold(").substringBefore("\n")
        assertTrue("the gate does not fill the screen", Regex("""modifier\s*=\s*Modifier\.fillMaxSize\(\)\.permsTag\(PermsTags\.SHEET\)""").containsMatchIn(scaffold) ||
            Regex("""^\s*Modifier\.fillMaxSize\(\)\.permsTag\(PermsTags\.SHEET\)""").containsMatchIn(gate.substringAfter("Scaffold(")))
    }

    @Test fun `the sheet tag is the SHEET tag under the default namespace and moves with a host namespace`() {
        assertEquals(PermsTags.SHEET, permsTagIn("perms", PermsTags.SHEET))
        assertEquals("perms-app:sheet", permsTagIn("perms-app", PermsTags.SHEET))
    }

    @Test fun `the gate's content applies the system bar insets`() {
        assertTrue("the gate content ignores the Scaffold insets (title under the status bar)", gate.contains("Column(Modifier.padding(bars)"))
        assertTrue("the gate actions are not in a BottomAppBar (navigation bar inset)", gate.contains("BottomAppBar"))
    }
}
