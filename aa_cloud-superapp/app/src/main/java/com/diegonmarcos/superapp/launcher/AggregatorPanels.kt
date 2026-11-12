package com.diegonmarcos.superapp.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.c3.C3HealthFragment
import com.diegonmarcos.superapp.cloud.C3MeshFragment
import com.diegonmarcos.superapp.cal.CalendarAgendaFragment
import com.diegonmarcos.superapp.cal.CalendarMonthFragment
import com.diegonmarcos.superapp.boards.TasksFragment
import com.diegonmarcos.superapp.configs.BadgePanes
import com.diegonmarcos.superapp.rss.RssFeedFragment
import com.diegonmarcos.superapp.settings.GridColumns
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos

/*
 * The panel half of the aggregator page: the card with its fold, the body each panel kind draws, the Filters row, and the
 * in-page anchors. The notification and feed bodies live beside it (AggregatorNotify / AggregatorFeeds).
 */

/**
 * In-page `anchor:` links for one stack. Generic — fed from the panels' own declarations, so no panel kind is special-cased
 * and no page is either. A tile whose target reads `anchor:<id>` scrolls the page it is already on to the view registered
 * under `<id>`, opening the owning card first (a collapsed body is not composed, so its children would not be there).
 * Undeclared ids are inert (false), so a stale link does nothing rather than jumping the page somewhere arbitrary.
 */
internal class PageAnchors(private val state: AggregatorState, private val scroll: ScrollState) {
    private val targets = HashMap<String, LayoutCoordinates>()
    private val owner = HashMap<String, Int>()
    private var content: LayoutCoordinates? = null

    init {
        state.panels.forEachIndexed { i, p ->
            if (p.anchor.isNotBlank()) owner.putIfAbsent(p.anchor, i)
            for (a in p.anchors) if (a.id.isNotBlank()) owner.putIfAbsent(a.id, i)
        }
    }

    fun declares(id: String) = id in owner

    companion object {
        /** [id] as the panel that now owns it names it: a retired alias maps to its panel's `anchor`, anything else is itself. */
        fun resolve(panels: List<Sections.StackPanel>, id: String): String =
            panels.firstOrNull { it.anchor.isNotBlank() && id in it.aliases }?.anchor ?: id
    }

    fun content(coords: LayoutCoordinates) { content = coords }

    fun target(id: String, coords: LayoutCoordinates) { if (id.isNotBlank() && declares(id)) targets[id] = coords }

    /** True when [target] was an `anchor:` link naming a declared id and it was handled — the caller must then NOT fall
     *  through to the navigating dispatcher. */
    fun dispatch(target: String, scope: CoroutineScope): Boolean {
        if (!target.startsWith(StackAnchors.PREFIX)) return false
        val id = target.removePrefix(StackAnchors.PREFIX).let { resolve(state.panels, it) }
        val idx = owner[id] ?: return false
        state.panelOpen[idx] = true
        scope.launch {
            // Two frames: the first composes the opened body, the second lays it out, and offsets are all zero before.
            withFrameNanos { }
            withFrameNanos { }
            val t = targets[id]?.takeIf { it.isAttached } ?: return@launch
            val c = content?.takeIf { it.isAttached } ?: return@launch
            scroll.animateScrollTo((t.positionInRoot().y - c.positionInRoot().y).toInt().coerceAtLeast(0))
        }
        return true
    }
}

/** Mark a header as an anchor target: the page scrolls here when an `anchor:<id>` link names it. */
internal fun Modifier.anchorTarget(anchors: PageAnchors, id: String?): Modifier =
    if (id.isNullOrBlank()) this else onGloballyPositioned { anchors.target(id, it) }

/** What the page hands every body: how to open a target, how to open a container, and where its embedded fragments live. */
internal class PageActions(
    val open: (String) -> Unit,
    val openContainer: (name: String, label: String, url: String) -> Unit,
    val host: Fragment,
)

// ── the panel card ───────────────────────────────────────────────────

