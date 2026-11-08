package com.diegonmarcos.superapp.appstore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Cloud page's Commits and CI-CD panels: the declared feeds they are built from, and their tally lines. Pure, no device. */
class StoreFeedPanelsTest {
    private val feeds = FeedViewer.parse(JSONObject(File("src/main/assets/appstore-feeds.json").readText()))

    @Test fun thePanelsAreTheDeclaredCommitsAndCicdFeedsInOrder() {
        assertEquals(listOf("commits", "prs", "cicd"), feeds.map { it.id })
        assertEquals(listOf("Commits"), feeds.filter { it.id == "commits" }.map { it.label })
        assertTrue(feeds.first { it.id == "cicd" }.state != null)   // runs carry a state, commits do not
    }

    @Test fun cicdTallyCountsPassedFailedAndRunningWithoutCallingARunningOneFailed() {
        val f = feeds.first { it.id == "cicd" }
        val e = listOf("success", "success", "failure", "in_progress", "").map { FeedViewer.Entry("1", "ci", "", "", it) }
        val t = StoreFeedPanels.tally(f, e)
        assertEquals(2, t.ok); assertEquals(1, t.bad); assertEquals(2, t.other)
        assertEquals("5 runs · 2 passed · 1 failed · 2 running or other", StoreFeedPanels.summary(f, e))
    }

    @Test fun prsSummaryCountsOpenAndClosed() {
        val f = feeds.first { it.id == "prs" }
        val e = listOf("open", "closed", "closed").mapIndexed { i, s -> FeedViewer.Entry("#$i", "t$i", "$s  ·  a  ·  d", "", s) }
        assertEquals("3 PRs · 1 open · 2 closed", StoreFeedPanels.summary(f, e))
    }

    @Test fun commitsSummaryNamesTheAuthorsAndTheLatestSha() {
        val f = feeds.first { it.id == "commits" }
        val e = listOf(FeedViewer.Entry("abcdef0123", "m", "Ann  ·  2026-11-07", "", ""), FeedViewer.Entry("1234567890", "m", "Bob  ·  2026-11-06", "", ""),
            FeedViewer.Entry("0000000000", "m", "Ann  ·  2026-11-05", "", ""))
        assertEquals("3 commits · 2 authors · latest abcdef0", StoreFeedPanels.summary(f, e))
        assertEquals("0 entries", StoreFeedPanels.summary(f, emptyList()))
    }

    @Test fun aPanelShowsAtMostEightRowsBeforeShowAll() {
        assertEquals(8, StoreFeedPanels.ROWS)
    }
}
