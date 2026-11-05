package com.diegonmarcos.cloudcalc.ui

import com.diegonmarcos.superapp.perms.rememberPermsMissing
import com.diegonmarcos.superapp.perms.PermsFixChip
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.audio.Mic
import com.diegonmarcos.cloudcalc.audio.Player
import com.diegonmarcos.cloudcalc.audio.SoundDecl
import com.diegonmarcos.cloudcalc.audio.SoundFlow
import com.diegonmarcos.cloudcalc.audio.SoundStore
import com.diegonmarcos.superapp.decisions.Decision
import com.diegonmarcos.cloudcalc.jev.JevConfig
import com.diegonmarcos.cloudcalc.sound.Analysis
import com.diegonmarcos.cloudcalc.sound.Generator
import com.diegonmarcos.cloudcalc.sound.Notes
import com.diegonmarcos.cloudcalc.sound.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.sound.SoundConfig
import com.diegonmarcos.superapp.image.mlkit.RecognitionRoutes
import com.diegonmarcos.superapp.sound.SoundPrefs

/** Microphone and file work block: always off the main thread. */
private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

/**
 * The microphone gate (#1515): a sound mode that listens composes its content through it; while RECORD_AUDIO is
 * missing it shows the compact "Fix in Perms" chip (Configs > Perms asks and grants, nothing is asked here).
 */
@Composable
fun MicGate(why: Int = R.string.mic_needed, content: @Composable () -> Unit) {
    if (rememberPermsMissing(listOf("microphone")).isEmpty()) content()
    else Column(Modifier.fillMaxSize().padding(CalcMetrics.gutter)) {
        Text(stringResource(why))
        PermsFixChip(listOf("microphone"), "sound")
    }
}

/** The three per-device knobs every sound screen shares: calibration, tuning reference, temperature. */
@Composable
fun SoundKnobs(onChange: (SoundStore.Knobs) -> Unit = {}) {
    val ctx = LocalContext.current
    val k = remember { SoundStore.knobs(ctx) }
    var cal by rememberSaveable { mutableStateOf(k.calibrationDb.toString()) }
    var a4 by rememberSaveable { mutableStateOf(k.a4Hz.toString()) }
    var temp by rememberSaveable { mutableStateOf(k.temperatureC.toString()) }
    fun save() {
        val n = SoundStore.Knobs(cal.toDoubleOrNull() ?: return, a4.toDoubleOrNull()?.takeIf { it > 0 } ?: return, temp.toDoubleOrNull()?.takeIf { it > -273.15 } ?: return)
        SoundStore.setKnobs(ctx, n); onChange(n)
    }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        OutlinedTextField(cal, { cal = it; save() }, Modifier.weight(1f).testTag(SoundTags.CALIBRATION), label = { Text(stringResource(R.string.calibration)) }, singleLine = true, keyboardOptions = num)
        OutlinedTextField(a4, { a4 = it; save() }, Modifier.weight(1f), label = { Text(stringResource(R.string.sound_a4)) }, singleLine = true, keyboardOptions = num)
        OutlinedTextField(temp, { temp = it; save() }, Modifier.weight(1f), label = { Text(stringResource(R.string.sound_temperature)) }, singleLine = true, keyboardOptions = num)
    }
}

@Composable
internal fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.hairline), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun hz(f: Double?): String = f?.let { if (it >= 1000) "%.2f kHz".format(it / 1000) else "%.1f Hz".format(it) } ?: "—"
internal fun noteText(n: Notes.Note?): String = n?.let { "${it.label} %+d¢".format(Math.round(it.cents)) } ?: "—"
internal fun metres(m: Double?): String = m?.let { if (it < 1) "%.1f cm".format(it * 100) else "%.2f m".format(it) } ?: "—"
internal fun db(v: Double): String = "%.1f dB".format(v)

// ── Wave detection ──────────────────────────────────────────────────────────────────────────

