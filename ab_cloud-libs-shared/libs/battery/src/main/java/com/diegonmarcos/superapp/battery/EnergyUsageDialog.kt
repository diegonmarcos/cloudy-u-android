package com.diegonmarcos.superapp.battery

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.DialogFragment
import com.diegonmarcos.superapp.adbdebug.AdbShellLink
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * AccuBattery-style energy page — opened from Configs/About → Battery &
 * Usage. Renders, in one scroll:
 *   1. Live discharge speed (whole-device mA / %·h from
 *      the battery SoT, BatteryRepository — the same report the status strip uses).
 *   2. "Draining the device" — marginal mA per system state from the
 *      Tier-1 watchdog ([EnergyWatchdog.attribution]).
 *   3. "By foreground app" — per-app average draw + energy share while
 *      that app was foreground (correlation, the no-privilege proxy for
 *      AccuBattery's per-app list; the privileged adb channel upgrades this
 *      to exact mAh from dumpsys batterystats).
 *   4. "Inside Cloud SuperApp" — our own subsystem ledger
 *      ([EnergyLedger]) so we can see + fix what WE cost.
 *
 * Full-screen DialogFragment so it's decoupled from the host's fragment
 * container — open with `EnergyUsageDialog().show(fm, TAG)`.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The page is a ComposeView under
 * CloudKitTheme (libs:ui-kit) with its palette in the battery_* colour resources; the host
 * contract (DialogFragment, TAG) is unchanged.
 */
class EnergyUsageDialog : DialogFragment() {

    // Toggle for the derived "Actual in (est wall)" row in the Power in/out
    // card (the user opted for "both modes, toggle"). NET + CONSUMPTION are
    // always shown; the est. wall-in line is shown/hidden by this.
    private var showEstIn by mutableStateOf(true)

    /** Bumped by Refresh / Reset window: every card re-reads its source. */
    private var revision by mutableIntStateOf(0)

    /** Charging snapshots: re-read after a capture lands. */
    private var snapVersion by mutableIntStateOf(0)
    private var capturing by mutableStateOf(false)

