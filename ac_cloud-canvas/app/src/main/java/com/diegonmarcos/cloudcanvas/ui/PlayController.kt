package com.diegonmarcos.cloudcanvas.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudcanvas.audio.AudioOut
import com.diegonmarcos.cloudcanvas.audio.Drum
import com.diegonmarcos.cloudcanvas.audio.EngineKind
import com.diegonmarcos.cloudcanvas.audio.FluidSynthBackend
import com.diegonmarcos.cloudcanvas.audio.GmMap
import com.diegonmarcos.cloudcanvas.audio.SfDecl
import com.diegonmarcos.cloudcanvas.audio.SfStatus
import com.diegonmarcos.cloudcanvas.audio.Inst
import com.diegonmarcos.cloudcanvas.audio.MixEngine
import com.diegonmarcos.cloudcanvas.audio.NoteEvent
import com.diegonmarcos.cloudcanvas.audio.OfflineMix
import com.diegonmarcos.cloudcanvas.audio.SampleBank
import com.diegonmarcos.cloudcanvas.audio.SampleLoader
import com.diegonmarcos.cloudcanvas.audio.Wave
import com.diegonmarcos.cloudcanvas.audio.WavEncoder
import com.diegonmarcos.cloudcanvas.draw.ExportNames

/**
 * The state of the Play page: the engine (mixer + transport), the low-latency output and the page's own view state. Held by the shell.
 * #1809 which sound plays is [engineKind]: SoundFont (FluidSynth + GeneralUser GS, the default) or Classic (the #1787 samples and
 * synthesized voices), chosen on the page and remembered. Classic also plays while the SoundFont bank loads and if it cannot start.
 */
class PlayController(private val ctx: Context, demo: Boolean) {
    val audio = AudioOut(ctx)
    val engine = MixEngine(audio.sampleRate)
    private val prefs = ctx.getSharedPreferences("canvas_play", Context.MODE_PRIVATE)
    private val decl: SfDecl? = runCatching { SfDecl.load(ctx.assets) }.getOrNull()

    /** The engine the user picked (build.json::audio.default_engine until they pick). */
    var engineKind by mutableStateOf(EngineKind.of(prefs.getString("engine", null) ?: com.diegonmarcos.cloudcanvas.BuildConfig.AUDIO_DEFAULT_ENGINE))
        private set
    /** The SoundFont engine's state: null while loading, the resolved presets, or why it cannot play. */
    var sfStatus by mutableStateOf<SfStatus?>(null)
        private set
    private var sf: FluidSynthBackend? = null
    @Volatile private var sfLoading = false
    /** Bumped twice a second while the page is open, so the engine line shows the output's latency as it is measured. */
    var outTick by mutableIntStateOf(0)

    var view by mutableStateOf("instruments")
    var octave by mutableIntStateOf(4)
    var message by mutableStateOf<String?>(null)

    init { if (demo) loadDemo() }

    /** Bumped when the sample bank finishes decoding, so the sound line redraws. */
    var samplesReady by mutableIntStateOf(0)

    fun start() {
        applyEngine()
        audio.start(engine)
    }

    /** #1809 picks the engine, remembers it and switches the live mixer to it (notes already sounding are released). */
    fun selectEngine(k: EngineKind) {
        engineKind = k
        prefs.edit().putString("engine", k.id).apply()
        applyEngine()
    }

    private fun applyEngine() {
        if (engineKind == EngineKind.SoundFont && decl != null) {
            val ready = sf
            if (ready != null) { engine.sfDecl = decl; engine.sf = ready; return }
            engine.sf = null   // the synthesized voices play during the second the bank takes to load
            if (sfLoading) return
            sfLoading = true
            Thread({
                val (b, st) = FluidSynthBackend.open(ctx.assets, decl, audio.sampleRate)
                sf = b
                sfStatus = st
                sfLoading = false
                if (b != null && engineKind == EngineKind.SoundFont) { engine.sfDecl = decl; engine.sf = b }
                else if (b == null) { message = "SoundFont engine unavailable (${st.error}): Classic plays"; loadClassic() }
            }, "canvas-soundfont").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
        } else {
            engine.sf = null
            loadClassic()
        }
    }

