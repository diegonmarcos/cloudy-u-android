package com.diegonmarcos.superapp.fleetxray.perf

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigs
import com.diegonmarcos.superapp.themebg.ThemeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags of the Performance section (UiAutomator resource ids). */
public object XrayPerfTags {
    public const val SECTION: String = "fleetxray_perf_section"
    public const val RUN: String = "fleetxray_perf_run"
    public const val CLEAR: String = "fleetxray_perf_clear"
    public const val EXPORT: String = "fleetxray_perf_export"
    public const val SAVE: String = "fleetxray_perf_save"
    public const val SHARE: String = "fleetxray_perf_share"
    public const val STATUS: String = "fleetxray_perf_status"
    public const val TABLE: String = "fleetxray_perf_table"
}

internal fun perfDevice(ctx: Context, now: Long = System.currentTimeMillis()): PerfDevice {
    val decl = runCatching { FleetConfigs.current }.getOrNull()
    val ver = decl?.version?.ifBlank { null } ?: runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    return PerfDevice(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT,
        decl?.name?.ifBlank { null } ?: ctx.packageName, ver, SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(now)))
}

private val COLS = listOf("Operation" to 190, "Runs" to 44, "p50 ms" to 62, "p95 ms" to 62, "max ms" to 62, "CPU %" to 52, "items/s" to 70, "MB/s" to 54, "GC n/ms" to 70, "Verdict" to 84)

private fun rowOf(s: OpSummary): List<String> = listOf(
    s.op, s.runs.toString(), "%.1f".format(Locale.ROOT, s.p50WallMs), "%.1f".format(Locale.ROOT, s.p95WallMs), "%.1f".format(Locale.ROOT, s.maxWallMs),
    s.cpuRatio?.let { "%.0f".format(Locale.ROOT, it * 100) } ?: "n/a", s.itemsPerSec?.let { "%.0f".format(Locale.ROOT, it) } ?: "n/a",
    s.mbPerSec?.let { "%.2f".format(Locale.ROOT, it) } ?: "n/a", (s.gcCount?.toString() ?: "n/a") + "/" + (s.gcTimeMs?.toString() ?: "n/a"), s.verdict.label)

/** Configs > X-Ray App > Performance: the per-operation table, Run benchmark, Clear and Export .md (Save through the system dialog, or Share). */
@Composable
internal fun XrayPerfSection(c: ThemeColors) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf<Pair<String, String>?>(null) }
    var status by remember { mutableStateOf("") }
    val sums = remember(tick) { XrayProfiler.summaries() }
    val benches = remember(tick) { XrayBenchmarks.declared() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val r = ready
        if (uri != null && r != null) scope.launch {
            val err = withContext(Dispatchers.IO) { XrayFileExport.write(ctx, uri, r.second) }
            status = if (err == null) "Saved ${r.first}" else "Save failed: $err"
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag(XrayPerfTags.SECTION).clip(shape).background(c.tabStrip, shape).border(1.dp, c.islandBorder, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Performance", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Wall time, thread CPU, items and bytes, GC per operation. CPU above 60% of wall = CPU-bound (Rust may help); below 30% = IO-bound (Rust will not help). " +
                (if (benches.isEmpty()) "This app declares no benchmark; the table fills as the app works." else "${benches.size} declared benchmarks, read-only and time-bounded."),
            color = c.inkDim, fontSize = 11.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PerfButton(if (running != null) "Running..." else "Run benchmark", c, XrayPerfTags.RUN) {
                if (running == null) {
                    running = benches.firstOrNull()?.title ?: ""
                    status = "Running ${benches.size} benchmarks"
                    scope.launch {
                        val out = XrayBenchmarks.runAll(ctx) { running = it }
                        running = null; tick++
                        status = "Benchmark done: " + out.groupingBy { it.state }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }.ifEmpty { "nothing declared" }
                    }
                }
            }
            PerfButton("Clear", c, XrayPerfTags.CLEAR) { XrayProfiler.clear(); ready = null; status = "Cleared"; tick++ }
            PerfButton("Export .md", c, XrayPerfTags.EXPORT) {
                val d = perfDevice(ctx)
                val md = XrayPerfReport.markdown(d, XrayProfiler.runs(), XrayProfiler.outcomes())
                ready = XrayPerfReport.fileName(d) to md
                status = "Report ready: ${md.length} characters"
            }
        }
        ready?.let { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PerfButton("Save .md", c, XrayPerfTags.SAVE) { save.launch(r.first) }
                PerfButton("Share", c, XrayPerfTags.SHARE) { XrayFileExport.share(ctx, r.second, r.first) }
            }
        }
        if (status.isNotEmpty() || running != null) Text(running?.let { "Running: $it" } ?: status, Modifier.testTag(XrayPerfTags.STATUS), color = c.inkDim, fontSize = 11.sp)
        Column(Modifier.testTag(XrayPerfTags.TABLE).horizontalScroll(rememberScrollState())) {
            Row { COLS.forEach { (h, w) -> Text(h, Modifier.width(w.dp), color = c.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace) } }
            if (sums.isEmpty()) Text("no operation recorded yet", color = c.inkDim, fontSize = 11.sp)
            sums.forEach { s ->
                Row { rowOf(s).forEachIndexed { i, v -> Text(v, Modifier.width(COLS[i].second.dp), color = c.ink, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1) } }
            }
        }
    }
}

@Composable
private fun PerfButton(label: String, c: ThemeColors, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(label, Modifier.clip(shape).background(c.tabSelected, shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag),
        color = c.onTabSelected, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}
