package com.diegonmarcos.superapp.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1372 the view rules every Perms UI in the fleet draws from (PermsView). perms-mutation.yml plants one break per rule. */
class PermsViewTest {
    private fun rt(id: String, s: PermState, a: PermAction? = if (s == PermState.DENIED) PermAction.GRANT else if (s == PermState.PERMANENTLY_DENIED) PermAction.OPEN else null) =
        PermItem(id, id, "why $id", "", PermItemKind.RUNTIME, s, action = a)
    private fun sp(id: String, s: PermState, a: PermAction? = if (s == PermState.DENIED || s == PermState.PERMANENTLY_DENIED) PermAction.OPEN else null) =
        PermItem(id, id, "why $id", "", PermItemKind.SPECIAL, s, action = a)
    private fun hc(id: String, s: PermState) = PermItem(id, id, "", "", PermItemKind.HEALTH, s, action = if (s == PermState.DENIED) PermAction.GRANT else null)
    private fun hp(id: String, s: PermState) = PermItem(id, id, "", "", PermItemKind.HELPER, s, action = if (s != PermState.GRANTED) PermAction.OPEN else null)
    private fun inst(id: String) = PermItem(id, id, "", "check", PermItemKind.INSTALL, PermState.GRANTED)

    // ── the first-run gate ──

    @Test fun `the gate does not exist when everything is granted`() {
        val granted = listOf(rt("n", PermState.GRANTED), sp("i", PermState.GRANTED), inst("x"), hc("h", PermState.GRANTED))
        assertFalse(PermsView.gateShows(granted))
        assertFalse(PermsView.gateShows(emptyList()))
        assertTrue(PermsView.gateShows(granted + rt("c", PermState.DENIED)))
        assertTrue(PermsView.gateShows(granted + sp("s", PermState.PERMANENTLY_DENIED)))
    }

    // ── state chips ──

    @Test fun `each state has its chip and tone`() {
        assertEquals(PermChip.GRANTED, PermsView.chip(PermState.GRANTED))
        assertEquals(PermChip.MISSING, PermsView.chip(PermState.DENIED))
        assertEquals(PermChip.DENIED, PermsView.chip(PermState.PERMANENTLY_DENIED))
        assertEquals(PermChip.NOT_APPLICABLE, PermsView.chip(PermState.NOT_APPLICABLE))
        assertEquals(PermChip.UNKNOWN, PermsView.chip(PermState.UNKNOWN))
        assertEquals(listOf(PermChip.Tone.OK, PermChip.Tone.WARN, PermChip.Tone.BAD, PermChip.Tone.IDLE, PermChip.Tone.IDLE), PermChip.values().map { it.tone })
        assertEquals(listOf("Granted", "Missing", "Denied", "Not applicable", "Check"), PermChip.values().map { it.word })
    }

    @Test fun `the row line is the reason, how to fix a permanent denial, or why it does not apply`() {
        val r = rt("a", PermState.DENIED).copy(detail = "detail")
        assertEquals("why a", PermsView.reasonLine(r))
        assertEquals("Denied twice: allow it in Settings", PermsView.reasonLine(r.copy(state = PermState.PERMANENTLY_DENIED)))
        assertEquals("detail", PermsView.reasonLine(r.copy(state = PermState.NOT_APPLICABLE)))
        assertEquals("detail", PermsView.reasonLine(r.copy(reason = "")))
    }

    // ── progress count ──

    @Test fun `progress counts runtime, special and helper rows and Health Connect once, never install or not applicable`() {
        val items = listOf(rt("A", PermState.GRANTED), rt("B", PermState.DENIED), sp("s", PermState.GRANTED), sp("n", PermState.NOT_APPLICABLE),
            sp("u", PermState.UNKNOWN), hc("R1", PermState.GRANTED), hc("R2", PermState.DENIED), hp("h", PermState.DENIED), inst("i"))
        val p = PermsView.progress(items)
        assertEquals(PermsView.Progress(2, 5), p)
        assertEquals("2 of 5 granted", p.label); assertEquals(3, p.missing); assertEquals(0.4f, p.fraction, 0.001f)
        assertEquals(PermsView.Progress(1, 1), PermsView.progress(listOf(hc("R1", PermState.GRANTED), hc("R2", PermState.GRANTED))))
        assertEquals(1f, PermsView.Progress(0, 0).fraction, 0f)
    }

