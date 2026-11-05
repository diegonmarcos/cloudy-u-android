package com.diegonmarcos.cloudlib.jsonviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTreeTest {
    private val sample = """{"name":"cloud","n":3,"ok":true,"none":null,"tags":["a","b"],"nested":{"deep":{"x":1.5},"a b":"sp"},"empty":{}}"""
    private val tree = JsonTree.parse(sample)!!

    @Test fun `not json parses to null`() {
        assertNull(JsonTree.parse("{oops"))
        assertNull(JsonTree.parse(""))
    }

    @Test fun `the first level is open by default, deeper levels are closed`() {
        val rows = tree.rows(tree.defaultExpanded())
        assertEquals(listOf("$", "$.name", "$.n", "$.ok", "$.none", "$.tags", "$.nested", "$.empty"), rows.map { it.path })
        assertEquals("{7}", rows[0].text)
        assertEquals("[2]", rows.first { it.path == "$.tags" }.text)
        assertTrue(rows.first { it.path == "$.tags" }.expandable && !rows.first { it.path == "$.tags" }.expanded)
    }

    @Test fun `opening a node shows its children, closing hides the whole branch`() {
        val open = tree.defaultExpanded() + "$.nested" + "$.nested.deep"
        assertEquals(listOf("$.nested", "$.nested.deep", "$.nested.deep.x", "$.nested[\"a b\"]"), tree.rows(open).map { it.path }.filter { it.startsWith("$.nested") })
        val closedParent = open - "$.nested"
        assertTrue(tree.rows(closedParent).none { it.path.startsWith("$.nested.") })
    }

    @Test fun `kinds and leaf text`() {
        val rows = tree.rows(tree.defaultExpanded() + "$.tags").associateBy { it.path }
        assertEquals(JsonKind.String, rows["$.name"]!!.kind); assertEquals("\"cloud\"", rows["$.name"]!!.text)
        assertEquals(JsonKind.Number, rows["$.n"]!!.kind)
        assertEquals(JsonKind.Boolean, rows["$.ok"]!!.kind)
        assertEquals(JsonKind.Null, rows["$.none"]!!.kind)
        assertEquals("$.tags[1]", rows["$.tags[1]"]!!.path)
        assertEquals(2, rows["$.tags[1]"]!!.depth)
    }

    @Test fun `search keeps the matches with their parents, everything open`() {
        val rows = tree.rows(emptySet(), "deep")
        assertEquals(listOf("$", "$.nested", "$.nested.deep"), rows.map { it.path })
        val byValue = tree.rows(emptySet(), "SP")
        assertEquals(listOf("$", "$.nested", "$.nested[\"a b\"]"), byValue.map { it.path })
        assertEquals(emptyList<String>(), tree.rows(emptySet(), "zzz-nothing").map { it.path })
    }

    @Test fun `copy path uses dots, brackets and quoted keys`() {
        assertEquals("$.a", JsonTree.child("$", "a"))
        assertEquals("$[\"a b\"]", JsonTree.child("$", "a b"))
        assertEquals("$[\"1st\"]", JsonTree.child("$", "1st"))
        assertEquals("$[\"q\\\"t\"]", JsonTree.child("$", "q\"t"))
        assertEquals("$.tags[0]", tree.pathOf("$.tags[0]"))
    }

    @Test fun `copy value gives the json of a container and the plain text of a string`() {
        assertEquals("[\n  \"a\",\n  \"b\"\n]", tree.valueAt("$.tags"))
        assertEquals("\"cloud\"", tree.valueAt("$.name"))
        assertEquals("cloud", tree.plainAt("$.name"))
        assertEquals("3", tree.plainAt("$.n"))
        assertEquals("{}", tree.valueAt("$.empty"))
        assertNull(tree.valueAt("$.missing"))
    }
}
