package com.diegonmarcos.cloudcanvas.audio

import android.content.res.AssetManager
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * #1809 the Play page's SoundFont engine: FluidSynth (LGPL-2.1, its official Android build, dynamically linked) playing the bundled
 * General MIDI bank GeneralUser GS through libcanvasaudio.so (app/src/main/cpp). Everything here but [SfNative] and [FluidSynthBackend]
 * is pure Kotlin, so the JVM suite checks the declaration, the GM map and the channel layout against build.json and the bank itself.
 */
enum class EngineKind(val id: String, val label: String) {
    SoundFont("soundfont", "SoundFont"), Classic("classic", "Classic");

    companion object {
        fun of(id: String?): EngineKind = entries.firstOrNull { it.id == id } ?: SoundFont
    }
}

data class Preset(val bank: Int, val program: Int, val name: String)

/** assets/soundfont/soundfont.json (build.json::audio.soundfont.config_asset), decoded: the engine's configuration for the bundled bank. */
class SfDecl(
    val engine: String,
    val engineVersion: String,
    val engineLicence: String,
    val bankAsset: String,
    val bankName: String,
    val bankVersion: String,
    val bankAuthor: String,
    val bankLicence: String,
    val groups: Int,
    val midiChannels: Int,
    val kinds: List<String>,
    val presets: Map<String, Preset>,
    val drumNotes: Map<String, Int>,
    val settings: List<Pair<String, String>>,
    val exclusive: Boolean,
    val ringBursts: Int,
) {
    companion object {
        fun parse(json: String): SfDecl {
            val o = JSONObject(json)
            val bank = o.getJSONObject("bank")
            val pr = o.getJSONObject("presets")
            val dn = o.getJSONObject("drum_notes")
            val st = o.getJSONObject("settings")
            val out = o.optJSONObject("output")
            val kinds = o.getJSONArray("kinds").let { a -> (0 until a.length()).map { a.getString(it) } }
            return SfDecl(
                o.getString("engine"), o.getString("engine_version"), o.getString("engine_licence"),
                bank.getString("asset"), bank.getString("name"), bank.getString("version"), bank.getString("author"), bank.getString("licence"),
                o.getInt("audio_groups"), o.getInt("midi_channels"), kinds,
                pr.keys().asSequence().associateWith { k -> pr.getJSONObject(k).let { Preset(it.getInt("bank"), it.getInt("program"), it.getString("name")) } },
                dn.keys().asSequence().associateWith { dn.getInt(it) },
                st.keys().asSequence().map { it to st.getString(it) }.toList(),
                (out?.optString("sharing_mode", "Exclusive") ?: "Exclusive") == "Exclusive",
                out?.optInt("ring_bursts", 3) ?: 3,
            )
        }

        /** Where the configuration sits in the APK's assets. */
        const val ASSET = "soundfont/soundfont.json"

        @Volatile private var cached: SfDecl? = null

        /** The app's configuration, read once from the APK. */
        fun load(assets: AssetManager): SfDecl = cached ?: parse(assets.open(ASSET).use { String(it.readBytes()) }).also { cached = it }
    }
}

/** Instruments to General MIDI: the preset of each kind, the GM drum key of each pad, and the MIDI channel of a (track, kind). */
object GmMap {
    fun kind(inst: Inst, wave: Wave): String = when (inst) {
        Inst.Piano -> "piano"
        Inst.Drums -> "drums"
        Inst.Synth -> "synth_" + wave.name.lowercase()
    }

    /** Every kind the Play page can play: the piano, the kit and the synth in each of its waves. */
    fun allKinds(): List<Pair<Inst, Wave>> = listOf(Inst.Piano to Wave.Sine, Inst.Drums to Wave.Sine) + Wave.entries.map { Inst.Synth to it }

    /** channel = kind index * groups + (track - 1): the channel's output group is its track (FluidSynth renders channel c into group c % groups). */
    fun channel(d: SfDecl, track: Int, inst: Inst, wave: Wave): Int {
        val k = d.kinds.indexOf(kind(inst, wave))
        require(k >= 0) { "no SoundFont kind for $inst/$wave" }
        return k * d.groups + (track - 1).coerceIn(0, d.groups - 1)
    }

    /** The drum pad a stored drum note names (a loop stores the pad's ordinal). */
    fun drum(note: Int): Drum = Drum.entries[Math.floorMod(note, Drum.entries.size)]

    /** The MIDI key a note plays: the GM percussion key of a drum pad, the note itself otherwise. */
    fun key(d: SfDecl, inst: Inst, note: Int): Int =
        if (inst == Inst.Drums) d.drumNotes.getValue(drum(note).name) else note.coerceIn(0, 127)

