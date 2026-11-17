package com.diegonmarcos.cloudcanvas.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudcanvas.audio.AudioOut
import com.diegonmarcos.cloudcanvas.audio.Drum
import com.diegonmarcos.cloudcanvas.audio.Inst
import com.diegonmarcos.cloudcanvas.audio.MixEngine
import com.diegonmarcos.cloudcanvas.audio.NoteEvent
import com.diegonmarcos.cloudcanvas.audio.OfflineMix
import com.diegonmarcos.cloudcanvas.audio.Wave
import com.diegonmarcos.cloudcanvas.audio.WavEncoder
import com.diegonmarcos.cloudcanvas.draw.ExportNames

/** The state of the Play page: the engine (mixer + transport), the low-latency output and the page's own view state. Held by the shell. */
class PlayController(private val ctx: Context, demo: Boolean) {
    val audio = AudioOut(ctx)
    val engine = MixEngine(audio.sampleRate)

    var view by mutableStateOf("instruments")
    var octave by mutableIntStateOf(4)
    var message by mutableStateOf<String?>(null)

    init { if (demo) loadDemo() }

    fun start() = audio.start(engine)

    fun stopAll() {
        engine.stop()
        audio.stop()
    }

    fun togglePlay() { val s = engine.session.value; if (s.playing) engine.stop() else engine.play() }

    /** A sample beat on tracks 1 to 3 (drums, bass, chords), so the mixer has something to mix. Replaces those tracks' loops. */
    fun loadDemo() {
        fun d(beat: Double, d: Drum) = NoteEvent(beat, Inst.Drums, d.ordinal, 0.25, 0.9f)
        val drums = buildList {
            for (b in 0 until 8) { if (b % 2 == 0) add(d(b.toDouble(), Drum.Kick)); if (b % 2 == 1) add(d(b.toDouble(), Drum.Snare)) }
            for (i in 0 until 16) add(d(i * 0.5, Drum.HatClosed))
            add(d(3.5, Drum.Kick)); add(d(7.5, Drum.Kick))
        }
        val bassNotes = intArrayOf(36, 36, 43, 41)
        val bass = (0 until 8).map { NoteEvent(it.toDouble(), Inst.Synth, bassNotes[it / 2], 0.9, 0.8f, Wave.Saw) }
        val chords = listOf(listOf(60, 64, 67), listOf(65, 69, 72), listOf(67, 71, 74), listOf(64, 67, 72))
        val keys = chords.flatMapIndexed { i, ch -> ch.map { NoteEvent(i * 2.0, Inst.Piano, it, 1.8, 0.7f) } }
        engine.update { s ->
            var m = s.mixer
            m = m.clear(1).clear(2).clear(3)
            drums.forEach { m = m.addEvent(1, it) }
            bass.forEach { m = m.addEvent(2, it) }
            keys.forEach { m = m.addEvent(3, it) }
            s.copy(mixer = m.volume(2, 0.65f).pan(2, -0.3f).volume(3, 0.55f).pan(3, 0.3f))
        }
    }

    /** Renders two passes of the loop offline and writes the WAV into the chosen folder. Runs on the caller's (background) thread. */
    fun exportWav(tree: Uri) {
        val s = engine.session.value
        val pcm = OfflineMix.render(s, 44100, repeats = 2)
        val name = ExportNames.wav(System.currentTimeMillis() / 1000)
        val out = Folders.write(ctx, tree, name, "audio/wav", WavEncoder.encode(pcm, 44100, 2))
        message = if (out != null) "Saved $name" else "The folder could not be written. Pick it again."
    }
}
