package com.diegonmarcos.cloudnav.tracker

import android.app.DatePickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.cloudnav.maps.takeout.ImportController
import com.diegonmarcos.cloudnav.NavConfig
import com.diegonmarcos.cloudnav.maps.TimelineBuilder
import com.diegonmarcos.cloudnav.maps.TimelineSync
import com.diegonmarcos.cloudnav.maps.TimelineViews
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Explored > Trips: the Trip dashboards over THE timeline store (#1863 order: Day, Week, Month | World, Cities, Places, Trips, Insights |
 * Export/Import), read through the same [DayTimeline] / [Aggregates] the fleet door serves, past days from the [ExploredCache].
 * Owner corrections go to [CorrectionStore] and the list is rebuilt.
 * #1887 the aggregates come from the process-scoped [ExploredRepository] (painted at once from memory, refreshed in the background only
 * when stale); the Day tab reads one 48 h window ([DayLoad]) and names its places after the first paint; Health Connect is read in the
 * background at most every [INGEST_EVERY_MS]; the row counts are read when the Export/Import tab opens.
 */
class DayFragment : KitComposeFragment() {
    private val zone: ZoneId = ZoneId.systemDefault()
    private var ui by mutableStateOf(DayUi(LocalDate.now(), LocalDate.now(), loading = true))
    private var tab by mutableStateOf(DayTab.DAY)
    private val dayLoads = LatestOnly()
    /** The date, generation and time the shown day was built for: a resume rebuilds it only when one of them moved. */
    private var dayKey: Pair<LocalDate, Long>? = null
    private var dayBuiltMs = 0L

    override fun palette(): KitPalette = TrackerInk.palette()

