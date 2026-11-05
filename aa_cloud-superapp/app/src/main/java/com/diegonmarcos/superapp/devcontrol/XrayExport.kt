package com.diegonmarcos.superapp.devcontrol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.adbdebug.ChannelOps
import com.diegonmarcos.superapp.adbdebug.SetupAction
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import com.diegonmarcos.superapp.battery.BatteryRepository
import com.diegonmarcos.superapp.ops.dagu.DaguPrefs
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.xray.PARTIAL_LABEL
import com.diegonmarcos.superapp.xray.SRC_APP
import com.diegonmarcos.superapp.xray.XSection
import com.diegonmarcos.superapp.xray.XrayAdbGate
import com.diegonmarcos.superapp.xray.XrayBattery
import com.diegonmarcos.superapp.xray.XrayExporter
import com.diegonmarcos.superapp.xray.XrayFormat
import com.diegonmarcos.superapp.xray.XrayInputs
import com.diegonmarcos.superapp.xray.XrayPoster
import com.diegonmarcos.superapp.xray.XrayShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import com.diegonmarcos.superapp.uikit.KitTextButton
import androidx.compose.runtime.Composable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The four outputs of Export All. */
internal enum class XrayOut(val label: String) {
    MARKDOWN("Export Markdown file"), JSON("Export JSON file"), COPY("Copy to clipboard"), POST("Post to the C3 API")
}

/**
 * #1504 Configs ▸ X-Ray ▸ Export All. On demand only (a tap; nothing here polls or schedules): gate on ADB, walk every X-Ray block of
 * the page and wait for its probes, run the shell probes ([XrayExporter]), then write the chosen output.
 */
internal object XrayExport {
    const val MACRO = "EXPORT ALL"
    private const val WAIT_MS = 25_000L
    private const val CLIP_MAX = 400_000
    private val busy = AtomicBoolean(false)

    suspend fun run(ctx: Context, ask: suspend (String) -> XrayAdbGate.Choice, page: AboutPage, scope: CoroutineScope, out: XrayOut, say: (String) -> Unit): String {
        if (!busy.compareAndSet(false, true)) return "An export is already running"
        try {
            return withContext(Dispatchers.IO) { go(ctx.applicationContext, ask, page, scope, out, say) }
        } finally { busy.set(false) }
    }

