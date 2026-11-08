package com.diegonmarcos.superapp.appstore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * #642 — The Cloud page's Commits / PRs / CI-CD feeds, rendered as sortable, groupable, filterable TABLES (the treatment the
 * torrent search table got, [StoreTorrentSearch.ResultTable] + [StoreTorrentHost]). The feed DATA path is untouched: rows still
 * come from [FeedViewer.Entry] via the proxy-first loader; this file is only the Entry → columns mapping and the pure
 * sort / group / filter logic over it, split out of the Compose so a JVM test can hand it invented rows and check the ordering.
 *
 * COLUMNS, per feed: Repo · Time · Name · Description plus a per-feed status column — commits → Author, PRs → State,
 * CI-CD → Result. The GitHub item fields map on: repo (from the feed's own `/repos/{owner}/{repo}` url, since the per-item JSON
 * does not repeat it), time (the trailing `…  ·  <date>` of the declared subtitle — ISO-8601, so a string compare is a date
 * compare), name (the title's first line), description (a commit message's body; PRs and CI-CD carry none in the declared
 * templates, so that cell is honestly blank), and the status value ([FeedViewer.Entry.state] for PRs/CI-CD, the subtitle's
 * leading author for commits).
 *
 * SORT keeps Time as the ALWAYS-secondary key: sort by Repo and each repo's rows fall by time desc; sort by Author, then time
 * desc. Re-tapping the active header toggles asc/desc. Default is Time desc. GROUP is All (one table) or By Repo (a sub-table
 * per repo). FILTER is a chip row over a per-feed category (PRs: Open/Closed; CI-CD: Error/Green/Running; commits: by author).
 */
object StoreFeedTable {

    // ── the column model ─────────────────────────────────────────────────────

    enum class Kind { TEXT, TIME, STATUS }

    class Col(val key: String, val label: String, val kind: Kind)

    private val REPO = Col("repo", "Repo", Kind.TEXT)
    private val TIME = Col("time", "Time", Kind.TIME)
    private val NAME = Col("name", "Name", Kind.TEXT)
    private val DESC = Col("desc", "Description", Kind.TEXT)

    /** True when this feed is the commits feed: no `state` field, and `open` is not one of its ok words (which marks PRs). */
    private fun isCommits(feed: FeedViewer.Feed) = feed.state == null && "open" !in feed.ok
    private fun isPrs(feed: FeedViewer.Feed) = "open" in feed.ok

    /** The per-feed status column: Author for commits, State for PRs, Result for CI-CD. */
    private fun statusCol(feed: FeedViewer.Feed): Col = when {
        isPrs(feed) -> Col("status", "State", Kind.STATUS)
        isCommits(feed) -> Col("status", "Author", Kind.TEXT)
        else -> Col("status", "Result", Kind.STATUS)
    }

    /** The feed's columns, in draw order: Repo · Time · Name · Description · <status>. */
    fun columns(feed: FeedViewer.Feed): List<Col> = listOf(REPO, TIME, NAME, DESC, statusCol(feed))

    // ── the row, mapped from one feed Entry ──────────────────────────────────

    /** One table row: every cell already a string, plus [state] kept raw for colouring and filtering. */
    class Row(val repo: String, val time: String, val name: String, val desc: String, val status: String,
              val link: String, val state: String) {
        fun cell(key: String): String = when (key) {
            "repo" -> repo; "time" -> time; "name" -> name; "desc" -> desc; "status" -> status; else -> ""
        }
    }

    private val REPO_IN_URL = Regex("/repos/([^/]+/[^/?#]+)")

    /** `owner/repo` out of the feed's declared url; blank when the url is not a GitHub repo url. */
    fun repoOf(feed: FeedViewer.Feed): String = REPO_IN_URL.find(feed.url)?.groupValues?.get(1).orEmpty()

    private const val SEP = "  ·  "

    fun row(feed: FeedViewer.Feed, e: FeedViewer.Entry): Row {
        val repo = repoOf(feed)
        val parts = e.subtitle.split(SEP).map { it.trim() }
        val time = parts.lastOrNull().orEmpty()
        val name = e.title.lineSequence().firstOrNull().orEmpty().trim()
        // Only the commit message carries a body; a PR/run title is a single line, so its Description is honestly empty.
        val desc = if (isCommits(feed)) e.title.lineSequence().drop(1).joinToString("\n").trim() else ""
        val status = when {
            isCommits(feed) -> parts.firstOrNull().orEmpty()            // the author, the subtitle's leading field
            isPrs(feed) -> e.state.ifBlank { parts.firstOrNull().orEmpty() }
            else -> e.state.ifBlank { "running" }                       // a run with no conclusion yet is running, not failed
        }
        return Row(repo, time, name, desc, status, e.link, e.state)
    }

    fun rows(feed: FeedViewer.Feed, entries: List<FeedViewer.Entry>): List<Row> = entries.map { row(feed, it) }

