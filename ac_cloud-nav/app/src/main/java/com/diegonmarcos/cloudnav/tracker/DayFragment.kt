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
 */
class DayFragment : KitComposeFragment() {
    private val zone: ZoneId = ZoneId.systemDefault()
    private var ui by mutableStateOf(DayUi(LocalDate.now(), LocalDate.now(), loading = true))
    private var tab by mutableStateOf(DayTab.DAY)

    override fun palette(): KitPalette = TrackerInk.palette()

    // #1853 Import tab: the system pickers (no storage permission: the picker hands out a read grant for the chosen zip, file or folder)
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importFrom(it) } }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { importFrom(it) } }

    /** Starts the import of a picked zip, JSON file or folder in the foreground service (progress notification, Cancel); the Day, Trips, Places, Cities and World views reload when it ends. */
    fun importFrom(uri: android.net.Uri) {
        tab = DayTab.IMPORT
        val act = activity
        TakeoutImportService.start(requireContext(), uri) { act?.runOnUiThread { if (isAdded) load(ui.date) } }
    }

    override fun onResume() { super.onResume(); load(ui.date) }

    @Composable
    override fun Content() {
        DayScreen(ui, tab, { tab = it }, DayActions(
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
            onHome = { HomeStore(requireContext()).set(it); load(ui.date) },
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
        CorrectionStore(requireContext()).add(c)
        runCatching { ExploredCache.markDirty(MapsDbDigestBackend(requireContext().applicationContext), c.startMs, c.endMs, zone) }   // #1863 the corrected day is recomputed
        load(ui.date)
    }

    private fun load(date: LocalDate) {
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val today = LocalDate.now(zone)
            ui = ui.copy(date = date, today = today, loading = true)
            val next = withContext(Dispatchers.IO) { runCatching { compute(app, date, today) }.getOrNull() }
            ui = next?.copy(dataStatus = ui.dataStatus, dedupe = ui.dedupe, rangePreview = ui.rangePreview) ?: ui.copy(loading = false)
        }
    }

    private fun compute(app: android.content.Context, date: LocalDate, today: LocalDate): DayUi {
        val now = System.currentTimeMillis()
        runCatching { kotlinx.coroutines.runBlocking { StepsRepository(app).ingestHealth(zone) } }   // #1851 Health Connect steps and sleep into the store first (compute runs off the main thread)
        val cs = CorrectionStore(app); val corrections = cs.all(); val memory = cs.memory()
        val first = AggregatesStore.windowStart(app, today, zone)   // 90 days (home detection; the month rankings use the last 30), or back to the first imported day (#1853)
        val store = TimelineSync.view(app, DayTimeline.dayStart(minOf(first, date), zone) - 12 * 3_600_000L, now)
        val a = DayTimeline.dayStart(date, zone); val b = minOf(DayTimeline.dayStart(date.plusDays(1), zone), now)
        // name the unnamed places of the shown day through the open reverse-geocode provider (cached, rate-limited)
        // the day's recorded fixes (engine + foreground tracker), read once per open; no new polling
        val fixes = runCatching { StepsRepository(app).fixes(a, b) }.getOrDefault(emptyList())
        // the store already holds the stop detector's segments; only a day it recorded nothing for is cut from the raw fixes
        val raw = if (fixes.isNotEmpty() && store.none { it.endMs > a && it.startMs < b }) FixSegmenter.segment(fixes, a, b, NavConfig.dayRules, zone)
        else DayTimeline.build(store, a, b, corrections, memory)
        val namer = PlaceNamer(app)
        val named = namer.name(raw.map { (it as? DaySeg.Stay)?.place })
        // #1853 `named` has one entry per row (null for a move), so a stay takes the entry at its own index; a running counter over stays only
        // handed every stay after the first move the previous row's (null) place, and the day showed "Missing visit" for it
        val dayList = raw.mapIndexed { i, s -> if (s is DaySeg.Stay) s.copy(place = named[i]) else s }.let { l -> DayTimeline.fillLinks(l) }
        // #1863 every past date comes from its cached digest (recomputed only when its inputs changed); today is live; the aggregates fold the digests
        val ex = AggregatesStore.explore(app, store, first, today, now, zone)
        val homeSet = HomeStore(app).get()
        return DayUi(date, today, dayList, ex.stats, ex.places, ex.cities, ex.countries, false, fixes, ex.trips, homeSet,
            stepsTotal(app, store, date, a, b), if (first.isBefore(today.minusDays(Trips.HOME_WINDOW_DAYS - 1L))) "all recorded and imported history" else "last 30 days",
            digests = ex.cache.digests, world = ex.world, cityTable = ex.cityTable,
            cacheLine = "${ex.cache.reused} days from cache, ${ex.cache.recomputed.size} recomputed, ${ex.cache.live.size} live",
            counts = runCatching { ExploredStore.counts(app) }.getOrDefault(emptyMap()))
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
            load(ui.date)
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