    private suspend fun go(ctx: Context, ask: suspend (String) -> XrayAdbGate.Choice, page: AboutPage, scope: CoroutineScope, out: XrayOut, say: (String) -> Unit): String {
        say("checking the ADB shell…")
        val gate = XrayAdbGate.run(
            ready = { ShellChannels.active(ctx) != null },
            // the ADB Shell page's own Reconnect (lib ChannelOps), not a raw connect
            rearm = { runCatching { ChannelOps.reconnect(ctx) }.isSuccess && ShellChannels.active(ctx) != null },
            openWirelessDebugging = { openWirelessDebugging(ctx) },
            ask = ask,
        )
        if (gate is XrayAdbGate.Result.Cancelled) return "Export cancelled"
        val ch = if (gate is XrayAdbGate.Result.Ready) ShellChannels.active(ctx) else null
        val shell = ch?.let { c ->
            object : XrayShell {
                override val channel = c.name()
                // #1510 through the SuperApp's one shell broker (SuperApp entry: the X-Ray probes are declared `exact` lines).
                override fun exec(cmd: String): String? = ShellBroker.execText(ctx, cmd)
            }
        }

        say("reading every X-Ray block…")
        val walk = walk(page, scope)
        say(if (shell != null) "running the shell probes (batterystats, wakelocks, alarms, services…)" else "reading what the app sandbox can see ($PARTIAL_LABEL)…")
        val extra = runCatching {
            val live = runCatching { BatteryRepository.report(ctx)?.sessions }.getOrNull().orEmpty()
            XrayBattery.sections(BatteryRepository.sessions90(ctx, live), BatteryRepository.cycles(ctx), label = { uid -> BatteryRepository.appLabel(ctx, uid).second })
        }.getOrDefault(emptyList())
        val report = XrayExporter.build(XrayInputs(
            device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
            appVersion = "${BuildConfig.VERSION_NAME} ${BuildConfig.GIT_SHORT_SHA}",
            now = System.currentTimeMillis(), walk = walk, shell = shell,
            partialReason = (gate as? XrayAdbGate.Result.Partial)?.reason.orEmpty(),
            app = { key -> if (key.startsWith("secure:")) runCatching { Settings.Secure.getString(ctx.contentResolver, key.removePrefix("secure:")) }.getOrNull() else null },
            extra = extra,
        ))
        val tag = if (report.partial) " [$PARTIAL_LABEL]" else ""
        return when (out) {
            XrayOut.MARKDOWN -> "Saved " + save(ctx, XrayFormat.fileStem(report) + ".md", "text/markdown", XrayFormat.markdown(report)) + tag
            XrayOut.JSON -> "Saved " + save(ctx, XrayFormat.fileStem(report) + ".json", "application/json", XrayFormat.jsonText(report)) + tag
            XrayOut.COPY -> {
                val md = XrayFormat.markdown(report)
                val text = if (md.length > CLIP_MAX) md.take(CLIP_MAX) + "\n…(clipboard cut; export the file for the whole report)" else md
                withContext(Dispatchers.Main) {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("X-Ray report", text))
                }
                "Copied ${text.length} chars$tag"
            }
            XrayOut.POST -> {
                say("posting to the C3 API…")
                val ops = BuildConfig.UI_C3_OPS_BASE_URL.trimEnd('/')
                val bases = listOf(ops.replace("/c3-infra-api", "/cloudy-c3-api"), ops).distinct()
                val bearer = AccountData.secret(ctx, "fleet.bearer").trim().ifEmpty { runCatching { DaguPrefs(ctx).bearerToken }.getOrDefault("") }
                when (val r = XrayPoster.post(bases, bearer, report, XrayPoster.fleetTransport, XrayPoster.plainTransport)) {
                    is XrayPoster.Outcome.Ok -> "Posted via ${r.via} to ${r.route}$tag"
                    is XrayPoster.Outcome.Failed -> "Post failed: ${r.reason}"
                }
            }
        }
    }

    /** Load every block (main thread, as the page does), wait for their probes, then read each block's text with its live values. */
    private suspend fun walk(page: AboutPage, scope: CoroutineScope): List<XSection> {
        val secs = page.sections.filter { it.macro != MACRO }
        withContext(Dispatchers.Main) { secs.forEach { it.load(scope) } }
        var waited = 0L
        while (secs.any { it.pending.get() > 0 } && waited < WAIT_MS) { delay(250); waited += 250 }
        return withContext(Dispatchers.Main) {
            secs.map { s ->
                var text = s.buf.toString().lineSequence().drop(2).joinToString("\n") // the "## head" line and its blank
                for (it in s.items) if (it is AboutItem.Row && it.text != it.initial) text = text.replace("  ${it.key}: ${it.initial}\n", "  ${it.key}: ${it.text}\n")
                if (s.pending.get() > 0) text += "\n  (still reading when exported)"
                XSection("X-Ray · ${s.macro}", s.head, SRC_APP, text, ok = s.started)
            }
        }
    }

    private fun save(ctx: Context, name: String, mime: String, text: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/xray")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("could not create the file")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("could not write the file")
            return "Download/xray/$name"
        }
        val dir = File(ctx.getExternalFilesDir(null), "xray").apply { mkdirs() }
        return File(dir, name).also { it.writeText(text) }.absolutePath
    }

    private fun openWirelessDebugging(ctx: Context) {
        runCatching { ChannelOps.setup(ctx, SetupAction.OPEN_WIRELESS_DEBUGGING) }
    }
}

/** The ADB prompt on screen: the message and where the answer goes. */
internal class XrayAsk(val message: String, val answer: (XrayAdbGate.Choice) -> Unit)

@Composable
internal fun XrayAdbDialog(a: XrayAsk) {
    AlertDialog(
        onDismissRequest = { a.answer(XrayAdbGate.Choice.CANCEL) },
        title = { Text("X-Ray needs ADB") },
        text = { Text(a.message) },
        confirmButton = { KitTextButton(onClick = { a.answer(XrayAdbGate.Choice.REARM) }) { Text("Re-arm channel") } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                KitTextButton(onClick = { a.answer(XrayAdbGate.Choice.OPEN_WIRELESS_DEBUGGING) }) { Text("Wireless debugging") }
                KitTextButton(onClick = { a.answer(XrayAdbGate.Choice.PARTIAL) }) { Text("Export partial") }
            }
        },
    )
}
