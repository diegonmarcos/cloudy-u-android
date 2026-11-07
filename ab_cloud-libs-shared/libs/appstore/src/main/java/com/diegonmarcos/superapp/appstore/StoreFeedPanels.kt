package com.diegonmarcos.superapp.appstore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The Cloud page's two feed panels, "Commits" and "CI-CD", drawn BELOW the page's action buttons (Check all / Install all /
 * Update all / Download all). They were the Store's Feed destination; Feed moved into Cloud, so this is the same declared
 * feeds ([FeedViewer.feeds], assets/appstore-feeds.json), the same proxy-first loader and the same rate-limit wording, in two
 * collapsible data-dense panels instead of a page of their own. Nothing of the old page is dropped: every row still carries
 * its ref, title, subtitle and state colour and opens the link the feed supplied; the Configs > Sources switches still turn a
 * feed off; a failed read says why and is never an empty list.
 *
 * Each panel adds a tally line over the loaded rows (for CI-CD: passed / failed / running; for Commits: authors and the last
 * date) and shows [ROWS] rows with a "show all" toggle. Loads are cached for [TTL_MS] so switching the Cloud page's group tabs
 * does not re-read GitHub each time.
 */
object StoreFeedPanels {
    /** Rows a panel shows before "show all". */
    const val ROWS = 8
    private const val TTL_MS = 60_000L

    fun tag(feedId: String) = "store-cloud:feed-panel:$feedId"
    fun rowTag(feedId: String, i: Int) = "store-cloud:feed-row:$feedId:$i"
    fun moreTag(feedId: String) = "store-cloud:feed-more:$feedId"
    const val TAG_PANELS = "store-cloud:feed-panels"

    private val OK = Color(0xFF48BB78)
    private val BAD = Color(0xFFF56565)

    private class Loaded(val entries: List<FeedViewer.Entry>, val error: String?, val at: Long = System.currentTimeMillis())
    private val cache = ConcurrentHashMap<String, Loaded>()

    /** The tally over a feed's rows: how many are ok / bad / neither (a run still going is neither, never red). */
    class Tally(val ok: Int, val bad: Int, val other: Int)

    fun tally(feed: FeedViewer.Feed, entries: List<FeedViewer.Entry>): Tally =
        Tally(entries.count { it.state in feed.ok }, entries.count { it.state in feed.bad }, entries.count { it.state !in feed.ok && it.state !in feed.bad })

    /** One line of facts over a feed's rows, in the feed's own terms. */
    fun summary(feed: FeedViewer.Feed, entries: List<FeedViewer.Entry>): String {
        if (entries.isEmpty()) return "0 entries"
        val t = tally(feed, entries)
        return if (feed.state != null) "${entries.size} runs · ${t.ok} passed · ${t.bad} failed · ${t.other} running or other"
        else {
            val authors = entries.mapNotNull { it.subtitle.substringBefore("  ·  ").takeIf { a -> a.isNotBlank() } }.distinct().size
            "${entries.size} commits · $authors authors · latest ${entries.first().ref.take(7)}"
        }
    }

    /** Reads [feed] off the main thread (cached for a minute). Never throws: a failure is the answer's [Loaded.error]. */
    private suspend fun read(feed: FeedViewer.Feed, force: Boolean): Loaded {
        val now = System.currentTimeMillis()
        cache[feed.id]?.takeIf { !force && now - it.at < TTL_MS }?.let { return it }
        val l = withContext(Dispatchers.IO) {
            runCatching { FeedViewer.load(feed) }
                .fold({ Loaded(it, null) }, { Loaded(emptyList(), FeedViewer.explain(it)) })
        }
        cache[feed.id] = l
        return l
    }

    /** Every declared feed as a panel, in declared order (Commits, CI-CD). */
    @Composable
    fun Panels() {
        val ctx = LocalContext.current
        val feeds = remember(ctx) { FeedViewer.feeds(ctx) }
        if (feeds.isEmpty()) return
        Column(Modifier.fillMaxWidth().testTag(TAG_PANELS).padding(top = sdp(StoreDensity.S4), bottom = sdp(StoreDensity.S4)),
            verticalArrangement = Arrangement.spacedBy(sdp(StoreDensity.S4))) {
            feeds.forEach { Panel(it) }
        }
    }

