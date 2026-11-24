package com.diegonmarcos.cloudcanvas.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudlib.canvas.core.R
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudcanvas.audio.Arrangement as Arr
import com.diegonmarcos.cloudcanvas.audio.Cymatics
import com.diegonmarcos.cloudlib.cymatics.CymaticsPlate
import com.diegonmarcos.superapp.perms.PermsLink
import com.diegonmarcos.cloudcanvas.audio.Pitch
import com.diegonmarcos.cloudcanvas.audio.Session
import com.diegonmarcos.cloudcanvas.audio.Drum
import com.diegonmarcos.cloudcanvas.audio.Inst
import com.diegonmarcos.cloudcanvas.audio.LoopClock
import com.diegonmarcos.cloudcanvas.audio.MixerState
import com.diegonmarcos.cloudcanvas.audio.Notes
import com.diegonmarcos.cloudcanvas.audio.EngineKind
import com.diegonmarcos.cloudcanvas.audio.TouchVelocity
import com.diegonmarcos.cloudcanvas.audio.Wave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VIEW_INSTRUMENTS = "instruments"
private const val VIEW_MIXER = "mixer"
// #1865 the panels under the arranger
private const val PANEL_KEYS = "keys"
private const val PANEL_PADS = "pads"
private const val PANEL_MIXER = "mixer"
private const val PANEL_INSTRUMENT = "instrument"
private const val PANEL_CYMATICS = "cymatics"

/**
 * The Play page as a studio (#1865): the transport (Play, REC, Looping Play, tempo, metronome, loop length, export), the sound engine line,
 * the multitrack arranger (a ruler with the Looping Play region, instrument and audio tracks with their coloured clips, the playhead), the
 * panel buttons (mixer, instrument settings, keyboard, pads, cymatics) and the panel: an octave-labelled piano keyboard, the drum pads, the
 * mixer (instrument and audio tracks), the instrument settings or the cymatics plate. Every earlier control is kept: the instruments view
 * is the instrument settings panel, the mixer view the mixer panel. [view] is the launch extra `view` (instruments | mixer), handed down by
 * the shell.
 */
