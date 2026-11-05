package com.diegonmarcos.capitalfinterm.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.BuildConfig
import com.diegonmarcos.capitalfinterm.DashStatus
import com.diegonmarcos.capitalfinterm.Dashboards
import com.diegonmarcos.capitalfinterm.Decl
import com.diegonmarcos.capitalfinterm.Entry
import com.diegonmarcos.capitalfinterm.FinNavStack
import com.diegonmarcos.capitalfinterm.Pages
import com.diegonmarcos.capitalfinterm.HomeCard
import com.diegonmarcos.superapp.navbartop2.SecondRowIcons
import com.diegonmarcos.capitalfinterm.R
import com.diegonmarcos.capitalfinterm.ui.fin.FinDim
import com.diegonmarcos.capitalfinterm.ui.fin.FinType
import com.diegonmarcos.capitalfinterm.ui.fin.FinUi
import com.diegonmarcos.capitalfinterm.ui.fin.LocalFinPalette
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * A section's page (UI spec 3, 4.5). Its stack's top decides: the tab's list (a search field, the dashboards as a 2-column grid of
 * tiles with a LIVE / PARTLY / KEY pill, then the other declared entries), a dashboard, or a quote. From 600dp wide a tab with
 * dashboards draws two panes: the list as 56dp rows at the left (360dp, or 40 % up to 480dp), the selected dashboard or quote at
 * the right. Nothing here is typed text: the words are build.json's.
 */
@Composable
fun SectionPage(sectionId: String, stack: List<String>, nav: FinNav, onSelectList: (String) -> Unit, onOpenConfig: () -> Unit, onOpenPage: (String) -> Unit = {}) {
    val entries = remember(sectionId) { Pages.entries(Decl.stacks, sectionId) }
    // #1399 a section that declares its sub-pages opens on its Homepage: every sub-page as a card (icon, name, one line).
    val cards = remember(sectionId) { Pages.homeCards(Decl.pages(sectionId), entries) }
    val page = FinNavStack.pageOf(stack)?.let { Pages.entryOf(entries, it) }
    val top = stack.lastOrNull() ?: FinNavStack.LIST
    val hasDash = entries.any { it.kind == Pages.KIND_DASHBOARD }
    val p = LocalFinPalette.current
    BoxWithConstraints(Modifier.fillMaxSize().testTag(Tags.PAGE + ":" + sectionId)) {
        val two = hasDash && maxWidth >= FinDim.twoPane
        if (two) {
            val listW = if (maxWidth < 840.dp) 360.dp else (maxWidth * 0.4f).coerceAtMost(480.dp)
            val selected = FinNavStack.dashboardOf(stack)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(listW).fillMaxHeight()) { TabList(sectionId, entries, compact = true, selected = selected, nav = nav, onOpen = onSelectList, onOpenConfig = onOpenConfig) }
                Box(Modifier.width(1.dp).fillMaxHeight().background(p.hairline))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val sym = FinNavStack.symbolOf(top)
                    when {
                        sym != null -> QuotePage(sym, nav)
                        selected != null -> DashboardPage(selected, nav)
                        page != null -> EntryPage(page, nav)
                        cards.isNotEmpty() -> HomeGrid(sectionId, cards, nav, onOpenPage)
                        else -> Box(Modifier.fillMaxSize().background(p.canvas))
                    }
                }
            }
        } else {
            val sym = FinNavStack.symbolOf(top)
            when {
                sym != null -> QuotePage(sym, nav)
                top.startsWith("dash:") -> DashboardPage(top.removePrefix("dash:"), nav)
                page != null -> EntryPage(page, nav)
                cards.isNotEmpty() -> HomeGrid(sectionId, cards, nav, onOpenPage)
                else -> TabList(sectionId, entries, compact = false, selected = null, nav = nav, onOpen = onSelectList, onOpenConfig = onOpenConfig)
            }
        }
    }
}