@Composable
fun SoundEventsMode(mode: Declarations.Mode) = MicGate {
    val ctx = LocalContext.current
    val cfg = SoundDecl.config
    val scope = rememberCoroutineScope()
    var seconds by rememberSaveable { mutableStateOf((cfg.recordMs / 1000.0).toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<Analysis.Result, JSONObject>?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        SoundKnobs()
        OutlinedTextField(seconds, { seconds = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sound_listen_seconds)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Button(enabled = !busy, modifier = Modifier.testTag(SoundTags.LISTEN), onClick = {
            val ms = ((seconds.toDoubleOrNull() ?: (cfg.recordMs / 1000.0)) * 1000).toInt().coerceIn(100, cfg.maxRecordMs)
            scope.launch {
                busy = true; error = ""
                runCatching {
                    val pcm = io { Mic.record(ctx, cfg.sampleRate, ms) }
                    val r = io { SoundFlow.analyze(pcm, cfg.sampleRate) }
                    result = r to SoundFlow.summary(r, SoundStore.knobs(ctx))
                }.onFailure { error = it.message ?: it.javaClass.simpleName }
                busy = false
            }
        }) { Text(stringResource(if (busy) R.string.sound_listening else R.string.sound_listen)) }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        result?.let { (r, s) ->
            AnalysisView(r, s)
            AskAboutResult(mode.id, stringResource(R.string.sound_events_calc, s.getDouble("duration_s").toString()), detected(r, s))
        }
    }
}

/** The line a result is asked about and kept as: frequency and the events' kinds. */
private fun detected(r: Analysis.Result, s: JSONObject): String =
    if (r.events.isEmpty()) "no events (${db(r.levelDb)})"
    else hz(s.optDouble("frequency_hz")) + ", " + r.events.groupingBy { it.kind }.eachCount().entries.joinToString { "${it.value} ${it.key}" }

@Composable
private fun AnalysisView(r: Analysis.Result, s: JSONObject) {
    Column(Modifier.fillMaxWidth().testTag(CalcTags.RESULT)) {
        Fact(stringResource(R.string.sound_level), db(s.getDouble("level_db")))
        Fact(stringResource(R.string.sound_frequency), hz(s.optDouble("frequency_hz").takeIf { !it.isNaN() }) + "  " + s.optString("note").takeIf { it.isNotBlank() && it != "null" }.orEmpty())
        Fact(stringResource(R.string.sound_dominant), hz(r.peakHz))
        Fact(stringResource(R.string.sound_wavelength), metres(s.optDouble("wavelength_m").takeIf { !it.isNaN() }))
        Fact(stringResource(R.string.sound_onsets), r.onsets.size.toString())
        Fact(stringResource(R.string.sound_periodic), r.periodicity.periodS?.let { "%.3f s (%.2f Hz)".format(it, 1 / it) } ?: stringResource(R.string.sound_not_periodic))
        HorizontalDivider(Modifier.padding(vertical = CalcMetrics.gap))
        if (r.events.isEmpty()) Text(stringResource(R.string.sound_no_events))
        r.events.forEachIndexed { i, e ->
            Text("${i + 1}. ${e.kind}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(SoundTags.event(i)))
            Fact(stringResource(R.string.sound_start_duration), "%.3f s · %.0f ms".format(e.start, e.duration * 1000))
            Fact(stringResource(R.string.sound_fundamental), hz(e.f0) + "  " + noteText(e.f0?.let { Notes.of(it, SoundDecl.config.a4Hz) }))
            Fact(stringResource(R.string.sound_bandwidth), hz(e.bandwidth) + " · " + stringResource(R.string.sound_noisiness, "%.2f".format(e.flatness)))
            Fact(stringResource(R.string.sound_envelope), stringResource(R.string.sound_attack_decay, "%.0f".format(e.attackMs), "%.0f".format(e.decayMs)))
            if (e.harmonicsDb.size > 1) Fact(stringResource(R.string.sound_harmonics), e.harmonicsDb.drop(1).joinToString(" ") { "%.0f".format(it) } + " dB")
            Sparkline(e.envelope)
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, floorDb: Double = SoundFlow.FLOOR_DB) {
    val colour = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(CalcMetrics.keyHeight)) {
        if (values.size < 2) return@Canvas
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = i * size.width / (values.size - 1)
            val y = size.height * (1 - ((v - floorDb) / -floorDb).coerceIn(0.0, 1.0).toFloat())
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, colour, style = Stroke(CalcMetrics.stroke.toPx()))
    }
}

// ── History: waterfall + graphs, save and export ────────────────────────────────────────────

@Composable
fun SoundHistoryMode(mode: Declarations.Mode) = MicGate {
    val ctx = LocalContext.current
    val cfg = SoundDecl.config
    val h = cfg.history
    var running by remember { mutableStateOf(SoundStore.live?.running == true) }
    var tick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf(SoundStore.sessions(ctx)) }
    var exporting by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val f = exporting ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            note = runCatching { io { ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } }; ctx.getString(R.string.sound_exported, f.name) }
                .getOrElse { it.message ?: it.javaClass.simpleName }
        }
        exporting = null
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val live = SoundStore.Live(cfg.sampleRate, h.maxSamples, cfg.sampleRate * h.maxWavSeconds, h.rows, h.bands)
        SoundStore.live = live
        live.running = true
        val n = Integer.highestOneBit(max(256, cfg.sampleRate * h.refreshMs / 1000))
        // Leaving the tab cancels this effect: the session is kept (SoundStore.live) but stops.
        try {
            error = withContext(Dispatchers.Default) {
                runCatching {
                    Mic.stream(ctx, cfg.sampleRate, n) { pcm ->
                        live.keep(pcm)
                        val knobs = SoundStore.knobs(ctx)
                        val r = SoundFlow.reading(pcm, cfg.sampleRate, cfg, knobs)
                        val t = (System.currentTimeMillis() - live.startedAt) / 1000.0
                        live.add(Session.Sample(t, r.levelDb, r.aDb, r.cDb, r.dominantHz, r.f0, r.note?.label, r.wavelengthM))
                        live.spectrogram.push(Session.logBands(r.spectrumDb, cfg.sampleRate, h.bands, h.fminHz))
                        tick++
                    }
                }.exceptionOrNull()?.message.orEmpty()
            }
        } finally {
            live.running = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        SoundKnobs()
        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            Button(onClick = { running = !running }, modifier = Modifier.testTag(SoundTags.RUN)) { Text(stringResource(if (running) R.string.sound_stop else R.string.sound_start)) }
            OutlinedButton(enabled = !running && SoundStore.live != null, onClick = {
                val live = SoundStore.live ?: return@OutlinedButton
                scope.launch {
                    note = runCatching { io { SoundStore.save(ctx, live) }.joinToString { it.name }.let { ctx.getString(R.string.sound_saved, it) } }
                        .getOrElse { it.message ?: it.javaClass.simpleName }
                    sessions = SoundStore.sessions(ctx)
                }
            }) { Text(stringResource(R.string.sound_save)) }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
        val live = SoundStore.live
        val samples = remember(tick, live) { live?.snapshot().orEmpty() }
        samples.lastOrNull()?.let { s ->
            Fact(stringResource(R.string.sound_level), db(s.levelDb) + " · " + db(s.aDb) + "(A) · " + db(s.cDb) + "(C)")
            Fact(stringResource(R.string.sound_frequency), hz(s.f0 ?: s.peakHz) + "  " + (s.note ?: ""))
            Fact(stringResource(R.string.sound_wavelength), metres(s.wavelengthM))
        }
        if (live != null) Waterfall(live, tick)
        Text(stringResource(R.string.sound_graph_level), style = MaterialTheme.typography.labelLarge)
        Graph(samples.map { it.t to it.levelDb }, log = false)
        Text(stringResource(R.string.sound_graph_frequency), style = MaterialTheme.typography.labelLarge)
        Graph(samples.map { it.t to (it.f0 ?: it.peakHz) }, log = true)
        Text(stringResource(R.string.sound_graph_wavelength), style = MaterialTheme.typography.labelLarge)
        Graph(samples.mapNotNull { s -> s.wavelengthM?.let { s.t to it } }, log = true)
        HorizontalDivider(Modifier.padding(vertical = CalcMetrics.gap))
        Text(stringResource(R.string.sound_sessions), style = MaterialTheme.typography.titleMedium)
        if (sessions.isEmpty()) Text(stringResource(R.string.sound_no_sessions), style = MaterialTheme.typography.bodySmall)
        sessions.forEach { f ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${f.name} · ${f.length() / 1024} KiB", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { exporting = f; export.launch(f.name) }) { Text(stringResource(R.string.sound_export)) }
            }
        }
    }
}