@Composable
fun PlayScreen(ctl: PlayController, view: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s by ctl.engine.session.collectAsState()
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    var beat by remember { mutableFloatStateOf(0f) }
    var micLevel by remember { mutableFloatStateOf(0f) }
    val ink = colorResource(R.color.app_text)
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { Folders.remember(ctx, "play", uri); scope.launch { withContext(Dispatchers.Default) { ctl.exportWav(uri) } } }
    }
    // #1865 RECORD_AUDIO is needed the first time REC arms the microphone; the one place that asks is the Permissions page (#1274, libs:perms)
    fun pressRec() {
        val needsMic = ctl.armedAudio != 0 && !ctl.micRecording
        if (needsMic && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { ctl.message = "Microphone permission is needed to record an audio track. Grant it in Configs > Perms."; PermsLink.open(ctx, "canvas-play", listOf("microphone")) }
        else ctl.rec()
    }
    DisposableEffect(Unit) {
        ctl.start()
        onDispose { ctl.stopTake(); ctl.stopAll() }
    }
    LaunchedEffect(view) {
        if (view == VIEW_MIXER || view == VIEW_INSTRUMENTS) ctl.view = view
        if (view == VIEW_MIXER) ctl.panel = PANEL_MIXER else if (view == VIEW_INSTRUMENTS) ctl.panel = PANEL_INSTRUMENT
    }
    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            val cur = ctl.engine.session.value
            val len = LoopClock(cur.bpm, cur.bars, ctl.engine.sampleRate).loopFrames
            progress = if (cur.playing && len > 0) (ctl.engine.frame % len).toFloat() / len else 0f
            beat = if (cur.playing) ctl.engine.loopBeat().toFloat() else cur.loopRegion().startBeat.toFloat()
            micLevel = ctl.micLevel
            if (++ticks % 10 == 0) ctl.outTick++   // #1809 the engine line's latency and under-runs, twice a second
            delay(50)
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // ── the transport ───────────────────────────────────────────────────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).testTag("canvas_play_transport")) {
            Button(onClick = { ctl.togglePlay() }) {
                Icon(if (s.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
                Text(if (s.playing) "Stop" else "Play")
            }
            RecButton(s.recording || ctl.micRecording, micLevel, Modifier.testTag("canvas_play_rec")) { pressRec() }
            Button(onClick = { ctl.engine.record() }, enabled = !s.recording) { Text(if (s.recording) "Recording" else "Record") }
            FilterChip(selected = s.overdub, onClick = { ctl.loopingPlay(!s.overdub) }, leadingIcon = { Icon(Icons.Filled.Loop, contentDescription = null) },
                label = { Text("Looping Play") }, modifier = Modifier.testTag("canvas_play_looping"))
            TextButton(onClick = { ctl.engine.update { it.copy(bpm = (it.bpm - 5).coerceAtLeast(40)) } }) { Text("-") }
            Text("${s.bpm} BPM", color = ink, fontSize = 14.sp)
            TextButton(onClick = { ctl.engine.update { it.copy(bpm = (it.bpm + 5).coerceAtMost(220)) } }) { Text("+") }
            FilterChip(selected = s.metronome, onClick = { ctl.engine.update { it.copy(metronome = !it.metronome) } }, label = { Text("Metronome") })
            listOf(1, 2, 4).forEach { b -> FilterChip(selected = s.bars == b, onClick = { ctl.engine.update { it.copy(bars = b, region = null) } }, label = { Text("$b bar" + if (b > 1) "s" else "") }) }
            AssistChip(onClick = {
                val t = Folders.saved(ctx, "play")
                if (t == null) pickFolder.launch(null) else scope.launch { withContext(Dispatchers.Default) { ctl.exportWav(t) } }
            }, label = { Text("Export WAV") })
            AssistChip(onClick = { pickFolder.launch(null) }, label = { Text("Folder") })
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = colorResource(R.color.app_accent))
        // #1809 the sound engine: SoundFont (FluidSynth + GeneralUser GS) or Classic (#1787), and the engine, bank and output in use
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text("Sound", color = ink, fontSize = 13.sp)
            EngineKind.entries.forEach { k -> FilterChip(selected = ctl.engineKind == k, onClick = { ctl.selectEngine(k) }, label = { Text(k.label) }, modifier = Modifier.testTag("canvas_play_engine_" + k.id)) }
        }
        Text(ctl.engineLine(), color = colorResource(R.color.app_text_secondary), fontSize = 11.sp, modifier = Modifier.testTag("canvas_play_engine"))
        ctl.message?.let { Text(it, color = colorResource(R.color.app_accent), fontSize = 12.sp) }
        // ── the arranger ────────────────────────────────────────────────────────────────────────────────────────────────────────────
        Arranger(ctl, s, beat, Modifier.weight(1f).fillMaxWidth())
        // ── the panel buttons ───────────────────────────────────────────────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).testTag("canvas_play_panels")) {
            PanelButton(Icons.Filled.Tune, label = "Mixer", on = ctl.panel == PANEL_MIXER) { ctl.panel = PANEL_MIXER; ctl.view = VIEW_MIXER }
            PanelButton(Icons.Filled.SettingsInputComponent, label = "Instruments", on = ctl.panel == PANEL_INSTRUMENT) { ctl.panel = PANEL_INSTRUMENT; ctl.view = VIEW_INSTRUMENTS }
            PanelButton(Icons.Filled.Piano, label = "Keyboard", on = ctl.panel == PANEL_KEYS) { ctl.panel = PANEL_KEYS; ctl.view = VIEW_INSTRUMENTS }
            PanelButton(Icons.Filled.GridView, label = "Pads", on = ctl.panel == PANEL_PADS) { ctl.panel = PANEL_PADS; ctl.view = VIEW_INSTRUMENTS }
            PanelButton(Icons.Filled.BlurCircular, label = "Cymatics", on = ctl.panel == PANEL_CYMATICS) { ctl.panel = PANEL_CYMATICS }
            Text("Plays on " + (if (ctl.armedAudio != 0) "Audio ${ctl.armedAudio - Arr.AUDIO_BASE} (microphone)" else "Track ${s.selected}") + if (s.recording || ctl.micRecording) " (recording)" else "",
                color = colorResource(R.color.app_text_secondary), fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth().height(250.dp)) {
            when (ctl.panel) {
                PANEL_MIXER -> MixerView(ctl, s)
                PANEL_INSTRUMENT -> InstrumentsView(ctl, s)
                PANEL_PADS -> DrumPads(ctl)
                PANEL_CYMATICS -> CymaticsView(ctl, s)
                else -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { ctl.octave = (ctl.octave - 1).coerceAtLeast(1) }) { Text("Oct -") }
                        Text("C${ctl.octave}", color = ink, fontSize = 13.sp)
                        TextButton(onClick = { ctl.octave = (ctl.octave + 1).coerceAtMost(7) }) { Text("Oct +") }
                        Text(ctl.soundLine(if (s.inst == Inst.Drums) Inst.Piano else s.inst, s.wave), color = colorResource(R.color.app_text_secondary), fontSize = 11.sp)
                    }
                    Keyboard(ctl, s.copy(inst = if (s.inst == Inst.Drums) Inst.Piano else s.inst), (ctl.octave + 1) * 12, 200.dp)
                }
            }
        }
    }
}

