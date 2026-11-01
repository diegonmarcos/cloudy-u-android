package com.diegonmarcos.superapp.devcontrol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #773 — Configs ▸ About's rules, now plain classes behind the Compose page that replaced a 2700-line View fragment.
 *
 *   A1 a section body does not run until it is loaded, runs exactly once, and a throw is shown in its own block
 *   A2 reload() starts the block over: a switch moved, the rows say what is true now
 *   A3 every macro group but the first is preceded by a way back to the Index, and the page ends with one
 *   A4 the copy buffer carries a block's heading and its rows in declared order
 *   A5 the number and time wording
 *   A6 the mesh IP a WireGuard peer is pinged on
 */
class AboutModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `A1 a body is deferred, once-only, and a throw is rendered in the block`() {
        var runs = 0
        val ok = AboutSection("M", "Ok") { runs++; row("k", "v") }
        assertEquals(0, runs)
        assertFalse(ok.started)
        ok.load(scope); ok.load(scope)
        assertEquals(1, runs)
        assertTrue(ok.started)
        assertEquals(1, ok.items.size)

        val bad = AboutSection("M", "Bad") { error("boom") }
        bad.load(scope)
        val warn = bad.items.single() as AboutItem.Small
        assertTrue(warn.text, warn.text.startsWith("⚠ Bad failed to load"))
        assertTrue(warn.text.contains("boom"))
        assertTrue(bad.buf.contains("FAILED: IllegalStateException: boom"))
    }

    @Test
    fun `A2 reload runs the body again from scratch`() {
        var n = 0
        val s = AboutSection("M", "S") { row("count", (++n).toString()) }
        s.load(scope)
        val items = AboutItems(s, scope)
        items.reload()
        assertEquals(2, n)
        assertEquals("2", (s.items.single() as AboutItem.Row).text)
        assertFalse("the buffer was reset, not appended to", s.buf.contains("count: 1"))
        assertTrue(s.buf.contains("count: 2"))
    }

    @Test
    fun `A3 back links close every macro group`() {
        val page = AboutPage(attached = { true })
        page.macro("📱  APP & BUILD"); page.infoSection("A") {}
        page.macro("☁  REPO & RELEASES"); page.infoSection("B") {}
        page.macro("🖥️  DEVICE"); page.copyAllButton()
        val out = page.withBackLinks()
        assertEquals(listOf("APP & BUILD", "REPO & RELEASES", "DEVICE"), page.macros.map { it.label })
        assertEquals("📱", page.macros[0].glyph)
        // before the 2nd and 3rd macro, and at the very end
        assertEquals(3, out.count { it === AboutBlock.BackToIndex })
        assertTrue(out.last() === AboutBlock.BackToIndex)
        assertFalse("none before the first macro", out.first() === AboutBlock.BackToIndex)
        for ((i, b) in out.withIndex()) if (b is AboutBlock.Macro && b.label != "APP & BUILD") {
            assertTrue(out[i - 1] === AboutBlock.BackToIndex)
        }
    }

    @Test
    fun `A4 the buffer is the heading then the rows`() {
        val page = AboutPage(attached = { true })
        page.macro("X"); page.infoSection("Heading") { row("Name", "Value"); small("note") }
        val s = page.sections.single()
        s.load(scope)
        assertEquals("\n## Heading\n  Name: Value\n", s.buf.toString())
    }

    @Test
    fun `A5 sizes and durations`() {
        assertEquals("12 B", AboutFormat.sizeStr(12))
        assertEquals("1.0 KiB", AboutFormat.sizeStr(1024))
        assertEquals("1.50 MiB", AboutFormat.sizeStr(1_572_864))
        assertEquals("2.00 GiB", AboutFormat.sizeStr(2L * 1_073_741_824))
        assertEquals("—", AboutFormat.fmtDuration(-1))
        assertEquals("5 s", AboutFormat.fmtDuration(5_000))
        assertEquals("2 min 03 s", AboutFormat.fmtDuration(123_000))
        assertEquals("1 h 01 min", AboutFormat.fmtDuration(3_660_000))
        assertEquals("—", AboutFormat.fmtSecs(-1))
        assertEquals("59s", AboutFormat.fmtSecs(59))
        assertEquals("2m 05s", AboutFormat.fmtSecs(125))
        assertEquals("1h 02m", AboutFormat.fmtSecs(3720))
        assertEquals("✓ reachable · 12 ms", AboutFormat.pingVerdict(true, 12, 1500))
        assertEquals("✗ no answer (1500 ms)", AboutFormat.pingVerdict(false, 1500, 1500))
    }

    @Test
    fun `A6 a peer is pinged on its slash-32, else a host address, else its endpoint`() {
        assertEquals("10.0.0.7", AboutFormat.meshIpOf("10.0.0.0/24, 10.0.0.7/32", "h:51820"))
        assertEquals("10.9.8.5", AboutFormat.meshIpOf("10.9.8.5/24", "h:51820"))
        // a network address (.0) and the default route are not pingable hosts
        assertEquals("vpn.example.org", AboutFormat.meshIpOf("10.0.0.0/24, 0.0.0.0/0", "vpn.example.org:51820"))
        assertNull(AboutFormat.meshIpOf("", ""))
    }
}
