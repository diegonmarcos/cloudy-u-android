package com.diegonmarcos.hardtechbiomed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages: the entries of a declared stack and where each one leads, from the declared sources only. */
class PagesTest {
    private val stacks = """{"one":[
        {"title":"A","subtitle":"a line","site":"Site A"},
        {"title":"B","subtitle":"b line","registry":"9.9.9"},
        {"title":"C","subtitle":"c line","repo":"some-repo","path":"dir/sub"},
        {"title":"D","subtitle":"d line","repo":"some-repo"},
        {"title":"E","subtitle":"e line","planned":true,"note":"Planned: later","site":"Site A"},
        {"kind":"section_title","title":"Group"},
        {"title":"F","subtitle":"f line"}
    ]}"""
    private val targets = """{"sites":{"Site A":"https://a.example/"},"registry_sites":{"9.9.9":"https://b.example/"},"repo_base":"https://host.example/org/"}"""

    @Test fun entriesKeepTheirOrderAndFields() {
        val list = Pages.entries(stacks, "one")
        assertEquals(listOf("A", "B", "C", "D", "E", "Group", "F"), list.map { it.title })
        assertEquals(Pages.KIND_ENTRY, list[0].kind)
        assertEquals(Pages.KIND_SECTION_TITLE, list[5].kind)
        assertTrue(list[4].planned)
        assertEquals("Planned: later", list[4].note)
    }

    @Test fun anUndeclaredSectionHasNoEntries() {
        assertTrue(Pages.entries(stacks, "none").isEmpty())
        assertTrue(Pages.entries("not json", "one").isEmpty())
    }

    @Test fun eachSourceResolvesFromTheBakedTargets() {
        val list = Pages.entries(stacks, "one")
        assertEquals("https://a.example/", Pages.resolve(list[0], targets))
        assertEquals("https://b.example/", Pages.resolve(list[1], targets))
        assertEquals("https://host.example/org/some-repo/tree/main/dir/sub", Pages.resolve(list[2], targets))
        assertEquals("https://host.example/org/some-repo", Pages.resolve(list[3], targets))
    }

    @Test fun plannedOrSourcelessOrUnresolvedIsNull() {
        val list = Pages.entries(stacks, "one")
        assertNull(Pages.resolve(list[4], targets))
        assertNull(Pages.resolve(list[6], targets))
        assertNull(Pages.resolve(list[0], """{"sites":{}}"""))
        assertNull(Pages.resolve(list[2], """{"repo_base":""}"""))
        assertNull(Pages.resolve(list[0], "not json"))
    }
}
