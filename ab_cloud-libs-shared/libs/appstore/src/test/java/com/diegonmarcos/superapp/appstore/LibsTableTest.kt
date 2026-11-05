package com.diegonmarcos.superapp.appstore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1511 the Shared libs table's pure part: rows, groups in declared order, filters, and the real data file's invariants. */
class LibsTableTest {
    private val root = JSONObject("""{"categories":[{"id":"ui","label":"UI"},{"id":"core","label":"Core"},{"id":"empty","label":"Empty"}],
      "build_time":[
        {"id":"ui-kit","ml":false,"class":"gui","category":"ui","ships":"static","description":"d","compiled_by":["ac_cloud-drive","ab_x/keyboard-engines"],"engines":[]},
        {"id":"aaa","ml":false,"class":"mesh","category":"core","ships":"apk","compiled_by":[],"engines":["Cloud-Lib-X"],"primary":"ac_cloud-store"},
        {"id":"bbb","ml":false,"class":"logic","category":"core","ships":"static","compiled_by":["ac_cloud-tasks"],"engines":[]},
        {"id":"orphan","ml":false,"class":"logic","category":"nope","ships":"static","compiled_by":[],"engines":[]},
        {"id":"ml-l-x","ml":true,"class":"engine","category":"ui","ships":"static","compiled_by":[],"engines":[]}]}""")

    @Test fun parseSplitsMlAndNamesConsumers() {
        val rows = LibsTable.parse(root, false)
        assertEquals(listOf("ui-kit", "aaa", "bbb", "orphan"), rows.map { it.id })
        assertEquals(listOf("cloud-drive", "keyboard-engines"), rows[0].consumers)
        assertEquals("gui · 2 apps · static", rows[0].meta)
        assertEquals("mesh · 0 apps · APK", rows[1].meta)
        assertEquals("cloud-store", rows[1].primary)
        assertEquals(listOf("ml-l-x"), LibsTable.parse(root, true).map { it.id })
    }

    @Test fun groupsFollowDeclaredOrderDropEmptyAndTrailOther() {
        val g = LibsTable.groups(root, LibsTable.parse(root, false), 0)
        assertEquals(listOf("UI", "Core", "Other"), g.map { it.label })
        assertEquals(listOf("aaa", "bbb"), g[1].rows.map { it.id })
        assertEquals(listOf("orphan"), g[2].rows.map { it.id })
    }

    @Test fun filtersKeepApkOrStatic() {
        val rows = LibsTable.parse(root, false)
        assertEquals(listOf("aaa"), LibsTable.groups(root, rows, 1).flatMap { it.rows }.map { it.id })
        assertEquals(listOf("ui-kit", "bbb", "orphan"), LibsTable.groups(root, rows, 2).flatMap { it.rows }.map { it.id })
        assertEquals("4 total  ·  1 APK  ·  3 static", LibsTable.summary(rows))
    }

    @Test fun detailNamesTheConsumersOnTap() {
        val d = LibsTable.detail(LibsTable.parse(root, false)[0])
        assertEquals(listOf("d", "Compiled into 2: cloud-drive, keyboard-engines"), d)
        assertTrue(LibsTable.detail(LibsTable.parse(root, false)[1]).contains("Inside Cloud-Lib-X"))
    }

    /** The committed data: every lib has a declared category, and every declared category is drawn. */
    @Test fun realDataHasNoUncategorizedLib() {
        val f = listOf("aa_cloud-superapp/data/lib-consumers.json", "../aa_cloud-superapp/data/lib-consumers.json",
            "../../../aa_cloud-superapp/data/lib-consumers.json").map(::File).firstOrNull { it.exists() } ?: error("lib-consumers.json not found from ${File(".").absolutePath}")
        val real = JSONObject(f.readText())
        val ids = (0 until real.getJSONArray("categories").length()).map { real.getJSONArray("categories").getJSONObject(it).getString("id") }.toSet()
        val libs = real.getJSONArray("build_time")
        for (i in 0 until libs.length()) {
            val e = libs.getJSONObject(i)
            assertTrue("${e.getString("id")} has no declared category", e.getString("category") in ids)
        }
        assertTrue(LibsTable.groups(real, LibsTable.parse(real, false) + LibsTable.parse(real, true), 0).none { it.label == LibsTable.OTHER })
    }
}