    @Test fun `a missing row the phone cannot fix is not counted as missing`() {
        val adb = sp("adb", PermState.DENIED, a = null)
        assertEquals(PermsView.Progress(1, 1), PermsView.progress(listOf(rt("A", PermState.GRANTED), adb)))
        assertEquals(PermsView.Progress(1, 2), PermsView.progress(listOf(rt("A", PermState.GRANTED), rt("P", PermState.PERMANENTLY_DENIED))))
    }

    @Test fun `Health Connect is one state, missing while any type is, not applicable without it`() {
        assertEquals(PermState.DENIED, PermsView.healthState(listOf(hc("R1", PermState.GRANTED), hc("R2", PermState.DENIED))))
        assertEquals(PermState.GRANTED, PermsView.healthState(listOf(hc("R1", PermState.GRANTED))))
        assertEquals(PermState.NOT_APPLICABLE, PermsView.healthState(listOf(hc("R1", PermState.NOT_APPLICABLE))))
        assertEquals(PermState.NOT_APPLICABLE, PermsView.healthState(emptyList()))
        assertEquals(PermState.UNKNOWN, PermsView.healthState(listOf(hc("R1", PermState.GRANTED), hc("R2", PermState.UNKNOWN))))
    }

    // ── grouping and folded groups ──

    @Test fun `groups come in page order, empty ones left out`() {
        val g = PermsView.groups(listOf(inst("i"), hp("h", PermState.GRANTED), hc("R", PermState.DENIED), sp("s", PermState.GRANTED), rt("A", PermState.DENIED)))
        assertEquals(listOf(PermsView.RUNTIME, PermsView.SPECIAL, PermsView.HEALTH, PermsView.HELPERS, PermsView.INSTALL), g.map { it.title })
        assertEquals(listOf("A"), g[0].items.map { it.id }); assertEquals(listOf("s"), g[1].items.map { it.id })
        assertEquals(listOf("R"), g[2].items.map { it.id }); assertEquals(listOf("h"), g[3].items.map { it.id }); assertEquals(listOf("i"), g[4].items.map { it.id })
        assertTrue(g.none { it.collapsed })
        assertEquals(listOf(PermsView.RUNTIME), PermsView.groups(listOf(rt("A", PermState.GRANTED))).map { it.title })
    }

    @Test fun `unused rows of any kind sit folded under Not used yet, last`() {
        val unused = sp("U", PermState.DENIED).copy(used = false)
        val g = PermsView.groups(listOf(rt("A", PermState.DENIED), unused))
        assertEquals(listOf(PermsView.RUNTIME, PermsView.NOT_USED), g.map { it.title })
        assertEquals(listOf(unused), g.last().items); assertTrue(g.last().collapsed); assertFalse(g.first().collapsed)
        assertEquals("1", g.last().count)
    }

    @Test fun `Granted at install folds away once it is longer than three rows`() {
        val three = PermsView.groups((1..3).map { inst("i$it") }).single()
        assertFalse(three.collapsed)
        val four = PermsView.groups((1..4).map { inst("i$it") }).single()
        assertTrue(four.collapsed); assertEquals("4", four.count)
    }

    @Test fun `highlighted rows come first inside their group, the group count reads granted of all`() {
        val g = PermsView.groups(listOf(rt("A", PermState.GRANTED), rt("B", PermState.DENIED).copy(highlight = true), rt("C", PermState.GRANTED))).single()
        assertEquals(listOf("B", "A", "C"), g.items.map { it.id })
        assertEquals("2 / 3", g.count)
    }

    @Test fun `Health Connect's row says what it reads and writes, and folds its types`() {
        val h = listOf(hc("r1", PermState.GRANTED).copy(title = "Read steps"), hc("r2", PermState.GRANTED).copy(title = "Read heart rate"),
            hc("w1", PermState.DENIED).copy(title = "Write steps"))
        assertEquals("Reads 2 and writes 1 data types", PermsView.healthLine(h))
        assertEquals("1 data types", PermsView.healthLine(listOf(hc("x", PermState.DENIED))))
        assertEquals("not here", PermsView.healthLine(listOf(hc("x", PermState.NOT_APPLICABLE).copy(detail = "not here"))))
        assertEquals("Show 3 types", PermsView.typesToggle(3, open = false)); assertEquals("Hide 3 types", PermsView.typesToggle(3, open = true))
    }

    // ── first-run sheet, prompt card ──

