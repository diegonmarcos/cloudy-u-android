package com.diegonmarcos.cloudnav.tracker

import android.app.DatePickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
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
 * Explored > Trips: the Trip dashboards over THE timeline store (Day first, then Trips, Places, Cities, World, Insights), read through
 * the same [DayTimeline] / [Aggregates] the fleet door serves. Owner corrections go to [CorrectionStore] and the list is rebuilt.
 */
class DayFragment : KitComposeFragment() {
    private val zone: ZoneId = ZoneId.systemDefault()
    private var ui by mutableStateOf(DayUi(LocalDate.now(), LocalDate.now(), loading = true))
    private var tab by mutableStateOf(DayTab.DAY)

    override fun palette(): KitPalette = TrackerInk.palette()

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
            onAddVisit = { a, b, name ->
                val near = ui.day.filterIsInstance<DaySeg.Stay>().firstOrNull { it.place?.located == true }?.place
                correct(Edits.addVisit(a, b, name, near))
            },
        ), zone)
    }

    private fun correct(c: Correction) {
        CorrectionStore(requireContext()).add(c)
        load(ui.date)
    }

    private fun load(date: LocalDate) {
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val today = LocalDate.now(zone)
            ui = ui.copy(date = date, today = today, loading = true)
            val next = withContext(Dispatchers.IO) { runCatching { compute(app, date, today) }.getOrNull() }
            ui = next ?: ui.copy(loading = false)
        }
    }

    private fun compute(app: android.content.Context, date: LocalDate, today: LocalDate): DayUi {
        val now = System.currentTimeMillis()
        val cs = CorrectionStore(app); val corrections = cs.all(); val memory = cs.memory()
        val first = today.minusDays(Trips.HOME_WINDOW_DAYS - 1L)   // 90 days: home detection; the month rankings use the last 30
        val store = TimelineSync.view(app, DayTimeline.dayStart(first, zone) - 12 * 3_600_000L, now)
        val a = DayTimeline.dayStart(date, zone); val b = minOf(DayTimeline.dayStart(date.plusDays(1), zone), now)
        // name the unnamed places of the shown day through the open reverse-geocode provider (cached, rate-limited)
        // the day's recorded fixes (engine + foreground tracker), read once per open; no new polling
        val fixes = runCatching { StepsRepository(app).fixes(a, b) }.getOrDefault(emptyList())
        // the store already holds the stop detector's segments; only a day it recorded nothing for is cut from the raw fixes
        val raw = if (fixes.isNotEmpty() && store.none { it.endMs > a && it.startMs < b }) FixSegmenter.segment(fixes, a, b, NavConfig.dayRules, zone)
        else DayTimeline.build(store, a, b, corrections, memory)
        val namer = PlaceNamer(app)
        val named = namer.name(raw.map { (it as? DaySeg.Stay)?.place })
        var k = 0
        val dayList = raw.map { s -> if (s is DaySeg.Stay) s.copy(place = named[k++]) else s }.let { l -> DayTimeline.fillLinks(l) }
        val dates = (0 until Trips.HOME_WINDOW_DAYS).map { first.plusDays(it.toLong()) }
        val stats = Aggregates.perDay(store, dates, now, zone, corrections, memory)
        val homeSet = HomeStore(app).get()
        val trips = Trips.group(stats, homeSet)
        val month = dates.takeLast(30).map { d -> DayTimeline.build(store, DayTimeline.dayStart(d, zone), minOf(DayTimeline.dayStart(d.plusDays(1), zone), now), corrections, memory) }
        return DayUi(date, today, dayList, stats, Rankings.places(month), Rankings.cities(month), Rankings.countries(month), false, fixes, trips, homeSet,
            stepsTotal(app, store, a, b))
    }

    /** The day's steps from the step counter (recorded by the tracker engine into the store), else n/a and why. */
    private fun stepsTotal(app: android.content.Context, store: List<com.diegonmarcos.cloudnav.maps.TlSegment>, a: Long, b: Long): StepsTotal {
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
