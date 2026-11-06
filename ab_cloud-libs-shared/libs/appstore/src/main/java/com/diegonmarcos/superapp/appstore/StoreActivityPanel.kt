package com.diegonmarcos.superapp.appstore

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.graphics.drawable.toBitmap
import com.diegonmarcos.superapp.appstore.dl.DlJob
import com.diegonmarcos.superapp.appstore.dl.DownloadEngine
import com.diegonmarcos.superapp.appstore.dl.UserAction
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * #1524 THE ONE ACTIVITY PANEL of the Store, and the only place a download or install progress is drawn (StoreActivityGuardTest).
 * Collapsed (the default): one "Overall" line ([StoreActivity.Overall.text]) with a tone dot, a failure-count chip, an overall bar and a
 * chevron; idle it says "All up to date" or "N updates available" with an Update all action. Expanded: every download AND install,
 * in parallel, one dense [KitStoreRowLine] each, grouped Active, Queued, Done / Failed. Whether it is expanded is remembered.
 *
 * It only OBSERVES: [DownloadEngine.state] for downloads and the [StoreJobs] board for installs; leaving the page, switching tab or
 * recomposing changes nothing about a job. The row buttons are the only place the UI stops engine work, each built from its own tap
 * (EngineGuardTest holds that nothing else may).
 */
@Composable
fun StoreActivityPanel(updates: Int, onUpdateAll: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    var engine by remember { mutableStateOf(StoreEngine.engineOrNull()) }
    // Building the engine reads the persisted queue (disk): off the main thread. It also resumes what a restart interrupted.
    LaunchedEffect(Unit) { if (engine == null) engine = withContext(Dispatchers.IO) { StoreEngine.get(app) } }
    val e = engine
    val jobs by (e?.state ?: NONE).collectAsState()
    var boardTick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val o: () -> Unit = { boardTick++ }
        StoreJobs.addObserver(o)
        onDispose { StoreJobs.removeObserver(o) }
    }
    // #1551 the persisted install queue repaints the panel on every transition it saves, and only then.
    val queueTick by StoreAuto.saves.collectAsState()
    val items = remember(jobs, boardTick, queueTick) {
        StoreActivity.items(jobs, StoreJobs.board.rows(), StoreAuto.activityQueue(app) { t ->
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
        })
    }
    val all = StoreActivity.overall(items, updates)

    val prefs = remember { StoreActivity.Prefs(PrefsFlags(app)) }
    var expanded by remember { mutableStateOf(prefs.expanded) }
    var finishedOpen by remember { mutableStateOf(prefs.finishedOpen) }
    val tone = toneInk(all)

    Column(modifier.fillMaxWidth().testTag(TAG)) {
        KitStoreRowLine(Modifier.testTag("$TAG:head"), onClick = { expanded = !expanded; prefs.expanded = expanded }) {
            KitStoreDot("●", tone, Modifier.testTag("$TAG:tone"))
            Text(all.text(), color = if (all.busy || all.paused > 0) Color.White else tone, fontSize = KitStoreRow.meta, maxLines = 1,
                modifier = Modifier.weight(1f).testTag("$TAG:text"))
            if (all.failed > 0) {
                Spacer(Modifier.width(KitStoreRow.gap))
                KitStoreCaption("⚠ ${all.failed}", color = KitStoreRow.BAD, modifier = Modifier.testTag("$TAG:errors"))
            }
            if (!all.busy && all.paused == 0 && updates > 0) {
                Spacer(Modifier.width(KitStoreRow.gap))
                KitStoreQuick("Update all", KitStoreRow.AMBER_FILL, Modifier.testTag("$TAG:update-all"), onUpdateAll)
            }
            Spacer(Modifier.width(KitStoreRow.gap))
            KitStoreCaption(if (expanded) "▴" else "▾", color = Color.White, modifier = Modifier.testTag("$TAG:chevron"))
        }
        if (all.busy || all.paused > 0)
            LinearProgressIndicator(progress = { all.percent / 100f }, color = tone,
                modifier = Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH).testTag("$TAG:bar"))
        if (expanded) {
            Section("Active", items, StoreActivity.Group.ACTIVE, e)
            Section("Queued", items, StoreActivity.Group.QUEUED, e)
            val fin = StoreActivity.section(items, StoreActivity.Group.FINISHED)
            if (fin.isNotEmpty()) {
                KitStoreRowLine(Modifier.testTag("$TAG:finished"), onClick = { finishedOpen = !finishedOpen; prefs.finishedOpen = finishedOpen }) {
                    KitStoreCaption((if (finishedOpen) "▴ " else "▾ ") + "Done ${all.done} · Failed ${all.failed}", color = Color.White,
                        modifier = Modifier.weight(1f))
                    if (finishedOpen) KitStoreQuick("Clear", KitStoreRow.RULE, Modifier.testTag("$TAG:clear")) {
                        e?.clearFinished(); StoreJobs.board.clearFinished(); boardTick++
                    }
                }
                if (finishedOpen) fin.forEach { Item(it, e) }
            }
            if (items.isEmpty()) KitStoreRowLine { KitStoreCaption("Nothing running.", color = DIM) }
        }
    }
}

