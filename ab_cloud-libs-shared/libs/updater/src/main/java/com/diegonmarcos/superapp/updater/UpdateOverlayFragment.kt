package com.diegonmarcos.superapp.updater

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.core.NotificationStore
import com.diegonmarcos.superapp.core.StoreNotifyGate

/**
 * Fullscreen overlay shown while the in-app updater is checking,
 * downloading, or installing. Driven by [UpdateProgress] state
 * transitions (subscribed by the host activity).
 *
 *   CheckingManifest → indeterminate spinner + "Checking…"
 *   Downloading      → percent bar + "Downloading 42%" + bytes/total
 *   Installing       → indeterminate spinner + "Installing…"
 *   Failed           → message + Dismiss
 *
 * 2026-10-15 owner decision: all apps must be Compose. The view is a ComposeView over [state]; the
 * hosts' contract is unchanged (add the fragment, call [applyState] on every progress tick), and the
 * overlay is shown over both View and Compose hosts.
 */
class UpdateOverlayFragment : Fragment() {

    /** What the host last told us; every part of the screen is derived from it. */
    private var state by mutableStateOf<UpdateProgress.State>(UpdateProgress.state)

    /** Report text once collected, so Copy/Save/Send all send the SAME bytes
     *  the user just read rather than re-collecting a slightly different one. */
    private var report: String? = null
    private var failed: UpdateProgress.State.Failed? = null
    private var showingReport by mutableStateOf(false)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        applyState(UpdateProgress.state)
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { OverlayTheme { Overlay() } }
        }
    }

    @Composable
    private fun OverlayTheme(content: @Composable () -> Unit) {
        val scheme = darkColorScheme(
            primary = colorResource(R.color.updater_primary),
            onPrimary = colorResource(R.color.updater_on_primary),
            surface = colorResource(R.color.updater_scrim),
            onSurface = colorResource(R.color.updater_title),
            onSurfaceVariant = colorResource(R.color.updater_detail),
            secondaryContainer = colorResource(R.color.updater_neutral),
            surfaceVariant = colorResource(R.color.updater_chip),
        )
        MaterialTheme(colorScheme = scheme, content = content)
    }

    @Composable
    private fun Overlay() {
        val st = state
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // Terminal states (Failed / Done) get the OK button + hidden bar;
        // in-progress states show the bar and hide the button.
        val terminal = st is UpdateProgress.State.Failed || st is UpdateProgress.State.Done
        val active = st is UpdateProgress.State.CheckingManifest ||
            st is UpdateProgress.State.Downloading || st is UpdateProgress.State.Installing
        // Metered "ask before download" prompt: Update now (primary) + Later
        // (the reused dismiss button, which resets → dismisses the overlay).
        val prompt = st is UpdateProgress.State.UpdateAvailable
        val reportShown = showingReport && st is UpdateProgress.State.Failed

        var title = ""
        var detail = ""
        var determinate: Int? = null
        var dismissLabel = "OK"
        when (st) {
            is UpdateProgress.State.Idle -> { /* about to dismiss */ }
            is UpdateProgress.State.Cancelled -> { /* applyState resets it, which dismisses */ }
            is UpdateProgress.State.UpdateAvailable -> {
                title = "Update available"
                detail = "${st.totalBytes.toMib()} MiB · you're on mobile data. Download now?"
                dismissLabel = "Later"
            }
            is UpdateProgress.State.CheckingManifest -> {
                title = batch("Checking for updates…")
                detail = "Reading GHCR manifest"
            }
            is UpdateProgress.State.Downloading -> {
                // An unknown total must READ as unknown. Showing "0%" and
                // "12.40 / -0.00 MiB" for a download whose bytes are genuinely
                // moving is the single hardest state to tell apart from a stuck
                // one, and a determinate bar pinned at zero says "stuck" as
                // loudly as a bar can.
                if (st.total > 0) {
                    title = batch("Downloading ${st.percent}%")
                    detail = "${st.bytes.toMib()} / ${st.total.toMib()} MiB"
                    determinate = st.percent
                } else {
                    title = batch("Downloading…")
                    detail = "${st.bytes.toMib()} MiB so far · total size unknown"
                }
            }
            is UpdateProgress.State.Waiting -> {
                title = batch("Waiting")
                detail = st.reason
            }
            is UpdateProgress.State.Installing -> {
                title = batch("Installing…")
                detail = "Handing the APK to the system installer"
            }
            is UpdateProgress.State.Done -> {
                title = "Done"
                detail = "Update complete"
            }
            is UpdateProgress.State.Failed -> {
                title = "Update failed"
                detail = st.message
            }
        }
        if (reportShown) {
            title = "Diagnosis"
            detail = report.orEmpty()
        }

        // Solid dark scrim so the overlay is unambiguously the focus. The click sink blocks input to
        // the app underneath on purpose.
        Box(
            Modifier.fillMaxSize().background(colorResource(R.color.updater_scrim)).clickable(enabled = true, onClick = {}),
        ) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (!terminal && !prompt) {
                    val bar = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp).height(8.dp)
                    val pct = determinate
                    if (pct != null) {
                        LinearProgressIndicator(progress = { pct / 100f }, modifier = bar)
                    } else {
                        LinearProgressIndicator(modifier = bar)
                    }
                }
                if (reportShown) {
                    // The report is long; let it scroll in place rather than opening a
                    // second screen the user has to find their way back from.
                    Column(Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState())) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                detail,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                } else {
                    Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                // Diagnose — offered only on Failed. "Update failed: <platform string>"
                // is where every one of these ends, and the platform string names a
                // symptom, not a cause. This turns the dead end into the local state
                // that explains it.
                if (st is UpdateProgress.State.Failed && !reportShown) {
                    OverlayButton("Diagnose", MaterialTheme.colorScheme.surfaceVariant, 12.dp) { showReport(ctx) }
                }
                // #1735 open installer sessions, and the way to free them: Android refuses new installs at 50.
                if (st is UpdateProgress.State.Failed) {
                    val open = remember(st) { InstallDiagnostics.openSessions(ctx) }
                    OverlayButton("Free install sessions ($open open)", MaterialTheme.colorScheme.surfaceVariant, 8.dp) {
                        Thread { val m = InstallDiagnostics.reapSessions(ctx.applicationContext); view?.post { toast(ctx, m) } }.start()
                    }
                }
                // Copy / Save / Send, revealed with the report. Three because the phone
                // that cannot reach the network is exactly the phone you most need the
                // report off, so Send must never be the only way out.
                if (reportShown) {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverlayButton("Copy", MaterialTheme.colorScheme.surfaceVariant, 0.dp) { copyReport(ctx) }
                        OverlayButton("Save", MaterialTheme.colorScheme.surfaceVariant, 0.dp) { saveReport(ctx) }
                        if (com.diegonmarcos.superapp.devtools.DiagnosticsPush.canReport()) {
                            OverlayButton("Send", MaterialTheme.colorScheme.surfaceVariant, 0.dp) { sendReport(ctx) }
                        }
                    }
                }
                // Cancel control — shown DURING an active check/download/install so a
                // stuck update can always be aborted (the reported "can't cancel when it
                // gets stuck" bug). Aborts the worker + flips state to Cancelled.
                if (active) {
                    OverlayButton("Cancel", MaterialTheme.colorScheme.secondaryContainer, 20.dp) {
                        runCatching { Updater.cancelNow(requireContext()) }
                    }
                    // Minimize — "give me my screen back", NOT "stop". Shown beside Cancel
                    // during any active state, because a manual update the user chose to
                    // start is still not a reason to hold the whole screen hostage until it
                    // finishes. It is deliberately a peer of Cancel and not a corner "x":
                    // the two outcomes are different enough that the safe one has to be as
                    // easy to hit as the destructive one.
                    //
                    // This touches UpdateProgress' VISIBILITY latch only. No cancelNow, no
                    // reset, no state write — the download and install carry on untouched
                    // and never learn this happened. The scrim goes with the fragment, so
                    // input returns to the app underneath.
                    OverlayButton("Minimize", MaterialTheme.colorScheme.secondaryContainer, 12.dp) { minimize() }
                }
                // "Update now" — shown ONLY on the metered UpdateAvailable prompt. Grants
                // consent to download over mobile data; kicks a forced one-shot check.
                if (prompt) {
                    OverlayButton("Update now", MaterialTheme.colorScheme.primary, 20.dp) {
                        runCatching { Updater.downloadNow(requireContext()) }
                    }
                }
                // Dismiss control — shown ONLY on terminal states (Failed / Done) so
                // the user can read the error and hand controls back. During an active
                // check/download/install it stays hidden (the scrim blocks input on
                // purpose). Tapping it drives UpdateProgress → Idle, which makes
                // the host activity remove this overlay.
                if (terminal || prompt) {
                    OverlayButton(dismissLabel, MaterialTheme.colorScheme.primary, 28.dp) { UpdateProgress.reset() }
                }
            }
        }
    }

    @Composable
    private fun OverlayButton(label: String, fill: androidx.compose.ui.graphics.Color, top: androidx.compose.ui.unit.Dp, onTap: () -> Unit) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = top)
                .background(fill)
                .clickable(onClick = onTap)
                .padding(horizontal = 28.dp, vertical = 12.dp),
        )
    }

    /** Called by the host on every [UpdateProgress] tick (and once when the view is created). */
    fun applyState(state: UpdateProgress.State) {
        if (state is UpdateProgress.State.Failed) failed = state else showingReport = false
        this.state = state
        if (state is UpdateProgress.State.Cancelled) UpdateProgress.reset() // dismiss
    }

    private fun minimize() {
        runCatching {
            val c = requireContext()
            // Tell the user where the work went and how to get it back,
            // on the feed they already have. Reusing NotificationStore
            // rather than adding a status surface is the whole point —
            // this complaint has produced four of those already.
            if (StoreNotifyGate.mayPost(c)) NotificationStore.push(
                ctx      = c,
                source   = "Updater",
                title    = "Update continues in the background",
                body     = (UpdateProgress.batchLabel ?: "Update") +
                    " is still running. Tap Check for updates to bring the progress view back.",
                severity = NotificationStore.Sev.INFO,
            )
            // Latch first, then drop the view: the host re-attaches on
            // the next progress tick, so removing the fragment without
            // setting the latch would simply bounce it back on screen.
            UpdateProgress.minimize()
            parentFragmentManager.beginTransaction()
                .remove(this@UpdateOverlayFragment)
                .commitAllowingStateLoss()
        }
    }

    /** Prefix an active-state title with the "Chat · 2/5" batch header when a
     *  multi-app "Update all" is running; plain title for single installs. */
    private fun batch(title: String): String =
        UpdateProgress.batchLabel?.let { "$it\n$title" } ?: title

    private fun Long.toMib(): String = "%.2f".format(this / (1024.0 * 1024.0))
    /** Collect once, show it, and swap the buttons for the export row. */
    private fun showReport(ctx: android.content.Context) {
        val f = failed
        report = report ?: InstallDiagnostics.collect(
            ctx,
            appId = f?.appId?.ifBlank { ctx.packageName } ?: ctx.packageName,
            pkg = f?.pkg?.ifBlank { ctx.packageName } ?: ctx.packageName,
            error = f?.message ?: "unknown",
            apk = f?.apkPath?.takeIf { it.isNotBlank() }?.let { java.io.File(it) },
        )
        showingReport = true
    }

    private fun copyReport(ctx: android.content.Context) {
        val cb = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        cb?.setPrimaryClip(android.content.ClipData.newPlainText("diagnosis", report.orEmpty()))
        toast(ctx, "Copied — paste it anywhere")
    }

    private fun saveReport(ctx: android.content.Context) {
        val name = "install-diagnosis-${System.currentTimeMillis()}.txt"
        val path = com.diegonmarcos.superapp.devtools.DiagnosticsPush
            .downloadBundle(ctx, name, report.orEmpty())
        toast(ctx, if (path != null) "Saved to Downloads/$name" else "Could not write to Downloads")
    }

    /** Off the main thread: this is a network POST behind a button someone
     *  just tapped, and blocking the UI thread on it would ANR the overlay. */
    private fun sendReport(ctx: android.content.Context) {
        val app = failed?.appId?.ifBlank { ctx.packageName } ?: ctx.packageName
        val body = report.orEmpty()
        toast(ctx, "Sending…")
        Thread {
            val code = com.diegonmarcos.superapp.devtools.DiagnosticsPush.postReport(app, body)
            view?.post {
                toast(ctx, if (code in 200..299) "Sent as $app" else "Send failed (HTTP $code) — Copy or Save instead")
            }
        }.start()
    }

    private fun toast(ctx: android.content.Context, m: String) =
        android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        const val TAG = "update_overlay"
        fun newInstance() = UpdateOverlayFragment()
    }
}
