package com.diegonmarcos.cloudcanvas.audio

import kotlin.math.pow

/**
 * The sampled instruments of the Play page (#1787): a real grand piano and a real acoustic drum kit, decoded once into memory and
 * played by [SampleVoice] inside the same [MixEngine] the live output and the WAV export share (so a recorded loop and its export
 * sound the same as the live keys).
 *
 * Assets (app/src/main/assets/samples, sources and licences in samples/{piano,drums}/NOTICE.md and licenses/curated.json):
 *  - piano/NNN_vL.ogg: Salamander Grand Piano V3 (Alexander Holm, CC-BY 3.0), the root note NNN (MIDI) every 3 semitones from A0 (21) to C8 (108),
 *    velocity layer L = 1 soft, 2 medium, 3 loud (Salamander layers v4, v9 and v14 of 16); mono, 44.1 kHz, Ogg Vorbis.
 *  - drums/<pad>_vL.ogg: MuldjordKit (Lars Muldjord, CC-BY 4.0, FreePats stereo edition), three velocity layers per pad.
 *
 * The bank is pure data: no Android class, so the JVM suite plays it. [SampleLoader] fills it on the phone; until it has (or if an asset
 * cannot be decoded) every voice falls back to the synthesized one of Synth.kt, so the page never goes silent.
 */
class Sample(val pcm: ShortArray, val rate: Int) {
    val frames: Int get() = pcm.size
}

/** One pitched or unpitched sound with its velocity layers, softest first. */
class Layered(val layers: List<Sample>) {
    init { require(layers.isNotEmpty()) { "a sound needs at least one layer" } }
    fun forVelocity(vel: Float): Sample = layers[SampleBank.layerIndex(vel, layers.size)]
}

class SampleBank(
    /** Root MIDI note to its layers. */
    val piano: Map<Int, Layered>,
    /** Drum pad to its layers; a pad missing here (Clap: no acoustic clap in the kit) keeps its synthesized voice. */
    val drums: Map<Drum, Layered>,
) {
    /** The sampled root nearest to [midi] (ties go to the lower root, which is pitched up: brighter than a root pitched down). */
    fun pianoRoot(midi: Int): Int? = nearestRoot(piano.keys, midi)

    companion object {
        /** The root notes the asset set holds: every 3 semitones from A0 (21) to C8 (108), as the Salamander set was recorded. */
        val PIANO_ROOTS: List<Int> = (21..108 step 3).toList()
        const val PIANO_LAYERS = 3
        const val DRUM_LAYERS = 3

        /** The drum pads that have kit samples, and their asset base names. */
        val DRUM_FILES: Map<Drum, String> = linkedMapOf(
            Drum.Kick to "kick", Drum.Snare to "snare", Drum.HatClosed to "hat_closed", Drum.HatOpen to "hat_open",
            Drum.TomLow to "tom_low", Drum.TomMid to "tom_mid", Drum.TomHigh to "tom_high", Drum.Crash to "crash", Drum.Ride to "ride",
        )

        fun pianoAsset(root: Int, layer: Int) = "samples/piano/%03d_v%d.ogg".format(root, layer)
        fun drumAsset(drum: Drum, layer: Int) = "samples/drums/${DRUM_FILES.getValue(drum)}_v$layer.ogg"

        /** The layer for a velocity 0..1: equal thirds of the range for three layers. */
        fun layerIndex(vel: Float, count: Int): Int = (vel.coerceIn(0f, 1f) * count).toInt().coerceIn(0, count - 1)

        fun nearestRoot(roots: Collection<Int>, midi: Int): Int? =
            roots.minWithOrNull(compareBy<Int>({ Math.abs(it - midi) }, { it }))

        /** The playback rate ratio that turns a sample of [root] recorded at [srcRate] into [midi] at the engine's [outRate]. */
        fun ratio(root: Int, midi: Int, srcRate: Int, outRate: Int): Double = 2.0.pow((midi - root) / 12.0) * srcRate / outRate

        /** The gain of a velocity inside its layer: the layer gives the timbre, this keeps a soft touch softer than a firm one in the same layer. */
        fun gain(vel: Float): Float = 0.25f + 0.75f * vel.coerceIn(0f, 1f)

        /** The bank the live engine and the WAV export use; null until [SampleLoader] has decoded the assets. */
        @Volatile var current: SampleBank? = null
    }
}

/**
 * Plays one [Sample] at a pitch ratio (linear interpolation between the source frames) with a short fade-in against clicks and, after
 * [noteOff], a damper release ([releaseSec]; a drum hit ignores the release and rings out, as a real one does).
 */
class SampleVoice(
    private val sample: Sample,
    private val ratio: Double,
    private val gain: Float,
    sr: Int,
    private val damped: Boolean,
    releaseSec: Double = 0.25,
) : Voice {
    private var pos = 0.0
    private var level = 1f
    private var releasing = false
    private val releaseMul = Math.exp(-1.0 / (sr * releaseSec / 6.9)).toFloat()  // -60 dB after releaseSec
    private val fadeIn = (sr * 0.002).toInt().coerceAtLeast(1)
    private var t = 0

    override fun noteOff() { if (damped) releasing = true }

    override fun mix(out: FloatArray, n: Int): Boolean {
        val pcm = sample.pcm
        val last = pcm.size - 1
        for (i in 0 until n) {
            val idx = pos.toInt()
            if (idx >= last) return false
            val frac = (pos - idx).toFloat()
            val s = (pcm[idx] * (1f - frac) + pcm[idx + 1] * frac) / 32768f
            if (releasing) level *= releaseMul
            val fade = if (t < fadeIn) t.toFloat() / fadeIn else 1f
            out[i] += s * gain * level * fade
            pos += ratio
            t++
        }
        return level >= 0.0005f
    }

    companion object {
        /** A piano voice for [midi] at [vel] from [bank], or null when the bank holds no piano. */
        fun piano(bank: SampleBank, midi: Int, vel: Float, sr: Int): SampleVoice? {
            val root = bank.pianoRoot(midi) ?: return null
            val s = bank.piano.getValue(root).forVelocity(vel)
            return SampleVoice(s, SampleBank.ratio(root, midi, s.rate, sr), SampleBank.gain(vel) * 0.9f, sr, damped = true)
        }

        /** A drum hit of [drum] at [vel] from [bank], or null when the kit has no sample for that pad. */
        fun drum(bank: SampleBank, drum: Drum, vel: Float, sr: Int): SampleVoice? {
            val s = bank.drums[drum]?.forVelocity(vel) ?: return null
            return SampleVoice(s, s.rate.toDouble() / sr, SampleBank.gain(vel), sr, damped = false)
        }
    }
}

/**
 * Velocity from where a key or pad is struck (#1787): the further from the top edge, the firmer the hit, as on a real key where the
 * fingertip near the front edge plays loudest. 0.3 at the top edge to 1.0 at the bottom; phone screens report no usable pressure.
 */
object TouchVelocity {
    fun of(fraction: Float): Float = (0.3f + 0.7f * fraction.coerceIn(0f, 1f))
}
