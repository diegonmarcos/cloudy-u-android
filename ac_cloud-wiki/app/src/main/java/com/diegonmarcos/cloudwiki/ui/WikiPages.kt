package com.diegonmarcos.cloudwiki.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwiki.Md
import com.diegonmarcos.cloudwiki.R
import com.diegonmarcos.cloudwiki.Wiki
import com.diegonmarcos.cloudwiki.WikiData
import com.diegonmarcos.cloudwiki.WikiPage as Page
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.graphview.GEdge
import com.diegonmarcos.superapp.graphview.GraphView
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.cloudwiki.Decl
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** The wiki's navigation state: the active tag filter (shared by every topic) and the tab each topic is on ([OVERVIEW] or a page id). */
class WikiNav {
    val tags = mutableStateListOf<String>()
    val tabs = mutableStateMapOf<String, String>()

    fun toggle(tag: String) { val next = Wiki.toggle(tags.toList(), tag); tags.clear(); tags.addAll(next) }
    fun clear() = tags.clear()

    companion object { const val OVERVIEW = "overview" }
}

/** One side-menu page: the graph, or a topic (its subpage tabs over the Overview or a page). [onTopic] switches the side menu to another topic. */
@Composable
fun WikiPage(data: WikiData?, wiki: NavSection, pageId: String, nav: WikiNav, onTopic: (String) -> Unit) {
    val p = LocalKitPalette.current
    if (data == null || data.topics.isEmpty()) {
        KitStoreCaption(stringResource(R.string.loading), Modifier.fillMaxWidth().padding(KitStoreRow.padH).testTag(Tags.LOADING), color = p.textSecondary, align = TextAlign.Center)
        return
    }
    // A link, a result or a graph node opens one page: its topic in the menu, its tab selected.
    val open: (String) -> Unit = { id -> data.page(id)?.let { pg -> nav.tabs[pg.topic] = pg.id; onTopic(pg.topic) } }
    if (pageId == Decl.PAGE_GRAPH) { GraphPage(data, open); return }
    val topic = data.topic(pageId) ?: return
    val tab = nav.tabs[topic.id]?.takeIf { t -> t == WikiNav.OVERVIEW || topic.pages.any { it.id == t } } ?: WikiNav.OVERVIEW
    val overview = stringResource(R.string.overview)
    Column(Modifier.fillMaxSize().testTag(Tags.PAGE)) {
        NavBarTopSecondRow(
            pages = listOf(NavPage(WikiNav.OVERVIEW, overview, "menu_book")) + topic.pages.map { NavPage(it.id, it.title, "description") },
            selectedId = tab,
            onSelect = { nav.tabs[topic.id] = it.id },
            modifier = Modifier.testTag(Tags.TABS),
            underTopChrome = false,
        )
        val page = data.page(tab)
        if (page == null) Overview(data, topic.intro, topic.pages, nav, open)
        else Article(data, page, nav, open, goOverview = { nav.tabs[topic.id] = WikiNav.OVERVIEW })
    }
}

/** A tag chip: filled when selected. */
@Composable
fun TagChip(tag: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.padding(end = KitStoreRow.gap).clip(shape).background(if (selected) p.accent else p.surface)
            .border(1.dp, if (selected) p.accent else p.hairline, shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp).testTag(Tags.chip(tag)),
    ) {
        Text("#$tag", color = if (selected) p.tileInk else p.textPrimary, fontSize = KitStoreRow.body, maxLines = 1)
    }
}

/** The chip row: the selected tags first (tap to drop), then the tags of the pages that still match. */
@Composable
private fun Chips(pages: List<Page>, nav: WikiNav) {
    val chips = Wiki.chips(pages, nav.tags.toList())
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag(Tags.CHIPS)) {
        items(chips, key = { it }) { t -> TagChip(t, t in nav.tags) { nav.toggle(t) } }
    }
}

