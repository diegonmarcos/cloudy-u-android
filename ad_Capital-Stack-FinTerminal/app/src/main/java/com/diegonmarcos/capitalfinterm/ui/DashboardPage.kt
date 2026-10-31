package com.diegonmarcos.capitalfinterm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.diegonmarcos.capitalfinterm.Alerts
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.AssetHttp
import com.diegonmarcos.capitalfinterm.CachedHttp
import com.diegonmarcos.capitalfinterm.Catalog
import com.diegonmarcos.capitalfinterm.ChartPoint
import com.diegonmarcos.capitalfinterm.DashStatus
import com.diegonmarcos.capitalfinterm.Dashboard
import com.diegonmarcos.capitalfinterm.Dashboards
import com.diegonmarcos.capitalfinterm.Decl
import com.diegonmarcos.capitalfinterm.Engine
import com.diegonmarcos.capitalfinterm.FinFormat
import com.diegonmarcos.capitalfinterm.FinLogic
import com.diegonmarcos.capitalfinterm.FinSearchIndex
import com.diegonmarcos.capitalfinterm.Http
import com.diegonmarcos.capitalfinterm.Mesh
import com.diegonmarcos.capitalfinterm.ModelCtx
import com.diegonmarcos.capitalfinterm.Panel
import com.diegonmarcos.capitalfinterm.PanelData
import com.diegonmarcos.capitalfinterm.Priority
import com.diegonmarcos.capitalfinterm.R
import com.diegonmarcos.capitalfinterm.Source
import com.diegonmarcos.capitalfinterm.SourceKeys
import com.diegonmarcos.capitalfinterm.UrlHttp
import com.diegonmarcos.capitalfinterm.UserLists
import com.diegonmarcos.capitalfinterm.ui.fin.FinBars
import com.diegonmarcos.capitalfinterm.ui.fin.FinCard
import com.diegonmarcos.capitalfinterm.ui.fin.FinChartHeader
import com.diegonmarcos.capitalfinterm.ui.fin.FinChartSkeleton
import com.diegonmarcos.capitalfinterm.ui.fin.FinChip
import com.diegonmarcos.capitalfinterm.ui.fin.FinDim
import com.diegonmarcos.capitalfinterm.ui.fin.FinEmptyBody
import com.diegonmarcos.capitalfinterm.ui.fin.FinErrorBody
import com.diegonmarcos.capitalfinterm.ui.fin.FinEvent
import com.diegonmarcos.capitalfinterm.ui.fin.FinEventList
import com.diegonmarcos.capitalfinterm.ui.fin.FinEventSkeleton
import com.diegonmarcos.capitalfinterm.ui.fin.FinFooter
import com.diegonmarcos.capitalfinterm.ui.fin.FinHeatGrid
import com.diegonmarcos.capitalfinterm.ui.fin.FinKeyRow
import com.diegonmarcos.capitalfinterm.ui.fin.FinKpiGrid
import com.diegonmarcos.capitalfinterm.ui.fin.FinKpiSkeleton
import com.diegonmarcos.capitalfinterm.ui.fin.FinKpiTile
import com.diegonmarcos.capitalfinterm.ui.fin.FinLineChart
import com.diegonmarcos.capitalfinterm.ui.fin.FinMonitorRow
import com.diegonmarcos.capitalfinterm.ui.fin.FinMonitorSkeleton
import com.diegonmarcos.capitalfinterm.ui.fin.FinNewsList
import com.diegonmarcos.capitalfinterm.ui.fin.FinNote
import com.diegonmarcos.capitalfinterm.ui.fin.FinPrefs
import com.diegonmarcos.capitalfinterm.ui.fin.FinQuote
import com.diegonmarcos.capitalfinterm.ui.fin.FinSkeleton
import com.diegonmarcos.capitalfinterm.ui.fin.FinState
import com.diegonmarcos.capitalfinterm.ui.fin.FinTable
import com.diegonmarcos.capitalfinterm.ui.fin.FinTableSkeleton
import com.diegonmarcos.capitalfinterm.ui.fin.FinTape
import com.diegonmarcos.capitalfinterm.ui.fin.FinTitleRow
import com.diegonmarcos.capitalfinterm.ui.fin.FinType
import com.diegonmarcos.capitalfinterm.ui.fin.FinWatchEmpty
import com.diegonmarcos.capitalfinterm.ui.fin.FinWatchRow
import com.diegonmarcos.capitalfinterm.ui.fin.Fresh
import com.diegonmarcos.capitalfinterm.ui.fin.LocalFinPalette
import com.diegonmarcos.superapp.uikit.KitState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * The process-wide network seam of the dashboards: Android's resolver through UrlHttp, an answer remembered for a minute. The mesh hosts
 * (taken from the baked fleet declaration) are the only ones allowed over plain http, WireGuard encrypting that hop.
 */