    @Composable
    private fun Panel(feed: FeedViewer.Feed) {
        val ctx = LocalContext.current
        val on = remember(feed.id) { StoreSourcePrefs.enabled(ctx, feed.id) }
        var open by remember(feed.id) { mutableStateOf(true) }
        var all by remember(feed.id) { mutableStateOf(false) }
        var loaded by remember(feed.id) { mutableStateOf<Loaded?>(cache[feed.id]) }
        var reload by remember(feed.id) { mutableStateOf(0) }
        LaunchedEffect(feed.id, reload, on) { if (on) loaded = read(feed, force = reload > 0) }
        val opener = remember(ctx) { FeedViewer.opener(ctx) }
        val l = loaded
        Column(Modifier.fillMaxWidth().testTag(tag(feed.id)).background(Ink.CARD.ink())) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = sdp(StoreDensity.S12), vertical = sdp(StoreDensity.S6))) {
                Text("${if (open) "⌄" else "›"}  ${feed.label}", color = Ink.WHITE.ink(), fontSize = StoreDensity.T_TITLE.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (on) Text("refresh", color = Ink.DIM.ink(), fontSize = StoreDensity.T_CAPTION.sp,
                    modifier = Modifier.clickable { reload++ }.testTag(tag(feed.id) + ":refresh"))
            }
            if (!open) return@Column
            val pad = sdp(StoreDensity.S12)
            when {
                !on -> Text("${feed.label} is off. Turn it on in Settings > Configs > Sources.", color = Ink.DIM.ink(),
                    fontSize = StoreDensity.T_CAPTION.sp, modifier = Modifier.padding(pad))
                l == null -> Text("loading…", color = Ink.DIM.ink(), fontSize = StoreDensity.T_CAPTION.sp, modifier = Modifier.padding(pad))
                l.error != null -> Text(l.error, color = Ink.DIM.ink(), fontSize = StoreDensity.T_CAPTION.sp, modifier = Modifier.padding(pad))
                l.entries.isEmpty() -> Text("Nothing in this feed.", color = Ink.DIM.ink(), fontSize = StoreDensity.T_CAPTION.sp, modifier = Modifier.padding(pad))
                else -> {
                    Text(summary(feed, l.entries), color = Ink.DIM.ink(), fontSize = StoreDensity.T_CAPTION.sp,
                        modifier = Modifier.padding(horizontal = pad))
                    val shown = if (all) l.entries else l.entries.take(ROWS)
                    shown.forEachIndexed { i, e -> FeedRow(feed, e, opener, Modifier.testTag(rowTag(feed.id, i))) }
                    if (l.entries.size > ROWS) Text(if (all) "show fewer" else "show all ${l.entries.size}", color = Ink.BLUE_FILL.ink(),
                        fontSize = StoreDensity.T_CAPTION.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { all = !all }.testTag(moreTag(feed.id)).padding(horizontal = pad, vertical = sdp(StoreDensity.S6)))
                }
            }
        }
    }

    @Composable
    private fun FeedRow(feed: FeedViewer.Feed, e: FeedViewer.Entry, open: (String) -> Unit, modifier: Modifier) {
        // A value in NEITHER list is neutral: an in-flight run is not a failure.
        val state = when (e.state) { in feed.ok -> OK; in feed.bad -> BAD; else -> Ink.DIM.ink() }
        val tap = if (e.link.isNotEmpty()) Modifier.clickable { open(e.link) } else Modifier
        Column(modifier.fillMaxWidth().then(tap).padding(horizontal = sdp(StoreDensity.S12), vertical = sdp(StoreDensity.S4))) {
            Row {
                Text(e.ref.take(7), color = state, fontFamily = FontFamily.Monospace, fontSize = StoreDensity.T_CAPTION.sp,
                    modifier = Modifier.padding(end = sdp(StoreDensity.S8)))
                // Only the FIRST line of a commit message: the body belongs on the page the row opens.
                Text(e.title.lineSequence().firstOrNull().orEmpty(), Modifier.weight(1f), color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_BODY.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (e.subtitle.isNotEmpty()) Text(e.subtitle, color = Ink.DIM.ink(), fontSize = StoreDensity.T_CAPTION.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
