package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.shellbroker.Allowlist
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1550 the ADB Shell terminal's rules: the declared palette (every snippet allowed for the SuperApp, every group used, the
 * seven requested groups present), destructive confirmation per declared rule, history recall, autocomplete, entries
 * (exit code, duration, status), search, export and the ANSI SGR parser.
 */
class TerminalModelTest {
    private val raw = File("../../ab_cloud-libs-shared/libs/shell-broker/src/main/assets/shell-allowlist.json").readText()
    private val d = TerminalModel.parse(raw)
    private val list = Allowlist.parse(raw)

    @Test fun `the palette is declared, grouped and every snippet is allowed`() {
        assertTrue(listOf("packages", "settings", "appops", "dumpsys", "logcat", "network", "battery").all { it in d.groups })
        assertTrue(d.snippets.size >= 20)
        assertEquals("unique ids", d.snippets.size, d.snippets.map { it.id }.toSet().size)
        for (s in d.snippets) {
            assertTrue("${s.id} in a declared group", s.group in d.groups)
            assertTrue("${s.id} refused: ${s.cmd}", list.decide(ShellBroker.SELF, s.cmd) is Allowlist.Decision.Allow)
            assertNull("a palette snippet is never destructive: ${s.cmd}", TerminalModel.confirmFor(d, s.cmd))
        }
        assertTrue(d.groups.all { g -> d.snippets.any { it.group == g } })
    }

    @Test fun `destructive commands ask first, per declared rule and per segment`() {
        for (c in listOf("pm uninstall com.x", "pm clear com.x", "pm disable-user com.x", "am force-stop com.x", "settings put global a 1",
            "wm density 400", "appops set com.x RUN_IN_BACKGROUND deny", "id; pm clear com.x", "getprop && settings delete global a"))
            assertNotNull("must confirm: $c", TerminalModel.confirmFor(d, c))
        for (c in listOf("pm list packages", "settings get global adb_enabled", "dumpsys battery", "appops set com.x RUN_IN_BACKGROUND allow"))
            assertNull("must not confirm: $c", TerminalModel.confirmFor(d, c))
        assertEquals("uninstall", TerminalModel.confirmFor(d, "pm uninstall com.x")!!.id)
    }

    @Test fun `favourites come first, then the declared groups in order`() {
        val g = TerminalModel.byGroup(d, setOf("bat-status"))
        assertEquals(TerminalModel.FAVOURITES, g.first().first)
        assertEquals(listOf("bat-status"), g.first().second.map { it.id })
        assertEquals(d.groups.filter { x -> d.snippets.any { it.group == x } }, g.drop(1).map { it.first })
    }

    @Test fun `history recalls up and down and keeps the draft`() {
        var h = TerminalModel.History().push("id").push("uptime").push("id")
        assertEquals(listOf("uptime", "id"), h.items)
        var (h2, t) = h.up("draft"); assertEquals("id", t)
        h2.up(t).also { h2 = it.first; t = it.second }; assertEquals("uptime", t)
        h2.up(t).also { h2 = it.first; t = it.second }; assertEquals("oldest stays", "uptime", t)
        h2.down(t).also { h2 = it.first; t = it.second }; assertEquals("id", t)
        h2.down(t).also { h2 = it.first; t = it.second }; assertEquals("draft", t)
        assertEquals(-1, h2.cursor)
        assertEquals("empty history keeps the line", "x", TerminalModel.History().up("x").second)
    }

    @Test fun `autocomplete from history, snippets and suggestions`() {
        val c = TerminalModel.complete("dumpsys b", d, listOf("dumpsys bluetooth_manager"))
        assertEquals("dumpsys bluetooth_manager", c.first())
        assertTrue("dumpsys battery" in c)
        assertTrue(TerminalModel.complete("", d, emptyList()).isEmpty())
        assertTrue(TerminalModel.complete("dumpsys battery", d, emptyList()).none { it == "dumpsys battery" })
    }

    @Test fun `entries carry exit code, duration and status`() {
        val e = TerminalModel.Entry(1, "id", 1_000, 2_250, TerminalModel.State.OK, 0, "uid=2000(shell)\n")
        assertEquals("exit 0", TerminalModel.status(e)); assertEquals("1.3 s", TerminalModel.duration(e.copy(endedAt = 2_300)))
        assertEquals("340 ms", TerminalModel.duration(e.copy(endedAt = 1_340)))
        assertEquals("exit 3", TerminalModel.status(e.copy(state = TerminalModel.State.EXIT, exit = 3)))
        assertEquals(false, TerminalModel.ok(e.copy(state = TerminalModel.State.EXIT, exit = 3)))
        assertEquals("denied", TerminalModel.status(e.copy(state = TerminalModel.State.DENIED)))
        assertEquals("running", TerminalModel.duration(e.copy(endedAt = null)))
        assertNull(TerminalModel.ok(e.copy(state = TerminalModel.State.RUNNING)))
    }

    @Test fun `search and export`() {
        val es = listOf(TerminalModel.Entry(1, "id", 0, 5, TerminalModel.State.OK, 0, "uid=2000\n"),
            TerminalModel.Entry(2, "logcat -d", 10, 20, TerminalModel.State.OK, 0, "\u001B[31mE boom\u001B[0m\nI fine\n"))
        assertEquals(listOf(2L), TerminalModel.search(es, "BOOM").map { it.id })
        assertEquals("blank query keeps all", 2, TerminalModel.search(es, "").size)
        assertEquals(1, TerminalModel.hits(es, "boom"))
        val x = TerminalModel.export("ADB Shell", es) { "t$it" }
        assertTrue(x.contains("[t10] $ logcat -d   (exit 0, 10 ms)"))
        assertTrue("ANSI stripped", x.contains("E boom") && !x.contains("\u001B"))
    }

    @Test fun `ANSI SGR spans`() {
        val s = TerminalModel.spans("a\u001B[1;31mred\u001B[0m b \u001B[92mgreen\u001B[39m c\u001B[2K")
        assertEquals(listOf("a", "red", " b ", "green", " c"), s.map { it.text })
        assertEquals(1, s[1].fg); assertTrue(s[1].bold)
        assertNull(s[2].fg); assertFalse(s[2].bold)
        assertEquals(10, s[3].fg)
        assertNull(s[4].fg)
        assertEquals("plain", TerminalModel.spans("plain").single().text)
        assertEquals("redx", TerminalModel.stripAnsi("\u001B[31mred\u001B[0mx"))
    }

    @Test fun `privilege line names uid and route`() {
        assertEquals("shell (uid 2000) via embedded-adb", TerminalModel.privilege("2000", "embedded-adb"))
        assertEquals("root (uid 0) via local-server", TerminalModel.privilege("0", "local-server"))
        assertEquals("broker via none", TerminalModel.privilege(null, "none"))
    }
}