object Net {
    private val cleartext: Set<String> by lazy { Decl.fleetMesh.values.map { Mesh.hostOf(it) }.toSet() }
    val cache: CachedHttp by lazy { CachedHttp(UrlHttp(cleartextHosts = cleartext)) }

    @Volatile private var assets: ((String) -> String)? = null

    /** The network seam of the panels: `asset:` names a declared data file (the central bank calendar); every other address goes through the cache. */
    val http: Http by lazy { AssetHttp(cache) { name -> assets?.invoke(name) ?: throw java.io.IOException("no assets") } }

    /** Wires the declared data files and each source's declared schedule.ttl_s (a rate-limited feed is never asked more often than declared). */
    fun configure(ctx: android.content.Context, sources: List<Source>) {
        val app = ctx.applicationContext
        assets = { name -> AssetData.read(app, name) }
        val timed = sources.filter { it.endpoint.isNotEmpty() && !it.endpoint.startsWith(AssetHttp.ASSET) }
        cache.ttlFor = { url -> timed.firstOrNull { url.startsWith(it.endpoint) }?.let { it.ttlS * 1000L } }
    }

    /** Short timeouts: off the mesh the question must fail fast. */
    val probe: Http by lazy { UrlHttp(connectMs = 3_000, readMs = 4_000, cleartextHosts = cleartext) }

    /** The container actions: a start can take a while. */
    val control: Http by lazy { UrlHttp(connectMs = 3_000, readMs = 30_000, cleartextHosts = cleartext) }

    private val up = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /** Whether [healthUrl] answers, remembered for 30 seconds so the panels of a dashboard ask once. */
    fun reachable(healthUrl: String): Boolean {
        val now = System.currentTimeMillis()
        up[healthUrl]?.let { (at, ok) -> if (now - at < 30_000L) return ok }
        val ok = runCatching { probe.get(healthUrl); true }.getOrDefault(false)
        up[healthUrl] = now to ok
        return ok
    }

    fun forget() { up.clear(); cache.clear() }
}

/** The LIVE / PARTLY / KEY pill of a tab list tile (UI spec 3: up / amber / text3). */
fun statusWord(status: DashStatus): Pair<String, KitState> = when (status) {
    DashStatus.LIVE -> "LIVE" to KitState.OK
    DashStatus.PARTLY_LIVE -> "PARTLY" to KitState.WARN
    DashStatus.NEEDS_KEY, DashStatus.LAYOUT_ONLY -> "KEY" to KitState.IDLE
}

/**
 * The panels' answers, kept for the life of the process (UI spec 5.3: a second visit paints cached content at once and refreshes
 * silently). Keyed `dashboard/panel`. Observable, so the tape, search and the quote screen read what the panels fetched.
 */
object FinStore {
    data class Entry(val data: PanelData?, val fetchedAt: Long, val failures: Int, val error: String, val source: String)

    val entries = mutableStateMapOf<String, Entry>()

    fun key(d: Dashboard, p: Panel) = d.id + "/" + p.id
}

/** A quotes panel's rows ([label, last, chg, chg %, date]) as instruments. */
fun quotesOf(d: PanelData, betterWhen: String? = null): List<FinQuote> = d.rows.map { r ->
    val label = r.getOrElse(0) { "" }
    FinQuote(symbol = label, last = r.getOrElse(1) { "" }, chg = r.getOrElse(2) { "" }, pct = r.getOrElse(3) { "" }, date = r.getOrElse(4) { "" },
        lowerBetter = betterWhen != null && FinFormat.lowerBetter(betterWhen, label))
}

/** A calendar panel's rows ([time, country, event, importance, values...]) as events. */
fun eventsOf(d: PanelData, source: String): List<FinEvent> = d.rows.mapIndexed { i, r ->
    FinEvent(id = "$i:" + r.getOrElse(0) { "" }, ts = r.getOrElse(0) { "" }, country = r.getOrElse(1) { "" }, title = r.getOrElse(2) { "" },
        importance = FinLogic.importance(r.getOrNull(3)), afp = FinLogic.afp(d.columns, r), source = source)
}