/** The tab's list screen: dashboards as tiles (or 56dp rows in the two-pane list), then the other declared entries. */
@Composable
private fun TabList(sectionId: String, entries: List<Entry>, compact: Boolean, selected: String?, nav: FinNav, onOpen: (String) -> Unit, onOpenConfig: () -> Unit) {
    val ctx = LocalContext.current
    val sources = remember { AssetData.sources(ctx) }
    val dashboards = remember { AssetData.dashboards(ctx) }
    val dashEntries = entries.filter { it.kind == Pages.KIND_DASHBOARD }
    val others = entries.filter { it.kind != Pages.KIND_DASHBOARD && it.kind != Pages.KIND_PM_TABLE }
    fun status(e: Entry): DashStatus? = dashboards.firstOrNull { it.id == e.dashboard }?.let { Dashboards.status(it, sources) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = FinDim.screenPad, end = FinDim.screenPad, top = FinDim.gap, bottom = FinDim.bottomInset),
        verticalArrangement = Arrangement.spacedBy(FinDim.gap),
    ) {
        if (dashEntries.isNotEmpty()) item(key = "search") { FinSearchField(onClick = { nav.search() }) }
        if (sectionId == Decl.SECTION_CONFIG) item(key = "cvd") { CvdRow() }
        if (compact) {
            dashEntries.forEach { e -> item(key = "d:" + e.dashboard) { DashRow(e, status(e), e.dashboard == selected) { onOpen(e.dashboard) } } }
        } else {
            dashEntries.chunked(2).forEachIndexed { i, pair ->
                item(key = "t:$i") {
                    Row(horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) {
                        pair.forEach { e -> DashTile(e, status(e), Modifier.weight(1f)) { onOpen(e.dashboard) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        others.forEachIndexed { i, e ->
            item(key = "o:$i") {
                when (e.kind) {
                    Pages.KIND_SECTION_TITLE -> GroupTitle(e.title)
                    Pages.KIND_HOLDINGS -> HoldingsPanel()
                    Pages.KIND_ABOUT -> AboutPanel()
                    Pages.KIND_SOURCES -> SourcesPanel()
                    else -> EntryRow(e, Pages.resolve(e, Decl.targets)) { url -> openUrl(ctx, url) }
                }
            }
        }
    }
}

/** #1399 a non-dashboard sub-page (MY-PM's holdings, #1478 the PM tables): its declared panel, alone. */
@Composable
private fun EntryPage(e: Entry, nav: FinNav) {
    Box(Modifier.fillMaxSize().padding(horizontal = FinDim.screenPad, vertical = FinDim.gap)) {
        when (e.kind) {
            Pages.KIND_HOLDINGS -> HoldingsPanel()
            Pages.KIND_PM_TABLE -> PmPage(e.table, nav)
            else -> Text(e.title, color = LocalFinPalette.current.text, style = FinType.title)
        }
    }
}

/** #1399 the section's Homepage: a 2-column grid of cards, one per sub-page (icon, name, one-line description); a tap opens it. */
@Composable
private fun HomeGrid(sectionId: String, cards: List<HomeCard>, nav: FinNav, onOpen: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().testTag(HOME_GRID + sectionId),
        contentPadding = PaddingValues(start = FinDim.screenPad, end = FinDim.screenPad, top = FinDim.gap, bottom = FinDim.bottomInset),
        verticalArrangement = Arrangement.spacedBy(FinDim.gap),
    ) {
        if (cards.any { it.entry.kind == Pages.KIND_DASHBOARD }) item(key = "search") { FinSearchField(onClick = { nav.search() }) }
        cards.chunked(2).forEachIndexed { i, pair ->
            item(key = "h:$i") {
                Row(horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) {
                    pair.forEach { c -> HomeCardTile(c, Modifier.weight(1f)) { onOpen(c.pageId) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The test tag prefix of a Homepage grid (+ section id) and of one card (+ sub-page id). */
const val HOME_GRID = "home_grid:"
const val HOME_CARD = "home_card:"

@Composable
private fun HomeCardTile(c: HomeCard, modifier: Modifier, onOpen: () -> Unit) {
    val p = LocalFinPalette.current
    Column(
        modifier.height(112.dp).clip(RoundedCornerShape(FinDim.cardRadius)).background(p.surface).border(1.dp, p.hairline, RoundedCornerShape(FinDim.cardRadius))
            .clickable(onClick = onOpen).padding(12.dp).testTag(HOME_CARD + c.pageId),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(SecondRowIcons.vector(c.icon), contentDescription = null, tint = p.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(c.title, Modifier.weight(1f), color = p.text, style = FinType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(c.subtitle, color = p.text2, style = FinType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The status pill of a tile: LIVE (up), PARTLY (amber), KEY (text3); 20dp. */
@Composable
private fun StatusPill(status: DashStatus?) {
    if (status == null) return
    val p = LocalFinPalette.current
    val (word, color) = when (status) {
        DashStatus.LIVE -> "LIVE" to p.up
        DashStatus.PARTLY_LIVE -> "PARTLY" to p.amber
        else -> "KEY" to p.text3
    }
    Box(Modifier.height(20.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center) { Text(word, color = color, style = FinType.label) }
}

/** A dashboard tile of the tab's list (UI spec 3): 96dp, title, one-line description, the status pill at the top right. */
@Composable
private fun DashTile(e: Entry, status: DashStatus?, modifier: Modifier, onOpen: () -> Unit) {
    val p = LocalFinPalette.current
    Column(
        modifier.height(96.dp).clip(RoundedCornerShape(FinDim.cardRadius)).background(p.surface).border(1.dp, p.hairline, RoundedCornerShape(FinDim.cardRadius))
            .clickable(onClick = onOpen).padding(12.dp).testTag(Tags.entry(e.title)),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(e.title, Modifier.weight(1f), color = p.text, style = FinType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp))
            StatusPill(status)
        }
        Text(e.subtitle, color = p.text2, style = FinType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A dashboard row of the two-pane list (UI spec 4.5): 56dp; selected = surfaceRaised fill and a 3dp accent bar at the left. */
@Composable
private fun DashRow(e: Entry, status: DashStatus?, selected: Boolean, onOpen: () -> Unit) {
    val p = LocalFinPalette.current
    Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) p.surfaceRaised else Color.Transparent)
        .clickable(onClick = onOpen).testTag(Tags.entry(e.title)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) p.accent else Color.Transparent))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(e.title, color = p.text, style = FinType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.subtitle, color = p.text2, style = FinType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusPill(status)
        Spacer(Modifier.width(8.dp))
    }
}

/** Config: "Colour-blind safe" (preference fin_cvd): up turns blue, down amber (UI spec 1.1). */
@Composable
private fun CvdRow() {
    val ctx = LocalContext.current
    val p = LocalFinPalette.current
    Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(FinDim.cardRadius)).background(p.surface).border(1.dp, p.hairline, RoundedCornerShape(FinDim.cardRadius))
        .padding(horizontal = 12.dp).testTag("fin:cvd"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Colour-blind safe", color = p.text, style = FinType.body)
            Text("Up in blue, down in amber; the sign always shows direction", color = p.text2, style = FinType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(FinUi.cvd, onCheckedChange = { FinUi.setCvd(ctx, it) }, colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
    }
}

/** One `entry`: title and one-line description, opening its resolved target; with none it is greyed and says why. */
@Composable
private fun EntryRow(e: Entry, url: String?, onOpen: (String) -> Unit) {
    if (url == null) {
        KitCard(Modifier.alpha(0.5f).testTag(Tags.entry(e.title))) {
            KitSettingsRow(title = e.title, subtitle = e.subtitle + "\n" + e.note.ifBlank { stringResource(R.string.entry_planned) })
        }
    } else {
        KitCard(Modifier.testTag(Tags.entry(e.title))) {
            KitSettingsRow(title = e.title, subtitle = e.subtitle, onClick = { onOpen(url) },
                trailing = { Text(stringResource(R.string.entry_open), color = LocalKitPalette.current.accent) })
        }
    }
}

@Composable
private fun AboutPanel() {
    KitCard {
        KitSectionHeader(stringResource(R.string.app_name), stringResource(R.string.about_body, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHORT_SHA))
    }
    DataSourcesPanel()
}

internal fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