    /** A touch velocity 0..1 to MIDI 1..127. */
    fun velocity(vel: Float): Int = (1f + vel.coerceIn(0f, 1f) * 126f).roundToInt()

    fun preset(d: SfDecl, inst: Inst, wave: Wave): Preset = d.presets.getValue(kind(inst, wave))
}

/**
 * The SoundFont synth as the mixer sees it (MixEngine): MIDI in, one stereo plane pair per output group plus the four effects planes
 * (reverb L/R, chorus L/R) out. The JVM suite drives MixEngine through a fake.
 */
interface SfBackend {
    val groups: Int
    fun noteOn(ch: Int, key: Int, vel: Int)
    fun noteOff(ch: Int, key: Int)
    fun cc(ch: Int, num: Int, value: Int)
    fun allNotesOff()

    /** ADDS [len] frames at [offset] into [planes]: plane p (0 until 2 * groups + 4) starts at p * [stride]. */
    fun process(planes: FloatArray, stride: Int, offset: Int, len: Int)
    fun close()
}

/** The FluidSynth calls of libcanvasaudio.so. */
object SfNative {
    /** False when the native library could not be loaded (an ABI without it): the Classic engine plays. */
    val available: Boolean = runCatching { System.loadLibrary("canvasaudio") }.isSuccess

    @JvmStatic external fun create(assets: AssetManager, bank: String, rate: Int, groups: Int, channels: Int, keys: Array<String>, values: Array<String>): Long
    @JvmStatic external fun lastError(): String
    @JvmStatic external fun version(): String
    @JvmStatic external fun destroy(h: Long)
    @JvmStatic external fun program(h: Long, chan: Int, bank: Int, prog: Int, drum: Boolean): Boolean
    @JvmStatic external fun presetName(h: Long, bank: Int, prog: Int): String?
    @JvmStatic external fun noteOn(h: Long, chan: Int, key: Int, vel: Int)
    @JvmStatic external fun noteOff(h: Long, chan: Int, key: Int)
    @JvmStatic external fun cc(h: Long, chan: Int, num: Int, value: Int)
    @JvmStatic external fun allNotesOff(h: Long)
    @JvmStatic external fun process(h: Long, len: Int, offset: Int, stride: Int, planes: FloatArray): Int
}

/** What the SoundFont engine resolved: the version, every kind's preset as the bank names it, or why it is not available. */
data class SfStatus(val engineVersion: String, val presetNames: Map<String, String>, val error: String? = null)

class FluidSynthBackend private constructor(private var h: Long, override val groups: Int) : SfBackend {
    override fun noteOn(ch: Int, key: Int, vel: Int) { if (h != 0L) SfNative.noteOn(h, ch, key, vel) }
    override fun noteOff(ch: Int, key: Int) { if (h != 0L) SfNative.noteOff(h, ch, key) }
    override fun cc(ch: Int, num: Int, value: Int) { if (h != 0L) SfNative.cc(h, ch, num, value) }
    override fun allNotesOff() { if (h != 0L) SfNative.allNotesOff(h) }
    override fun process(planes: FloatArray, stride: Int, offset: Int, len: Int) { if (h != 0L) SfNative.process(h, len, offset, stride, planes) }
    override fun close() { val x = h; h = 0L; if (x != 0L) SfNative.destroy(x) }

    companion object {
        /**
         * Loads the bank (about a second for 32 MB, read in place from the APK) and puts each kind's preset on its channels for every
         * track. Null with the reason when the library is missing, the bank does not load or a declared preset is not in it.
         */
        fun open(assets: AssetManager, d: SfDecl, rate: Int): Pair<FluidSynthBackend?, SfStatus> {
            if (!SfNative.available) return null to SfStatus("", emptyMap(), "the SoundFont library is not built for this device")
            val h = SfNative.create(assets, d.bankAsset, rate, d.groups, d.midiChannels, d.settings.map { it.first }.toTypedArray(), d.settings.map { it.second }.toTypedArray())
            val version = SfNative.version()
            if (h == 0L) return null to SfStatus(version, emptyMap(), SfNative.lastError())
            val names = LinkedHashMap<String, String>()
            for ((inst, wave) in GmMap.allKinds()) {
                val p = GmMap.preset(d, inst, wave)
                val name = SfNative.presetName(h, p.bank, p.program)
                if (name == null) { SfNative.destroy(h); return null to SfStatus(version, names, "the bank has no preset ${p.bank}:${p.program} for ${GmMap.kind(inst, wave)}") }
                names[GmMap.kind(inst, wave)] = name
                for (t in 1..d.groups) SfNative.program(h, GmMap.channel(d, t, inst, wave), p.bank, p.program, inst == Inst.Drums)
            }
            return FluidSynthBackend(h, d.groups) to SfStatus(version, names)
        }
    }
}