/** A topic's Overview: its intro and its subpages; with a tag filter on, every page of the wiki that carries all the selected tags. */
@Composable
private fun Overview(data: WikiData, intro: String, pages: List<Page>, nav: WikiNav, open: (String) -> Unit) {
    val p = LocalKitPalette.current
    val filtering = nav.tags.isNotEmpty()
    val scope = if (filtering) data.pages else pages
    val rows = Wiki.filter(scope, nav.tags.toList())
    Column(Modifier.fillMaxSize()) {
        Chips(scope, nav)
        if (filtering) {
            Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH).clickable { nav.clear() }, verticalAlignment = Alignment.CenterVertically) {
                KitStoreCaption("${stringResource(R.string.tag_filter)}: ${nav.tags.joinToString(" ") { "#$it" }}  ·  ${stringResource(R.string.clear_tags)}", color = p.accent)
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag(Tags.RESULTS)) {
            if (!filtering) item(key = "intro") { Box(Modifier.padding(KitStoreRow.padH)) { Markdown(intro, data, open) } }
            if (rows.isEmpty()) item(key = "none") { KitStoreCaption(stringResource(R.string.no_match), Modifier.fillMaxWidth().padding(KitStoreRow.padH), color = p.textSecondary, align = TextAlign.Center) }
            items(rows, key = { it.id }) { pg ->
                KitStoreRowLine(Modifier.testTag(Tags.result(pg.id)), onClick = { open(pg.id) }) {
                    KitStoreDot("●", KitStoreRow.UP)
                    Column(Modifier.weight(1f)) {
                        KitStoreName(pg.title, color = p.textPrimary)
                        KitStoreCaption((if (filtering) "${data.topic(pg.topic)?.label.orEmpty()} · " else "") + pg.tags.joinToString(" ") { "#$it" }, color = p.textSecondary)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(KitStoreRow.RULE))
            }
        }
    }
}

/** One page: title, tag chips (tap one to filter the whole wiki by it), the Markdown explanation and the pages it links to. */
@Composable
private fun Article(data: WikiData, page: Page, nav: WikiNav, open: (String) -> Unit, goOverview: () -> Unit) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(KitStoreRow.padH).testTag(Tags.ARTICLE)) {
        Text(page.title, color = p.textPrimary, fontSize = KitStoreRow.title * 1.9f, fontWeight = FontWeight.SemiBold)
        LazyRow(Modifier.fillMaxWidth().padding(vertical = KitStoreRow.gap).testTag(Tags.CHIPS)) {
            items(page.tags, key = { it }) { t -> TagChip(t, t in nav.tags) { nav.toggle(t); goOverview() } }
        }
        Markdown(page.md, data, open)
    }
}

/** Renders [md] (Md blocks); a `[[id]]` is a tappable link showing the target page's title. */
@Composable
fun Markdown(md: String, data: WikiData, open: (String) -> Unit) {
    val p = LocalKitPalette.current
    val link = TextLinkStyles(SpanStyle(color = p.accent, textDecoration = TextDecoration.Underline))
    fun text(spans: List<Md.Span>): AnnotatedString = buildAnnotatedString {
        for (s in spans) when (s) {
            is Md.Span.Text -> withStyle(SpanStyle(fontWeight = if (s.bold) FontWeight.Bold else null, fontStyle = if (s.italic) FontStyle.Italic else null)) { append(s.text) }
            is Md.Span.Link -> withLink(LinkAnnotation.Clickable(s.id, link) { open(s.id) }) { append(data.page(s.id)?.title ?: s.id) }
        }
    }
    Column {
        for (b in Md.parse(md)) when (b) {
            is Md.Block.Heading -> Text(text(b.spans), color = p.textPrimary, fontSize = KitStoreRow.title * (2.1f - 0.25f * b.level), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp))
            is Md.Block.Para -> Text(text(b.spans), color = p.textPrimary, fontSize = KitStoreRow.body * 1.4f, modifier = Modifier.padding(bottom = 8.dp))
            is Md.Block.Bullet -> Row(Modifier.padding(bottom = 4.dp)) {
                Text("•", color = p.textSecondary, fontSize = KitStoreRow.body * 1.4f, modifier = Modifier.padding(end = 8.dp))
                Text(text(b.spans), color = p.textPrimary, fontSize = KitStoreRow.body * 1.4f)
            }
        }
    }
}

/** The graph of every page: nodes coloured by topic, solid edges for links, faint ones for shared tags; tap a node to open its page. */
@Composable
private fun GraphPage(data: WikiData, open: (String) -> Unit) {
    val p = LocalKitPalette.current
    val nodes = remember(data) { Wiki.nodes(data) }
    val edges = remember(data) { Wiki.edges(data) }
    val colors = remember(data) { data.topics.mapIndexed { i, t -> t.id to topicColor(i, data.topics.size) }.toMap() }
    Column(Modifier.fillMaxSize().testTag(Tags.GRAPH)) {
        KitStoreCaption(
            stringResource(R.string.graph_legend, nodes.size, edges.count { it.kind == GEdge.Kind.LINK }, edges.count { it.kind == GEdge.Kind.TAG }) + "  ·  " + stringResource(R.string.graph_hint),
            Modifier.fillMaxWidth().padding(KitStoreRow.padH, KitStoreRow.tight), color = p.textSecondary,
        )
        GraphView(
            nodes = nodes, edges = edges,
            nodeColor = { colors[it.group] ?: p.accent },
            linkColor = p.textPrimary, tagColor = p.textSecondary, labelColor = p.textPrimary,
            onOpen = open,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** An evenly spread hue per topic, so a cluster of one topic reads as one colour. */
fun topicColor(index: Int, count: Int): Color = Color.hsv(360f * index / count.coerceAtLeast(1), 0.55f, 0.95f)