@Composable
private fun Waterfall(live: SoundStore.Live, tick: Int) {
    val scheme = MaterialTheme.colorScheme
    val bmp = remember(tick) { live.spectrogram.bitmap(scheme.background.toArgb(), scheme.primary.toArgb(), scheme.onBackground.toArgb(), SoundFlow.FLOOR_DB).asImageBitmap() }
    Canvas(Modifier.fillMaxWidth().height(CalcMetrics.spectrumHeight).testTag(SoundTags.WATERFALL)) {
        drawImage(bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
    }
}

/** A polyline of (t, y) over its own range; [log] plots y on a log axis (frequency, wavelength). */
@Composable
private fun Graph(points: List<Pair<Double, Double>>, log: Boolean) {
    val colour = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(CalcMetrics.keyHeight * 2)) {
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
        val pts = points.filter { !it.second.isNaN() && (!log || it.second > 0) }
        if (pts.size < 2) return@Canvas
        fun y(v: Double) = if (log) ln(v) else v
        val t0 = pts.first().first; val t1 = pts.last().first
        val lo = pts.minOf { y(it.second) }; val hi = pts.maxOf { y(it.second) }
        val span = if (hi > lo) hi - lo else 1.0
        val p = Path()
        pts.forEachIndexed { i, (t, v) ->
            val x = if (t1 > t0) ((t - t0) / (t1 - t0)).toFloat() * size.width else 0f
            val yy = size.height * (1 - ((y(v) - lo) / span).toFloat())
            if (i == 0) p.moveTo(x, yy) else p.lineTo(x, yy)
        }
        drawPath(p, colour, style = Stroke(CalcMetrics.stroke.toPx()))
    }
}