    // ── sort: the tapped column, with Time ALWAYS the secondary key ───────────

    /** The first tap on a column: Time newest-first, text columns A→Z. */
    fun defaultDescending(c: Col): Boolean = c.kind == Kind.TIME

    /**
     * [rows] ordered by [col], [descending] or not, with time desc as the secondary key for every non-time column — so
     * grouping/sorting by Repo or Author still falls newest-first inside each value. Stable. Blank cells sort last in either
     * direction (an unknown value is not "smallest"). Time compares as its ISO-8601 string, which is its chronological order.
     */
    fun sort(rows: List<Row>, col: Col, descending: Boolean): List<Row> {
        val key: (Row) -> String =
            if (col.kind == Kind.TIME) { r -> r.time } else { r -> r.cell(col.key).lowercase() }
        // Blanks sort last in either direction; time is the always-secondary key (desc) for every non-time column.
        fun cmpBlankLast(ka: String, kb: String, desc: Boolean): Int = when {
            ka.isBlank() && kb.isBlank() -> 0
            ka.isBlank() -> 1
            kb.isBlank() -> -1
            else -> if (desc) kb.compareTo(ka) else ka.compareTo(kb)
        }
        val cmp = Comparator<Row> { a, b ->
            val primary = cmpBlankLast(key(a), key(b), descending)
            if (primary != 0 || col.kind == Kind.TIME) primary
            else cmpBlankLast(a.time, b.time, desc = true)
        }
        return rows.sortedWith(cmp)
    }

    // ── group: All, or one sub-table per repo ────────────────────────────────

    /** [rows] as the groups to draw: one `"" to rows` when [byRepo] is false, else one entry per repo (first-seen order,
     *  so a prior [sort] by repo lays the groups out in order and keeps each group's own time ordering). */
    fun group(rows: List<Row>, byRepo: Boolean): List<Pair<String, List<Row>>> =
        if (!byRepo) listOf("" to rows) else rows.groupBy { it.repo.ifBlank { StoreTorrentHost.DASH } }.toList()

    // ── filter: a per-feed category, chips over it ────────────────────────────

    /** The category a row falls in, in the feed's own terms (the chip label). */
    fun categoryOf(feed: FeedViewer.Feed, r: Row): String = when {
        isPrs(feed) -> if (r.state == "open") "Open" else "Closed"
        isCommits(feed) -> r.status.ifBlank { StoreTorrentHost.DASH }           // the author
        r.state in feed.bad -> "Error"
        r.state in feed.ok -> "Green"
        else -> "Running"
    }

    /** The chips to offer for [rows], in a stable order. PRs/CI-CD are a fixed set; commits are the authors present. */
    fun categories(feed: FeedViewer.Feed, rows: List<Row>): List<String> = when {
        isPrs(feed) -> listOf("Open", "Closed")
        isCommits(feed) -> rows.map { categoryOf(feed, it) }.distinct().sorted()
        else -> (listOf("Green", "Error", "Running") + rows.map { categoryOf(feed, it) }).distinct()
    }

    /** [rows] kept to the [selected] categories; none selected = all of them. */
    fun filter(feed: FeedViewer.Feed, rows: List<Row>, selected: Collection<String>): List<Row> =
        if (selected.isEmpty()) rows else rows.filter { categoryOf(feed, it) in selected }

    // ── the whole pipeline, for a test and for the view ───────────────────────

    /** Map → filter → sort → group, the exact order the view applies. */
    fun view(feed: FeedViewer.Feed, entries: List<FeedViewer.Entry>, selected: Collection<String>, col: Col,
             descending: Boolean, byRepo: Boolean): List<Pair<String, List<Row>>> =
        group(sort(filter(feed, rows(feed, entries), selected), col, descending), byRepo)

    // ── the Compose table ─────────────────────────────────────────────────────

    private val OK = Color(0xFF48BB78)
    private val BAD = Color(0xFFF56565)

    fun tableTag(feedId: String) = "store-cloud:feed-table:$feedId"
    fun groupTag(feedId: String, repo: String) = "store-cloud:feed-group:$feedId:${repo.ifBlank { "all" }}"
    fun headerTag(feedId: String, key: String) = "store-cloud:feed-col:$feedId:$key"
    fun chipTag(feedId: String, label: String) = "store-cloud:feed-chip:$feedId:$label"
    fun groupToggleTag(feedId: String, byRepo: Boolean) = "store-cloud:feed-grp:$feedId:${if (byRepo) "repo" else "all"}"

    private fun colWidth(c: Col): Int = when (c.key) {
        "repo" -> 10; "time" -> 9; "name" -> 16; "desc" -> 16; "status" -> 8; else -> 6
    } * StoreDensity.S12

