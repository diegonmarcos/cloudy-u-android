package com.diegonmarcos.superapp.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat sessions' named groups: create, assign, collapse, rename, delete, and that all of it survives a reload. */
class AiSessionGroupsTest {
    private var disk = ""
    private fun open() = AiSessionGroups({ disk }, { disk = it })

    @Test fun groupsAreCreatedAssignedCollapsedAndPersisted() {
        val g = open()
        val work = g.add("Work")!!
        val home = g.add(" Home ")!!
        assertEquals(listOf("Work", "Home"), g.groups.map { it.name })
        assertNull(g.add("   "))
        g.assign("s1", work); g.assign("s2", home); g.assign("s3", work)
        g.toggle(home)
        val again = open()
        assertEquals(listOf("Work", "Home"), again.groups.map { it.name })
        assertEquals(work, again.groupOf("s1")); assertEquals(work, again.groupOf("s3")); assertEquals(home, again.groupOf("s2"))
        assertNull(again.groupOf("s9"))
        assertEquals(listOf(false, true), again.groups.map { it.collapsed })
    }

    @Test fun movingAndRemovingFallBackToUngrouped() {
        val g = open()
        val a = g.add("A")!!; val b = g.add("B")!!
        g.assign("s", a); g.assign("s", b)
        assertEquals(b, g.groupOf("s"))
        g.assign("s", null)
        assertNull(g.groupOf("s"))
        g.assign("t", a); g.remove(a)
        assertNull(open().groupOf("t"))
        g.rename(b, "Bee")
        assertEquals("Bee", open().groups.single().name)
        assertTrue(open().groups.none { it.id == a })
        // a deleted group's id is never reused for a live one
        assertEquals("g3", g.add("C"))
    }

    @Test fun archivedChatsAreRememberedToo() {
        val g = open()
        g.archive("s1", true); g.archive("s2", true); g.archive("s2", false)
        assertTrue(open().isArchived("s1")); assertTrue(!open().isArchived("s2"))
    }

    @Test fun garbageOnDiskIsNoGroups() {
        disk = "not json"
        assertTrue(open().groups.isEmpty())
    }
}