    @Test fun `the first-run sheet lists every used missing row and Health Connect once`() {
        val items = listOf(rt("A", PermState.DENIED), rt("G", PermState.GRANTED), sp("U", PermState.DENIED).copy(used = false),
            hc("R1", PermState.DENIED), hc("R2", PermState.DENIED), inst("i"), hp("h", PermState.DENIED))
        val m = PermsView.missing(items)
        assertEquals(listOf("A", "h", "R1"), m.map { it.id })
        assertEquals(PermsView.HEALTH, m.last().title)
    }

    @Test fun `the prompt card says what is missing in one sentence`() {
        assertEquals("", PermsView.promptText(emptyList()))
        assertEquals("Notifications is not granted", PermsView.promptText(listOf("Notifications")))
        assertEquals("2 permissions missing: A, B", PermsView.promptText(listOf("A", "B")))
        assertEquals("5 permissions missing: A, B, C and 2 more", PermsView.promptText(listOf("A", "B", "C", "D", "E")))
    }

    // ── catalogue rows, actions and the Grant all walk ──

    private fun entry(kind: PermKind, auto: Boolean = true) = PermEntry("e", "Label", "G", kind, PermSection.RUNTIME, why = "Because.", icon = "camera", autoRequest = auto)

    @Test fun `a catalogue row keeps its label, plain reason and icon, and its button is the dialog while it can be shown`() {
        val i = PermsView.item(entry(PermKind.RUNTIME), PermStatus(PermState.DENIED, "r"), highlight = true)
        assertEquals(PermItem("e", "Label", "Because.", "camera", PermItemKind.RUNTIME, PermState.DENIED, "r", PermAction.GRANT, highlight = true), i)
        assertEquals(PermAction.OPEN, PermsView.action(entry(PermKind.RUNTIME), PermStatus(PermState.PERMANENTLY_DENIED)))
        assertEquals(PermAction.OPEN, PermsView.action(entry(PermKind.RUNTIME, auto = false), PermStatus(PermState.DENIED)))
        assertEquals(PermAction.OPEN, PermsView.action(entry(PermKind.SPECIAL), PermStatus(PermState.DENIED)))
        assertEquals(PermAction.OPEN, PermsView.action(entry(PermKind.SPECIAL), PermStatus(PermState.UNKNOWN)))
        assertNull(PermsView.action(entry(PermKind.RUNTIME), PermStatus(PermState.GRANTED)))
        assertNull(PermsView.action(entry(PermKind.ADB_ONLY), PermStatus(PermState.DENIED)))
        assertEquals(PermItemKind.SPECIAL, PermsView.item(entry(PermKind.APPOPS), PermStatus(PermState.GRANTED)).kind)
    }

    @Test fun `a helper's row names the helper and the work it does`() {
        assertEquals("Cloud Terminal Termux", PermsView.appName("cloud-terminal-termux")); assertEquals("Cloud Termux", PermsView.appName("Cloud Termux"))
        assertEquals("Cloud Sample Engine", PermsView.appName("cloud-lib-sample-engine")); assertEquals("Cloud Lib", PermsView.appName("cloud-lib"))
        val h = HelperNeed("cld.termux", "cloud-termux", listOf("e"), "Runs git.")
        val i = PermsView.helperItem(h, entry(PermKind.SPECIAL), PermStatus(PermState.UNKNOWN, "cannot report"))
        assertEquals("cld.termux:e", i.id); assertEquals("Cloud Termux: Label", i.title); assertEquals("Runs git.", i.reason)
        assertEquals(PermItemKind.HELPER, i.kind); assertEquals(PermAction.OPEN, i.action)
        assertNull(PermsView.helperItem(h, entry(PermKind.SPECIAL), PermStatus(PermState.GRANTED)).action)
    }

    @Test fun `Grant all walks the dialog rows first, then each page, helpers last, never an own unknown row`() {
        val items = listOf(hp("h", PermState.UNKNOWN), sp("s", PermState.DENIED), rt("A", PermState.DENIED), sp("u", PermState.UNKNOWN, a = PermAction.OPEN),
            rt("G", PermState.GRANTED), rt("B", PermState.DENIED), inst("i"), sp("adb", PermState.DENIED, a = null))
        assertEquals(listOf("A", "B", "s", "h"), PermsView.walk(items).map { it.id })
        assertTrue(PermsView.walk(listOf(rt("G", PermState.GRANTED))).isEmpty())
    }
}