    /** The exact per-app load through the privileged adb channel, started by its button. */
    private sealed interface Exact {
        data object Idle : Exact
        data object Running : Exact
        data class Done(val rows: List<BatteryPrivileged.App>?) : Exact
    }
    private var exact by mutableStateOf<Exact>(Exact.Idle)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.battery_bg)))
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater, c: ViewGroup?, s: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val palette = KitPalette(
                surface = colorResource(R.color.battery_card),
                surfaceSelected = colorResource(R.color.battery_pill),
                textPrimary = colorResource(R.color.battery_text),
                textSecondary = colorResource(R.color.battery_text_dim),
                accent = colorResource(R.color.battery_accent),
                hairline = colorResource(R.color.battery_pill),
                tileInk = colorResource(R.color.battery_text),
            )
            CloudKitTheme(palette) { Screen() }
        }
    }

    @Composable
    private fun Screen() {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        val rev = revision
        Column(
            Modifier.fillMaxSize().background(colorResource(R.color.battery_bg))
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 48.dp)
        ) {
            // Header + close
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Battery Usage Details", color = p.textPrimary, fontSize = 22.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("✕", color = p.textSecondary, fontSize = 20.sp,
                    modifier = Modifier.clickable { dismiss() }
                        .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp))
            }

            // 1) Live discharge speed — the battery SoT's numbers (BatteryRepository),
            //    the same ones the badge, the strip popup and About › Battery print.
            val rep = remember(rev) { runCatching { BatteryRepository.report(ctx) }.getOrNull() }
            Card("Discharging speed") {
                if (rep == null) { Line("—", "no data"); return@Card }
                Big(rep.smoothedMa?.let { BatteryRows.fmtMa(it) } ?: "—",
                    BatteryRows.rate(rep.ratePctH, rep.rateW))
                Line("Live draw", rep.powerW?.let { BatteryRows.fmtW(it) } ?: "—")
                Line("Battery temp", rep.reading.tempC?.let { BatteryRows.fmtTemp(it) } ?: "—")
                Line(if (rep.reading.onPower) "Time to full" else "Est. battery last",
                    if (rep.reading.onPower) BatteryRows.toFull(rep) else BatteryRows.toEmpty(rep.toEmptyMs, "current rate"))
            }

            // 1b) Power IN / OUT — the SoT's BatteryTruth.powerFlow over the same
            //     report: NET is the battery's V×I, CONSUMPTION measured on battery
            //     (charger − battery, or the watchdog's model, on power), IN the
            //     charger's live input when readable, else NET + CONSUMPTION (est).
            Card("Power in / out") {
                if (rep == null) { Line("—", "no data"); return@Card }
                val modeled = if (rep.reading.onPower && rep.chargerLiveW == null)
                    runCatching { EnergyWatchdog.modeledConsumptionW(ctx, rep.reading.voltageMv) }.getOrNull() else null
                val sec = BatteryRows.powerFlow(BatteryTruth.powerFlow(rep, modeled))
                Big(sec.rows.first().value,
                    if (rep.reading.onPower) "on power — into the battery" else "on battery — out of the cell")
                for ((i, row) in sec.rows.withIndex()) if (i < 2 || showEstIn) Line(row.label, row.value)
                Small("IN = NET + CONSUMPTION. When the charger's live input is not readable, 'Actual in' is derived (est). NET is measured.")
                Pill(if (showEstIn) "Hide actual IN" else "Show actual IN") { showEstIn = !showEstIn }
            }

            // 1c) Battery health — the SoT report's capacity section (charge
            //     counter ÷ level vs design, the counted cycles and their counters).
            Card("Battery health") {
                if (rep == null) { Line("—", "no data"); return@Card }
                Line("Voltage", rep.reading.voltageMv?.let { BatteryRows.fmtV(it) } ?: "—")
                Line("Temperature", rep.reading.tempC?.let { BatteryRows.fmtTemp(it) } ?: "—")
                for (row in BatteryRows.capacity(rep, null, null, null).rows) Line(row.label, row.value)
                Small("Health = est. full ÷ design capacity. Design and the gauge's own full/cycles come through the privileged shell: Configs › About › Battery.")
            }

            // 1d) Charging snapshots — capture the charge/PD fields on demand
            //     (best at <30%, cool, plugged) so the decisive fast-charge
            //     evidence is logged the instant it happens. The dumpsys read
            //     runs via the embedded-adb channel off the main thread.
            Card("Charging snapshots") {
                Pill("Capture snapshot now") {
                    if (!capturing) {
                        capturing = true
                        Thread {
                            runCatching { ChargeSnapshot.capture(ctx) }
                            capturing = false
                            snapVersion++
                        }.start()
                    }
                }
                if (capturing) Small("Capturing (dumpsys via adb)…")
                Small("Drain to <30%, keep cool, plug the Anker, then Capture. mcv→9000 (PD tag) = fast-charge engaged; 5V/normal = not.")
                val snaps = remember(snapVersion, rev) { ChargeSnapshot.recent(ctx) }
                if (snaps.length() == 0) {
                    Small("No snapshots yet — tap Capture (ideally at <30% while charging).")
                } else {
                    for (i in 0 until minOf(snaps.length(), 12)) {
                        val o = snaps.getJSONObject(i)
                        Line(fmtAgo(o.optLong("ts", 0L)), ChargeSnapshot.fmtLine(o))
                    }
                }
            }

            val attr = remember(rev) { runCatching { EnergyWatchdog.attribution(ctx) }.getOrDefault(emptyMap()) }
            val sampleCount = (attr["samples"] as? Int) ?: 0

            // 2) Device states → marginal draw
            @Suppress("UNCHECKED_CAST")
            val states = attr["states"] as? Map<String, Map<String, Any>>
            Card("Draining the device  ·  $sampleCount samples") {
                if (states == null || sampleCount == 0) {
                    Small("Collecting samples… open the app a few times while on battery, then reopen this page.")
                    return@Card
                }
                val baseline = (attr["baseline_idle_ma"] as? Int) ?: 0
                Line("Idle baseline", "$baseline mA")
                states.entries.sortedByDescending { (it.value["marginal_ma"] as? Int) ?: 0 }.forEach { (k, v) ->
                    val m = (v["marginal_ma"] as? Int) ?: 0
                    val n = (v["samples"] as? Int) ?: 0
                    if (n > 0) Line(k.replace('_', ' '), (if (m >= 0) "+$m mA" else "$m mA") + "   ($n)")
                }
            }

            // 3) By app — AccuBattery-style estimate (foreground time × avg
            //    screen-on discharge mA). No privilege; just Usage
            //    Access. Same method AccuBattery uses for its per-app list.
            Card("By app  ·  estimate (foreground × draw)") {
                if (!EnergyWatchdog.hasUsageAccess(ctx)) {
                    Small("Needs Usage Access. Grant it in Configs/About → Permissions → Grant Usage Access.")
                    return@Card
                }
                val est = remember(rev) { runCatching { EnergyWatchdog.perAppEstimate(ctx) }.getOrDefault(emptyList()) }
                if (est.isEmpty()) {
                    Small("No foreground time in the current discharge session yet.")
                    return@Card
                }
                Small("mAh ≈ app foreground-time × avg screen-on draw (this discharge session)")
                est.take(15).forEach { e ->
                    AppRow(e.pkg, e.label, "%.1f mAh  ·  %s".format(e.mAh, fmtDur(e.fgMs)))
                }
            }

            // 3b) Exact per-app mAh: dumpsys batterystats through the app's own privileged adb channel.
            Card("Per-app exact  ·  ADB channel") {
                val channel = remember(rev) { BatteryRepository.privilegedChannel(ctx) }
                if (channel == null) {
                    Small("Pair the ADB channel to read exact per-app mAh from dumpsys batterystats.")
                    Pill("Pair ADB ›") { val host = requireActivity(); dismiss(); AdbShellLink.open(host) }
                    return@Card
                }
                Small("Channel: $channel")
                // dumpsys batterystats can take seconds: run it OFF the main thread on tap.
                Pill("Load per-app (dumpsys)") {
                    exact = Exact.Running
                    Thread { exact = Exact.Done(runCatching { BatteryRepository.privileged(ctx)?.apps }.getOrNull()) }.start()
                }
                when (val x = exact) {
                    Exact.Idle -> {}
                    Exact.Running -> Small("Running dumpsys batterystats…")
                    is Exact.Done -> when {
                        x.rows == null -> Small("The channel did not answer — tap again in a moment.")
                        x.rows.isEmpty() -> Small("No per-uid power section yet (needs discharge since last full charge).")
                        else -> x.rows.take(15).forEach { a ->
                            val (pkg, label) = BatteryRepository.appLabel(ctx, a.uid)
                            AppRow(pkg, label, "%.1f mAh".format(a.mAh))
                        }
                    }
                }
            }

            // 4) Inside Cloud SuperApp (our own subsystem ledger)
            val (since, ledger) = remember(rev) { EnergyLedger.snapshot() }
            Card("Inside Cloud SuperApp") {
                if (ledger.isEmpty()) {
                    Small("No subsystem activity recorded yet this session.")
                    return@Card
                }
                Small("CPU-time / wakeups / bytes per subsystem since " +
                    (if (since > 0) fmtAgo(since) else "start"))
                ledger.entries.sortedByDescending { it.value.cpuNanos }.forEach { (tag, st) ->
                    val cpuMs = st.cpuNanos / 1_000_000L
                    val parts = buildString {
                        append("${cpuMs} ms")
                        if (st.wakeups > 0) append("  ·  ${st.wakeups} wake")
                        if (st.bytes > 0) append("  ·  ${sizeStr(st.bytes)}")
                    }
                    Line(tag, parts)
                }
            }

            // Actions
            Row(Modifier.padding(top = 8.dp)) {
                Pill("Refresh") { revision++ }
                Spacer(Modifier.width(8.dp))
                Pill("Reset window") {
                    EnergyLedger.reset(); runCatching { EnergyStore(ctx).clear() }
                    revision++
                }
            }
        }
    }

    // ── tiny composable helpers ─────────────────────────────────────
    @Composable
    private fun Card(title: String, body: @Composable () -> Unit) {
        val p = LocalKitPalette.current
        Text(title, color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface).padding(12.dp)) {
            body()
        }
    }

    @Composable
    private fun Big(value: String, sub: String) {
        val p = LocalKitPalette.current
        Text(value, color = p.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        Text(sub, color = p.textPrimary.copy(alpha = 0.6f), fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp))
    }

    @Composable
    private fun Line(k: String, v: String) {
        val p = LocalKitPalette.current
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(k, color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(v, color = p.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End)
        }
    }

    @Composable
    private fun AppRow(pkg: String, label: String, value: String) {
        val ctx = LocalContext.current
        val bmp = remember(pkg) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(64, 64).asImageBitmap() }.getOrNull()
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 10.dp).size(28.dp)) {
                if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(28.dp))
            }
            Text(label, color = LocalKitPalette.current.textPrimary, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(value, color = colorResource(R.color.battery_value), fontSize = 13.sp,
                fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    private fun Pill(label: String, onClick: () -> Unit) {
        val p = LocalKitPalette.current
        Text(label, color = p.textPrimary, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(p.surfaceSelected)
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 8.dp))
    }

    @Composable
    private fun Small(t: String) {
        Text(t, color = LocalKitPalette.current.textPrimary.copy(alpha = 0.47f), fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
    }

    private fun sizeStr(b: Long): String = when {
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.1f KB".format(b / (1L shl 10).toDouble())
        else -> "$b B"
    }
    private fun fmtDur(ms: Long): String {
        val m = ms / 60_000L
        return when {
            m >= 60 -> "${m / 60}h ${m % 60}m"
            m >= 1 -> "${m}m"
            else -> "${ms / 1000L}s"
        }
    }
    private fun fmtAgo(sinceMs: Long): String {
        val mins = (System.currentTimeMillis() - sinceMs) / 60_000L
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            else -> "${mins / 60}h ${mins % 60}m ago"
        }
    }

    companion object { const val TAG = "energy_usage_dialog" }
}
