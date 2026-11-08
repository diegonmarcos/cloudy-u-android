package com.diegonmarcos.superapp.appstore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #642 The Commits / PRs / CI-CD tables: the Entry → columns mapping and the pure sort (Time always secondary), group
 * (All / By Repo) and filter logic over it. No device — [StoreFeedTable] holds no Android. */
class StoreFeedTableTest {
    private val feeds = FeedViewer.parse(JSONObject(File("src/main/assets/appstore-feeds.json").readText())).associateBy { it.id }
    private val commits = feeds.getValue("commits")
    private val prs = feeds.getValue("prs")
    private val cicd = feeds.getValue("cicd")

    private fun e(ref: String, title: String, subtitle: String, state: String = "", link: String = "l") =
        FeedViewer.Entry(ref, title, subtitle, link, state)

    // ── columns ───────────────────────────────────────────────────────────────

    @Test fun eachFeedCarriesRepoTimeNameDescriptionAndItsOwnStatusColumn() {
        assertEquals(listOf("Repo", "Time", "Name", "Description", "Author"), StoreFeedTable.columns(commits).map { it.label })
        assertEquals("State", StoreFeedTable.columns(prs).last().label)
        assertEquals("Result", StoreFeedTable.columns(cicd).last().label)
        // Time is the one TIME-kind column on every feed.
        assertEquals(1, StoreFeedTable.columns(cicd).count { it.kind == StoreFeedTable.Kind.TIME })
    }

    @Test fun theRepoColumnComesFromTheFeedUrlSinceTheItemsDoNotRepeatIt() {
        assertEquals("diegonmarcos/cloudy-u-android", StoreFeedTable.repoOf(commits))
    }

    // ── row mapping ─────────────────────────────────────────────────────────

    @Test fun aCommitRowMapsAuthorNameBodyAndTimeFromTheEntry() {
        val r = StoreFeedTable.row(commits, e("abcdef0", "feat: thing\n\nthe body line", "Ann  ·  2026-11-07T10:00:00Z"))
        assertEquals("diegonmarcos/cloudy-u-android", r.repo)
        assertEquals("2026-11-07T10:00:00Z", r.time)
        assertEquals("feat: thing", r.name)
        assertEquals("the body line", r.desc)
        assertEquals("Ann", r.status)   // Author
    }

    @Test fun aPrRowTakesStateFromTheEntryAndHasNoDescription() {
        val r = StoreFeedTable.row(prs, e("#7", "add tables", "open  ·  bob  ·  2026-11-06T09:00:00Z", state = "open"))
        assertEquals("open", r.status)
        assertEquals("", r.desc)        // the GitHub PR body is not in the declared template: honestly blank
        assertEquals("2026-11-06T09:00:00Z", r.time)
    }

    @Test fun aRunningCicdRowIsResultRunningNotFailed() {
        val done = StoreFeedTable.row(cicd, e("sha", "ship", "completed  ·  failure  ·  2026-11-05", state = "failure"))
        val run = StoreFeedTable.row(cicd, e("sha", "ship", "in_progress  ·    ·  2026-11-05", state = ""))
        assertEquals("failure", done.status)
        assertEquals("running", run.status)
    }

    // ── sort: Time is ALWAYS the secondary key ───────────────────────────────

    private fun rows() = listOf(
        StoreFeedTable.Row("org/b", "2026-01-01T00:00:00Z", "n1", "", "Zoe", "l", ""),
        StoreFeedTable.Row("org/a", "2026-03-01T00:00:00Z", "n2", "", "Zoe", "l", ""),
        StoreFeedTable.Row("org/a", "2026-02-01T00:00:00Z", "n3", "", "Amy", "l", ""),
    )

    @Test fun defaultSortIsTimeDescending() {
        val out = StoreFeedTable.sort(rows(), StoreFeedTable.columns(commits)[1], descending = true)
        assertEquals(listOf("n2", "n3", "n1"), out.map { it.name })
    }

    @Test fun sortingByRepoFallsBackToTimeDescendingWithinEachRepo() {
        val repoCol = StoreFeedTable.columns(commits)[0]
        val out = StoreFeedTable.sort(rows(), repoCol, descending = false)
        // org/a before org/b; inside org/a the newer (n2) before the older (n3)
        assertEquals(listOf("n2", "n3", "n1"), out.map { it.name })
    }

    @Test fun sortingByAuthorThenTimeDescending() {
        val authorCol = StoreFeedTable.columns(commits).last()   // Author
        val out = StoreFeedTable.sort(rows(), authorCol, descending = false)
        // Amy (n3) first, then the two Zoe rows newest-first (n2 then n1)
        assertEquals(listOf("n3", "n2", "n1"), out.map { it.name })
    }