/** What the screens ask of the navigation (AppShell holds the stack). */
interface FinNav {
    fun openDashboard(id: String)
    fun openQuote(symbol: String)
    fun openSources()
    fun search()
}

/** How a panel's body is drawn: the declared `display`, else by view (quotes = monitor rows; the user's watchlist = watch rows). */
private fun displayOf(panel: Panel): String = panel.raw.optString("display").ifEmpty {
    when {
        panel.fetch?.optString("each_user") == "watchlist" -> "watch"
        panel.view == Dashboards.VIEW_QUOTES -> "rows"
        panel.view == Dashboards.VIEW_CALENDAR -> "calendar"
        else -> ""
    }
}

/**
 * One dashboard (data/dashboards.json, UI spec 3): the ticker tape when declared, then a card per panel in the declared order (the
 * panels without a source yet as key rows at the bottom), pull-to-refresh, each panel refreshed on its source's cadence while the
 * screen is resumed, the bottom inset so the island never covers a row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardPage(dashboardId: String, nav: FinNav) {
    val ctx = LocalContext.current
    val sources = remember { AssetData.sources(ctx) }
    val dash = remember(dashboardId) { Dashboards.byId(AssetData.dashboards(ctx), dashboardId) }
    val box = remember { EncryptedSecretBox(ctx) }
    val keys = remember { SourceKeys(box) }
    var user by remember { mutableStateOf(UserLists(Alerts.watchlist(box.get(KEY_WATCHLIST)), Alerts.rules(box.get(KEY_ALERTS)))) }
    remember(sources) { Net.configure(ctx, sources); true }
    val p = LocalFinPalette.current
    if (dash == null) {
        Box(Modifier.fillMaxSize().background(p.canvas).padding(FinDim.screenPad)) { FinEmptyBody(ctx.getString(R.string.dash_missing)) }
        return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val life by lifecycle.currentStateFlow.collectAsState()
    val resumed = life.isAtLeast(Lifecycle.State.RESUMED)
    var manual by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val order = remember(dash) { Dashboards.drawOrder(dash) }
    val live = remember(dash) { dash.panels.filter { it.isLive } }

    fun saveUser(next: UserLists) {
        user = next
        box.put(KEY_WATCHLIST, Alerts.watchlistText(next.watchlist))
        box.put(KEY_ALERTS, Alerts.rulesText(next.alerts))
    }

    // One cadence loop per live panel (UI spec 5.1): jittered, backed off after a failure, only while the screen is resumed.
    live.forEach { panel ->
        LaunchedEffect(dash.id, panel.id, resumed, user) {
            val source = Catalog.byId(sources, panel.source)
            if (!resumed || source == null) return@LaunchedEffect
            val key = FinStore.key(dash, panel)
            val every = panel.raw.optLong("refresh_s", 0L).takeIf { it > 0 }?.times(1000L)
                ?: FinFormat.policy(panel.raw.optString("refresh_source").ifEmpty { source.id }).everyMs
            val cached = FinStore.entries[key]
            val age = System.currentTimeMillis() - (cached?.fetchedAt ?: 0L)
            if (cached?.data != null && age < every) delay(every - age)
            while (isActive) {
                loadPanel(dash, panel, sources, source, user)
                delay(FinFormat.nextDelay(every, FinStore.entries[key]?.failures ?: 0, Math.random()))
            }
        }
    }
    // Pull-to-refresh (UI spec 2.12): every live panel again in parallel; content stays (no skeleton); the indicator at most 8 s.
    LaunchedEffect(manual) {
        if (manual == 0) return@LaunchedEffect
        refreshing = true
        Net.forget()
        val failed = withTimeoutOrNull(8_000) {
            coroutineScope {
                live.mapNotNull { pnl -> Catalog.byId(sources, pnl.source)?.let { src -> async { loadPanel(dash, pnl, sources, src, user) } } }.awaitAll().count { !it }
            }
        }
        refreshing = false
        if (failed != null && failed > 0) snack.showSnackbar("$failed of ${live.size} panels did not refresh")
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(p.canvas).testTag(Tags.PAGE)) {
        val width = maxWidth
        val tapePanels = dash.panels.filter { it.raw.optBoolean("tape") }
        val tapeQuotes = tapePanels.mapNotNull { FinStore.entries[FinStore.key(dash, it)]?.data }.flatMap { quotesOf(it) }.take(12)
        // The tape hides on a compact-height (landscape) phone: it would be the third fixed bar (UI spec 4.5).
        val tapeWanted = tapePanels.isNotEmpty() && maxHeight >= 480.dp
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { manual += 1 }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(), state = rememberLazyListState(),
                contentPadding = PaddingValues(top = if (tapeWanted) 0.dp else FinDim.gap, bottom = FinDim.bottomInset),
                verticalArrangement = Arrangement.spacedBy(FinDim.gap),
            ) {
                if (tapeWanted) item(key = "tape") { FinTape(tapeQuotes.ifEmpty { null }, stale = false) { q -> nav.openQuote(q.symbol) } }
                if (dash.search) item(key = "search") { Box(Modifier.padding(horizontal = FinDim.screenPad)) { FinSearchField(onClick = { nav.search() }) } }
                if (dash.panels.any { displayOf(it) == "watch" }) {
                    item(key = "addrule") { Box(Modifier.padding(horizontal = FinDim.screenPad)) { AddRuleField(user) { saveUser(it) } } }
                }
                items(order, key = { it.id }) { panel ->
                    Box(Modifier.padding(horizontal = FinDim.screenPad)) {
                        PanelSlot(dash, panel, sources, keys, user, width - FinDim.screenPad * 2, nav,
                            onUser = { saveUser(it) },
                            onRemoved = { sym, prev ->
                                scope.launch {
                                    val r = snack.showSnackbar("$sym removed", actionLabel = "Undo")
                                    if (r == SnackbarResult.ActionPerformed) saveUser(prev)
                                }
                            },
                            onRefresh = { manual += 1 })
                    }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = FinDim.bottomInset))
    }
}

/** Fetches one panel through its priority route (the container's /v1 first when declared, then the direct source); true when it answered. */
private suspend fun loadPanel(dash: Dashboard, panel: Panel, all: List<Source>, source: Source, user: UserLists): Boolean {
    val key = FinStore.key(dash, panel)
    val got = withContext(Dispatchers.IO) {
        runCatching {
            val route = Priority.route(panel, dash, all, Decl.fleetMesh) { url -> Net.reachable(url) }
            val fallback = Catalog.byId(all, panel.fetch?.optJSONObject("fallback")?.optString("source").orEmpty())?.takeIf { it.feeds(dash.tab, dash.entry) }
            val model = if (panel.derive in Engine.MODEL_DERIVES) {
                val api = Priority.api(panel, dash, all, Decl.fleetMesh) { url -> Net.reachable(url) }
                val now = java.time.LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES).toString() + "Z"
                ModelCtx(all, dash, api, user, now)
            } else null
            Engine.load(panel, source, Net.http, LocalDate.now(ZoneOffset.UTC), route, fallback, model)
        }
    }
    val prev = FinStore.entries[key]
    FinStore.entries[key] = got.fold(
        { FinStore.Entry(it, System.currentTimeMillis(), 0, "", source.id) },
        { e -> FinStore.Entry(prev?.data, prev?.fetchedAt ?: 0L, (prev?.failures ?: 0) + 1, FinFormat.failureReason(e), source.id) },
    )
    return got.isSuccess
}