// ── Generator ───────────────────────────────────────────────────────────────────────────────

@Composable
fun SoundGeneratorMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val cfg = SoundDecl.config
    val d = cfg.generatorDefaults
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(d.kind) }
    var wave by rememberSaveable { mutableStateOf(d.wave) }
    var colour by rememberSaveable { mutableStateOf(Generator.COLOURS.first()) }
    var f1 by rememberSaveable { mutableStateOf(d.f1.toString()) }
    var f2 by rememberSaveable { mutableStateOf(d.f2.toString()) }
    var amp by rememberSaveable { mutableStateOf(d.amplitude.toString()) }
    var ms by rememberSaveable { mutableStateOf(d.ms.toString()) }
    var logSweep by rememberSaveable { mutableStateOf(d.logSweep) }
    var last by remember { mutableStateOf<SoundStore.Generated?>(null) }
    var error by remember { mutableStateOf("") }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        Chips(Generator.KINDS, kind) { kind = it }
        if (kind != Generator.NOISE) Chips(Generator.WAVES, wave) { wave = it } else Chips(Generator.COLOURS, colour) { colour = it }
        if (kind != Generator.NOISE) OutlinedTextField(f1, { f1 = it }, Modifier.fillMaxWidth().testTag(SoundTags.F1), label = { Text(stringResource(R.string.sound_f1)) }, singleLine = true, keyboardOptions = num)
        val second = when (kind) { Generator.SWEEP -> R.string.sound_f_end; Generator.DUAL -> R.string.sound_f_second; Generator.BEATS -> R.string.sound_f_beat; else -> null }
        if (second != null) OutlinedTextField(f2, { f2 = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(second)) }, singleLine = true, keyboardOptions = num)
        if (kind == Generator.SWEEP) Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            Switch(checked = logSweep, onCheckedChange = { logSweep = it }); Text(stringResource(R.string.sound_log_sweep))
        }
        OutlinedTextField(amp, { amp = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sound_amplitude, "%.2f".format(cfg.limits.maxAmplitude))) }, singleLine = true, keyboardOptions = num)
        OutlinedTextField(ms, { ms = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sound_duration_ms)) }, singleLine = true, keyboardOptions = num)
        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            Button(modifier = Modifier.testTag(SoundTags.PLAY), onClick = {
                val spec = Generator.Spec(kind, wave, f1.toDoubleOrNull() ?: d.f1, f2.toDoubleOrNull() ?: d.f2, amp.toDoubleOrNull() ?: d.amplitude, ms.toIntOrNull() ?: d.ms, colour, logSweep)
                scope.launch {
                    error = ""
                    runCatching { last = io { SoundFlow.generate(ctx, spec, play = true) } }.onFailure { error = it.message ?: it.javaClass.simpleName }
                }
            }) { Text(stringResource(R.string.sound_play)) }
            OutlinedButton(onClick = { Player.stop() }) { Text(stringResource(R.string.sound_stop)) }
        }
        Text(stringResource(R.string.sound_volume_guard, Math.round(Player.deviceVolume(ctx) * 100).toString(), "%.2f".format(cfg.limits.maxAmplitude), Math.round(cfg.limits.loudVolume * 100).toString(), "%.2f".format(cfg.limits.loudAmplitude)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        last?.let { g ->
            g.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(SoundTags.GUARD_NOTE)) }
            Text(stringResource(R.string.sound_played, g.spec.kind, g.spec.wave, hz(g.spec.f1), "%.2f".format(g.spec.amplitude), g.spec.ms.toString()), modifier = Modifier.testTag(CalcTags.RESULT))
            Scope(g.pcm, g.sampleRate / 50)
        }
    }
}