    private fun statusColor(feed: FeedViewer.Feed, r: Row): Color = when {
        isCommits(feed) -> Ink.WHITE.ink()
        r.state in feed.ok -> OK
        r.state in feed.bad -> BAD
        else -> Ink.DIM.ink()
    }

    /**
     * The feed as one or more columnar tables: the group toggle (All / By Repo) and the filter chips over the table, then the
     * header (tap any column to sort; the active one shows ▲/▼) and the rows. Matches [StoreTorrentSearch.ResultTable]: fixed
     * header above scrolling rows, both sliding sideways together, hairline rules, "—" for a blank cell, a tap opens the row's link.
     */
    @Composable
    fun Table(feed: FeedViewer.Feed, entries: List<FeedViewer.Entry>, open: (String) -> Unit, modifier: Modifier = Modifier) {
        val cols = remember(feed.id) { columns(feed) }
        var sortCol by remember(feed.id) { mutableStateOf(TIME) }
        var desc by remember(feed.id) { mutableStateOf(true) }
        var byRepo by remember(feed.id) { mutableStateOf(false) }
        val picked = remember(feed.id) { mutableStateListOf<String>() }
        val all = remember(feed.id, entries) { rows(feed, entries) }
        val chips = remember(feed.id, entries) { categories(feed, all) }

        Column(modifier.fillMaxWidth().testTag(tableTag(feed.id)), verticalArrangement = Arrangement.spacedBy(sdp(StoreDensity.S6))) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                StoreSearchPage.SourcePill("All", !byRepo, groupToggleTag(feed.id, false)) { byRepo = false }
                StoreSearchPage.SourcePill("By Repo", byRepo, groupToggleTag(feed.id, true)) { byRepo = true }
            }
            if (chips.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                StoreSearchPage.SourcePill("All ${all.size}", picked.isEmpty(), chipTag(feed.id, "all")) { picked.clear() }
                chips.forEach { c ->
                    val n = all.count { categoryOf(feed, it) == c }
                    StoreSearchPage.SourcePill("$c $n", c in picked, chipTag(feed.id, c)) { if (c in picked) picked.remove(c) else picked.add(c) }
                }
            }
            val groups = remember(feed.id, entries, picked.toList(), sortCol.key, desc, byRepo) {
                view(feed, entries, picked.toList(), sortCol, desc, byRepo)
            }
            val onSort: (Col) -> Unit = { c -> if (c.key == sortCol.key) desc = !desc else { sortCol = c; desc = defaultDescending(c) } }
            groups.forEach { (repo, rws) ->
                if (byRepo) Text(repo.ifBlank { StoreTorrentHost.DASH }, color = Ink.WHITE.ink(), fontSize = StoreDensity.T_TITLE.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = sdp(StoreDensity.S4)))
                OneTable(feed, cols, rws, sortCol, desc, onSort, open, Modifier.testTag(groupTag(feed.id, repo)))
            }
        }
    }

    @Composable
    private fun OneTable(feed: FeedViewer.Feed, cols: List<Col>, rows: List<Row>, sortCol: Col, desc: Boolean,
                         onSort: (Col) -> Unit, open: (String) -> Unit, modifier: Modifier) {
        @Composable fun cell(c: Col, text: String, mod: Modifier = Modifier, color: Color = Ink.WHITE.ink(), bold: Boolean = false) {
            Text(text.ifBlank { StoreTorrentHost.DASH }, color = color, fontSize = StoreDensity.T_CAPTION.sp,
                fontFamily = if (c.key == "repo") FontFamily.Monospace else FontFamily.Default,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = mod.width(sdp(colWidth(c))).padding(horizontal = sdp(StoreDensity.S4), vertical = sdp(StoreDensity.S2)))
        }
        @Composable fun rule() = Box(Modifier.fillMaxWidth().height(StoreDensity.STROKE.dp).background(Ink.RULE.ink()))
        Column(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(Ink.CARD.ink())) {
                cols.forEach { c ->
                    cell(c, c.label + if (c.key == sortCol.key) (if (desc) " ▼" else " ▲") else "",
                        Modifier.clickable { onSort(c) }.testTag(headerTag(feed.id, c.key)), Ink.WHITE.ink(), bold = true)
                }
            }
            rule()
            Column(Modifier.heightIn(max = sdp(StoreDensity.SHEET)).verticalScroll(rememberScrollState())) {
                rows.forEachIndexed { i, r ->
                    val tap = if (r.link.isNotEmpty()) Modifier.clickable { open(r.link) } else Modifier
                    Row(Modifier.then(tap).testTag(StoreFeedPanels.rowTag(feed.id, i))) {
                        cols.forEach { c ->
                            val isStatus = c.key == "status"
                            cell(c, r.cell(c.key), color = if (isStatus) statusColor(feed, r) else Ink.WHITE.ink(),
                                bold = c.key == "name")
                        }
                    }
                    rule()
                }
            }
        }
    }
}
