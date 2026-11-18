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
import com.diegonmarcos.cloudcanvas.CanvasStart
import com.diegonmarcos.cloudcanvas.R
import com.diegonmarcos.cloudcanvas.audio.Drum
import com.diegonmarcos.cloudcanvas.audio.Inst
import com.diegonmarcos.cloudcanvas.audio.LoopClock
import com.diegonmarcos.cloudcanvas.audio.MixerState
import com.diegonmarcos.cloudcanvas.audio.Notes
import com.diegonmarcos.cloudcanvas.audio.TouchVelocity
import com.diegonmarcos.cloudcanvas.audio.Wave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VIEW_INSTRUMENTS = "instruments"
private const val VIEW_MIXER = "mixer"

/** The Play page: transport, then the instruments (piano, drum pads, synth) or the multitrack mixer. The audio runs while the page is on screen. */
@Composable
fun PlayScreen(ctl: PlayController, start: CanvasStart, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s by ctl.engine.session.collectAsState()
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    val ink = colorResource(R.color.app_text)
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { Folders.remember(ctx, "play", uri); scope.launch { withContext(Dispatchers.Default) { ctl.exportWav(uri) } } }
    }
    DisposableEffect(Unit) {
        ctl.start()
        onDispose { ctl.stopAll() }
    }
    LaunchedEffect(start) { if (start.view == VIEW_MIXER || start.view == VIEW_INSTRUMENTS) ctl.view = start.view }
    LaunchedEffect(Unit) {
        while (true) {
            val cur = ctl.engine.session.value
            val len = LoopClock(cur.bpm, cur.bars, ctl.engine.sampleRate).loopFrames
            progress = if (cur.playing && len > 0) (ctl.engine.frame % len).toFloat() / len else 0f
            delay(50)
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Button(onClick = { ctl.togglePlay() }) { Text(if (s.playing) "Stop" else "Play") }
            Button(onClick = { ctl.engine.record() }, enabled = !s.recording) { Text(if (s.recording) "Recording" else "Record") }
            TextButton(onClick = { ctl.engine.update { it.copy(bpm = (it.bpm - 5).coerceAtLeast(40)) } }) { Text("-") }
            Text("${s.bpm} BPM", color = ink, fontSize = 14.sp)
            TextButton(onClick = { ctl.engine.update { it.copy(bpm = (it.bpm + 5).coerceAtMost(220)) } }) { Text("+") }
            FilterChip(selected = s.metronome, onClick = { ctl.engine.update { it.copy(metronome = !it.metronome) } }, label = { Text("Metronome") })
            listOf(1, 2, 4).forEach { b -> FilterChip(selected = s.bars == b, onClick = { ctl.engine.update { it.copy(bars = b) } }, label = { Text("$b bar" + if (b > 1) "s" else "") }) }
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = colorResource(R.color.app_accent))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = ctl.view == VIEW_INSTRUMENTS, onClick = { ctl.view = VIEW_INSTRUMENTS }, label = { Text("Instruments") })
            FilterChip(selected = ctl.view == VIEW_MIXER, onClick = { ctl.view = VIEW_MIXER }, label = { Text("Mixer") })
            AssistChip(onClick = {
                val t = Folders.saved(ctx, "play")
                if (t == null) pickFolder.launch(null) else scope.launch { withContext(Dispatchers.Default) { ctl.exportWav(t) } }
            }, label = { Text("Export WAV") })
            AssistChip(onClick = { pickFolder.launch(null) }, label = { Text("Folder") })
        }
        ctl.message?.let { Text(it, color = colorResource(R.color.app_accent), fontSize = 12.sp) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ctl.view == VIEW_MIXER) MixerView(ctl, s) else InstrumentsView(ctl, s)
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
        Text(ctl.soundLine(s.inst), color = colorResource(R.color.app_text_secondary), fontSize = 11.sp, modifier = Modifier.testTag("canvas_play_sound"))
        if (s.inst == Inst.Drums) DrumPads(ctl) else Keyboard(ctl, s, (ctl.octave + 1) * 12)
    }
}

/** Ten white keys from C of the chosen octave, the black keys over them; every key plays on touch down and releases on touch up, so chords work. */
@Composable
private fun Keyboard(ctl: PlayController, s: com.diegonmarcos.cloudcanvas.audio.Session, base: Int) {
    val whiteOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12, 14, 16)
    BoxWithConstraints(Modifier.fillMaxWidth().height(230.dp)) {
        val w = maxWidth / whiteOffsets.size
        whiteOffsets.forEachIndexed { i, off ->
            KeyBox(ctl, s, base + off, Modifier.offset(x = w * i).width(w).fillMaxSize(), Color(0xFFF2F2F2), Notes.name(base + off))
        }
        whiteOffsets.forEachIndexed { i, off ->
            if (i < whiteOffsets.size - 1 && Notes.isBlack(base + off + 1)) {
                KeyBox(ctl, s, base + off + 1, Modifier.offset(x = w * (i + 1) - w * 0.3f).width(w * 0.6f).height(140.dp), Color(0xFF1A1A1A), "")
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
    }
}