@Composable
private fun Chips(options: List<String>, selected: String, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        items(options) { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(o) }, modifier = Modifier.testTag(SoundTags.chip(o))) }
    }
}

/** An oscilloscope trace of the first [count] samples. */
@Composable
internal fun Scope(pcm: ShortArray, count: Int, colour: Color = MaterialTheme.colorScheme.primary) {
    Canvas(Modifier.fillMaxWidth().height(CalcMetrics.keyHeight * 2).testTag(SoundTags.SCOPE)) {
        val n = minOf(count, pcm.size)
        if (n < 2) return@Canvas
        val p = Path()
        for (i in 0 until n) {
            val x = i * size.width / (n - 1)
            val y = size.height / 2 * (1 - pcm[i] / 32768f)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, colour, style = Stroke(CalcMetrics.hairline.toPx()))
    }
}

// ── What is it? ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SoundIdentifyMode(mode: Declarations.Mode) = MicGate {
    val ctx = LocalContext.current
    val cfg = SoundDecl.config
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var measured by remember { mutableStateOf<Pair<Analysis.Result, JSONObject>?>(null) }
    var decision by remember { mutableStateOf<Decision?>(null) }
    // #799 the route: Model (Jev) by default, On-device ML (YAMNet, offline) when picked or when the model cannot answer.
    var route by remember { mutableStateOf(SoundPrefs.route(ctx)) }
    var heard by remember { mutableStateOf<Recognition?>(null) }
    var answered by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        SoundConfig.routes().forEach { (id, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(selected = route == id, role = Role.RadioButton, onClick = {
                    route = id
                    SoundPrefs.set(ctx, id)
                }).testTag(SoundTags.route(id)),
            ) {
                RadioButton(selected = route == id, onClick = null)
                Text(label)
            }
        }
        Text(
            if (route == SoundConfig.ML) stringResource(R.string.sound_identify_on_device_doc) else stringResource(R.string.sound_identify_doc, SoundFlow.model(ctx)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(enabled = !busy, modifier = Modifier.testTag(SoundTags.LISTEN), onClick = {
            scope.launch {
                busy = true; error = ""; decision = null; heard = null; answered = ""
                runCatching {
                    val pcm = io { Mic.record(ctx, cfg.sampleRate, cfg.recordMs) }
                    val knobs = SoundStore.knobs(ctx)
                    val r = io { SoundFlow.analyze(pcm, cfg.sampleRate) }
                    measured = r to SoundFlow.summary(r, knobs)
                    // #799 the user's route with the fallback rule: the model's decision when it answered, else YAMNet's, saying why.
                    val id = io { SoundFlow.identifyRouted(ctx, pcm, cfg.sampleRate, r, knobs, route) }
                    answered = RecognitionRoutes.answeredBy(id.result, SoundConfig.routes())
                    if (id.decision != null) decision = id.decision else heard = id.result
                }.onFailure { error = it.message ?: it.javaClass.simpleName }
                busy = false
            }
        }) { Text(stringResource(if (busy) R.string.sound_listening else R.string.sound_identify)) }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (answered.isNotBlank()) Text(stringResource(R.string.sound_answered_by, answered), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(SoundTags.ANSWERED))
        heard?.let { h -> HeardBars(h) }
        decision?.let { d -> DecisionBars(d) }
        measured?.let { (r, s) ->
            HorizontalDivider(Modifier.padding(vertical = CalcMetrics.gap))
            Text(stringResource(R.string.sound_measured), style = MaterialTheme.typography.titleMedium)
            AnalysisView(r, s)
            val top = heard?.labels?.firstOrNull()?.let { it.label to it.p }
                ?: decision?.options(JevConfig.IDENTIFY_CLASS)?.firstOrNull()?.let { it.label to it.p }
            AskAboutResult(mode.id, detected(r, s), top?.let { "${it.first} ${Math.round(it.second * 100)}%" } ?: detected(r, s))
        }
    }
}