/** The device-only slots of the watchlist and the alert rules (EncryptedSecretBox; no source id starts with "_"). */
internal const val KEY_WATCHLIST = "_watchlist"
internal const val KEY_ALERTS = "_alerts"

/** The 48dp search field that opens the search overlay (UI spec 4.2). */
@Composable
fun FinSearchField(onClick: () -> Unit) {
    val p = LocalFinPalette.current
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(p.surfaceRaised).clickable(onClick = onClick).padding(horizontal = 12.dp)
        .testTag("fin:searchfield"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = p.text2)
        Spacer(Modifier.width(8.dp))
        Text("Symbol, index, pair, country, event", color = p.text3, style = FinType.body)
    }
}

/** Watchlist & Alerts: one 48dp field takes a symbol (added to the watchlist) or a rule (AAPL > 230, AAPL < 200, events >= 3). */
@Composable
private fun AddRuleField(user: UserLists, onChange: (UserLists) -> Unit) {
    val p = LocalFinPalette.current
    var text by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    fun submit() {
        val rule = Alerts.rule(text)
        val sym = if (rule == null) Alerts.symbol(text) else null
        when {
            rule != null -> { onChange(user.copy(alerts = (user.alerts + rule).distinct())); text = "" }
            sym != null -> { onChange(user.copy(watchlist = (user.watchlist + sym).distinct())); text = "" }
            else -> bad = true
        }
    }
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(p.surfaceRaised).padding(horizontal = 12.dp).testTag("fin:addrule"),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = if (bad) p.down else p.text2)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Add symbol or rule (AAPL > 230)", color = p.text3, style = FinType.body)
            BasicTextField(text, { text = it; bad = false }, Modifier.fillMaxWidth(), singleLine = true, textStyle = FinType.body.copy(color = p.text),
                cursorBrush = SolidColor(p.accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
        }
        Text("Add", Modifier.clickable { submit() }.padding(8.dp), color = p.accent, style = FinType.body)
    }
}