    /** #1787 the sampled piano and kit decode once per process in the background; the synthesized voices play meanwhile. */
    private fun loadClassic() {
        SampleLoader.loadAsync(ctx) { ok, failed -> samplesReady = ok; if (failed > 0) message = "$failed sample(s) could not be decoded: those notes stay synthesized" }
    }

    /** True when the SoundFont engine is the one playing right now. */
    val soundFontPlaying: Boolean get() = engine.sf != null

    /**
     * The engine line of the Play page: which engine and bank play, and the output (path, mode, rate, latency, under-runs).
     * e.g. "Engine: FluidSynth 2.5.1, bank GeneralUser GS 2.0.3 (S. Christian Collins) | Output: Oboe AAudio, LowLatency, Exclusive, ...".
     */
    fun engineLine(): String {
        @Suppress("UNUSED_VARIABLE") val tick = outTick
        val d = decl
        val st = sfStatus
        val eng = when {
            engineKind == EngineKind.Classic -> "Engine: Classic (Salamander Grand Piano, MuldjordKit, synthesized voices)"
            d == null -> "Engine: Classic (no SoundFont declaration)"
            soundFontPlaying -> "Engine: ${d.engine} ${st?.engineVersion ?: d.engineVersion}, bank ${d.bankName} ${d.bankVersion} (${d.bankAuthor})"
            st?.error != null -> "Engine: Classic (SoundFont unavailable: ${st.error})"
            else -> "Engine: loading ${d.bankName} ${d.bankVersion}; Classic meanwhile"
        }
        return eng + " | Output: " + (audio.status?.line() ?: "starting")
    }

    /** What the instrument plays right now, for the line under the instrument chips. */
    fun soundLine(inst: Inst, wave: Wave = Wave.Sine): String {
        // samplesReady redraws it when Classic finishes decoding; sfStatus and outTick when the SoundFont engine is attached
        @Suppress("UNUSED_VARIABLE") val tick = samplesReady + outTick + (sfStatus?.presetNames?.size ?: 0) + engineKind.ordinal
        val d = decl
        if (soundFontPlaying && d != null) {
            val p = GmMap.preset(d, inst, wave)
            val name = sfStatus?.presetNames?.get(GmMap.kind(inst, wave)) ?: p.name
            return "SoundFont preset: $name (bank ${p.bank}, program ${p.program})" + if (inst == Inst.Drums) ", General MIDI drum map" else ""
        }
        val bank = SampleBank.current
        return when (inst) {
            Inst.Piano -> if (bank?.piano?.isNotEmpty() == true) "Sampled grand piano: Salamander Grand Piano V3 (Alexander Holm, CC-BY 3.0)" else "Piano samples loading: synthesized piano meanwhile"
            Inst.Drums -> if (bank?.drums?.isNotEmpty() == true) "Sampled acoustic kit: MuldjordKit (Lars Muldjord, CC-BY 4.0); Clap is synthesized" else "Drum samples loading: synthesized kit meanwhile"
            Inst.Synth -> "Synthesizer: oscillators computed on the phone"
        }
    }

    fun stopAll() {
        engine.stop()
        audio.stop()
    }

    /** Frees the SoundFont engine (the shell's Play page is gone for good). */
    fun release() {
        engine.sf = null
        sf?.close(); sf = null
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
        // #1809 the export plays through the engine the page plays: a SoundFont synth of its own at 44.1 kHz, or the Classic voices
        val d = decl
        val sfx = if (soundFontPlaying && d != null) FluidSynthBackend.open(ctx.assets, d, 44100).first else null
        val pcm = try { OfflineMix.render(s, 44100, repeats = 2, sf = sfx, decl = d) } finally { sfx?.close() }
        val name = ExportNames.wav(System.currentTimeMillis() / 1000)
        val out = Folders.write(ctx, tree, name, "audio/wav", WavEncoder.encode(pcm, 44100, 2))
        message = if (out != null) "Saved $name" else "The folder could not be written. Pick it again."
    }
}