/** The round REC button with its label; it glows while a take records, its ring follows the microphone level. */
@Composable
private fun RecButton(on: Boolean, level: Float, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (on) Color(0xFFE53935) else Color(0xFFD9D9D9))
                .border((1 + 4 * level).dp, if (on) Color(0xFFFFCDD2) else Color(0xFF777777), CircleShape),
        )
        Text("REC", fontSize = 10.sp, color = colorResource(R.color.app_text))
    }
}

@Composable
private fun PanelButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val accent = colorResource(R.color.app_accent)
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).border(2.dp, if (on) accent else colorResource(R.color.app_outline), CircleShape)
                .background(if (on) accent.copy(alpha = 0.2f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, tint = if (on) accent else colorResource(R.color.app_text)) }
        Text(label, fontSize = 9.sp, color = if (on) accent else colorResource(R.color.app_text))
    }
}

private const val HEADER_DP = 64

/**
 * The multitrack arranger: a ruler (bar numbers, beat ticks, the Looping Play region; tap a bar to loop it, drag across bars to loop them,
 * long-press for the whole loop), one row per instrument track and per audio track (tap the header: an instrument track becomes the
 * record target, an audio track is armed for the microphone), each row's clips in the track's colour with their names and notes, and the
 * playhead across every row.
 */