/**
 * One panel: a 48dp key row when its source has no connector or key yet; otherwise a card with the title row, the body for the
 * panel's view in its state (skeleton / content / error / empty), the panel note and the one-line footer. Rows and calendars bleed
 * to the card edge; the title and footer keep the 12dp inset.
 */
@Composable
private fun PanelSlot(
    dash: Dashboard, panel: Panel, sources: List<Source>, keys: SourceKeys, user: UserLists, width: Dp, nav: FinNav,
    onUser: (UserLists) -> Unit, onRemoved: (String, UserLists) -> Unit, onRefresh: () -> Unit,
) {
    val ctx = LocalContext.current
    val segments = remember(dash, panel) { Dashboards.segments(dash, panel) }
    var seg by rememberSaveable(dash.id + panel.id) { mutableIntStateOf(0) }
    val shown = segments.getOrElse(seg) { panel }
    val source = Catalog.byId(sources, shown.source)
    if (!shown.isLive && !shown.isStatic) {
        val name = source?.name ?: shown.source
        val caption = when {
            source != null && source.needsKey && !keys.has(shown.source) -> "Add your $name key in Config > Sources"
            source != null && source.needsKey -> "$name key saved; connector pending"
            else -> "No connector yet: $name"
        }
        FinKeyRow(shown.id, shown.title, caption, onClick = { nav.openSources() })
        return
    }
    if (shown.isStatic) {
        FinCard {
            FinTitleRow(shown.title)
            FinBars(Engine.alloc(shown, dash.legs).chart, "%", shown.id)
        }
        return
    }
    val display = displayOf(shown)
    if (display == "tape") return
    val key = FinStore.key(dash, shown)
    val entry = FinStore.entries[key]
    val now = System.currentTimeMillis()
    val data = entry?.data
    val state: FinState<PanelData> = when {
        entry != null && data != null -> {
            val stale = entry.failures > 0 || FinFormat.isStale(entry.fetchedAt, now, shown.raw.optString("refresh_source").ifEmpty { source?.id.orEmpty() })
            val fetched = LocalTime.ofInstant(java.time.Instant.ofEpochMilli(entry.fetchedAt), java.time.ZoneId.systemDefault()).truncatedTo(ChronoUnit.MINUTES).toString()
            if (data.rows.isEmpty() && data.chart.isEmpty()) FinState.Empty(data.note.ifBlank { "Nothing in this window" })
            else FinState.Ok(data, data.asOf, fetched, data.servedBy, stale)
        }
        entry != null && entry.failures > 0 -> FinState.Failed(source?.name.orEmpty(), entry.error)
        else -> FinState.Loading
    }
    val ok = state as? FinState.Ok
    var menu by remember { mutableStateOf(false) }
    val bleed = display == "rows" || display == "watch" || display == "calendar"
    val inset = if (bleed) Modifier.padding(horizontal = FinDim.cardPad) else Modifier
    val chartish = shown.view == Dashboards.VIEW_CURVE || shown.view == Dashboards.VIEW_CANDLES
    FinCard(pad = !bleed, modifier = if (bleed) Modifier.padding(0.dp) else Modifier) {
        if (bleed) Spacer(Modifier.height(4.dp))
        if (!chartish) {
            Box(inset) {
                FinTitleRow(shown.title, stale = ok?.stale == true,
                    staleNote = if (entry == null) "" else "Last fetched ${ok?.fetchedAt.orEmpty()}" + if (entry.failures > 0) ", refresh failed: ${entry.error}" else "",
                    trailing = if (segments.size > 1) ({ SegmentChips(segments, sources, seg) { seg = it } }) else null)
            }
        }
        PanelBody(dash, shown, display, state, user, if (bleed) width else width - FinDim.cardPad * 2, nav, onUser, onRemoved, onRetry = onRefresh,
            header = if (chartish) ({ hover, ranges, range, onRange ->
                FinChartHeader(shown.title, hover, ranges, range, onRange, ok?.stale == true)
                if (segments.size > 1) Row(Modifier.padding(bottom = 4.dp)) { SegmentChips(segments, sources, seg) { seg = it } }
            }) else null)
        Column(inset) {
            ok?.data?.note?.takeIf { it.isNotBlank() && display != "calendar" }?.let { FinNote(it) }
            ok?.data?.alerts?.forEach { FinNote(it) }
            Box {
                FinFooter(
                    shown.id,
                    fresh = when {
                        entry == null -> Fresh.NONE
                        entry.failures > 0 -> Fresh.ERROR
                        ok?.stale == true -> Fresh.STALE
                        ok != null -> Fresh.FRESH
                        else -> Fresh.NONE
                    },
                    fetchedAt = ok?.fetchedAt.orEmpty(), asOf = ok?.asOf.orEmpty(), source = source?.name.orEmpty(), servedBy = ok?.servedBy.orEmpty(),
                    onTap = { menu = true },
                )
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Refresh now") }, onClick = { menu = false; onRefresh() })
                    if (source != null && source.docs.isNotBlank()) DropdownMenuItem(text = { Text("Open source page") }, onClick = { menu = false; openUrl(ctx, source.docs) })
                    DropdownMenuItem(text = { Text("Source settings") }, onClick = { menu = false; nav.openSources() })
                }
            }
        }
        if (bleed) Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SegmentChips(segments: List<Panel>, sources: List<Source>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEachIndexed { i, s ->
            val label = s.raw.optString("segment_label").ifEmpty { Catalog.byId(sources, s.source)?.name?.substringBefore(' ') ?: s.source }
            FinChip(label, i == selected, Modifier.height(24.dp)) { onSelect(i) }
        }
    }
}

