package com.diegonmarcos.cloudkgstore

import com.diegonmarcos.cloudkgstore.ui.meEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Which html of a runtime clone the Me tab serves. */
class MeEntryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theDeclaredEntryWins() {
        val d = tmp.newFolder(); java.io.File(d, "index.html").writeText("a"); java.io.File(d, "a.html").writeText("b")
        assertEquals("index.html", meEntry(d, "index.html")!!.name)
    }

    @Test fun withoutTheEntryTheFirstRootHtmlIsServed() {
        val d = tmp.newFolder(); java.io.File(d, "topics.md").writeText("x"); java.io.File(d, "b.html").writeText("b"); java.io.File(d, "a.htm").writeText("a")
        assertEquals("a.htm", meEntry(d, "index.html")!!.name)
    }

    @Test fun aTraversingEntryIsIgnoredAndAnEmptyCloneServesNothing() {
        val d = tmp.newFolder()
        assertNull(meEntry(d, "../x.html"))
        java.io.File(d, ".git").mkdir(); java.io.File(d, ".git/x.html").writeText("x")
        assertNull(meEntry(d, ""))
    }
}
