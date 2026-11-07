package com.diegonmarcos.superapp.devcontrol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.diegonmarcos.superapp.settings.ConfigHeader
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitFilterChips
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Configs ▸ Setup ▸ Launcher ▸ Logcat (build.json config page `logcat`): the DEDICATED Cloud SuperApp logcat. It streams this
 * app's own lines (its uid, com.diegonmarcos.superapp) ONLINE from the moment it opens, from the shared LogPipe stream, and has
 * two separate control rows: FILTERS (level, tag, search, autoscroll) and ACTIONS (clear, pause / resume, copy, export, share).
 * The all-apps / one-app reader that used to be this page is Configs ▸ Watchtower - Obsv ▸ Logcat Fleet ([FleetLogcatFragment]).
 */
class LogcatFragment : KitComposeFragment() {
    override fun palette() = LauncherPalette.kit(requireContext())

    private val log = LogcatSink(this)

    @Composable
    override fun Content() {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            ConfigHeader("Logcat")
            AppLogcatScreen(onExport = log::export, onShare = log::share, onCopy = log::copy, lifecycle = viewLifecycleOwner.lifecycle,
                modifier = Modifier.weight(1f))
        }
    }

    companion object { fun newInstance() = LogcatFragment() }
}

/** Copy / Save-as / Share for a log page. Registers the SAF launcher, so build it as a fragment property. */
internal class LogcatSink(private val f: androidx.fragment.app.Fragment) {
    /** Buffer handed to the SAF writer — the picker is async, so the text has to survive the round trip. */
    private var pending: String = ""

    /** Save-as through the system picker: no FileProvider to declare and no storage permission to request. */
    private val saveAs =
        f.registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = f.requireContext()
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(pending.toByteArray()) } }.isSuccess
            Toast.makeText(ctx, if (ok) "Exported ${pending.length} chars" else "Export failed", Toast.LENGTH_SHORT).show()
        }

    fun export(text: String, fileName: String) {
        pending = text
        runCatching { saveAs.launch(fileName) }
            .onFailure { Toast.makeText(f.requireContext(), "No file picker available", Toast.LENGTH_SHORT).show() }
    }

    fun copy(text: String) {
        val ctx = f.requireContext()
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("logcat", text))
        Toast.makeText(ctx, "Copied ${text.length} chars", Toast.LENGTH_SHORT).show()
    }

    fun share(text: String, subject: String) {
        val ctx = f.requireContext()
        runCatching {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, LogcatModel.shareable(text)).putExtra(Intent.EXTRA_SUBJECT, subject), subject)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(ctx, "Nothing to share with", Toast.LENGTH_SHORT).show() }
    }
}

/** A line's colour by level: errors and fatals red, warnings amber, info plain, debug and verbose dim. */
@Composable
internal fun logLineColor(level: Char): Color {
    val p = LocalKitPalette.current
    return when (level) {
        'E', 'F', 'A' -> p.bad
        'W' -> p.warn
        'I' -> p.textPrimary
        else -> p.textSecondary
    }
}

/** The bordered terminal-style pane both Logcat pages draw their lines in. */
@Composable
internal fun LogPane(recs: List<LogcatModel.Rec>, autoscroll: Boolean, tag: String, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(recs.lastOrNull()?.raw, recs.size, autoscroll) {
        if (autoscroll && recs.isNotEmpty()) list.scrollToItem(recs.lastIndex)
    }
    LazyColumn(modifier.fillMaxWidth().heightIn(min = 160.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0D0D12))
        .border(1.dp, Color(0xFF4DA3FF), RoundedCornerShape(10.dp)).padding(8.dp).testTag(tag), state = list) {
        items(recs) { r ->
            Text(r.raw, color = logLineColor(r.level), fontFamily = FontFamily.Monospace, fontSize = 10.sp, softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()))
        }
    }
}

/**
 * The single-app logcat. Two rows, never mixed: FILTERS change what is SHOWN (level, tag, search, autoscroll), ACTIONS do
 * something with it (clear, pause / resume, copy, export, share). Copy, Export and Share take what the Filters currently show.
 * The stream polls LogPipe once a second while not paused; Pause freezes the pane, Clear hides everything up to now.
 */
@Composable
internal fun AppLogcatScreen(
    onExport: (text: String, fileName: String) -> Unit,
    onShare: (text: String, subject: String) -> Unit,
    onCopy: (text: String) -> Unit,
    lifecycle: Lifecycle,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    var minLevel by remember { mutableStateOf('V') }
    var tagFilter by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var autoscroll by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var recs by remember { mutableStateOf<List<LogcatModel.Rec>>(emptyList()) }
    var mark by remember { mutableStateOf<String?>(null) }

    // ONLINE by default: nothing to tap. This app's own uid needs no READ_LOGS and never raises the log-consent dialog.
    // Runs only while the page is RESUMED (nothing polls behind another screen) and not paused.
    LaunchedEffect(paused) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (!paused) {
                val text = withContext(Dispatchers.IO) { AboutLogcat.read(ctx, AboutLogcat.SCOPE_THIS_APP, false) }
                recs = LogcatModel.parse(text)
                delay(1_000)
            }
        }
    }

    val shown = LogcatModel.filter(LogcatModel.afterMark(recs, mark), minLevel, tagFilter, query)
    fun stamp() = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${com.diegonmarcos.superapp.BuildConfig.APPLICATION_ID} · uid ${android.os.Process.myUid()} · " +
            (if (paused) "paused" else "live") + " · ${shown.size} of ${recs.size} lines",
            color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.testTag("logcat:status"))

        // ── FILTERS: what is shown ────────────────────────────────────────────
        Column(Modifier.fillMaxWidth().testTag("logcat:filters"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Filters", color = p.textPrimary, fontSize = 12.sp)
            KitFilterChips(LogcatModel.LEVELS.map { it.toString() to it.toString() }, minLevel.toString(),
                { minLevel = it[0] }, group = "logcat-level")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(tagFilter, { tagFilter = it }, singleLine = true, label = { Text("Tag") },
                    modifier = Modifier.weight(1f).testTag("logcat:filter:tag"))
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search") },
                    modifier = Modifier.weight(1f).testTag("logcat:filter:search"))
            }
            com.diegonmarcos.superapp.uikit.KitSwitchRow("Autoscroll", "follow the newest line", autoscroll, { autoscroll = it },
                Modifier.testTag("logcat:filter:autoscroll"))
        }

        // ── ACTIONS: what to do with it ───────────────────────────────────────
        Column(Modifier.fillMaxWidth().testTag("logcat:actions"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Actions", color = p.textPrimary, fontSize = 12.sp)
            KitActionBar(listOf(
                KitAction("Clear", "logcat:act:clear") { mark = recs.lastOrNull()?.raw },
                KitAction(if (paused) "Resume" else "Pause", "logcat:act:pause") { paused = !paused },
                KitAction("Copy", "logcat:act:copy") { onCopy(LogcatModel.text(shown)) },
                KitAction("Export", "logcat:act:export") { onExport(LogcatModel.text(shown), AboutLogcat.exportName(AboutLogcat.SCOPE_THIS_APP, "", stamp())) },
                KitAction("Share", "logcat:act:share") { onShare(LogcatModel.text(shown), "Cloud SuperApp logcat") },
            ), filledFirst = false)
        }

        LogPane(shown, autoscroll, "logcat:pane", Modifier.weight(1f))
    }
}