/** The body of a live panel by its view and display, in its state (UI spec 2.x, each with its fixed loading height). */
@Composable
private fun PanelBody(
    dash: Dashboard, panel: Panel, display: String, state: FinState<PanelData>, user: UserLists, width: Dp, nav: FinNav,
    onUser: (UserLists) -> Unit, onRemoved: (String, UserLists) -> Unit, onRetry: () -> Unit,
    header: (@Composable (hover: String?, ranges: List<String>, range: String, onRange: (String) -> Unit) -> Unit)?,
) {
    val ctx = LocalContext.current
    val perRow = if (width >= 456.dp) 3 else 2
    val stale = (state as? FinState.Ok)?.stale == true
    if (header != null) {
        val candle = panel.view == Dashboards.VIEW_CANDLES
        val h = if (candle) FinDim.chartCandle else FinDim.chartLine
        var hover by remember { mutableStateOf<String?>(null) }
        val pts = (state as? FinState.Ok)?.data?.chart.orEmpty()
        val ranges = remember(pts) { FinLogic.ranges(pts, panel.view == Dashboards.VIEW_CURVE) }
        val prefKey = dash.id + "/" + panel.id
        var range by remember(prefKey) { mutableStateOf(FinPrefs.range(ctx, prefKey, if (candle) "6M" else "1Y")) }
        header(hover, ranges, range) { r -> range = r; FinPrefs.setRange(ctx, prefKey, r) }
        when (state) {
            FinState.Loading -> FinChartSkeleton(h)
            is FinState.Failed -> FinErrorBody(FinFormat.failureText(state.source, state.reason), h, onRetry)
            is FinState.Empty -> Box(Modifier.fillMaxWidth().height(h), contentAlignment = Alignment.Center) { FinEmptyBody(state.reason) }
            is FinState.Ok -> {
                val visible = if (ranges.isEmpty()) state.data.chart else FinLogic.range(state.data.chart, if (range in ranges) range else ranges.last())
                FinLineChart(visible, panel.view == Dashboards.VIEW_CURVE, h, panel.id) { hover = it }
                if (state.data.rows.isNotEmpty()) {
                    Spacer(Modifier.height(FinDim.gap))
                    FinTable(panel.id, state.data.columns, state.data.rows, state.data.links, declaredTypes(panel), width, limit = 5, details = state.data.details) { openUrl(ctx, it) }
                }
            }
        }
        return
    }
    when (state) {
        FinState.Loading -> when (display) {
            "kpi" -> FinKpiSkeleton(4, perRow)
            "rows", "watch" -> FinMonitorSkeleton()
            "calendar" -> FinEventSkeleton()
            else -> if (panel.view == Dashboards.VIEW_BARS || panel.view == Dashboards.VIEW_ALLOC) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { repeat(4) { FinSkeleton(null, 20.dp) } }
            } else FinTableSkeleton()
        }
        is FinState.Failed -> FinErrorBody(FinFormat.failureText(state.source, state.reason), if (display == "kpi") FinDim.kpi * 2 else 120.dp, onRetry)
        is FinState.Empty -> Box(Modifier.padding(horizontal = if (display == "rows" || display == "calendar") FinDim.cardPad else 0.dp)) { FinEmptyBody(state.reason) }
        is FinState.Ok -> {
            val data = state.data
            when (display) {
                "watch" -> WatchBody(data, user, stale, width, nav, onUser, onRemoved)
                "kpi" -> {
                    val unit = panel.raw.optString("change_unit")
                    val qs = quotesOf(data, panel.raw.optString("better_when"))
                    val tiles = ArrayList<@Composable (Modifier) -> Unit>()
                    qs.forEach { q -> tiles.add { m -> FinKpiTile(q, q.symbol, unit, stale, m) { nav.openQuote(q.symbol) } } }
                    val sp = panel.raw.optJSONObject("spread")
                    val of = sp?.optJSONArray("of")
                    val v = if (sp != null && of != null && of.length() == 2) FinLogic.spread(data.rows, of.optString(0), of.optString(1)) else null
                    if (sp != null && v != null) {
                        val q = FinQuote(sp.optString("label", "Spread"), last = FinFormat.signed(v, 0, " bp"), chg = "", pct = "", date = qs.firstOrNull()?.date.orEmpty())
                        tiles.add { m -> FinKpiTile(q, q.symbol, "", stale, m) }
                    }
                    FinKpiGrid(tiles, perRow)
                }
                "heat" -> FinHeatGrid(quotesOf(data), width, panel.raw.optDouble("heat_scale", 3.0), stale) { q -> nav.openQuote(q.symbol) }
                "rows" -> Column {
                    val qs = quotesOf(data, panel.raw.optString("better_when"))
                    qs.forEachIndexed { i, q -> FinMonitorRow(q, stale, width, divider = i < qs.size - 1) { nav.openQuote(q.symbol) } }
                }
                "calendar" -> {
                    var chip by rememberSaveable(panel.id) { mutableStateOf(0) }
                    val today = LocalDate.now(ZoneOffset.UTC)
                    val evs = eventsOf(data, panel.title).filter { FinLogic.inCalWindow(it.ts, chip, today) }
                    Column {
                        Row(Modifier.padding(horizontal = FinDim.cardPad, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FinLogic.CAL_CHIPS.forEachIndexed { i, l -> FinChip(l, i == chip, Modifier.height(24.dp)) { chip = i } }
                        }
                        if (evs.isEmpty()) Box(Modifier.padding(horizontal = FinDim.cardPad)) { FinEmptyBody(data.note.ifBlank { "No event in this window" }) }
                        else FinEventList(evs, panel.raw.optInt("limit", Int.MAX_VALUE))
                    }
                }
                else -> when (panel.view) {
                    Dashboards.VIEW_BARS, Dashboards.VIEW_ALLOC -> FinBars(data.chart.ifEmpty {
                        data.rows.mapIndexedNotNull { i, r -> FinFormat.parseNum(r.getOrNull(1))?.let { ChartPoint(r.getOrElse(0) { "" }, i.toDouble(), it) } }
                    }, panel.unit, panel.id)
                    Dashboards.VIEW_NEWS -> FinNewsList(panel.id, data.rows, data.links, panel.raw.optInt("limit", 8)) { openUrl(ctx, it) }
                    else -> FinTable(panel.id, data.columns, data.rows, data.links, declaredTypes(panel), width, details = data.details) { openUrl(ctx, it) }
                }
            }
        }
    }
}