@Composable
internal fun PagePanel(
    state: AggregatorState, index: Int, panel: Sections.StackPanel, anchors: PageAnchors, actions: PageActions,
) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    if (panel.kind == "entry") {
        // kind=entry - one titled line of a Cloud > Lnktree page: a card that opens its target, or a greyed "planned" one.
        PageEntry(panel, index, anchors, actions.open)
        return
    }
    if (panel.kind == "section_title") {
        // kind=section_title — a plain heading between cards (NOT a card).
        Text(panelTitle(ctx, panel), color = p.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, end = 4.dp, bottom = 6.dp)
                .anchorTarget(anchors, panel.anchor).testTag(AggTags.panel(index)))
        return
    }
    if (panel.kind == "notification_badges") {
        // #1068: the badges are One UI live cards straight on the page, not a titled, foldable card of controls.
        // `key` on the epoch: a return to the page re-reads every provider.
        Column(Modifier.fillMaxWidth().anchorTarget(anchors, panel.anchor).testTag(AggTags.panel(index))) {
            androidx.compose.runtime.key(state.badgeEpoch) { BadgeCards(summary = panel.summary) }
        }
        return
    }
    val open = state.isOpen(index, panel)
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(180), label = "panel-chevron")
    // A panel whose body is a child fragment stays COMPOSED while folded (zero-size): leaving the composition would
    // detach its container and the fragment, still attached to the manager, would have nowhere to draw when reopened.
    val keep = panel.kind in AggregatorState.EMBED_KINDS
    // #1068: a notification stream is not a card holding cards - the app containers sit straight on the One UI shade under a
    // plain section label (the label still folds the stream, so the page keeps its fold and nav re-tap behaviour).
    val bare = panel.kind == "notification_center"
    Column(
        Modifier.fillMaxWidth().padding(bottom = if (bare) 4.dp else 10.dp)
            .then(if (bare) Modifier else Modifier.clip(RoundedCornerShape(14.dp)).background(p.surface))
            .anchorTarget(anchors, panel.anchor).testTag(AggTags.panel(index)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { state.panelOpen[index] = !open }
                .padding(if (bare) PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp) else PaddingValues(14.dp))
                .testTag(AggTags.panelHeader(index)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bare) Text(panelTitle(ctx, panel), color = NotifCard.meta, fontSize = NotifCard.bodySp,
                fontWeight = FontWeight.Medium, fontFamily = NotifCard.font, modifier = Modifier.weight(1f))
            else Text(panelTitle(ctx, panel), color = p.accent, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = if (bare) NotifCard.meta else p.textPrimary,
                modifier = Modifier.size(if (bare) 18.dp else 20.dp).rotate(turn))
        }
        if (panel.subtitle.isNotBlank()) {
            Text(panel.subtitle, color = p.textPrimary, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.alpha(0.65f).padding(start = 14.dp, end = 14.dp, bottom = 6.dp))
        }
        if (open || keep) {
            // A notification shade is edge-to-edge: its group headers and rows supply their own structure, so the card's
            // inner gutter is chrome that only steals width from the list.
            val gutter = if (panel.kind == "notification_center") 0.dp else 16.dp
            Column(
                Modifier.fillMaxWidth().foldable(open).padding(start = gutter, top = 8.dp, end = gutter, bottom = 16.dp)
                    .testTag(AggTags.panelBody(index)),
            ) { PanelBody(state, index, panel, actions) }
        }
    }
}

/**
 * kind=entry: the title, a one-line description and where it leads. The target is resolved from a DECLARED source
 * ([LnktreeTargets]); an entry with none, or one marked `planned`, is drawn greyed with its short note and does nothing
 * when tapped. No numbers, no chart: nothing is shown that the entry does not really have.
 */
