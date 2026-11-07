package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.drawable.toBitmap
import com.diegonmarcos.superapp.apps.PhoneSections
import com.diegonmarcos.superapp.apps.PhoneTaxonomy
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSearchBar
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatePill
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Store ▸ Phone ▸ Search. Compose ([Content], inside the Phone page), drawn with the fleet's
 * shared parts: libs:ui-kit's search field, state pills, banners and empty state, and the Store's
 * own [StoreRow] for every result row (the SAME row Phone and Cloud draw).
 *
 * BEFORE A QUERY — the landing. Our list (the constellation fleet's apps and the declared apps of
 * the install-source map, with this phone's DeclaredEdits) filed under SuperApp ▸ Phone's
 * taxonomy: one shelf per sub-category that holds one of our apps, each a title line and a
 * sideways-scrolling row of cards, in the order SuperApp lists them ([StoreSuggest.landing] over
 * the shared [PhoneTaxonomy] / [PhoneSections], laid out by the same PhoneTaxonomyLayout.bucket
 * SuperApp calls). An installed app's card says so and opens it; any other offers Install by the
 * path that app installs by (the fleet's release path, or its declared ladder).
 *
 * A QUERY — the sources declared under `search` in the install-source map, each on its own thread
 * with its own state, so one failing never blanks the others; the hits merged by package
 * ([StoreSuggest.merge]) and grouped under the first source that found each, installed first.
 * No source calls a sibling fleet app: what the phone has comes from the PackageManager and the
 * fleet manifest baked into this APK.
 */
object StoreSearchPage {

    const val FLEET = "fleet"
    const val DECLARED = "declared"
    const val GITHUB = "github"
    const val PLAY = "play"
    private const val FLEET_KIND_APP = "app"

    /** The ids a searcher exists for: the asset may order, label and drop them, not invent one. */
    private val SEARCHERS = setOf(FLEET, DECLARED, SourceResolver.KIND_FDROID, SourceResolver.KIND_IZZY, GITHUB, PLAY)

    // ── the source subpages below Search ──────────────────────────────
    /** The Store section these subpages belong to (build.json::ui.sections[].id) and its pages, in strip order. */
    const val SECTION = "search"
    /** Cloudy: our repo releases (the constellation fleet and the declared list). */
    const val PAGE_CLOUDY = "search-cloudy"
    /** Android: Aurora (Play, anonymous), F-Droid, IzzyOnDroid and GitHub APK sources. */
    const val PAGE_ANDROID = "search-android"
    /** Torrent: the host's torrent indexers; a result is downloaded by the torrent engine, not installed. */
    const val PAGE_TORRENT = "search-torrent"
    val PAGES = listOf(PAGE_CLOUDY, PAGE_ANDROID, PAGE_TORRENT)

    /** Which subpage an install-source search source belongs to: the fleet and the declared list are Cloudy, every other APK source is Android. */
    fun pageOf(sourceId: String): String = if (sourceId == FLEET || sourceId == DECLARED) PAGE_CLOUDY else PAGE_ANDROID

    /** Where a result goes: Torrent results are downloaded by the torrent engine, the others are installed (or opened) by the APK path. */
    fun handlerOf(page: String): String = if (page == PAGE_TORRENT) "torrent-download" else "install"

    fun pageLabel(page: String): String = when (page) { PAGE_CLOUDY -> "Cloudy"; PAGE_ANDROID -> "Android"; else -> "Torrent" }

    /** What a subpage searches, in a sentence (the pre-query line of Android; Cloudy shows our list, Torrent its sources). */
    fun pageBlurb(page: String): String = when (page) {
        PAGE_CLOUDY -> "Our repo releases: the Cloud constellation apps and the apps you declared."
        PAGE_ANDROID -> "Android APK sources: Aurora (Google Play, anonymous), F-Droid, IzzyOnDroid and GitHub. Results install through the Store's install path."
        else -> "Torrent indexers. Results download with the Torrent engine."
    }

    /** Compose test tags. */
    const val TAG_PAGES = "store-search:pages"
    fun pageTag(page: String) = "store-search:page:$page"
    const val TAG_LANDING = "store-search:landing"
    const val TAG_SHELF = "store-search:shelf:"
    const val TAG_SHELF_ROW = "store-search:shelf-row:"
    const val TAG_CARD = "store-search:card:"
    const val TAG_GROUP = "store-search:group:"

    /** One declared search source. [decl] is its asset entry (GitHub's templates live there). */
    class SearchSource(val id: String, val label: String, val decl: JSONObject)

    class Decl(val sources: List<SearchSource>, val foss: Set<String>) {
        fun label(id: String): String = sources.firstOrNull { it.id == id }?.label ?: id
        val order: List<String> get() = sources.map { it.id }
    }

    /** `search` out of the install-source map: the sources in their declared order. */
    fun decl(map: JSONObject, cfg: SourceResolver.Config): Decl {
        val s = map.optJSONObject("search") ?: return Decl(emptyList(), emptySet())
        val arr = s.getJSONArray("sources")
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            require(id in SEARCHERS) { "search source '$id' has no searcher" }
            SearchSource(id, o.optString("label").ifEmpty { cfg.kind(id)?.label ?: id }, o)
        }
        val foss = s.optJSONArray("foss")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        return Decl(list, foss)
    }

    // ── the page's static inputs, read once per process ────────────────
    // All three are assets or BuildConfig baked into the APK: they cannot change while it runs, and
    // a result row or a card must not re-parse them on the main thread each time it is drawn.

    private val fleetApps: List<Fleet.App> by lazy { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64) }
    private val fleetByPkg: Map<String, Fleet.App> by lazy { PhoneAppActions.fleetByPackage(fleetApps) }
    @Volatile private var sourcesMemo: JSONObject? = null
    @Volatile private var controlsMemo: StoreControls.Decl? = null
    @Volatile private var cfgMemo: SourceResolver.Config? = null

    private fun sourcesOf(ctx: Context): JSONObject = sourcesMemo ?: PhoneAppActions.sources(ctx).also { sourcesMemo = it }
    private fun controlsOf(ctx: Context): StoreControls.Decl = controlsMemo ?: StoreControls.load(ctx).also { controlsMemo = it }

    // ── searching (blocking; off the main thread) ──────────────────────

    private fun matches(q: String, vararg fields: String) = fields.any { q in it.lowercase() }

    /** One source's hits for [query]. Throws with the source's own reason. */
    fun searchOne(ctx: Context, cfg: SourceResolver.Config, d: Decl, src: SearchSource, query: String): List<StoreSuggest.Hit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val foss = src.id in d.foss
        return when (src.id) {
            FLEET -> fleetApps
                .filter { it.kind == FLEET_KIND_APP && !it.blocked && matches(q, it.id, it.label, it.pkg) }
                .map { StoreSuggest.Hit(FLEET, it.pkg, it.label, foss = foss) }
            DECLARED -> {
                val pm = ctx.packageManager
                declaredList(ctx, cfg).filterKeys { it !in fleetByPkg }.mapNotNull { (pkg, label) ->
                    val ext = DeclaredEdits.apply(ctx, cfg, pkg, SourceResolver.resolve(cfg, pkg))
                    if (!ext.declared) return@mapNotNull null
                    val name = pmLabel(pm, pkg) ?: label.takeIf { it != pkg } ?: StoreSuggest.prettyLabel(pkg)
                    if (!matches(q, pkg, name)) null
                    else StoreSuggest.Hit(DECLARED, pkg, name, foss = foss || ext.openSource)
                }
            }
            SourceResolver.KIND_FDROID -> FDroidIndex.search(ctx, cfg.fdroid, query).map {
                StoreSuggest.Hit(src.id, it.pkg, it.name, it.license, foss = true, repo = it.sourceCode)
            }
            SourceResolver.KIND_IZZY -> FDroidIndex.search(ctx, SourceResolver.izzy(cfg), query).map {
                StoreSuggest.Hit(src.id, it.pkg, it.name, it.license, foss = true, repo = it.sourceCode)
            }
            GITHUB -> {
                val t = src.decl
                val url = t.getString("url").replace("{q}", URLEncoder.encode(query.trim(), "UTF-8"))
                val body = SourceResolver.getBody(url) ?: return emptyList()
                val items = JSONObject(body).optJSONArray(t.getString("items")) ?: return emptyList()
                (0 until items.length()).map { i ->
                    val item = items.getJSONObject(i)
                    StoreSuggest.Hit(GITHUB, "", FeedViewer.fill(t.getString("title"), item), FeedViewer.fill(t.optString("subtitle"), item),
                        foss = foss, repo = FeedViewer.fill(t.getString("repo"), item), link = FeedViewer.fill(t.getString("link"), item))
                }
            }
            PLAY -> {
                val pa = cfg.playAnon ?: error(ctx.getString(R.string.store_search_play_unavailable))
                // FOSS when the package is also in a signed FOSS repo (read from the cached indexes).
                val fossSet = runCatching { FDroidIndex.packages(ctx, cfg.fdroid) }.getOrDefault(emptySet()) +
                    (cfg.izzy?.let { z -> runCatching { FDroidIndex.packages(ctx, z) }.getOrDefault(emptySet()) } ?: emptySet())
                try {
                    PlayAnonFetcher.search(ctx, pa, query).map { StoreSuggest.Hit(PLAY, it.pkg, it.title, it.creator, foss = it.pkg in fossSet) }
                } catch (e: PlayAnonFetcher.TokenUnavailable) {
                    error(ctx.getString(R.string.store_search_play_token))
                }
            }
            else -> error("search source '${src.id}' has no searcher")
        }
    }

    /** Every enabled source, merged, with each source's state. Blocking: the debug API's `search` op. */
    fun search(ctx: Context, cfg: SourceResolver.Config, query: String, enabled: (String) -> Boolean): Pair<List<StoreSuggest.Result>, Map<String, String>> {
        val d = decl(sourcesOf(ctx), cfg)
        val states = LinkedHashMap<String, String>()
        val hits = ArrayList<StoreSuggest.Hit>()
        for (s in d.sources) {
            if (!enabled(s.id)) { states[s.id] = "off"; continue }
            runCatching { searchOne(ctx, cfg, d, s, query) }
                .onSuccess { hits += it; states[s.id] = "${it.size} found" }
                .onFailure { states[s.id] = "failed: ${it.message ?: it.javaClass.simpleName}" }
        }
        return StoreSuggest.merge(d.order, hits) to states
    }

    /** The declared list: the map's apps, plus the packages this phone declared (label by package). */
    private fun declaredList(ctx: Context, cfg: SourceResolver.Config): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        cfg.apps.values.forEach { out[it.pkg] = it.label }
        DeclaredEdits.addedPackages(ctx).forEach { (p, l) -> out.putIfAbsent(p, l) }
        return out
    }

    private fun pmLabel(pm: android.content.pm.PackageManager, pkg: String): String? =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()

    private fun isInstalled(ctx: Context, pkg: String): Boolean =
        pkg.isNotEmpty() && runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    // ── the landing (blocking; off the main thread) ─────────────────────

    /**
     * One card's app: what it is and how it would be installed — the fleet path, its declared ladder,
     * or, when no rung here can serve it, [handoff] (the store page it needs) with [reason] why.
     */
    class Card(
        val s: StoreSuggest.Suggestion, val fleet: Fleet.App?, val ext: SourceResolver.External?,
        val handoff: PhoneAppActions.Action? = null, val reason: String? = null,
    )

    class Landing(val sections: List<StoreSuggest.Section>, val cards: Map<String, Card>)

    /** The source badges of a declared app: its rungs' catalogues, a GitHub-hosted vendor rung as GitHub. */
    private fun badges(cfg: SourceResolver.Config, d: Decl, ext: SourceResolver.External): List<String> =
        ext.sources.map { src ->
            if (src is SourceResolver.Source.Vendor && listOfNotNull(src.feed, src.apk).any { "github.com" in it }) d.label(GITHUB)
            else cfg.kind(cfg.kind(src.kind)?.member)?.label ?: src.kind
        }.distinct()

    /** OUR LIST, filed under the Phone taxonomy: the landing's sections, shelves and cards. */
    fun landing(ctx: Context, cfg: SourceResolver.Config): Landing {
        val sources = sourcesOf(ctx)
        val d = decl(sources, cfg)
        val pm = ctx.packageManager
        val cards = LinkedHashMap<String, Card>()
        val ours = fleetApps.filter { it.kind == FLEET_KIND_APP && !it.blocked }
        for (a in ours) {
            val installed = isInstalled(ctx, a.pkg) || a.altId?.let { isInstalled(ctx, it) } == true
            cards.putIfAbsent(a.pkg, Card(StoreSuggest.Suggestion(a.pkg, pmLabel(pm, a.pkg) ?: a.label, listOf(d.label(FLEET)), installed), a, null))
        }
        for ((pkg, label) in declaredList(ctx, cfg)) {
            if (pkg in fleetByPkg || pkg in cards) continue
            val ext = DeclaredEdits.apply(ctx, cfg, pkg, SourceResolver.resolve(cfg, pkg))
            // Undeclared on this phone, or no public source at all: nothing to suggest.
            if (!ext.declared || ext.unresolved != null) continue
            val name = pmLabel(pm, pkg) ?: label.takeIf { it != pkg } ?: StoreSuggest.prettyLabel(pkg)
            val actions = PhoneAppActions.forMissing(ctx, ext, null, sources, cfg)
            val reason = actions.firstOrNull { it.kind == PhoneAppActions.Kind.INSTALL }?.disabledReason
            cards[pkg] = Card(StoreSuggest.Suggestion(pkg, name, badges(cfg, d, ext), isInstalled(ctx, pkg)), null, ext,
                handoff = if (reason == null) null else actions.firstOrNull { it.kind == PhoneAppActions.Kind.ORIGIN }, reason = reason)
        }
        PhoneTaxonomy.prime(ctx, cards.mapValues { it.value.s.label })
        val sections = PhoneSections.loadFromBuildConfig().map { it.title to it.prefix }
        val folders = PhoneTaxonomy.folders.map { StoreSuggest.Folder(it.id, it.label) }
        return Landing(StoreSuggest.landing(sections, folders, cards.values.map { it.s }) { PhoneTaxonomy.folderIdOf(it.pkg, it.label, ctx) }, cards)
    }

    // ── the page ───────────────────────────────────────────────────────

    /** The Store's look as kit roles: its card, its tab fills and its state colours. */
    internal fun palette(ctx: Context): KitPalette {
        val tab = controlsOf(ctx).groupTab
        return KitPalette.fromArgb(
            surface = CARD, surfaceSelected = tab.fill ?: IDLE, textPrimary = tab.textActive, textSecondary = tab.text,
            accent = tab.fillActive ?: IDLE, hairline = IDLE, tileInk = tab.textActive, ok = OK, warn = WARN, bad = BAD,
        )
    }

    /** The page, to be drawn inside the Phone page's CloudKitTheme (palette: [palette]). */
    @Composable
    fun Content(cfg: () -> SourceResolver.Config?) {
        Page(LocalContext.current, cfg)
    }

    private enum class Licence(val label: Int) {
        ALL(R.string.store_trait_all), FOSS(R.string.store_trait_oss), PRIVATE(R.string.store_trait_private)
    }

    private sealed class SrcState {
        object Off : SrcState()
        object Busy : SrcState()
        class Done(val hits: List<StoreSuggest.Hit>) : SrcState()
        class Failed(val why: String) : SrcState()
    }

    private val main = Handler(Looper.getMainLooper())

    private fun resolveCfg(ctx: Context, cfg: () -> SourceResolver.Config?): SourceResolver.Config =
        cfg() ?: cfgMemo ?: PhoneAppActions.resolver(sourcesOf(ctx)).also { cfgMemo = it }

    /** The source pill the Torrent subpage draws too. */
    @Composable
    internal fun SourcePill(label: String, on: Boolean, tag: String, onClick: () -> Unit) = Pill(label, on, tag, onClick)

    @Composable
    private fun Pill(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
        val p = LocalKitPalette.current
        Text(label, color = p.textPrimary, fontSize = KitDensity.caption, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.padding(end = KitDensity.small / 2, bottom = KitDensity.small / 2).testTag(tag)
                .clip(RoundedCornerShape(KitDensity.corner)).background(if (on) p.accent else p.surfaceSelected)
                .clickable(onClick = onClick).padding(horizontal = KitDensity.small, vertical = KitDensity.small / 2))
    }

    @Composable
    private fun Page(ctx: Context, cfg: () -> SourceResolver.Config?) {
        var query by remember { mutableStateOf("") }
        var asked by remember { mutableStateOf("") }
        var licence by remember { mutableStateOf(Licence.ALL) }
        val off = remember { mutableStateListOf<String>() }
        val states = remember { mutableStateMapOf<String, SrcState>() }
        var declState by remember { mutableStateOf<Decl?>(null) }
        // The source subpage (Cloudy | Android | Torrent): the host's strip sets it through StorePages; a host that draws no strip
        // (SuperApp's embedded Store) gets the same pages as pills above the search field.
        var sub by remember { mutableStateOf(StorePages.page(SECTION, PAGE_CLOUDY)) }
        var generation by remember { mutableStateOf(0) }
        // Install progress per package, and a bump whenever the phone's packages or the declared
        // list change, which redraws every row and card that reads them.
        val lines = remember { mutableStateMapOf<String, String>() }
        var tick by remember { mutableStateOf(0) }
        var landingState by remember { mutableStateOf<Landing?>(null) }
        var landingError by remember { mutableStateOf<String?>(null) }
        val open = remember { mutableStateMapOf<String, Boolean>() }

        fun loadLanding() {
            thread(name = "store-search-landing") {
                val built = runCatching { resolveCfg(ctx, cfg).let { c -> decl(sourcesOf(ctx), c) to landing(ctx, c) } }
                main.post {
                    built.onSuccess { (d, l) -> declState = d; landingState = l; landingError = null }
                        .onFailure { landingError = it.message ?: it.javaClass.simpleName }
                }
            }
        }
        DisposableEffect(Unit) {
            loadLanding()
            sub = StorePages.page(SECTION, PAGE_CLOUDY)
            val stopSub = StorePages.observe(SECTION) { p -> main.post { sub = p } }
            // A package added, replaced or removed: every installed mark may have changed.
            val stop = StoreSizeProbe.watch(ctx) { main.post { tick++; loadLanding() } }
            onDispose { stop(); stopSub() }
        }

        fun runSource(c: SourceResolver.Config, d: Decl, s: SearchSource, q: String, gen: Int) {
            states[s.id] = SrcState.Busy
            thread(name = "store-search-${s.id}") {
                val r = runCatching { searchOne(ctx, c, d, s, q) }
                main.post {
                    if (gen != generation) return@post  // an older query's answer
                    states[s.id] = r.fold({ SrcState.Done(it) }, { SrcState.Failed(it.message ?: it.javaClass.simpleName) })
                }
            }
        }

        fun run() {
            val q = query.trim()
            asked = q
            generation++
            states.clear()
            if (q.isEmpty()) return
            // The Torrent subpage runs its own sources from `asked` (StoreTorrentSearch); the APK sources below are Cloudy and Android.
            if (sub == PAGE_TORRENT) return
            val c = runCatching { resolveCfg(ctx, cfg) }.getOrElse { landingError = it.message ?: it.javaClass.simpleName; asked = ""; return }
            val d = declState ?: decl(sourcesOf(ctx), c).also { declState = it }
            val gen = generation
            for (s in d.sources) if (pageOf(s.id) != sub) continue else if (s.id in off) states[s.id] = SrcState.Off else runSource(c, d, s, q, gen)
        }
        // Switching the subpage re-asks the open query on that page's sources.
        androidx.compose.runtime.LaunchedEffect(sub) { if (asked.isNotEmpty()) run() }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
            if (!StorePages.hostDrawsStrip) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(TAG_PAGES)) {
                    PAGES.forEach { pg -> Pill(pageLabel(pg), sub == pg, pageTag(pg)) { StorePages.select(SECTION, pg) } }
                }
            }
            KitSearchBar(query, { query = it; if (it.isBlank()) { asked = ""; generation++; states.clear() } }, { run() },
                placeholder = ctx.getString(R.string.store_search_hint), clearLabel = ctx.getString(R.string.store_search_clear))
            val full = declState
            // This subpage's sources only; Results sees a declaration narrowed to them, so its counts and groups are the page's.
            val d = full?.let { f -> Decl(f.sources.filter { pageOf(it.id) == sub }, f.foss) }
            if (sub == PAGE_TORRENT) {
                StoreTorrentSearch.Content(ctx, asked)
                return@Column
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                d?.sources?.forEach { s ->
                    Pill(s.label, s.id !in off, "store-search:source:${s.id}") {
                        if (s.id in off) off.remove(s.id) else off.add(s.id)
                        if (asked.isNotEmpty()) run()
                    }
                }
            }
            if (asked.isEmpty()) {
                // Our list is the Cloudy landing; Android has no list of its own before a query, only the sources above.
                if (sub == PAGE_CLOUDY) LandingView(ctx, cfg, landingState, landingError, lines, tick)
                else Text(pageBlurb(sub), color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption)
            } else if (d != null) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Licence.values().forEach { l -> Pill(ctx.getString(l.label), licence == l, "store-search:licence:${l.name}") { licence = l } }
                }
                Results(ctx, cfg, d, asked, states, licence, lines, open, tick, { tick++ }) { id ->
                    val c = runCatching { resolveCfg(ctx, cfg) }.getOrNull()
                    val s = d.sources.firstOrNull { it.id == id }
                    if (c != null && s != null) runSource(c, d, s, asked, generation)
                }
            }
        }
    }

    @Composable
    private fun Results(
        ctx: Context, cfg: () -> SourceResolver.Config?, d: Decl, asked: String, states: Map<String, SrcState>,
        licence: Licence, lines: MutableMap<String, String>, open: MutableMap<String, Boolean>, tick: Int,
        bump: () -> Unit, retry: (String) -> Unit,
    ) {
        val p = LocalKitPalette.current
        val hits = states.values.filterIsInstance<SrcState.Done>().flatMap { it.hits }
        val merged = StoreSuggest.merge(d.order, hits).filter {
            when (licence) { Licence.ALL -> true; Licence.FOSS -> it.foss; Licence.PRIVATE -> !it.foss }
        }
        val on = d.sources.count { states[it.id] !is SrcState.Off }
        val busy = d.sources.count { states[it.id] is SrcState.Busy }
        // Status first: what the search is doing, then what it found.
        when {
            on == 0 -> KitStatusBanner(ctx.getString(R.string.store_search_all_off), KitState.WARN, tag = "store-search:status")
            busy > 0 -> KitStatusBanner(ctx.getString(R.string.store_search_searching, busy, on), KitState.BUSY, tag = "store-search:status")
            merged.isEmpty() -> KitEmptyState(ctx.getString(R.string.store_search_empty_title), ctx.getString(R.string.store_search_empty, asked))
            else -> Text(ctx.getString(R.string.store_search_summary, merged.size, asked), color = p.textSecondary, fontSize = KitDensity.caption)
        }
        val installed = { pkg: String -> isInstalled(ctx, pkg) }
        for ((id, group) in StoreSuggest.groups(d.order, merged, installed)) {
            val src = d.sources.first { it.id == id }
            val st = states[id]
            Column(Modifier.fillMaxWidth().testTag(TAG_GROUP + id), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 4)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (st) {
                        is SrcState.Done -> KitStatePill(
                            if (st.hits.isEmpty()) ctx.getString(R.string.store_search_state_none) else ctx.getString(R.string.store_search_state_found, st.hits.size),
                            if (st.hits.isEmpty()) KitState.IDLE else KitState.OK)
                        is SrcState.Busy -> KitStatePill(ctx.getString(R.string.store_search_state_busy), KitState.BUSY)
                        is SrcState.Failed -> KitStatePill(ctx.getString(R.string.store_search_state_failed), KitState.BAD)
                        else -> KitStatePill(ctx.getString(R.string.store_search_state_off), KitState.IDLE)
                    }
                    Text(src.label, color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = KitDensity.small))
                }
                if (st is SrcState.Failed) {
                    KitStatusBanner(ctx.getString(R.string.store_search_failed, src.label, st.why), KitState.BAD, tag = "store-search:failed:$id",
                        action = KitAction(ctx.getString(R.string.store_search_retry), "store-search:retry:$id") { retry(id) })
                }
                if (st is SrcState.Done && st.hits.isNotEmpty() && group.isEmpty()) {
                    Text(ctx.getString(R.string.store_search_merged_up, st.hits.size), color = p.textSecondary, fontSize = KitDensity.caption)
                }
                group.forEach { r ->
                    ResultRow(ctx, cfg, d, r, lines, open, tick, bump)
                }
            }
        }
    }

    // ── one result: THE Store row ──────────────────────────────────────

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    private fun start(ctx: Context, intent: Intent?, what: String) {
        if (intent == null) { toast(ctx, ctx.getString(R.string.store_search_open_failed, what)); return }
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast(ctx, ctx.getString(R.string.store_search_open_failed, what)) }
    }

    /** The ladder a search result installs by: its declared one when we have it, else the rungs it was found on. */
    private fun ladderOf(ctx: Context, c: SourceResolver.Config, r: StoreSuggest.Result): SourceResolver.External {
        val ext = DeclaredEdits.apply(ctx, c, r.pkg, SourceResolver.resolve(c, r.pkg))
        if (ext.declared) return ext
        val found = listOfNotNull(
            SourceResolver.Source.FDroid.takeIf { SourceResolver.KIND_FDROID in r.sources },
            SourceResolver.Source.Izzy.takeIf { SourceResolver.KIND_IZZY in r.sources },
            SourceResolver.Source.PlayAnon.takeIf { PLAY in r.sources },
            SourceResolver.Source.Play.takeIf { PLAY in r.sources },
        )
        return SourceResolver.External(r.pkg, r.title, found.ifEmpty { ext.sources }, declared = false, integrity = ext.integrity)
    }

    /** The rung kinds a declare records for [ext] (the ones DeclaredEdits can read back). */
    private fun kindsOf(ext: SourceResolver.External): List<String> = ext.sources.mapNotNull {
        it.kind.takeIf { k -> k == SourceResolver.KIND_FDROID || k == SourceResolver.KIND_IZZY || k == SourceResolver.KIND_PLAY_ANON }
    }

    private fun install(ctx: Context, lines: MutableMap<String, String>, pkg: String, work: () -> String?) {
        lines[pkg] = ctx.getString(R.string.store_search_installing)
        thread(name = "store-search-install") {
            val err = runCatching { work() }.getOrElse { it.message ?: it.javaClass.simpleName }
            main.post {
                lines[pkg] = err?.let { ctx.getString(R.string.store_search_install_failed, it) } ?: ctx.getString(R.string.store_search_install_started)
            }
        }
    }

    @Composable
    private fun ResultRow(
        ctx: Context, cfg: () -> SourceResolver.Config?, d: Decl, r: StoreSuggest.Result,
        lines: MutableMap<String, String>, open: MutableMap<String, Boolean>, tick: Int, bump: () -> Unit,
    ) {
        val c = remember(r.key) { runCatching { resolveCfg(ctx, cfg) }.getOrNull() }
        val installed = remember(r.pkg, tick) { isInstalled(ctx, r.pkg) }
        val fleetApp = if (FLEET in r.sources) fleetByPkg[r.pkg] else null
        val verbs = ArrayList<StoreVerb>()
        val reasons = ArrayList<String>()
        fun button(label: String, onClick: (() -> Unit)?) { verbs += StoreVerb(label, null, onClick, "store-search:verb:${r.key}:${verbs.size}") }
        var quick: Pair<String, () -> Unit>? = null
        when {
            r.pkg.isEmpty() -> {
                val go = { start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(r.link.ifEmpty { r.repo })), r.title) }
                quick = ctx.getString(R.string.store_search_releases) to go
                button(ctx.getString(R.string.store_search_releases), go)
            }
            installed -> {
                val go = { start(ctx, ctx.packageManager.getLaunchIntentForPackage(r.pkg), r.title) }
                quick = ctx.getString(R.string.store_phone_open) to go
                button(ctx.getString(R.string.store_phone_open), go)
            }
            fleetApp != null -> {
                val go = { install(ctx, lines, r.pkg) { FleetInstall.run(ctx.applicationContext, fleetApp) } }
                quick = ctx.getString(R.string.store_phone_install) to go
                button(ctx.getString(R.string.store_phone_install), go)
            }
            c != null -> {
                val ext = ladderOf(ctx, c, r)
                for (a in PhoneAppActions.forMissing(ctx, ext, null, sourcesOf(ctx), c)) {
                    if (a.kind == PhoneAppActions.Kind.INSTALL) {
                        val go: (() -> Unit)? = if (a.disabledReason != null) null
                            else { { install(ctx, lines, r.pkg) { ExternalInstall.run(ctx.applicationContext, c, ext) } } }
                        if (go != null) quick = a.label to go
                        button(a.label, go)
                        a.disabledReason?.let { reasons += it }
                    } else button(a.label) { start(ctx, a.intent, a.label) }
                }
                if (!ext.declared && ext.direct.isNotEmpty()) button(ctx.getString(R.string.store_search_install_declare)) {
                    DeclaredEdits.declare(ctx, r.pkg, r.title, kindsOf(ext)); bump()
                    install(ctx, lines, r.pkg) { ExternalInstall.run(ctx.applicationContext, c, ladderOf(ctx, c, r)) }
                }
            }
        }
        // Declare / undeclare: the Declared list's own toggle, for any package that is not a fleet app.
        if (r.pkg.isNotEmpty() && fleetApp == null && c != null) {
            val declared = DeclaredEdits.apply(ctx, c, r.pkg, SourceResolver.resolve(c, r.pkg)).declared
            button(ctx.getString(if (declared) R.string.store_search_undeclare else R.string.store_search_declare)) {
                if (declared) DeclaredEdits.undeclare(ctx, r.pkg) else DeclaredEdits.declare(ctx, r.pkg, r.title, kindsOf(ladderOf(ctx, c, r)))
                bump()
            }
        }
        val facts = listOf(r.pkg, r.by, ctx.getString(if (r.foss) R.string.store_trait_oss else R.string.store_trait_private))
            .filter { it.isNotBlank() }.joinToString("  ·  ")
        val isOpen = open[r.key] == true
        StoreRow.Card(Modifier.fillMaxWidth().testTag("store-search:row:${r.key}")) {
            StoreRow.Head(r.title, isOpen, { open[r.key] = !isOpen },
                dot = "●", dotColor = if (installed) OK else if (r.pkg.isEmpty()) DIM else MISSING,
                meta = r.sources.joinToString(" · ") { d.label(it) },
                quick = quick?.let { (label, go) -> StoreQuick(label, Ink.GREY_FILL, go) })
            lines[r.pkg]?.takeIf { r.pkg.isNotEmpty() }?.let { StoreRow.Line(it, color = OK) }
            if (isOpen) {
                StoreRow.Line(facts)
                reasons.forEach { StoreRow.Line(it) }
                if (r.pkg.isEmpty()) StoreRow.Line(ctx.getString(R.string.store_search_repo_only))
                StoreRow.Actions(verbs)
            }
        }
    }

    // ── the landing's view ─────────────────────────────────────────────

    @Composable
    private fun LandingView(
        ctx: Context, cfg: () -> SourceResolver.Config?, landing: Landing?, error: String?,
        lines: MutableMap<String, String>, tick: Int,
    ) {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().testTag(TAG_LANDING), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
            KitSectionHeader(ctx.getString(R.string.store_landing_title), ctx.getString(R.string.store_landing_caption))
            when {
                error != null -> KitStatusBanner(ctx.getString(R.string.store_landing_failed, error), KitState.BAD, tag = "store-search:landing-failed")
                landing == null -> KitStatusBanner(ctx.getString(R.string.store_landing_loading), KitState.BUSY, tag = "store-search:landing-loading")
                landing.sections.isEmpty() -> KitEmptyState(null, ctx.getString(R.string.store_landing_empty))
                else -> landing.sections.forEach { section ->
                    KitSectionHeader(section.title ?: ctx.getString(R.string.store_phone_other), "", eyebrow = true)
                    section.shelves.forEach { shelf ->
                        // Line 1: the sub-category. Line 2: its apps, scrolling sideways.
                        Text(ctx.getString(R.string.store_landing_shelf, shelf.title, shelf.apps.size), color = p.textPrimary,
                            fontSize = KitDensity.title, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(TAG_SHELF + shelf.folderId))
                        LazyRow(Modifier.fillMaxWidth().testTag(TAG_SHELF_ROW + shelf.folderId),
                            horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                            items(shelf.apps, key = { it.pkg }) { s ->
                                landing.cards[s.pkg]?.let { AppCard(ctx, cfg, it, lines[s.pkg], tick, lines) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppCard(
        ctx: Context, cfg: () -> SourceResolver.Config?, card: Card, line: String?, tick: Int,
        lines: MutableMap<String, String>,
    ) {
        val p = LocalKitPalette.current
        val s = card.s
        val installed = remember(s.pkg, tick) { isInstalled(ctx, s.pkg) || card.fleet?.altId?.let { isInstalled(ctx, it) } == true }
        Column(
            Modifier.width(CARD_WIDTH).testTag(TAG_CARD + s.pkg).clip(RoundedCornerShape(KitDensity.corner))
                .background(p.surface).padding(KitDensity.small),
            verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2),
        ) {
            AppIcon(ctx, s, installed)
            Text(s.label, color = p.textPrimary, fontSize = KitDensity.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(s.badges.joinToString(" · "), color = p.textSecondary, fontSize = KitDensity.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (installed) {
                KitStatePill(ctx.getString(R.string.store_landing_installed), KitState.OK,
                    Modifier.clickable { start(ctx, ctx.packageManager.getLaunchIntentForPackage(s.pkg), s.label) })
            } else {
                val (label, go) = cardAction(ctx, cfg, card, lines)
                Text(label, color = p.tileInk, fontSize = KitDensity.caption, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(KitDensity.corner)).background(p.accent)
                        .clickable(onClick = go).padding(horizontal = KitDensity.small, vertical = KitDensity.small / 4))
            }
            line?.let { Text(it, color = p.textSecondary, fontSize = KitDensity.caption, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }

    /** What a not-installed card's button does: the fleet path, the declared ladder, or its store hand-off. */
    private fun cardAction(ctx: Context, cfg: () -> SourceResolver.Config?, card: Card, lines: MutableMap<String, String>): Pair<String, () -> Unit> {
        val installLabel = ctx.getString(R.string.store_phone_install)
        val f = card.fleet
        if (f != null) return installLabel to { install(ctx, lines, f.pkg) { FleetInstall.run(ctx.applicationContext, f) } }
        val ext = card.ext ?: return installLabel to { start(ctx, null, card.s.label) }
        // No rung we can serve: the store it needs, by its own page (Play, Galaxy Store, …), and why.
        val h = card.handoff
        if (card.reason != null) return (h?.label ?: installLabel) to {
            lines[ext.pkg] = card.reason
            start(ctx, h?.intent, h?.label ?: ext.label)
        }
        return installLabel to {
            val c = runCatching { resolveCfg(ctx, cfg) }.getOrNull()
            if (c == null) start(ctx, null, ext.label)
            else install(ctx, lines, ext.pkg) { ExternalInstall.run(ctx.applicationContext, c, ext) }
        }
    }

    @Composable
    private fun AppIcon(ctx: Context, s: StoreSuggest.Suggestion, installed: Boolean) {
        val p = LocalKitPalette.current
        val icon: ImageBitmap? = remember(s.pkg, installed) {
            if (!installed) null else runCatching {
                ctx.packageManager.getApplicationIcon(s.pkg).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
            }.getOrNull()
        }
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(KitDensity.glyph + KitDensity.small))
        else Box(Modifier.size(KitDensity.glyph + KitDensity.small).clip(CircleShape).background(p.surfaceSelected), contentAlignment = Alignment.Center) {
            Text(s.label.take(1).uppercase(), color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.Bold)
        }
    }

    /** A card is two avatars wide: three and a bit show on a phone, the rest scroll in. */
    private val CARD_WIDTH = KitDensity.avatar * 2
    private const val ICON_PX = 96
    // The Store's own colours (StoreRow's card, the idle tab, the Phone page's state colours).
    private const val CARD = 0xFF1C1C24.toInt()
    private const val IDLE = 0xFF2A2A33.toInt()
    private const val OK = 0xFF48BB78.toInt()
    private const val WARN = 0xFFF6AD55.toInt()
    private const val BAD = 0xFFE53E3E.toInt()
    private const val MISSING = 0xFF9F7AEA.toInt()
    private const val DIM = 0x99FFFFFF.toInt()
}
