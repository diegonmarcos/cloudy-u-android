package com.diegonmarcos.superapp.bgpolicy

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.xray.XrayAnalyze
import com.diegonmarcos.superapp.xray.XrayProbes
import java.util.Locale

/** One line of either list: the app, what it costs (drainers only) and what the shell last said. */
internal data class HungerRow(
    val pkgs: List<String>, val label: String, val sub: String, val state: BgState?, val essential: Boolean, val fleet: Boolean,
)

/**
 * #1506 Quick Actions > Tools > Battery Hunger. Two lists over the one [BgEngine]: the drainers since the last charge (the
 * ranking is X-Ray's own [XrayAnalyze.suspects] over the same batterystats probe) and every installed app, fleet first, each
 * with Restrict, Stop and Allow, plus the bulk Restrict all non-essential and Restore. On demand only: nothing loads until a
 * button is tapped, and nothing polls.
 */
@Composable
fun BatteryHungerPanel(activity: Activity) {
    val p = LocalKitPalette.current
    val view = LocalView.current
    var drainers by remember { mutableStateOf<List<HungerRow>>(emptyList()) }
    var apps by remember { mutableStateOf<List<HungerRow>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val dialogs = remember { BgDialogs() }

    /** Run [work] on a worker thread behind the busy flag; [work] gets the engine or nothing when the shell is not there. */
    fun task(say: String, work: (BgEngine, BgConfig) -> Unit) {
        if (busy) return
        busy = true; status = say
        Thread({
            try {
                val shell = BgUi.ensureShell(activity, dialogs)
                if (shell == null) status = "ADB shell not connected: nothing was changed."
                else work(BgHost.engine(activity, shell), BgHost.config(activity))
            } catch (t: Throwable) { status = "Failed: ${t.message}" } finally { busy = false }
        }, "bg-policy").start()
    }

    fun replace(list: List<HungerRow>, o: BgOutcome): List<HungerRow> =
        list.map { r -> if (o.pkg in r.pkgs && o.after != null) r.copy(state = o.after) else r }

    fun bgAct(r: HungerRow, name: String, f: (BgEngine, String) -> BgOutcome) = task("$name ${r.label}…") { e, _ ->
        val outs = r.pkgs.map { pk -> f(e, pk).also { o -> drainers = replace(drainers, o); apps = replace(apps, o) } }
        status = BgUi.summary(name, outs) + outs.firstOrNull { !it.ok && it.note.isNotEmpty() }?.let { " (${it.note})" }.orEmpty()
    }

    fun loadApps() = task("reading every app through the shell…") { e, cfg ->
        val rows = readRows(activity, e, cfg)
        if (rows == null) { status = "The shell did not answer."; return@task }
        apps = rows
        status = "${apps.size} apps read, ${apps.count { it.state?.restricted == true }} restricted."
    }

    fun rank() = task("reading batterystats since the last charge…") { e, cfg ->
        val raw = HashMap<String, String>()
        for (id in listOf("batterystats", "packages", "services", "alarm", "jobs")) {
            val cmd = XrayProbes.all.first { it.id == id }.cmd
            raw[id] = BgHost.shell(activity)?.exec(cmd).orEmpty()
        }
        val sus = XrayAnalyze.suspects(raw, cfg.hungerTop)
        val pm = BgHost.packages(activity)
        val labels = pm.associate { it.pkg to it.label }
        val ess = BgHost.essential(activity, cfg)
        val first = sus.map { it.pkg.split('+').first() }
        val states = e.readAll(pm.filter { it.pkg in first }, cfg).orEmpty().associate { it.first.pkg to it.second }
        drainers = sus.map { s ->
            val pkgs = s.pkg.split('+')
            val sub = String.format(Locale.US, "%.0f mAh · %s", s.score, s.cause) + if (s.detail.isNotEmpty()) " · ${s.detail}" else ""
            HungerRow(pkgs, labels[pkgs.first()] ?: s.pkg, sub, states[pkgs.first()], pkgs.any { ess.isEssential(it) }, s.fleet)
        }
        status = if (sus.isEmpty()) "No drainers ranked (no per-app battery stats since the last charge)." else "${sus.size} drainers ranked since the last charge."
    }

    fun restrictAll() = dialogs.askConfirm("Restrict all non-essential",
        "Every installed app that is not on the essential list (SuperApp, phone, SMS, launcher, keyboard, system UI) loses background work. Restore puts each back exactly as it was.") {
        task("restricting every non-essential app…") { e, cfg ->
            val outs = e.restrictAll(BgHost.packages(activity), cfg)
            status = BgUi.summary("Restrict all", outs)
            readRows(activity, e, cfg)?.let { apps = it }
        }
    }

    fun restore() = task("restoring the apps as they were…") { e, cfg ->
        val outs = e.restore()
        status = if (outs.isEmpty()) "Nothing to restore." else BgUi.summary("Restore", outs)
        readRows(activity, e, cfg)?.let { apps = it }
    }

    fun components(r: HungerRow) {
        val pk = r.pkgs.first()
        Thread({
            val list = BgHost.components(activity, pk)
            if (list.isEmpty()) { BgUi.toast(activity, "No receivers or services found for ${r.label}."); return@Thread }
            dialogs.pick = BgDialogs.Pick("${r.label}: receivers and services",
                list.map { (if (it.second) "enabled   " else "DISABLED  ") + it.first.substringAfterLast('.') }) { i ->
                val (name, on) = list[i]
                task("${if (on) "disabling" else "enabling"} ${name.substringAfterLast('.')}…") { e, _ ->
                    val o = e.component(pk, name, enable = !on)
                    status = (if (o.ok) "Done: " else "Not done: ") + o.note
                }
            }
        }, "bg-components").start()
    }

    BgDialogHost(dialogs)
    Column(Modifier.fillMaxWidth().testTag("battery-hunger")) {
        Spacer(Modifier.height(KitStoreRow.step(16)))
        KitSectionHeader("Battery Hunger",
            "Who drained the battery since the last charge, and what each may do in the background. Needs the ADB shell; runs only when you tap.",
            Modifier.testTag("battery-hunger:header"))
        KitStoreRowLine(Modifier.testTag("battery-hunger:actions")) {
            KitStoreQuick("Rank drainers", KitStoreRow.BLUE_FILL, Modifier.testTag("battery-hunger:rank")) { Haptics.tap(view); rank() }
            Spacer(Modifier.width(KitStoreRow.gap))
            KitStoreQuick("All apps", KitStoreRow.BLUE_FILL, Modifier.testTag("battery-hunger:apps")) { Haptics.tap(view); loadApps() }
        }
        KitStoreRowLine(Modifier.testTag("battery-hunger:bulk")) {
            KitStoreQuick("Restrict all non-essential", KitStoreRow.AMBER_FILL, Modifier.testTag("battery-hunger:restrict-all")) { Haptics.tap(view); restrictAll() }
            Spacer(Modifier.width(KitStoreRow.gap))
            KitStoreQuick("Restore", KitStoreRow.UP, Modifier.testTag("battery-hunger:restore")) { Haptics.tap(view); restore() }
        }
        if (status.isNotEmpty()) KitStoreRowLine(Modifier.testTag("battery-hunger:status")) { KitStoreCaption(status, color = p.textSecondary) }
        if (drainers.isNotEmpty()) {
            KitStoreRowLine { KitStoreName("Drainers since last charge", Modifier.weight(1f), p.textPrimary) }
            for (r in drainers) HungerRowUi(r, busy, view, { bgAct(r, "Restrict") { e, pk -> e.restrict(pk) } }, { bgAct(r, "Stop") { e, pk -> e.forceStop(pk) } },
                { bgAct(r, "Allow") { e, pk -> e.allow(pk) } }, { components(r) })
        }
        if (apps.isNotEmpty()) {
            KitStoreRowLine { KitStoreName("All apps (fleet first)", Modifier.weight(1f), p.textPrimary) }
            for (r in apps) HungerRowUi(r, busy, view, { bgAct(r, "Restrict") { e, pk -> e.restrict(pk) } }, { bgAct(r, "Stop") { e, pk -> e.forceStop(pk) } },
                { bgAct(r, "Allow") { e, pk -> e.allow(pk) } }, { components(r) })
        }
    }
}

/** Every installed app with what the shell says about it, fleet first (null when the shell did not answer). */
private fun readRows(activity: Activity, e: BgEngine, cfg: BgConfig): List<HungerRow>? {
    val pkgs = BgHost.packages(activity)
    val ess = BgHost.essential(activity, cfg)
    val all = e.readAll(pkgs, cfg) ?: return null
    return all.map { (pk, s) -> HungerRow(listOf(pk.pkg), pk.label, pk.pkg, s, ess.isEssential(pk.pkg), cfg.isFleet(pk.pkg)) }
}

@Composable
private fun HungerRowUi(r: HungerRow, busy: Boolean, view: android.view.View, onRestrict: () -> Unit, onStop: () -> Unit, onAllow: () -> Unit, onComponents: () -> Unit) {
    val p = LocalKitPalette.current
    val s = r.state
    val ink: Color = when { s == null -> p.textSecondary; s.restricted -> KitStoreRow.BAD; s.running -> KitStoreRow.UPDATE; else -> KitStoreRow.UP }
    KitStoreRowLine(Modifier.testTag("battery-hunger:row:${r.pkgs.first()}"), onClick = { Haptics.tap(view); onComponents() }) {
        KitStoreDot(if (s == null) "·" else if (s.restricted) "⛔" else "●", ink)
        Column(Modifier.weight(1f)) {
            KitStoreName((if (r.fleet) "◆ " else "") + r.label + if (r.essential) "  (essential)" else "", color = p.textPrimary)
            KitStoreCaption(r.sub, color = p.textSecondary)
            if (s != null) KitStoreCaption(s.summary, color = ink)
        }
        if (!busy) {
            if (!r.essential) {
                KitStoreQuick("Restrict", KitStoreRow.AMBER_FILL, Modifier.testTag("battery-hunger:restrict:${r.pkgs.first()}")) { Haptics.tap(view); onRestrict() }
                Spacer(Modifier.width(KitStoreRow.tight))
                KitStoreQuick("Stop", KitStoreRow.BAD, Modifier.testTag("battery-hunger:stop:${r.pkgs.first()}")) { Haptics.tap(view); onStop() }
                Spacer(Modifier.width(KitStoreRow.tight))
            }
            KitStoreQuick("Allow", KitStoreRow.UP, Modifier.testTag("battery-hunger:allow:${r.pkgs.first()}")) { Haptics.tap(view); onAllow() }
        }
    }
}