    // #1853 Import tab: the system pickers (no storage permission: the picker hands out a read grant for the chosen zip, file or folder)
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importFrom(it) } }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { importFrom(it) } }

    /** Starts the import of a picked zip, JSON file or folder in the foreground service (progress notification, Cancel); the Day, Trips, Places, Cities and World views reload when it ends. */
    fun importFrom(uri: android.net.Uri) {
        tab = DayTab.IMPORT
        val act = activity
        TakeoutImportService.start(requireContext(), uri) { act?.runOnUiThread { if (isAdded) { load(ui.date); loadCounts() } } }
    }

    private fun repo() = ExploredRepository.get(requireContext().applicationContext)

    /** #1887 the snapshot's aggregate fields into the page state (the Day's own fields stay). */
    private fun DayUi.withSnapshot(s: ExploredSnapshot): DayUi {
        val ex = s.explored
        return copy(stats = ex.stats, places = ex.places, cities = ex.cities, countries = ex.countries, trips = ex.trips, homeOverride = s.home,
            rangeLabel = s.rangeLabel, digests = ex.cache.digests, world = ex.world, cityTable = ex.cityTable, cacheLine = s.cacheLine)
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val r = repo()
        r.current()?.let { ui = ui.withSnapshot(it) }   // #1887 a re-open or a strip tap paints the snapshot in memory at once
        viewLifecycleOwner.lifecycleScope.launch { r.state.collect { s -> if (s != null) ui = ui.withSnapshot(s) } }
    }

    override fun onResume() {
        super.onResume()
        val today = LocalDate.now(zone)
        val r = repo()
        r.open(zone, today)   // #1887 stale-while-revalidate: a refresh starts only when the snapshot is missing, stale or invalidated
        val live = ui.date == today && System.currentTimeMillis() - dayBuiltMs > LIVE_DAY_MS
        if (ui.day.isEmpty() || dayKey != (ui.date to r.generation) || live) load(ui.date)
        ingestLater()
        if (tab == DayTab.IMPORT) loadCounts()
    }

    /** #1887 Health Connect steps and sleep into the store, in the background and at most every [INGEST_EVERY_MS] (the engine's tick also ingests). */
    private fun ingestLater() {
        val now = System.currentTimeMillis()
        if (now - lastIngestMs < INGEST_EVERY_MS) return
        lastIngestMs = now
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { StepsRepository(app).ingestHealth(zone) }.getOrDefault(false) }
            if (ok && ui.date == ui.today) load(ui.date)
        }
    }

    /** #1887 the Export/Import page's row counts, read when that page is shown (never on the open path). */
    private fun loadCounts() {
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val c = withContext(Dispatchers.IO) { runCatching { ExploredStore.counts(app) }.getOrDefault(emptyMap()) }
            ui = ui.copy(counts = c)
        }
    }

    /** #1887 a write changed the store, the corrections or the home: the aggregates refresh in the background, the day reloads. */
    private fun changed() {
        val r = repo()
        r.invalidate()
        r.open(zone, LocalDate.now(zone))
        load(ui.date)
    }

    @Composable
    override fun Content() {
        DayScreen(ui, tab, { tab = it; if (it == DayTab.IMPORT) loadCounts() }, DayActions(
            onDate = { load(it) },
            onPickDate = {
                val d = ui.date
                DatePickerDialog(requireContext(), { _, y, m, day -> LocalDate.of(y, m + 1, day).takeIf { !it.isAfter(ui.today) }?.let { load(it) } }, d.year, d.monthValue - 1, d.dayOfMonth).show()
            },
            onConfirm = { correct(Edits.confirm(it)) },
            onEditPlace = { s, name -> correct(Edits.editPlace(s, name)) },
            onEditMode = { m, mode -> correct(Edits.editMode(m, mode)) },
            onDelete = { correct(Edits.delete(it)) },
            onSplit = { s, at -> correct(Edits.split(s, at)) },
            onResolve = { p -> withContext(Dispatchers.IO) { runCatching { PlaceNamer(requireContext().applicationContext).name(listOf(p)).firstOrNull() }.getOrNull() ?: p } },
            onOpenMap = { p ->
                if (p.located) {
                    // Cloud Nav's own map: the geo: hand-off MainActivity already routes to Places, kept inside this app
                    val q = "${p.lat},${p.lon}"
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("geo:$q?q=$q(" + android.net.Uri.encode(p.label ?: "") + ")")).setPackage(requireContext().packageName))
                }
            },
            onHome = { HomeStore(requireContext()).set(it); changed() },
            onImportFile = { pickFile.launch(arrayOf("*/*")) },
            onImportFolder = { pickFolder.launch(null) },
            onImportCancel = { ImportController.cancel() },
            onAddVisit = { a, b, name ->
                val near = ui.day.filterIsInstance<DaySeg.Stay>().firstOrNull { it.place?.located == true }?.place
                correct(Edits.addVisit(a, b, name, near))
            },
            onOpenDay = { d -> tab = DayTab.DAY; load(d) },
            data = dataActions(),
        ), zone)
    }

    private fun correct(c: Correction) {
        val app = requireContext().applicationContext
        // #1887 the store writes run off the main thread
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                CorrectionStore(app).add(c)
                runCatching { ExploredCache.markDirty(MapsDbDigestBackend(app), c.startMs, c.endMs, zone) }   // #1863 the corrected day is recomputed
            }
            changed()
        }
    }

    /** #1887 the shown day: painted from one 48 h window, the latest date only; its unnamed places are named after the paint. */
    private fun load(date: LocalDate) {
        val app = requireContext().applicationContext
        val today = LocalDate.now(zone)
        val gen = repo().generation
        ui = ui.copy(date = date, today = today, loading = true)
        dayLoads.launch(viewLifecycleOwner.lifecycleScope, Dispatchers.IO, { runCatching { compute(app, date, today) }.getOrNull() }) { next ->
            if (next == null) { ui = ui.copy(loading = false); return@launch }
            ui = ui.copy(date = date, today = today, day = next.day, fixes = next.fixes, steps = next.steps, sleep = next.sleep, homeOverride = HomeStore(app).get(), loading = false)
            dayKey = date to gen; dayBuiltMs = System.currentTimeMillis()
            nameLater(app, date, next.day)
        }
    }

    private class DayData(val day: List<DaySeg>, val fixes: List<PlaceFix>, val steps: StepsTotal, val sleep: DaySleep?)

    private fun compute(app: android.content.Context, date: LocalDate, today: LocalDate): DayData {
        val now = System.currentTimeMillis()
        val cs = CorrectionStore(app); val corrections = cs.all(); val memory = cs.memory()
        val a = DayTimeline.dayStart(date, zone); val b = minOf(DayTimeline.dayStart(date.plusDays(1), zone), now)
        // #1887 one 48 h window (the day and its 12 h sleep spill either side), never the whole history; no aggregate is folded here
        val d = DayLoad.day({ f, t -> TimelineSync.view(app, f, t) }, date, now, zone, corrections, memory)
        val store = d.window
        // the day's recorded fixes (engine + foreground tracker), read once per open; no new polling
        val fixes = runCatching { StepsRepository(app).fixes(a, b) }.getOrDefault(emptyList())
        // the store already holds the stop detector's segments; only a day it recorded nothing for is cut from the raw fixes
        val raw = if (fixes.isNotEmpty() && store.none { it.endMs > a && it.startMs < b }) FixSegmenter.segment(fixes, a, b, NavConfig.dayRules, zone) else d.day
        val sleep = DaySegments.sleepOf(d.stats, raw, NavGazetteer.get(app), zone)
        return DayData(DayTimeline.fillLinks(raw), fixes, stepsTotal(app, store, date, a, b), sleep)
    }

    /** Names the unnamed places of the shown day through the open reverse-geocode provider (cached, rate-limited), after the day is painted. */
    private fun nameLater(app: android.content.Context, date: LocalDate, day: List<DaySeg>) {
        if (day.none { it is DaySeg.Stay && it.place?.needsAddress == true }) return
        viewLifecycleOwner.lifecycleScope.launch {
            val named = withContext(Dispatchers.IO) { runCatching { PlaceNamer(app).name(day.map { (it as? DaySeg.Stay)?.place }) }.getOrNull() } ?: return@launch
            if (ui.date != date || ui.day !== day) return@launch   // a newer day is shown
            // #1853 `named` has one entry per row (null for a move), so a stay takes the entry at its own index; a running counter over stays only
            // handed every stay after the first move the previous row's (null) place, and the day showed "Missing visit" for it
            ui = ui.copy(day = day.mapIndexed { i, s -> if (s is DaySeg.Stay) s.copy(place = named[i]) else s }.let { l -> DayTimeline.fillLinks(l) })
        }
    }

    // --- #1863 Export / Import page ---

    private var pendingExport: Pair<ExportFormat, Pair<Long, Long>?>? = null
    private val pickExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val job = pendingExport; pendingExport = null
        if (uri != null && job != null) background("Export") { app ->
            val b = ExploredStore.bundle(app, job.second?.first, job.second?.second, zone)
            ExploredStore.write(app, uri, if (job.first == ExportFormat.KMZ) ExploredExport.kmz(b, zone) else ExploredExport.toJson(b).toByteArray(Charsets.UTF_8))
            "Exported ${b.segments.size} segments and ${b.fixes.size} positions as ${job.first.name}"
        }
    }
    private val pickRestore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) background("Restore") { app ->
            val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("cannot read the file")
            "Restored ${ExploredStore.restore(app, text, zone)} rows"
        }
    }

    /** Runs [work] off the main thread, shows its line on the Export / Import page and reloads. */
    private fun background(what: String, work: (android.content.Context) -> String) {
        val app = requireContext().applicationContext
        ui = ui.copy(dataStatus = "$what: running...")
        viewLifecycleOwner.lifecycleScope.launch {
            val line = withContext(Dispatchers.IO) { runCatching { work(app) }.fold({ it }, { "$what failed: ${it.message}" }) }
            ui = ui.copy(dataStatus = line)
            changed(); loadCounts()
        }
    }

    private fun dataActions() = DataActions(
        onExport = { fmt, range ->
            pendingExport = fmt to range
            val d = range?.let { java.time.Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate() to java.time.Instant.ofEpochMilli(it.second - 1).atZone(zone).toLocalDate() }
            pickExport.launch(ExploredExport.fileName(d?.first, d?.second, if (fmt == ExportFormat.KMZ) "kmz" else "json"))
        },
        onRestore = { pickRestore.launch(arrayOf("application/json", "*/*")) },
        onDedupePreview = {
            val app = requireContext().applicationContext
            ui = ui.copy(dataStatus = "Looking for duplicates...")
            viewLifecycleOwner.lifecycleScope.launch {
                val p = withContext(Dispatchers.IO) { runCatching { ExploredStore.dedupePlan(app, zone) }.getOrNull() }
                ui = ui.copy(dedupe = p, dataStatus = if (p == null) "Duplicate search failed" else "${p.total} duplicates found")
            }
        },
        onDedupeConfirm = { p -> ui = ui.copy(dedupe = null); background("Delete duplicates") { app -> "Deleted ${ExploredStore.applyDedupe(app, p, zone)} duplicates" } },
        onDedupeCancel = { ui = ui.copy(dedupe = null) },
        onRangePreview = { f, t ->
            val app = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                val p = withContext(Dispatchers.IO) { runCatching { ExploredStore.rangePreview(app, f, t) }.getOrNull() }
                ui = ui.copy(rangePreview = p?.let { (f to t) to it })
            }
        },
        onRangeConfirm = { f, t -> ui = ui.copy(rangePreview = null); background("Delete by dates") { app -> val p = ExploredStore.deleteRange(app, f, t, zone); "Deleted ${p.deleteIds.size} segments, cut ${p.updates.size}, removed ${p.fixIds.size} positions" } },
        onRangeCancel = { ui = ui.copy(rangePreview = null) },
        onPickRange = { cb ->
            val d = ui.date
            DatePickerDialog(requireContext(), { _, y, m, day ->
                val from = LocalDate.of(y, m + 1, day)
                DatePickerDialog(requireContext(), { _, y2, m2, d2 -> val to = LocalDate.of(y2, m2 + 1, d2); cb(minOf(from, to), maxOf(from, to)) }, y, m, day).show()
            }, d.year, d.monthValue - 1, d.dayOfMonth).show()
        },
        onDeleteAll = { background("Delete all") { app -> ExploredStore.deleteAll(app); "Deleted all location data and the cache" } },
        onRefresh = { background("Refresh") { app -> ExploredStore.refresh(app, System.currentTimeMillis(), zone); "Recomputed every day from the stored data" } },
    )

    companion object {
        /** #1887 Health Connect is read at most this often from the page (the engine's tick reads it too). */
        const val INGEST_EVERY_MS = 10 * 60_000L
        /** #1887 today's shown day is rebuilt on a resume after this long (its rows still grow). */
        const val LIVE_DAY_MS = 60_000L
        @Volatile private var lastIngestMs = 0L
    }

    /** The day's steps from the step counter (recorded by the tracker engine into the store), else n/a and why. */
    private fun stepsTotal(app: android.content.Context, store: List<com.diegonmarcos.cloudnav.maps.TlSegment>, date: LocalDate, a: Long, b: Long): StepsTotal {
        // #1851 the day's total as the one store holds it (Health Connect's, the same number Tracker > Steps shows), else the attributed slices
        StepsFromStore.read(app, date, date)[date.toString()]?.let { return StepsTotal(it, null) }
        val n = TimelineBuilder.stepsIn(TimelineViews.stepSlices(store), a, b)
        if (n > 0) return StepsTotal(n, null)
        val recog = android.os.Build.VERSION.SDK_INT < 29 ||
            androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return StepsTotal(null, when {
            !recog -> "physical-activity permission not granted (Configs > Perms)"
            !EnginePrefs(app).config.enabled -> "tracker engine is off (Configs > Tracker)"
            else -> "the step counter recorded no steps for this day"
        })
    }
}