@Composable
private fun PageEntry(panel: Sections.StackPanel, index: Int, anchors: PageAnchors, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    val url = remember(panel) { LnktreeTargets.of(panel) }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(p.surface)
            .alpha(if (url != null) 1f else 0.45f)
            .then(if (url != null) Modifier.clickable { onOpen(url) } else Modifier)
            .anchorTarget(anchors, panel.anchor).testTag(AggTags.panel(index)).padding(14.dp),
    ) {
        Text(panelTitle(ctx, panel), color = p.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (panel.subtitle.isNotBlank()) {
            Text(panel.subtitle, color = p.textPrimary, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.alpha(0.65f).padding(top = 2.dp))
        }
        Text(if (url != null) "Open" else panel.note.ifBlank { "Planned" },
            color = if (url != null) p.accent else p.textPrimary, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 6.dp).testTag(if (url != null) "agg:entry:open" else "agg:entry:planned"))
    }
}

/** Zero-size and unplaced while [open] is false, full size otherwise — folds a body without leaving the composition. */
private fun Modifier.foldable(open: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    if (open) layout(placeable.width, placeable.height) { placeable.place(0, 0) } else layout(0, 0) { }
}

// ── body dispatch ────────────────────────────────────────────────────

@Composable
private fun PanelBody(state: AggregatorState, index: Int, panel: Sections.StackPanel, actions: PageActions) {
    val onOpen = actions.open
    when (panel.kind) {
        "c3_public" -> EmbeddedFragment(state, index, actions.host) { C3HealthFragment.newInstance(C3HealthFragment.SCOPE_PUBLIC) }
        "c3_private" -> EmbeddedFragment(state, index, actions.host) { C3HealthFragment.newInstance(C3HealthFragment.SCOPE_PRIVATE) }
        "wg_mesh" -> EmbeddedFragment(state, index, actions.host) { C3MeshFragment.newInstance() }
        "rss" -> EmbeddedFragment(state, index, actions.host) { RssFeedFragment.newInstance(panel.scopes) }
        "calendar_month" -> EmbeddedFragment(state, index, actions.host) { CalendarMonthFragment.newInstance() }
        "calendar_agenda" -> EmbeddedFragment(state, index, actions.host) { CalendarAgendaFragment.newInstance() }
        "tasks" -> EmbeddedFragment(state, index, actions.host) { TasksFragment.newInstance() }
        "linktree_slide" -> LinktreeSlide(panel.slideId, onOpen)
        "link_grid" -> LinkGrid(panel.columns, panel.links, onOpen)
        "tile_row" -> TileRow(panel.tiles, onOpen)
        // The inbox kinds: summary first, then what actually arrived.
        "mail_accounts" -> { MailAccounts(onOpen); InboxNotifications(state, panel, onOpen) }
        "chat_matrix" -> { ChatPlaceholder("Matrix", "page:chat/matrix", onOpen); InboxNotifications(state, panel, onOpen) }
        "chat_mattermost" -> { ChatPlaceholder("Mattermost", "page:chat/mattermost", onOpen); InboxNotifications(state, panel, onOpen) }
        "open_link" -> AggLinkRow(Sections.LinkItem(label = panel.title.ifBlank { panel.url }, url = panel.url, icon = panel.iconName), onOpen)
        "notification_center" -> NotificationCenterBody(state, panel, onOpen)
        // The two halves of the retired Push tab (#580): views into the one notification centre, drawn by BadgePanes
        // from ui.notification_center — no second producer.
        "notification_badges" -> BadgeCards()
        "notification_badge_configs" -> BadgePane(state) { redraw -> BadgePanes.badges(redraw) }
        "feed" -> FeedBody(panel, onOpen)
        "stats" -> { StatsBody(panel); InboxNotifications(state, panel, onOpen) }
        "class_inbox" -> ClassInboxBody(state, panel, onOpen)
        // Boards (BoardsPanels.kt): each reads its owner app live, never a number of its own.
        "board_inbox" -> BoardInboxBody(state, onOpen)
        "board_steps" -> BoardStepsBody(state.epoch, onOpen)
        "board_places" -> BoardPlacesBody(state.epoch, onOpen)
        "board_timeline" -> BoardTimelineBody(state.epoch, onOpen)
        "board_task_projects" -> BoardTaskProjectsBody(state.epoch, onOpen)
        "board_resources" -> ResourcesBody(panel.resource)
        "cloud_dashboard" -> CloudDashboard(state, panel, actions)
        else -> AggCaption(panel.subtitle.ifBlank { "Coming soon" })
    }
}