/** #798 what the on-device model heard: every class over the declared floor with its probability, then when. */
@Composable
internal fun HeardBars(h: Recognition) {
    if (!h.ok) {
        Text(stringResource(R.string.sound_identify_unavailable, h.error.orEmpty()), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(SoundTags.VERDICT))
        return
    }
    Column(Modifier.fillMaxWidth().testTag(SoundTags.VERDICT)) {
        if (h.labels.isEmpty()) Text(stringResource(R.string.sound_heard_nothing))
        h.labels.forEach { o ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
                Text(o.label, Modifier.weight(1f))
                LinearProgressIndicator(progress = { o.p.toFloat() }, modifier = Modifier.weight(1f).padding(vertical = CalcMetrics.gap))
                Text("${Math.round(o.p * 100)}%")
            }
        }
        if (h.segments.isNotEmpty()) Text(
            h.segments.joinToString("  ·  ") { "%.1f–%.1f s %s".format(java.util.Locale.ROOT, it.startMs / 1000.0, it.endMs / 1000.0, it.label) },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.sound_on_device_meta, h.model, h.latencyMs.toString()), style = MaterialTheme.typography.bodySmall)
    }
}

/** Every answer of a decision with every option's probability; the model never decides alone. */
@Composable
internal fun DecisionBars(d: Decision) {
    if (!d.ok) {
        Text(stringResource(R.string.sound_identify_unavailable, d.error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(SoundTags.VERDICT))
        return
    }
    Column(Modifier.fillMaxWidth().testTag(SoundTags.VERDICT)) {
        val ids = (d.answers?.keys()?.asSequence()?.toList().orEmpty()).sortedBy { if (it == JevConfig.IDENTIFY_CLASS) 0 else 1 }
        ids.forEach { q ->
            Text(if (q == JevConfig.IDENTIFY_CLASS) stringResource(R.string.sound_class) else q, style = MaterialTheme.typography.titleSmall)
            d.options(q).take(6).forEach { o ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
                    Text(o.label, Modifier.weight(1f))
                    LinearProgressIndicator(progress = { o.p.toFloat() }, modifier = Modifier.weight(1f).padding(vertical = CalcMetrics.gap))
                    Text("${Math.round(o.p * 100)}%")
                }
            }
        }
        Text(stringResource(R.string.sound_decision_meta, d.model, d.latencyMs.toString(), d.cost?.let { "$%.6f".format(it) } ?: "—"), style = MaterialTheme.typography.bodySmall)
    }
}

/** The tags the Robolectric suites drive the Sound screens by. */
object SoundTags {
    const val LISTEN = "sound_listen"
    const val RUN = "sound_run"
    const val PLAY = "sound_play"
    const val F1 = "sound_f1"
    const val CALIBRATION = "sound_calibration"
    const val WATERFALL = "sound_waterfall"
    const val SCOPE = "sound_scope"
    const val VERDICT = "sound_verdict"
    const val ANSWERED = "sound_answered"
    const val GUARD_NOTE = "sound_guard_note"
    fun route(id: String) = "sound_route_$id"
    fun event(i: Int) = "sound_event_$i"
    fun chip(id: String) = "sound_chip_$id"
}
