package com.diegonmarcos.cloudcanvas.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** The instruments of the Play page. Every sound is synthesized here at run time: no sample file, no third-party audio. */
enum class Inst(val label: String) { Piano("Piano"), Drums("Drums"), Synth("Synth") }

enum class Wave(val label: String) { Sine("Sine"), Saw("Saw"), Square("Square"), Triangle("Triangle") }

/** The eight drum pads, in pad order. */
enum class Drum(val label: String) {
    Kick("Kick"), Snare("Snare"), Clap("Clap"), HatClosed("Hat closed"), HatOpen("Hat open"), TomLow("Tom low"), TomHigh("Tom high"), Crash("Crash")
}

/** One sounding voice: mixes its next [n] mono samples into [out] and says whether it is still alive. */
interface Voice {
    fun mix(out: FloatArray, n: Int): Boolean
    fun noteOff() {}
}

object Notes {
    fun freq(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun name(midi: Int): String = names[Math.floorMod(midi, 12)] + (midi / 12 - 1)
    fun isBlack(midi: Int): Boolean = Math.floorMod(midi, 12) in intArrayOf(1, 3, 6, 8, 10)
}

/** A pitched voice: a piano-like decaying additive tone, or a synth oscillator with a sustain until [noteOff]. */
class ToneVoice(midi: Int, private val inst: Inst, private val wave: Wave, private val vel: Float, private val sr: Int) : Voice {
    private val freq = Notes.freq(midi)
    private var phase = 0.0
    private var level = 0f
    private var attacking = true
    private var releasing = false
    private val attackStep = 1f / (0.004f * sr)
    private val decayMul = if (inst == Inst.Piano) exp(-1.0 / (sr * 0.9)).toFloat() else 1f
    private val releaseMul = exp(-1.0 / (sr * 0.12)).toFloat()
    private val sustain = if (inst == Inst.Piano) 1f else 0.75f

    override fun noteOff() { releasing = true }

    override fun mix(out: FloatArray, n: Int): Boolean {
        val inc = 2.0 * PI * freq / sr
        for (i in 0 until n) {
            if (attacking) {
                level += attackStep
                if (level >= 1f) { level = 1f; attacking = false }
            } else if (releasing) level *= releaseMul
            else if (inst == Inst.Piano) level *= decayMul
            else if (level > sustain) level -= 0.0002f
            val s = if (inst == Inst.Piano) {
                (sin(phase) + 0.5 * sin(2 * phase) + 0.25 * sin(3 * phase) + 0.12 * sin(4 * phase)).toFloat() * 0.5f
            } else osc(phase)
            out[i] += s * level * vel * 0.35f
            phase += inc
            if (phase > 2 * PI) phase -= 2 * PI
        }
        return attacking || level >= 0.0005f
    }

    private fun osc(p: Double): Float {
        val t = (p / (2 * PI)).toFloat()
        return when (wave) {
            Wave.Sine -> sin(p).toFloat()
            Wave.Saw -> 2f * t - 1f
            Wave.Square -> if (t < 0.5f) 0.7f else -0.7f
            Wave.Triangle -> if (t < 0.5f) 4f * t - 1f else 3f - 4f * t
        }
    }
}

/** A drum voice from sine sweeps and deterministic noise. */
class DrumVoice(private val drum: Drum, private val vel: Float, private val sr: Int) : Voice {
    private var t = 0
    private var seed = 12345 + drum.ordinal * 977
    private var prevNoise = 0f
    private var phase = 0.0
    private val life = when (drum) {
        Drum.Kick -> 0.45; Drum.Snare -> 0.3; Drum.Clap -> 0.25; Drum.HatClosed -> 0.08
        Drum.HatOpen -> 0.5; Drum.TomLow -> 0.5; Drum.TomHigh -> 0.4; Drum.Crash -> 1.2
    }

    private fun noise(): Float {
        seed = seed * 1103515245 + 12345
        return ((seed shr 8) and 0xFFFF) / 32768f - 1f
    }

    override fun mix(out: FloatArray, n: Int): Boolean {
        for (i in 0 until n) {
            val sec = t.toDouble() / sr
            if (sec > life) return false
            val s: Float = when (drum) {
                Drum.Kick -> sweep(sec, 150.0, 45.0, 28.0) * exp(-sec / 0.16).toFloat()
                Drum.TomLow -> sweep(sec, 160.0, 90.0, 18.0) * exp(-sec / 0.2).toFloat()
                Drum.TomHigh -> sweep(sec, 260.0, 150.0, 18.0) * exp(-sec / 0.16).toFloat()
                Drum.Snare -> noise() * 0.6f * exp(-sec / 0.07).toFloat() + sweep(sec, 220.0, 170.0, 20.0) * 0.5f * exp(-sec / 0.06).toFloat()
                Drum.Clap -> {
                    val burst = if ((sec % 0.012) < 0.006 && sec < 0.04) 1f else 0f
                    noise() * 0.7f * (burst + exp(-sec / 0.05).toFloat() * 0.6f)
                }
                Drum.HatClosed -> hat() * exp(-sec / 0.018).toFloat()
                Drum.HatOpen -> hat() * exp(-sec / 0.12).toFloat()
                Drum.Crash -> hat() * exp(-sec / 0.45).toFloat() * 0.8f
            }
            out[i] += s * vel * 0.8f
            t++
        }
        return true
    }

    private fun sweep(sec: Double, f0: Double, f1: Double, rate: Double): Float {
        val f = f1 + (f0 - f1) * exp(-sec * rate)
        phase += 2.0 * PI * f / sr
        return sin(phase).toFloat()
    }

    /** High-passed noise (the first difference): the metallic body of a hat or a crash. */
    private fun hat(): Float {
        val x = noise()
        val y = x - prevNoise
        prevNoise = x
        return y * 0.5f
    }
}

/** The metronome's click: a short sine, higher on the downbeat. */
class ClickVoice(accent: Boolean, private val sr: Int) : Voice {
    private val freq = if (accent) 1600.0 else 1000.0
    private var t = 0
    override fun mix(out: FloatArray, n: Int): Boolean {
        for (i in 0 until n) {
            val sec = t.toDouble() / sr
            if (sec > 0.03) return false
            out[i] += (sin(2 * PI * freq * sec) * exp(-sec / 0.008) * 0.5).toFloat()
            t++
        }
        return true
    }
}

object Voices {
    fun create(inst: Inst, note: Int, wave: Wave, vel: Float, sr: Int): Voice = when (inst) {
        Inst.Drums -> DrumVoice(Drum.entries[Math.floorMod(note, Drum.entries.size)], vel, sr)
        else -> ToneVoice(note, inst, wave, vel, sr)
    }
}