@Composable
private fun Arranger(ctl: PlayController, s: Session, beat: Float, modifier: Modifier) {
    val ink = colorResource(R.color.app_text)
    val accent = colorResource(R.color.app_accent)
    val loopBeats = s.bars * 4
    val region = s.loopRegion()
    val instRows = Arr.rows(s.mixer, emptyList())
    val audioRows = s.audio.map { lane ->
        val c = Arr.colorOf(lane.id - Arr.AUDIO_BASE + 4)
        Arr.Row(lane.id, "Audio ${lane.id - Arr.AUDIO_BASE}", Arr.Kind.Audio, c, lane.takes.map { Arr.Clip(lane.id, it.startBeat, it.lenBeats, it.name, c) })
    }
    Column(modifier.background(Color(0xFF1C2329), RoundedCornerShape(8.dp)).testTag("canvas_play_arranger")) {
        Row(Modifier.fillMaxWidth().height(28.dp)) {
            Box(Modifier.width(HEADER_DP.dp).fillMaxSize().clickable { ctl.addAudioTrack() }, contentAlignment = Alignment.Center) {
                Text("+ Audio", color = ink, fontSize = 11.sp)
            }
            var dragFrom by remember { mutableFloatStateOf(-1f) }
            Canvas(
                Modifier.weight(1f).fillMaxSize().testTag("canvas_play_ruler")
                    .pointerInput(loopBeats) {
                        detectTapGestures(
                            onTap = { o -> val b = Math.floor((o.x / size.width * loopBeats).toDouble() / 4) * 4; ctl.setRegion(b, b + 4) },
                            onLongPress = { ctl.setRegion(null, null) },
                        )
                    }
                    .pointerInput(loopBeats) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> dragFrom = o.x },
                            onDragEnd = { dragFrom = -1f },
                        ) { ch, _ ->
                            if (dragFrom >= 0f) {
                                val a = (minOf(dragFrom, ch.position.x) / size.width * loopBeats).toDouble()
                                val b = (maxOf(dragFrom, ch.position.x) / size.width * loopBeats).toDouble()
                                ctl.setRegion(Math.floor(a), Math.ceil(b))
                            }
                        }
                    },
            ) {
                val w = size.width / loopBeats
                if (s.region != null) drawRect(accent.copy(alpha = 0.25f), Offset((region.startBeat * w).toFloat(), 0f), Size((region.lengthBeats * w).toFloat(), size.height))
                for ((b, label) in Arr.ruler(loopBeats)) {
                    val x = b * w
                    drawLine(ink.copy(alpha = if (label != null) 0.9f else 0.4f), Offset(x, size.height * (if (label != null) 0.2f else 0.6f)), Offset(x, size.height), 1.5f)
                }
                drawLine(Color.White, Offset(beat * w, 0f), Offset(beat * w, size.height), 3f)
            }
        }
        // the bar numbers under the ruler
        Row(Modifier.fillMaxWidth().padding(start = HEADER_DP.dp)) {
            (1..s.bars).forEach { b -> Text("$b", color = ink, fontSize = 10.sp, modifier = Modifier.weight(1f)) }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            (instRows + audioRows).forEach { row ->
                val armed = if (row.kind == Arr.Kind.Audio) ctl.armedAudio == row.trackId else ctl.armedAudio == 0 && s.selected == row.trackId
                val events = if (row.kind == Arr.Kind.Instrument) s.mixer.tracks.firstOrNull { it.id == row.trackId }?.events.orEmpty() else emptyList()
                Row(Modifier.fillMaxWidth().height(44.dp).padding(vertical = 1.dp)) {
                    Box(
                        Modifier.width(HEADER_DP.dp).fillMaxSize().background(if (armed) Color(row.color) else Color(0xFF2A333B)).clickable {
                            if (row.kind == Arr.Kind.Audio) ctl.armedAudio = row.trackId
                            else { ctl.armedAudio = 0; ctl.engine.update { it.copy(selected = row.trackId) } }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (row.kind == Arr.Kind.Audio) Icons.Filled.GraphicEq else Icons.Filled.Piano, contentDescription = row.name,
                                tint = if (armed) Color(0xFF111111) else Color(row.color))
                            Text(row.name, fontSize = 9.sp, color = if (armed) Color(0xFF111111) else ink)
                        }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxSize().background(Color(0xFF242D34))) {
                        val bw = maxWidth / loopBeats
                        row.clips.forEach { c ->
                            Box(
                                Modifier.offset(x = bw * c.startBeat.toFloat()).width(bw * c.lenBeats.toFloat().coerceAtMost(loopBeats - c.startBeat.toFloat()))
                                    .fillMaxSize().background(Color(c.color), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
                            ) { Text(c.name, fontSize = 10.sp, color = Color(0xFF1A1A1A), maxLines = 1) }
                        }
                        Canvas(Modifier.fillMaxSize()) {
                            val w = size.width / loopBeats
                            if (s.region != null) {
                                drawRect(Color.Black.copy(alpha = 0.35f), Offset.Zero, Size((region.startBeat * w).toFloat(), size.height))
                                drawRect(Color.Black.copy(alpha = 0.35f), Offset((region.endBeat * w).toFloat(), 0f), Size(size.width - (region.endBeat * w).toFloat(), size.height))
                            }
                            // the notes inside the clips: a dash per note, high notes higher
                            for (e in events) {
                                val y = size.height * (0.9f - ((e.note % 48) / 48f) * 0.6f)
                                drawLine(Color(0xAA111111), Offset((e.beat * w).toFloat(), y), Offset(((e.beat + e.lenBeats) * w).toFloat(), y), 3f)
                            }
                            drawLine(Color.White, Offset(beat * w, 0f), Offset(beat * w, size.height), 3f)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Cymatics: the Chladni figure of the plate. A single note played by hand drives it alone; while the transport runs (Looping Play) it
 * follows the dominant frequencies of the music being generated, several times a second. The sand (bright) lies on the nodal lines.
 */
@Composable
private fun CymaticsView(ctl: PlayController, s: Session) {
    val ink = colorResource(R.color.app_text)
    val grid = 96
    var pattern by remember { mutableStateOf(FloatArray(0)) }
    var drive by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    LaunchedEffect(Unit) {
        var t = 0.0
        while (true) {
            val d = ctl.cymaticsDrive()
            val p = withContext(Dispatchers.Default) { Cymatics.blend(d, grid, t) }
            drive = d; pattern = p
            t = (t + 0.05) % 1.0
            delay(70)
        }
    }
    Row(Modifier.fillMaxSize().testTag("canvas_play_cymatics")) {
        // #1892 the plate is the shared renderer (libs:cymatics), the same one Cloud Calc's sound meter draws
        CymaticsPlate(pattern, grid, Modifier.fillMaxHeight().aspectRatio(1f))
        Column(Modifier.padding(start = 8.dp).verticalScroll(rememberScrollState())) {
            Text("Cymatics", color = ink, fontSize = 14.sp)
            Text(if (s.playing) "Looping Play: the plate follows the music" else "Play a note: the plate shows its standing wave", color = colorResource(R.color.app_text_secondary), fontSize = 11.sp)
            drive.forEach { (hz, lv) -> Text("%.1f Hz  %s  %d%%".format(hz, Pitch.name(Pitch.note(hz)), (lv * 100).toInt()), color = ink, fontSize = 12.sp) }
            if (drive.isEmpty()) Text("No sound yet", color = colorResource(R.color.app_text_secondary), fontSize = 11.sp)
        }
    }
}

@Composable
private fun InstrumentsView(ctl: PlayController, s: com.diegonmarcos.cloudcanvas.audio.Session) {
    val ink = colorResource(R.color.app_text)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Inst.entries.forEach { i -> FilterChip(selected = s.inst == i, onClick = { ctl.engine.update { it.copy(inst = i) } }, label = { Text(i.label) }) }
            if (s.inst == Inst.Synth) Wave.entries.forEach { w -> FilterChip(selected = s.wave == w, onClick = { ctl.engine.update { it.copy(wave = w) } }, label = { Text(w.label) }) }
            if (s.inst != Inst.Drums) {
                TextButton(onClick = { ctl.octave = (ctl.octave - 1).coerceAtLeast(1) }) { Text("Oct -") }
                Text("C${ctl.octave}", color = ink, fontSize = 13.sp)
                TextButton(onClick = { ctl.octave = (ctl.octave + 1).coerceAtMost(7) }) { Text("Oct +") }
            }
        }
        Text("Plays on Track ${s.selected}" + if (s.recording) " (recording)" else "", color = colorResource(R.color.app_text_secondary), fontSize = 12.sp)
        // #1787 which sound plays: the sampled instrument once decoded, the synthesized one meanwhile
        Text(ctl.soundLine(s.inst, s.wave), color = colorResource(R.color.app_text_secondary), fontSize = 11.sp, modifier = Modifier.testTag("canvas_play_sound"))
        if (s.inst == Inst.Drums) DrumPads(ctl) else Keyboard(ctl, s, (ctl.octave + 1) * 12)
    }
}

/** Ten white keys from C of the chosen octave, the black keys over them; every key plays on touch down and releases on touch up, so chords work. */
@Composable
private fun Keyboard(ctl: PlayController, s: com.diegonmarcos.cloudcanvas.audio.Session, base: Int, keysHeight: androidx.compose.ui.unit.Dp = 230.dp) {
    // #1865 two octaves and the next C, every C labelled with its octave (C3, C4, ...)
    val whiteOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24)
    BoxWithConstraints(Modifier.fillMaxWidth().height(keysHeight).testTag("canvas_play_keyboard")) {
        val w = maxWidth / whiteOffsets.size
        whiteOffsets.forEachIndexed { i, off ->
            KeyBox(ctl, s, base + off, Modifier.offset(x = w * i).width(w).fillMaxSize(), Color(0xFFF2F2F2), Notes.name(base + off))
        }
        whiteOffsets.forEachIndexed { i, off ->
            if (i < whiteOffsets.size - 1 && Notes.isBlack(base + off + 1)) {
                KeyBox(ctl, s, base + off + 1, Modifier.offset(x = w * (i + 1) - w * 0.3f).width(w * 0.6f).height(keysHeight * 0.6f), Color(0xFF1A1A1A), "")
            }
        }
    }
}

@Composable
private fun KeyBox(ctl: PlayController, s: com.diegonmarcos.cloudcanvas.audio.Session, note: Int, modifier: Modifier, color: Color, label: String) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier
            .background(if (down) colorResource(R.color.app_accent) else color, RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
            .border(1.dp, Color(0xFF555555), RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
            .pointerInput(note, s.inst, s.wave) {
                awaitEachGesture {
                    val d = awaitFirstDown()
                    d.consume()
                    down = true
                    // #1787 velocity from the strike point: near the front edge of the key is a firm hit
                    ctl.engine.noteOn(s.inst, s.wave, note, TouchVelocity.of(if (size.height > 0) d.position.y / size.height else 0.7f))
                    waitForUpOrCancellation()
                    down = false
                    ctl.engine.noteOff(s.inst, note)
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) { if (label.isNotEmpty() && label.startsWith("C")) Text(label, color = Color(0xFF333333), fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp)) }
}

@Composable
private fun DrumPads(ctl: PlayController) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ten pads (#1787 added Tom mid and Ride), two rows of five; a hit low on a pad is louder
        Drum.entries.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { drum ->
                    var down by remember { mutableStateOf(false) }
                    Box(
                        Modifier.weight(1f).height(110.dp)
                            .background(if (down) colorResource(R.color.app_accent) else colorResource(R.color.app_surface_raised), RoundedCornerShape(10.dp))
                            .border(1.dp, colorResource(R.color.app_outline), RoundedCornerShape(10.dp))
                            .pointerInput(drum) {
                                awaitEachGesture {
                                    val d = awaitFirstDown()
                                    d.consume()
                                    down = true
                                    ctl.engine.noteOn(Inst.Drums, Wave.Sine, drum.ordinal, TouchVelocity.of(if (size.height > 0) d.position.y / size.height else 0.7f))
                                    waitForUpOrCancellation()
                                    down = false
                                    ctl.engine.noteOff(Inst.Drums, drum.ordinal)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(drum.label, color = colorResource(R.color.app_text), fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun MixerView(ctl: PlayController, s: com.diegonmarcos.cloudcanvas.audio.Session) {
    val ink = colorResource(R.color.app_text)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text("${s.mixer.tracks.size} tracks", color = ink, fontSize = 13.sp)
            TextButton(onClick = { ctl.engine.update { it.copy(mixer = it.mixer.withCount(it.mixer.tracks.size - 1)) } }, enabled = s.mixer.tracks.size > MixerState.MIN_TRACKS) { Text("Fewer") }
            TextButton(onClick = { ctl.engine.update { it.copy(mixer = it.mixer.withCount(it.mixer.tracks.size + 1)) } }, enabled = s.mixer.tracks.size < MixerState.MAX_TRACKS) { Text("More") }
            AssistChip(onClick = { ctl.loadDemo() }, label = { Text("Demo beat") })
            // #1865 audio tracks: microphone takes
            AssistChip(onClick = { ctl.addAudioTrack() }, label = { Text("Add audio track") })
        }
        s.mixer.tracks.forEach { t ->
            val sel = s.selected == t.id
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (sel) colorResource(R.color.app_surface_raised) else colorResource(R.color.app_surface)),
                border = if (sel) androidx.compose.foundation.BorderStroke(1.5.dp, colorResource(R.color.app_accent)) else null,
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Text(t.name + if (t.hasLoop) "  (${t.events.size} notes)" else "  (empty)", color = ink, fontSize = 13.sp)
                        FilterChip(selected = sel, onClick = { ctl.engine.update { it.copy(selected = t.id) } }, label = { Text(if (sel) "Rec target" else "Select") })
                        FilterChip(selected = t.muted, onClick = { ctl.engine.update { it.copy(mixer = it.mixer.mute(t.id, !t.muted)) } }, label = { Text("Mute") })
                        FilterChip(selected = t.solo, onClick = { ctl.engine.update { it.copy(mixer = it.mixer.solo(t.id, !t.solo)) } }, label = { Text("Solo") })
                        TextButton(onClick = { ctl.engine.update { it.copy(mixer = it.mixer.clear(t.id)) } }, enabled = t.hasLoop) { Text("Clear") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vol", color = ink, fontSize = 11.sp, modifier = Modifier.width(26.dp))
                        Slider(value = t.volume, onValueChange = { v -> ctl.engine.update { it.copy(mixer = it.mixer.volume(t.id, v)) } }, modifier = Modifier.weight(1f))
                        Text("Pan", color = ink, fontSize = 11.sp, modifier = Modifier.width(26.dp).padding(start = 4.dp))
                        Slider(value = t.pan, onValueChange = { v -> ctl.engine.update { it.copy(mixer = it.mixer.pan(t.id, v)) } }, valueRange = -1f..1f, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        s.audio.forEach { lane ->
            val armed = ctl.armedAudio == lane.id
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (armed) colorResource(R.color.app_surface_raised) else colorResource(R.color.app_surface))) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Text("Audio ${lane.id - Arr.AUDIO_BASE}  (${lane.takes.size} takes)", color = ink, fontSize = 13.sp)
                        FilterChip(selected = armed, onClick = { ctl.armedAudio = if (armed) 0 else lane.id }, label = { Text(if (armed) "Armed" else "Arm") })
                        FilterChip(selected = lane.muted, onClick = { ctl.muteAudio(lane.id, !lane.muted) }, label = { Text("Mute") })
                        TextButton(onClick = { ctl.clearAudio(lane.id) }, enabled = lane.takes.isNotEmpty()) { Text("Clear") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vol", color = ink, fontSize = 11.sp, modifier = Modifier.width(26.dp))
                        Slider(value = lane.volume, onValueChange = { v -> ctl.audioVolume(lane.id, v) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