private fun declaredTypes(panel: Panel): List<String> {
    val a = panel.raw.optJSONArray("col_types") ?: return emptyList()
    return (0 until a.length()).map { a.optString(it) }
}

/** The watchlist's symbols as swipeable tall rows with their alert rules (UI spec 2.9). */
@Composable
private fun WatchBody(data: PanelData, user: UserLists, stale: Boolean, width: Dp, nav: FinNav, onUser: (UserLists) -> Unit, onRemoved: (String, UserLists) -> Unit) {
    var alertFor by remember { mutableStateOf<String?>(null) }
    if (user.watchlist.isEmpty()) { FinWatchEmpty(onAdd = { nav.search() }); return }
    val byLabel = quotesOf(data).associateBy { it.symbol.uppercase() }
    Column {
        user.watchlist.forEach { sym ->
            val q = byLabel[sym.uppercase()] ?: FinQuote(sym, last = "", chg = "", pct = "", date = "")
            val rules = user.alerts.filter { it.text().uppercase().startsWith(sym.uppercase() + " ") }
            FinWatchRow(q, rules.map { it.text() }, stale, width,
                onOpen = { nav.openQuote(sym) },
                onRemove = { val prev = user; onUser(user.copy(watchlist = user.watchlist - sym)); onRemoved(sym, prev) },
                onAlert = { alertFor = sym },
                onRemoveRule = { t -> onUser(user.copy(alerts = user.alerts.filterNot { it.text() == t })) })
        }
    }
    alertFor?.let { sym ->
        AlertSheet(sym, onDismiss = { alertFor = null }) { text ->
            Alerts.rule(text)?.let { r -> onUser(user.copy(alerts = (user.alerts + r).distinct())) }
            alertFor = null
        }
    }
}