/**
 * A child fragment as a panel body: a FragmentContainerView on a fixed host id (View.generateViewId() crashes on
 * FragmentManager restore because the new id won't match the saved host; stable resource ids survive process death).
 * Committed once the host is in the window, replacing whatever a restore brought back — the same pattern the section
 * tab strip's panes use.
 */
@Composable
private fun EmbeddedFragment(state: AggregatorState, index: Int, host: Fragment, create: () -> Fragment) {
    val hostId = EMBED_HOST_IDS[(state.embedSlots[index] ?: 0) % EMBED_HOST_IDS.size]
    val committed = remember { BooleanArray(1) }
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx -> FragmentContainerView(ctx).apply { id = hostId } },
        update = { view ->
            if (!committed[0]) {
                committed[0] = true
                view.post {
                    if (host.isAdded) host.childFragmentManager.beginTransaction()
                        .replace(hostId, create()).commitAllowingStateLoss()
                }
            }
        },
    )
}

private val EMBED_HOST_IDS = intArrayOf(
    R.id.stack_embed_0, R.id.stack_embed_1, R.id.stack_embed_2, R.id.stack_embed_3,
    R.id.stack_embed_4, R.id.stack_embed_5, R.id.stack_embed_6, R.id.stack_embed_7,
)

/** A BadgePanes pane, redrawn whenever [AggregatorState.badgeEpoch] moves: on return to the page, and when a switch moved
 *  in one pane has to repaint the other. Live state goes stale the moment a service dies. */
@Composable
private fun BadgePane(state: AggregatorState, pane: @Composable (redraw: () -> Unit) -> Unit) {
    // `key` on the epoch: a bump disposes the pane and composes a fresh one, which is exactly the redraw both panes need.
    androidx.compose.runtime.key(state.badgeEpoch) { pane { state.badgeEpoch++ } }
}

// ── links and tiles ──────────────────────────────────────────────────

@Composable
private fun LinktreeSlide(slideId: String, onOpen: (String) -> Unit) {
    val slide = Sections.linktreeSlide(slideId)
    if (slide == null) { AggEmptyHint("linktree slide not found: $slideId"); return }
    LinkGrid(slide.columns, emptyList(), onOpen)
}

/** Sub-section header per column → N-icon grid of links beneath it. N: build.json::ui.tile_columns by default, the
 *  Controls stepper when set. */
@Composable
private fun LinkGrid(columns: List<Sections.LinkColumn>, flatLinks: List<Sections.LinkItem>, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val cols = remember { GridColumns.cloud(ctx) }
    val p = LocalKitPalette.current
    for (col in columns) {
        if (col.header.isNotBlank()) {
            Text(col.header, color = p.accent, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().then(if (col.headerUrl.isNotBlank()) Modifier.clickable { onOpen(col.headerUrl) } else Modifier)
                    .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 5.dp))
        }
        IconGrid(col.links, cols, onOpen)
    }
    if (flatLinks.isNotEmpty()) IconGrid(flatLinks, cols, onOpen)
}

