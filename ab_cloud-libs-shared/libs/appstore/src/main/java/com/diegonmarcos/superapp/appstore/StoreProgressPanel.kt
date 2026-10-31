package com.diegonmarcos.superapp.appstore

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater

/**
 * The live progress row under a Store page's buttons (#785): WHICH app (its launcher icon, when it is
 * on the device — the PackageManager already has it), its stage, version, bytes and %, one overall line
 * while several keyed jobs run, Cancel while something is cancellable, and a tap that opens that app's
 * row ([onOpen], with [fleet] to look the app up in; Phone has neither). Same sources and rules for
 * every page ([StoreStages.progress] — the line `/api/store/progress` returns — and the [StoreJobs]
 * board), so every page and the API say the same thing.
 *
 * Attached with [UpdateProgress.addObserver], never setListener: that slot is the shell overlay's, and
 * taking it would turn the overlay off for as long as this page is open. The pipeline posts from its
 * worker thread, so the state is described HERE, on the thread that published it, so the line pairs
 * this state with the job that was running when it was published, then handed to the main thread.
 *
 * The states keep their old rules: an unknown size is an indeterminate bar and never a hard 0% (identical,
 * to the person watching, to a stalled transfer); a failure is never hidden (silence about work that did
 * not happen is hiding, not quietness); a Cancel the user asked for is not a failure to report, so the
 * row goes; and the Done/Idle dip between two apps of a batch keeps the row up instead of flickering it
 * out per app. A failure's reason is long and this line is one slot above the list: it names the app and
 * points at its row, where the reason is whole (#831).
 */
