package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.perms.PermCatalogue
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.PermStatus
import com.diegonmarcos.superapp.perms.PermsActions
import com.diegonmarcos.superapp.perms.PermsGrant
import com.diegonmarcos.superapp.perms.PermsView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1688 every ADB-path permission has Run on ADB + Copy command, both directions from the one declaration. */
class AdbToggleTest {
    private val cat = PermCatalogue.parse(File("../perms/src/main/assets/perms-catalogue.json").readText())
    private val pkg = "com.diegonmarcos.superapp"
    private val comps = mapOf("notification_listener" to "$pkg/.L", "accessibility" to "$pkg/.A", "device_admin" to "$pkg/.D")

    private class Fake(val up: Boolean, val answer: String? = "") : PermsGrant.GrantChannel {
        val ran = ArrayList<String>()
        override fun up() = up
        override fun exec(command: String): String? { ran += command; return answer }
    }

    @Test fun `every adb-path row has a toggle whose commands match the declaration both ways`() {
        var n = 0
        for (e in cat.entries) {
            val item = PermsView.item(e, PermStatus(PermState.DENIED))
            val en = (PermsActions.commands(pkg, item, comps) as? PermsActions.Grant.Shell)?.commands.orEmpty()
            val dis = (PermsActions.resetCommands(pkg, item, comps) as? PermsActions.Grant.Shell)?.commands.orEmpty()
            if (en.isEmpty() && dis.isEmpty()) continue
            n++
            val off = AdbToggle.forPerm(pkg, e, PermStatus(PermState.DENIED), comps)
            val on = AdbToggle.forPerm(pkg, e, PermStatus(PermState.GRANTED), comps)
            assertNotNull(e.id, off); assertNotNull(e.id, on)
            assertEquals(AdbToggle.Direction.ENABLE, off!!.direction); assertEquals(en, off.commands)
            assertEquals(AdbToggle.Direction.DISABLE, on!!.direction); assertEquals(dis, on.commands)
            if (en.isNotEmpty()) assertEquals(en.joinToString("\n") { "adb shell $it" }, off.copyText())
        }
        assertTrue("catalogue has adb-path rows", n > 5)
    }

    @Test fun `run re-reads the state and reports`() {
        val e = cat.entries.first { it.id == "write_secure_settings" }
        val t = AdbToggle.forPerm(pkg, e, PermStatus(PermState.DENIED))!!
        val ch = Fake(true)
        val ok = AdbToggle.run(t, ch) { true }
        assertTrue(ok.message, ok.ok); assertEquals(t.enable, ch.ran)
        val stuck = AdbToggle.run(t, Fake(true)) { false }
        assertFalse(stuck.ok); assertTrue(stuck.message.contains("did not follow"))
    }

    @Test fun `channel unavailable is a reason, never silent`() {
        val e = cat.entries.first { it.id == "write_secure_settings" }
        val t = AdbToggle.forPerm(pkg, e, PermStatus(PermState.GRANTED))!!
        val ch = Fake(false)
        val r = AdbToggle.run(t, ch) { null }
        assertFalse(r.ok); assertTrue(r.channelDown); assertTrue(r.message.contains("ADB Shell")); assertTrue(ch.ran.isEmpty())
    }

    @Test fun `a refused command stops and says why`() {
        val e = cat.entries.first { it.id == "write_secure_settings" }
        val t = AdbToggle.forPerm(pkg, e, PermStatus(PermState.DENIED))!!
        val r = AdbToggle.run(t, Fake(true, "error: refused by the shell broker (x)")) { true }
        assertFalse(r.ok); assertTrue(r.message.contains("refused"))
    }

    @Test fun `not applicable rows get none`() {
        assertEquals(null, AdbToggle.forPerm(pkg, cat.entries.first(), PermStatus(PermState.NOT_APPLICABLE)))
    }
}