/** The alert rule sheet, prefilled with the symbol (`AAPL > 230`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertSheet(symbol: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val p = LocalFinPalette.current
    var text by remember { mutableStateOf("$symbol > ") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New alert", color = p.text, style = FinType.title)
            Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(p.surfaceRaised).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, textStyle = FinType.num.copy(color = p.text), cursorBrush = SolidColor(p.accent))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinChip("$symbol >", text.contains('>')) { text = "$symbol > " }
                FinChip("$symbol <", text.contains('<')) { text = "$symbol < " }
                Spacer(Modifier.weight(1f))
                FinChip("Save", true) { onSave(text) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The search catalogue: every dashboard, every quote and event the loaded panels answered with, the watchlist. */
fun searchCatalogue(dashboards: List<Dashboard>, watchlist: List<String>): List<FinSearchIndex.Item> {
    val out = ArrayList<FinSearchIndex.Item>()
    watchlist.forEach { out += FinSearchIndex.Item("Watchlist", "EQ", it, "", FinSearchIndex.KIND_SYMBOL, "") }
    FinStore.entries.forEach { (k, e) ->
        val data = e.data ?: return@forEach
        val d = dashboards.firstOrNull { it.id == k.substringBefore('/') } ?: return@forEach
        val panel = d.panels.firstOrNull { it.id == k.substringAfter('/') } ?: return@forEach
        when (panel.view) {
            Dashboards.VIEW_QUOTES -> {
                val (group, badge) = FinSearchIndex.groupOf(d.tab, d.id, panel.id)
                quotesOf(data).forEach { q ->
                    out += FinSearchIndex.Item(group, badge, q.symbol, panel.title, FinSearchIndex.KIND_SYMBOL, k, FinLogic.cell(q.last),
                        if (FinLogic.missing(q.pct)) "" else FinLogic.change(q.pct) + "%")
                }
            }
            Dashboards.VIEW_CALENDAR -> eventsOf(data, panel.title).forEach { ev -> out += FinSearchIndex.Item("Economic events", "EV", ev.title, ev.country, FinSearchIndex.KIND_DASHBOARD, d.id) }
        }
    }
    dashboards.forEach { d -> out += FinSearchIndex.Item("Dashboards", "DB", d.entry, d.tab.uppercase(), FinSearchIndex.KIND_DASHBOARD, d.id) }
    return out
}