/** `links` as a wrap-flowing `cols`-column grid of icon tiles; padding cells fill the final row. */
@Composable
private fun IconGrid(links: List<Sections.LinkItem>, cols: Int, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    for (row in links.chunked(cols.coerceAtLeast(1))) {
        Row(Modifier.fillMaxWidth()) {
            for (link in row) {
                val res = remember(link.icon) { Sections.iconResFor(ctx, link.icon) }
                Column(
                    Modifier.weight(1f).clickable { onOpen(link.url) }.padding(horizontal = 4.dp, vertical = 8.dp)
                        .testTag("agg:link:" + link.label),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (res != 0) Icon(painterResource(res), null, tint = p.textPrimary, modifier = Modifier.size(28.dp))
                    Text(link.label, color = p.textPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
            repeat(cols.coerceAtLeast(1) - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Mini-tile index row. The card look and the wrap-at-`ui.tile_columns` layout both live in [IndexTileGrid], because
 *  Configs ▸ About draws the same index and is not a stack. */
@Composable
private fun TileRow(tiles: List<Sections.AggTile>, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val cols = remember { GridColumns.cloud(ctx) }
    IndexTileGrid(cols, tiles.map { tile ->
        // `onOpen` (not the activity dispatcher directly) so `anchor:` tiles scroll THIS page.
        IndexTiles.Cell(label = tile.label, iconRes = Sections.iconResFor(ctx, tile.iconName), onClick = { onOpen(tile.target) })
    })
}

@Composable
private fun MailAccounts(onOpen: (String) -> Unit) {
    val accounts = remember { Sections.mailAccounts() }
    if (accounts.isEmpty()) {
        AggCaption("No accounts declared. Add via build.json::ui.mail_accounts or the Import flow."); return
    }
    for (acct in accounts) {
        val transport = when (acct.kind) {
            "jmap" -> "JMAP"
            "imap" -> "IMAP/STARTTLS"
            "imaps" -> "IMAPS · SMTPS"
            "exchange" -> "Exchange"
            else -> acct.kind.uppercase()
        }
        val portSuffix = when {
            acct.imapPort > 0 && acct.smtpPort > 0 -> "  · ${acct.imapPort}/${acct.smtpPort}"
            acct.imapPort > 0 -> "  · imap:${acct.imapPort}"
            acct.smtpPort > 0 -> "  · smtp:${acct.smtpPort}"
            else -> ""
        }
        AggLinkRow(Sections.LinkItem(label = "${acct.label}  ·  $transport$portSuffix", url = "section:mail"), onOpen)
    }
    AggCaption("Unread / total counts pending JMAP slice C2 + IMAP slice.")
}

@Composable
private fun ChatPlaceholder(kind: String, target: String, onOpen: (String) -> Unit) {
    AggLinkRow(Sections.LinkItem(label = "Open $kind", url = target), onOpen)
    AggCaption("server list + unread counts pending integration")
}

/** kind=stats — a mock dashboard surface: one label/value line per declared row, with a "mock data" footer so it is
 *  clear the numbers are placeholders until the card's live fetch is plumbed in. */
@Composable
private fun StatsBody(panel: Sections.StackPanel) {
    val p = LocalKitPalette.current
    if (panel.subtitle.isNotBlank()) AggCaption(panel.subtitle)
    for (r in panel.rows) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(r.label, color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(r.value, color = p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
    AggCaption("Mock data — live fetch pending")
}

// ── kind=cloud_dashboard ─────────────────────────────────────────────

private data class CloudTile(
    val label: String, val icon: String, val openUrl: String,
    val showLight: Boolean, val ping: Pair<String, Int>?,
    /** Container name, the key every declarative lookup and every ops route is addressed by. Blank for a provider console,
     *  which is a web page and not a container — those keep the open-the-URL behaviour. */
    val name: String = "",
)

/** The anchor id [panel] declared for the header identified by [groupId] + [subLabel] (blank = the group header itself),
 *  or null when the panel declared none — an undeclared header is simply not an anchor target. */
private fun declaredAnchor(panel: Sections.StackPanel, groupId: String, subLabel: String): String? =
    panel.anchors.firstOrNull { it.group == groupId && it.subgroup == subLabel }?.id

/** At most 8 pings in flight, as the executor this replaces. */
private val pingGate = Semaphore(8)

/**
 * Live container map. Decodes the baked service inventory, buckets every container by category into the card's groups,
 * draws a TCP-ping status dot per container ({name}.mesh.diegonmarcos.com — green up / red down / grey checking, meaningful only over
 * WireGuard) and opens the container sheet on tap. Provider groups render external consoles (no ping).
 */
@Composable
private fun CloudDashboard(state: AggregatorState, panel: Sections.StackPanel, actions: PageActions) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val anchors = LocalAnchors.current
    val dash = remember { Sections.cloudServices() }
    val cols = remember { GridColumns.cloud(ctx) }
    val wanted = panel.dashGroupIds.ifEmpty { dash.groups.map { it.id } }
    val groups = dash.groups.filter { it.id in wanted }
    // Only label the group inside the card when one card shows >1 group (the Others card = Providers + MCP & API).
    val showGroupHeader = groups.size > 1
    for (group in groups) {
        if (showGroupHeader) {
            // The card's sub-tables are anchor targets in their own right (`anchor:stack/providers`), so one card serves
            // several index entries without being split into panels the data does not have.
            Text(group.label, color = p.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                    .anchorTarget(anchors, declaredAnchor(panel, group.id, "")))
        }
        if (group.providers.isNotEmpty()) {
            CloudGrid(cols, group.providers.map { CloudTile(it.label, group.icon, it.url, showLight = false, ping = null) }, actions)
        }
        for (sub in group.subgroups) {
            if (sub.containers.isEmpty()) continue
            // Subgroups carry no id in cloud_services.json, so the panel's declaration names the label it binds to:
            // renaming the label breaks the binding loudly (the checker fails) instead of silently renaming the anchor.
            Text(sub.label, color = p.textPrimary.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
                    .anchorTarget(anchors, declaredAnchor(panel, group.id, sub.label)))
            CloudGrid(cols, sub.containers.map {
                when {
                    // Explicit open-URL (e.g. VM dashboard) — open it verbatim but still light up from the url:port ping.
                    it.link.isNotBlank() -> CloudTile(it.label, sub.icon, it.link, showLight = true,
                        ping = if (it.port in 1..65535) it.url to it.port else null, name = it.name)
                    it.external -> CloudTile(it.label, sub.icon, it.url, showLight = false, ping = null, name = it.name)
                    else -> CloudTile(it.label, sub.icon, "https://${it.url}", showLight = true,
                        ping = if (it.port in 1..65535) it.url to it.port else null, name = it.name)
                }
            }, actions)
        }
    }
}

/** Wrap-flowing `cols`-column grid of cloud tiles (same grid as the link icon grid, padding cells fill the last row). */
@Composable
private fun CloudGrid(cols: Int, tiles: List<CloudTile>, actions: PageActions) {
    for (row in tiles.chunked(cols.coerceAtLeast(1))) {
        Row(Modifier.fillMaxWidth()) {
            for (t in row) CloudTileCell(t, actions, Modifier.weight(1f))
            repeat(cols.coerceAtLeast(1) - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Icon + STATUS LIGHT (under the icon) + label. Tap opens the container sheet — Infos / Actions for the box and the app
 *  inside it; a provider console has no container behind it and still opens its URL. */
@Composable
private fun CloudTileCell(t: CloudTile, actions: PageActions, modifier: Modifier) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val res = remember(t.icon) { Sections.iconResFor(ctx, t.icon) }
    var up by remember(t.ping) { mutableStateOf<Boolean?>(null) }
    t.ping?.let { (host, port) ->
        LaunchedEffect(host, port) {
            up = pingGate.withPermit {
                withContext(Dispatchers.IO) {
                    try { java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 1200); true } }
                    catch (_: Throwable) { false }
                }
            }
        }
    }
    Column(
        modifier.clickable {
            // Every tile in a container subgroup IS a container, so the tap opens the sheet that can describe and operate it.
            if (t.name.isNotBlank()) actions.openContainer(t.name, t.label, t.openUrl) else actions.open(t.openUrl)
        }.padding(horizontal = 4.dp, vertical = 8.dp).testTag("agg:cloud:" + t.label),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (res != 0) Icon(painterResource(res), null, tint = p.textPrimary, modifier = Modifier.size(28.dp))
        if (t.showLight) {
            Box(Modifier.padding(top = 3.dp).size(8.dp).clip(CircleShape).background(when (up) {
                true -> p.ok
                false -> p.bad
                null -> p.textPrimary.copy(alpha = 0.4f)
            }).testTag("agg:cloud-dot:" + t.label))
        }
        Text(t.label, color = p.textPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp))
    }
}

internal val LocalAnchors = androidx.compose.runtime.staticCompositionLocalOf<PageAnchors> {
    error("PageAnchors read outside the aggregator page")
}

// ── the Filters row ──────────────────────────────────────────────────

/**
 * The declared toggles, one segmented control each: a single rounded track holding equal-weight segments with exactly one
 * filled pill — the selected value is the only thing on the row with a background, which is what makes it legible at a
 * glance. Collapsible: five of the six Notify tabs exist precisely BECAUSE their filters are already chosen for them, so
 * they start folded behind one line; the All tab, where the filters are the point, starts open. The default is declared
 * as a filter with a "__"-prefixed id (not drawn), so reopening is remembered per page like every other choice.
 */
@Composable
internal fun FilterRow(state: AggregatorState) {
    val p = LocalKitPalette.current
    val hostView = LocalView.current
    val tick = state.epoch   // a pick re-reads the stored selection of every row (it can reset its taxonomy sibling)
    var open by remember { mutableStateOf(state.selection(AggregatorModel.FILTER_COLLAPSED, "no") != "yes") }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(if (open) "Filters  ▾" else "Filters  ▸", color = p.textPrimary, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().alpha(0.55f).clickable {
                Haptics.tap(hostView)
                open = !open
                com.diegonmarcos.superapp.launcher.StackFilters.select(state.ctx, state.page,
                    AggregatorModel.FILTER_COLLAPSED, if (open) "no" else "yes")
            }.padding(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 6.dp).testTag(AggTags.FILTERS_TOGGLE))
        if (!open) return@Column
        for (filter in state.filters.filterNot { it.id.startsWith(AggregatorModel.SETTING_PREFIX) }) {
            AggCaption(filter.label)
            val active = remember(tick, filter.id) { state.selected(filter) }
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(22.dp))
                .background(p.textPrimary.copy(alpha = 0.08f)).padding(3.dp)) {
                for (option in filter.options) {
                    val on = option.id == active
                    Text(option.label, color = if (on) p.tileInk else p.textSecondary, fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(19.dp))
                            .background(if (on) p.textPrimary else Color.Transparent)
                            .clickable { state.select(filter, option.id) }
                            .padding(horizontal = 8.dp, vertical = 7.dp).testTag(AggTags.filter(filter.id, option.id)))
                }
            }
        }
    }
}

// ── the Settings section: every control of the page, folded, at the very bottom (#1068) ──────────────

/**
 * The page is for LOOKING: the badge cards, then the notifications. Everything that configures it — the Filters toggles
 * and the panels flagged `bottom` in build.json (badge switches, order, customisation) — lives in this one folded
 * section after the last card, the Archive and the page actions. Folded until opened; remembered per page.
 */
@Composable
internal fun ConfigSection(
    state: AggregatorState, hasFilters: Boolean, bottom: List<IndexedValue<Sections.StackPanel>>,
    anchors: PageAnchors, actions: PageActions, flat: Boolean = false,
) {
    if (!hasFilters && bottom.isEmpty()) return
    // #1672 the Notify configs (the page carrying the badge list) also lists its app sections and notification access here.
    val notifyPage = remember(state.panels) { state.panels.any { it.kind == "notification_badge_configs" } }
    val p = LocalKitPalette.current
    val hostView = LocalView.current
    // #1672 the Configs sub-page IS the configuration: always open, no fold to tap through.
    val open = flat || state.configOpen
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(180), label = "config-chevron")
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag(AggTags.CONFIG_SECTION)) {
        Row(
            Modifier.fillMaxWidth().clip(NotifCard.shape).background(NotifCard.surface)
                .clickable { if (!flat) { Haptics.tap(hostView); state.toggleConfigOpen() } }
                .padding(horizontal = NotifCard.pad, vertical = 14.dp).testTag(AggTags.CONFIG_HEADER),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // #1672 ONE level: the section IS "Configs" and opens the configs directly (it read "Settings" and held a
            // second foldable "Configs" card inside it).
            Text(com.diegonmarcos.superapp.notificationcenter.NotifySections.blockLabel("configs", "Configs"),
                color = NotifCard.title, fontSize = NotifCard.titleSp, fontWeight = FontWeight.Medium,
                fontFamily = NotifCard.font, modifier = Modifier.weight(1f))
            if (!flat) Icon(painterResource(R.drawable.ic_chevron_right), null, tint = NotifCard.title,
                modifier = Modifier.size(20.dp).rotate(turn))
        }
        if (open) Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag(AggTags.CONFIG_BODY)) {
            if (hasFilters) FilterRow(state)
            for ((i, panel) in bottom) if (state.visible(panel)) {
                // The badge list is drawn straight into Configs under a plain label: no card, no second fold.
                if (panel.kind == "notification_badge_configs") Column(Modifier.fillMaxWidth().anchorTarget(anchors, panel.anchor)
                    .testTag(AggTags.panel(i))) {
                    ConfigLabel(panelTitle(LocalContext.current, panel).let { if (it.equals("Configs", true)) "Badges" else it })
                    BadgePane(state) { redraw -> BadgePanes.badges(redraw) }
                }
                else PagePanel(state, i, panel, anchors, actions)
            }
            if (notifyPage) {
                // #1672 what used to be page sections and is configuration: the app lists of Cloud / Phone Publisher
                // and the keep-alive list (read-only; their switches are the badge list above), and notification access.
                Column(Modifier.fillMaxWidth().testTag(AggTags.CONFIG_APPS)) {
                    for (sec in com.diegonmarcos.superapp.notificationcenter.NotifySections.appSections)
                        androidx.compose.runtime.key(sec.id) { AppSectionBlock(sec) }
                }
                NotificationAccessRow(actions.open)
            }
        }
    }
}

@Composable
private fun ConfigLabel(text: String) =
    Text(text, color = NotifCard.meta, fontSize = NotifCard.nameSp, fontWeight = FontWeight.Medium,
        fontFamily = NotifCard.font, maxLines = 1, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp))

