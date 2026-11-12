package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Tracker > Timeline (#1156): the day's raw STAY / MOVE list from the provider's `tracker/segments`. */
class TrackerTimelineFragment : KitComposeFragment() {
    private val zone: ZoneId = ZoneId.systemDefault()
    private var date by mutableStateOf(LocalDate.now())
    private var rows by mutableStateOf<List<TimelineRow>>(emptyList())
    private var loading by mutableStateOf(true)

    override fun palette(): KitPalette = TrackerInk.palette()
    override fun onResume() { super.onResume(); load(date) }

    @Composable
    override fun Content() {
        TrackerTimelineScreen(date, LocalDate.now(zone), rows, loading, { load(date.minusDays(1)) }, { load(date.plusDays(1)) }, zone)
    }

    private fun load(d: LocalDate) {
        val app = requireContext().applicationContext
        date = d; loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { TrackerRows.timeline(TrackerDebugApiProvider.segments(app, d.toString())) }.getOrDefault(emptyList()) }
            if (d == date) { rows = r; loading = false }
        }
    }
}

/** Tracker > Steps (#1156): steps per day from the provider's `tracker/steps` (table + chart); keeps the Health Connect grant action of the old Steps page. */
class TrackerStepsFragment : KitComposeFragment() {
    private var rows by mutableStateOf<List<StepsRow>>(emptyList())
    private var status by mutableStateOf("")
    private var hint by mutableStateOf("")
    private var needsPermission by mutableStateOf(false)
    private var loading by mutableStateOf(true)

    /** #1515 the Health Connect read grant lives in Configs > Perms (row health_connect); [onResume] reloads on return. */
    private fun askHealth() { runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(requireContext(), "Tracker steps", listOf("health_connect")) } }

    override fun palette(): KitPalette = TrackerInk.palette()
    override fun onResume() { super.onResume(); load() }

    @Composable
    override fun Content() {
        TrackerStepsScreen(rows, status, hint, needsPermission, loading) { askHealth() }
    }

    private fun load() {
        val app: Context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val j = withContext(Dispatchers.IO) { runCatching { TrackerDebugApiProvider.steps(app, DAYS) }.getOrNull() }
            if (j != null) {
                rows = TrackerRows.steps(j); status = j.optString("status_line"); hint = j.optString("hint")
                needsPermission = j.optString("status") == SourceStatus.NEEDS_PERMISSION.name
            }
            loading = false
        }
    }

    private companion object { const val DAYS = 14 }
}

/** Tracker > Sleep (#1156): sleep per day from the provider's `tracker/daily` (table + chart). */
class TrackerSleepFragment : KitComposeFragment() {
    private var rows by mutableStateOf<List<SleepRow>>(emptyList())
    private var loading by mutableStateOf(true)

    override fun palette(): KitPalette = TrackerInk.palette()
    override fun onResume() { super.onResume(); load() }

    @Composable
    override fun Content() { TrackerSleepScreen(rows, loading) }

    private fun load() {
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            rows = withContext(Dispatchers.IO) { runCatching { TrackerRows.sleep(TrackerDebugApiProvider.daily(app, DAYS)) }.getOrDefault(emptyList()) }
            loading = false
        }
    }

    private companion object { const val DAYS = 14 }
}