    @Test fun reTapTogglesDirectionButTimeStaysTheSecondaryKey() {
        val authorCol = StoreFeedTable.columns(commits).last()
        val out = StoreFeedTable.sort(rows(), authorCol, descending = true)
        // Zoe first (both, newest-first), then Amy
        assertEquals(listOf("n2", "n1", "n3"), out.map { it.name })
    }

    @Test fun aBlankCellSortsLastInEitherDirection() {
        val rs = listOf(
            StoreFeedTable.Row("org/a", "2026-02-01T00:00:00Z", "has", "", "Amy", "l", ""),
            StoreFeedTable.Row("org/a", "2026-01-01T00:00:00Z", "none", "", "", "l", ""),
        )
        val col = StoreFeedTable.columns(commits).last()
        assertEquals("none", StoreFeedTable.sort(rs, col, descending = true).last().name)
        assertEquals("none", StoreFeedTable.sort(rs, col, descending = false).last().name)
    }

    // ── group: All vs By Repo ────────────────────────────────────────────────

    @Test fun groupAllIsOneTableAndByRepoIsOnePerRepo() {
        val sorted = StoreFeedTable.sort(rows(), StoreFeedTable.columns(commits)[0], descending = false)
        assertEquals(1, StoreFeedTable.group(sorted, byRepo = false).size)
        val byRepo = StoreFeedTable.group(sorted, byRepo = true)
        assertEquals(listOf("org/a", "org/b"), byRepo.map { it.first })
        assertEquals(listOf("n2", "n3"), byRepo.first().second.map { it.name })   // org/a, time desc
    }

    // ── filter: a per-feed category ──────────────────────────────────────────

    @Test fun prsFilterOnOpenAndClosed() {
        val rs = listOf(
            StoreFeedTable.row(prs, e("#1", "a", "open  ·  x  ·  2026-11-07T00:00:00Z", state = "open")),
            StoreFeedTable.row(prs, e("#2", "b", "closed  ·  y  ·  2026-11-06T00:00:00Z", state = "closed")),
        )
        assertEquals(listOf("Open", "Closed"), StoreFeedTable.categories(prs, rs))
        assertEquals(1, StoreFeedTable.filter(prs, rs, listOf("Open")).size)
        assertEquals(2, StoreFeedTable.filter(prs, rs, emptyList()).size)   // none picked = all
    }

    @Test fun cicdFilterSortsRunsIntoGreenErrorRunning() {
        val rs = listOf(
            StoreFeedTable.row(cicd, e("s", "ci", "completed  ·  success  ·  2026-11-07", state = "success")),
            StoreFeedTable.row(cicd, e("s", "ci", "completed  ·  failure  ·  2026-11-06", state = "failure")),
            StoreFeedTable.row(cicd, e("s", "ci", "in_progress  ·    ·  2026-11-05", state = "")),
        )
        assertEquals("Green", StoreFeedTable.categoryOf(cicd, rs[0]))
        assertEquals("Error", StoreFeedTable.categoryOf(cicd, rs[1]))
        assertEquals("Running", StoreFeedTable.categoryOf(cicd, rs[2]))
        assertEquals(1, StoreFeedTable.filter(cicd, rs, listOf("Error")).size)
    }

    @Test fun commitsFilterByAuthor() {
        val rs = rows()   // Zoe, Zoe, Amy — but commits categoryOf is the status(author)
        val cats = StoreFeedTable.categories(commits, rs)
        assertEquals(listOf("Amy", "Zoe"), cats)
        assertEquals(2, StoreFeedTable.filter(commits, rs, listOf("Zoe")).size)
    }

    @Test fun theWholePipelineFiltersThenSortsThenGroups() {
        val entries = listOf(
            e("#1", "a", "open  ·  x  ·  2026-11-07T00:00:00Z", state = "open"),
            e("#2", "b", "closed  ·  y  ·  2026-11-06T00:00:00Z", state = "closed"),
            e("#3", "c", "open  ·  z  ·  2026-11-08T00:00:00Z", state = "open"),
        )
        val groups = StoreFeedTable.view(prs, entries, listOf("Open"), StoreFeedTable.columns(prs)[1], descending = true, byRepo = false)
        assertEquals(1, groups.size)
        assertEquals(listOf("c", "a"), groups.first().second.map { it.name })   // only opens, time desc
        assertFalse(groups.first().second.any { it.name == "b" })
        assertTrue(groups.first().second.all { it.state == "open" })
    }
}