/** #1672 Configs > notification access: granted or not, and the way to Configs > Perms. Never throws. */
@Composable
private fun NotificationAccessRow(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val granted = remember { runCatching { AggregatorModel.isNotificationAccessGranted(ctx) }.getOrDefault(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(NotifCard.shape).background(NotifCard.surface)
        .clickable { runCatching { onOpen(AggregatorModel.PERMS_TARGET) } }
        .padding(horizontal = NotifCard.pad, vertical = 12.dp).testTag(AggTags.CONFIG_ACCESS),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Notification access", color = NotifCard.title, fontSize = NotifCard.bodySp, fontFamily = NotifCard.font)
            val paused = remember { runCatching { com.diegonmarcos.superapp.system.SuperPowerSaving.pausedReason(ctx) }.getOrNull() }
            Text(if (granted) "granted · the Cloud and Phone blocks read the phone's notifications"
                else if (paused != null) "not granted · $paused (turn it off to restore notification access)"
                else "not granted · the Phone block cannot read notifications", color = NotifCard.meta,
                fontSize = NotifCard.metaSp, fontFamily = NotifCard.font)
        }
        Text("Perms", color = NotifCard.title, fontSize = NotifCard.metaSp, fontFamily = NotifCard.font)
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = NotifCard.title, modifier = Modifier.size(16.dp))
    }
}