class StoreProgressPanel(
    private val fleet: List<Fleet.App> = emptyList(),
    private val onOpen: ((Fleet.App) -> Unit)? = null,
) {
    /** What the row says now; null = nothing running, nothing drawn. */
    class Ui(
        val text: String, val failed: Boolean, val percent: Int, val indeterminate: Boolean, val cancellable: Boolean,
        val pkg: String = "", val target: Fleet.App? = null,
    )

    var ui by mutableStateOf<Ui?>(null)
        private set

    /** Bumped on every progress or job-board event: the page's [StoreTally] area re-reads on it, so a
     *  download landing in the cache shows there live. */
    var tick by mutableIntStateOf(0)
        private set

    /** #894 the one writer of the bar's state: monotonic per item (see [ProgressBarModel]). */
    private val model = ProgressBarModel()
    private val main = Handler(Looper.getMainLooper())

    private val progressObserver: (UpdateProgress.State) -> Unit = { state ->
        val p = StoreStages.progress(state)
        // While keyed jobs run, the bar speaks from THEIR board, not from the one process-wide state
        // whichever job wrote last.
        main.post { ui = if (StoreJobs.board.overall().active > 0) board() else single(state, p); tick++ }
    }
    private val boardObserver: () -> Unit = {
        main.post { ui = if (StoreJobs.board.overall().active > 0) board() else single(UpdateProgress.state, StoreStages.progress()); tick++ }
    }

    /** Listen from now on; [detach] stops. Idempotent: re-attaching never stacks observers. */
    fun attach() {
        detach()
        UpdateProgress.addObserver(progressObserver)
        StoreJobs.install()
        StoreJobs.addObserver(boardObserver)
    }

    fun detach() {
        UpdateProgress.removeObserver(progressObserver)
        StoreJobs.removeObserver(boardObserver)
    }

    /** Several jobs in flight: ONE overall line ("3 running · 2 queued · 41%"), bytes done over bytes total
     *  with installs as steps. One job: that job's own words. Never one app's percent for all of them. */
    private fun board(): Ui {
        val o = StoreJobs.board.overall()
        val only = StoreJobs.board.rows().filter { !it.finished }.singleOrNull()
        val d = model.step(if (o.multi) "overall" else only?.key.orEmpty(),
            if (o.percent > 0) 1L else 0L, if (o.multi) o.percent else only?.percent ?: -1, false)
        return Ui(if (o.multi || only == null) o.text() else "${only.label} · ${only.text()}", false, d.percent, d.indeterminate, true)
    }

    private fun single(state: UpdateProgress.State, p: StoreStages.Progress?): Ui? {
        if (state is UpdateProgress.State.Cancelled) { model.reset(); UpdateProgress.reset(); return null }
        if (p == null) { model.reset(); return null }
        val d = model.step(p.appId.ifEmpty { p.pkg }, p.bytes, p.percent, p.failed)
        // Offer Cancel only while something is actually cancellable. Failed has already stopped, and the
        // batch-gap between apps is not a job of its own.
        val cancellable = state is UpdateProgress.State.Downloading || state is UpdateProgress.State.CheckingManifest ||
            state is UpdateProgress.State.Installing
        val target = fleet.firstOrNull { it.id == p.appId || (p.pkg.isNotEmpty() && it.pkg == p.pkg) }
        return Ui(if (p.failed) StoreRowError.banner(p.app) else p.text, p.failed, d.percent, d.indeterminate, cancellable, p.pkg, target)
    }

    /** #812 A batch's outcome, IN the page's progress bar — never a dialog. A failure already drawn
     *  there ([StoreStages.progress]) keeps the bar. */
    fun report(title: String, b: StoreStages.Batch) {
        if (StoreStages.progress() != null) return
        val d = model.complete()
        ui = Ui("$title — ${b.summary.ifEmpty { "nothing to do" }}" + if (b.online) "" else " (offline)",
            false, d.percent, d.indeterminate, false)
    }

    @Composable
    fun Panel(modifier: Modifier = Modifier) {
        val u = ui ?: return
        val ctx = LocalContext.current
        val tint = (if (u.failed) FAILED else RUNNING).ink()
        val icon = remember(u.pkg) {
            u.pkg.takeIf { it.isNotEmpty() }?.let { pkg ->
                runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()
            }
        }
        val open: (() -> Unit)? = u.target?.takeIf { onOpen != null }?.let { t -> { onOpen?.invoke(t); Unit } }
        Column(
            modifier.fillMaxWidth().testTag(StoreBar.PROGRESS_TAG).tap(open)
                .padding(top = sdp(StoreDensity.S6), bottom = sdp(StoreDensity.S4)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Image(icon, null, Modifier.size(sdp(StoreDensity.GLYPH)).testTag("store-progress:icon"))
                    Spacer(Modifier.width(sdp(StoreDensity.S6)))
                }
                Text(u.text, color = tint, fontSize = StoreDensity.T_META.sp,
                    modifier = Modifier.weight(1f).testTag("store-progress:label")
                        .semantics { storeInk = if (u.failed) FAILED else RUNNING })
            }
            Spacer(Modifier.size(sdp(StoreDensity.S4)))
            if (u.indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("store-progress:bar"))
            else LinearProgressIndicator(progress = { u.percent / 100f }, modifier = Modifier.fillMaxWidth().testTag("store-progress:bar"))
            if (u.cancellable) Box(Modifier.fillMaxWidth().padding(top = sdp(StoreDensity.S6)), contentAlignment = Alignment.CenterEnd) {
                // Cancel. UpdateProgress.cancelRequested is polled by both download loops in ApkSource and by
                // both phases of Fleet.installAll, and the shell overlay has driven it through Updater.cancelNow
                // all along: a download started from the table can be stopped from here too.
                StoreBar.Button(CANCEL, Ink.GREY_FILL, { Updater.cancelNow(ctx.applicationContext) }, tag = "store-progress:cancel")
            }
        }
    }

    private companion object {
        const val RUNNING = 0xFFED8936.toInt()
        const val FAILED = 0xFFF56565.toInt()
        const val CANCEL = "Cancel"
        const val ICON_PX = 48
    }
}