@Composable
private fun Section(title: String, items: List<StoreActivity.Item>, g: StoreActivity.Group, e: DownloadEngine?) {
    val rows = StoreActivity.section(items, g)
    if (rows.isEmpty()) return
    KitStoreRowLine(Modifier.testTag("$TAG:group:${g.name}")) { KitStoreCaption("$title · ${rows.size}", color = DIM) }
    rows.forEach { Item(it, e) }
}

/** One download or install: icon, app, phase, and the verbs its state allows. */
@Composable
private fun Item(i: StoreActivity.Item, e: DownloadEngine?) {
    val ctx = LocalContext.current
    val icon = remember(i.pkg) { appIcon(ctx, i.pkg) }
    val ink = when (i.phase) {
        StoreActivity.Phase.FAILED -> KitStoreRow.BAD
        StoreActivity.Phase.DONE -> KitStoreRow.UP
        StoreActivity.Phase.PAUSED, StoreActivity.Phase.QUEUED -> DIM
        StoreActivity.Phase.WAITING_CONFIRM, StoreActivity.Phase.RETRY -> KitStoreRow.AMBER_FILL
        else -> KitStoreRow.UPDATE
    }
    KitStoreRowLine(Modifier.testTag("$TAG:${i.key}")) {
        if (icon != null) Image(icon, null, Modifier.size(sdp(StoreDensity.GLYPH)).testTag("$TAG:${i.key}:icon"))
        else Spacer(Modifier.size(sdp(StoreDensity.GLYPH)))
        Spacer(Modifier.width(KitStoreRow.gap))
        KitStoreName(i.label, Modifier.weight(0.8f).testTag("$TAG:${i.key}:name"), Color.White)
        Spacer(Modifier.width(KitStoreRow.gap))
        KitStoreCaption(i.phaseText(), color = ink, modifier = Modifier.weight(1.2f).testTag("$TAG:${i.key}:text"))
        val id = i.engineId
        if (e != null && id != null) {
            if (i.canPause) Verb("⏸", KitStoreRow.AMBER_FILL, "$TAG:${i.key}:pause") { e.pause(id, UserAction.tap("activity:pause")) }
            if (i.canResume) Verb("▶", KitStoreRow.UP, "$TAG:${i.key}:resume") { e.resume(id) }
            if (i.canRetry) Verb("↻", KitStoreRow.UP, "$TAG:${i.key}:retry") { e.resume(id) }
            if (i.canCancel) Verb("✕", KitStoreRow.RULE, "$TAG:${i.key}:cancel") { e.cancel(id, UserAction.tap("activity:cancel")) }
        }
    }
}

@Composable
private fun Verb(glyph: String, fill: Color, tag: String, onClick: () -> Unit) {
    Spacer(Modifier.width(KitStoreRow.tight))
    KitStoreQuick(glyph, fill, Modifier.testTag(tag), onClick)
}

private fun toneInk(o: StoreActivity.Overall): Color = when (o.tone) {
    StoreActivity.Tone.ACTIVE -> KitStoreRow.UP
    StoreActivity.Tone.PAUSED -> KitStoreRow.UPDATE
    StoreActivity.Tone.ERROR -> KitStoreRow.BAD
    StoreActivity.Tone.IDLE -> if (o.updates > 0) KitStoreRow.UPDATE else DIM
}

/** The app's launcher icon when it is on the device (the PackageManager already has it), else nothing. */
private fun appIcon(ctx: Context, pkg: String) =
    pkg.takeIf { it.isNotEmpty() }?.let { p ->
        runCatching { ctx.packageManager.getApplicationIcon(p).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()
    }

private class PrefsFlags(ctx: Context) : StoreActivity.Flags {
    private val sp = ctx.getSharedPreferences("store_activity", Context.MODE_PRIVATE)
    override fun get(key: String, default: Boolean) = sp.getBoolean(key, default)
    override fun put(key: String, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
}

private val NONE = MutableStateFlow<List<DlJob>>(emptyList())
private val DIM = Color.White.copy(alpha = 0.6f)
private const val TAG = "store-activity"
private const val ICON_PX = 48
