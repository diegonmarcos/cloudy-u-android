package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1256/#1315 the filters are DATA: an option's meaning is its declared predicates, filters combine, a filter's default is what an
 * empty selection means (archived = hidden), counts respect the other filters, the sort persists. The owner-visible bugs of the
 * audit are pinned: a CONFLICT row is "Needs you" (never hidden by Dirty), a DIVERGED row is both Ahead and Behind, a
 * light-checked CLEAN row is never "In sync". Mutations proven red: name equality instead of `kinds` (conflict test), ignoring
 * `level` (light clean test), counting over every row instead of the other filters' rows (counts test), default "all" for
 * archived (archived hidden test).
 */
class RepoFiltersTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val page by lazy { Declarations.parseGitPage(Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sync"]!!.jsonObject["git"]) }
    private val filters get() = page.filters

    private data class R(val name: String, val f: RepoFilters.Facts)
    private val rows = listOf(
        R("a", RepoFilters.Facts(true, RepoStatus.Kind.CLEAN, false, false, true, level = "full", name = "a")),
        R("b", RepoFilters.Facts(true, RepoStatus.Kind.DIRTY, true, true, false, level = "full", name = "b")),
        R("c", RepoFilters.Facts(false, RepoStatus.Kind.NOT_CLONED, null, false, null, name = "c")),
        R("d", RepoFilters.Facts(true, RepoStatus.Kind.CONFLICT, false, false, false, level = "full", name = "d")),
        R("e", RepoFilters.Facts(true, RepoStatus.Kind.DIVERGED, false, false, false, level = "full", name = "e")),
        R("f", RepoFilters.Facts(true, RepoStatus.Kind.CLEAN, false, false, false, level = "light", name = "f")),
        R("z", RepoFilters.Facts(true, RepoStatus.Kind.CLEAN, false, false, false, archived = true, level = "full", name = "z-archived_old")),
    )
    private fun pick(sel: Map<String, String>, q: String = "") = RepoFilters.apply(rows, filters, sel, q) { it.f }.map { it.name }

    @Test fun `every declared option has a meaning and the status options name real kinds`() {
        assertTrue(filters.isNotEmpty())
        val kinds = RepoStatus.Kind.entries.map { it.name.lowercase() }
        filters.forEach { f ->
            assertTrue("${f.id} has its default", f.options.any { it.id == f.default })
            // an option with no predicate matches everything: allowed only as `all`, or as the "everything" option of a filter whose default is not `all` (archived: hidden / shown)
            f.options.filter { it.id != RepoFilters.ALL && f.default == RepoFilters.ALL }.forEach { o ->
                assertTrue("${f.id}/${o.id} declares a predicate", o.kinds.isNotEmpty() || o.cloned != null || o.lfs != null || o.private != null || o.hub != null || o.archived != null || o.level.isNotBlank())
                o.kinds.forEach { k -> assertTrue("$k is a Kind", k in kinds) }
            }
        }
        assertEquals(listOf("all", "cloned", "not_cloned"), filters.first { it.id == "cloned" }.options.map { it.id })
        assertFalse("no unlisted filter: the Unlisted section is always its own table", filters.any { it.id == "unlisted" })
    }

    @Test fun `filters combine, the search narrows, and archived rows are hidden by default`() {
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), pick(emptyMap()))
        assertEquals(listOf("a", "b", "d", "e", "f"), pick(mapOf("cloned" to "cloned")))
        assertEquals(listOf("c"), pick(mapOf("cloned" to "not_cloned")))
        assertEquals(listOf("b"), pick(mapOf("lfs" to "lfs")))
        assertEquals(listOf("a", "c", "d", "e", "f"), pick(mapOf("visibility" to "public")))
        assertEquals(listOf("a"), pick(mapOf("hub" to "hub")))
        assertEquals(listOf("b", "c", "d", "e", "f"), pick(mapOf("hub" to "non_hub")))
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "z"), pick(mapOf("archived" to "shown")))
        assertEquals(listOf("a"), pick(emptyMap(), "a"))
        assertEquals(listOf("z"), pick(mapOf("archived" to "shown"), "ARCHIVED"))
    }

    @Test fun `a conflict is needs-you, a diverged row is ahead and behind, a light clean is not in sync`() {
        assertEquals(listOf("b", "d", "e"), pick(mapOf("status" to "needs_you")))
        assertEquals(listOf("e"), pick(mapOf("status" to "ahead")))
        assertEquals(listOf("e"), pick(mapOf("status" to "behind")))
        assertEquals("the light-checked f is not in sync", listOf("a"), pick(mapOf("status" to "in_sync")))
    }

    @Test fun `counts respect the other filters and the search`() {
        val status = filters.first { it.id == "status" }
        val all = RepoFilters.counts(rows, filters, emptyMap(), "", status) { it.f }
        assertEquals(6, all["all"]); assertEquals(3, all["needs_you"]); assertEquals(1, all["in_sync"]); assertEquals(1, all["ahead"])
        val onlyLfs = RepoFilters.counts(rows, filters, mapOf("lfs" to "lfs"), "", status) { it.f }
        assertEquals(1, onlyLfs["all"]); assertEquals(1, onlyLfs["needs_you"]); assertEquals(0, onlyLfs["in_sync"])
        val cloned = filters.first { it.id == "cloned" }
        assertEquals(mapOf("all" to 1, "cloned" to 1, "not_cloned" to 0), RepoFilters.counts(rows, filters, emptyMap(), "d", cloned) { it.f })
    }

    @Test fun `active counts the filters off their default, toggle goes back to it`() {
        val archived = filters.first { it.id == "archived" }
        assertEquals(0, RepoFilters.active(filters, emptyMap()))
        assertEquals(2, RepoFilters.active(filters, mapOf("cloned" to "cloned", "archived" to "shown")))
        assertEquals(0, RepoFilters.active(filters, mapOf("archived" to "hidden", "cloned" to "all")))
        assertEquals("shown", RepoFilters.toggle(archived, emptyMap(), "shown"))
        assertEquals("hidden", RepoFilters.toggle(archived, mapOf("archived" to "shown"), "shown"))
        assertEquals("hidden", RepoFilters.selected(archived, mapOf("archived" to "bogus")))
    }

    @Test fun `the selection and the sort persist as strings and come back`() {
        val sel = mapOf("cloned" to "cloned", "status" to "ahead", "lfs" to RepoFilters.ALL)
        assertEquals(mapOf("cloned" to "cloned", "status" to "ahead"), RepoFilters.decode(RepoFilters.encode(sel)))
        for (c in RepoColumn.entries) for (asc in listOf(true, false)) assertEquals(c to asc, RepoFilters.decodeSort(RepoFilters.encodeSort(c, asc)))
        assertEquals(RepoTable.DEFAULT_SORT to true, RepoFilters.decodeSort("NOPE:desc"))
        assertEquals(RepoTable.DEFAULT_SORT to true, RepoFilters.decodeSort(null))
    }

    private fun row(name: String, last: Long?) = RepoTableRow(name, false, null, null, null, null, null, null, lastCommitMs = last)

    @Test fun `Last commit is a sortable column, unknown sinks, asc and desc`() {
        assertEquals(RepoColumn.LAST_COMMIT, RepoColumn.entries.last())
        val l = listOf(row("x", 3000), row("y", null), row("z", 1000))
        assertEquals(listOf("z", "x", "y"), RepoTable.sort(l, RepoColumn.LAST_COMMIT, true) { it }.map { it.name })
        assertEquals(listOf("x", "z", "y"), RepoTable.sort(l, RepoColumn.LAST_COMMIT, false) { it }.map { it.name })
        assertEquals(RepoColumn.LAST_COMMIT to false, RepoTable.tap(RepoColumn.LAST_COMMIT, RepoColumn.LAST_COMMIT, true))
    }

    @Test fun `every column sorts both ways without losing a row`() {
        val l = listOf(row("b", 2), row("a", 1), row("c", null))
        for (c in RepoColumn.entries) for (asc in listOf(true, false)) assertEquals("$c $asc", setOf("a", "b", "c"), RepoTable.sort(l, c, asc) { it }.map { it.name }.toSet())
    }

    @Test fun `Last commit is the clone's HEAD when cloned, else the forge's pushed_at, printed relative`() {
        assertEquals(5L, RepoTable.lastCommit(true, 5, 9))
        assertEquals(9L, RepoTable.lastCommit(false, 5, 9))
        assertEquals(9L, RepoTable.lastCommit(true, null, 9))
        assertEquals(null, RepoTable.lastCommit(false, null, 0))
        val now = 10_000_000_000L
        assertEquals("—", RepoTable.age(null, now))
        assertEquals("now", RepoTable.age(now - 5_000, now))
        assertEquals("5m", RepoTable.age(now - 5 * 60_000, now))
        assertEquals("3h", RepoTable.age(now - 3 * 3_600_000, now))
        assertEquals("2d", RepoTable.age(now - 2 * 86_400_000L, now))
        val r = RepoTable.rowOf("n", false, false, null, RepoRemoteStats.Stats(1, 1, 1, false, "main", 1, pushedAtMs = 777))
        assertEquals(777L, r.lastCommitMs)
    }

    @Test fun `pushed_at is read from the repository answer and kept in the cache`() {
        val info = RepoRemoteStats.parseRepo("""{"size":3,"private":false,"default_branch":"main","pushed_at":"2026-10-01T00:00:00Z"}""")!!
        assertEquals(java.time.Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), info.pushedAtMs)
        val m = mapOf("o/r" to RepoRemoteStats.Stats(1, 2, 3, false, "main", 4, 5))
        assertEquals(m, RepoRemoteStats.Cache.decode(RepoRemoteStats.Cache.encode(m)))
        assertEquals(0L, RepoRemoteStats.isoMs("garbage"))
    }

    @Test fun `Activity Logs is declared and bounded`() {
        assertTrue(page.activityMax in 1..1000)
    }
}
