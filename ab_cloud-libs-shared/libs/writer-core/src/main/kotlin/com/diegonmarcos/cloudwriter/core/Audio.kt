package com.diegonmarcos.cloudwriter.core

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Cuts continuous 16-bit little-endian mono PCM into UTTERANCES for the model route: a segment
 * closes on a pause of [silenceMs] after at least [minMs] of voice, or at [maxMs] whatever is
 * happening, so a long monologue still reaches the page in pieces. Leading silence is never
 * recorded, and a blip shorter than [minMs] followed by a pause is dropped — a cough is not a
 * segment worth a request. Thresholds are build.json::writer_routes.speech.segment.
 */
class Segmenter(
    private val sampleRate: Int,
    private val silenceRms: Double,
    private val silenceMs: Int,
    private val minMs: Int,
    private val maxMs: Int,
) {
    private val buf = ByteArrayOutputStream()
    private var silentMs = 0.0
    private var voicedMs = 0.0

    /** Milliseconds of audio held for the segment in progress. */
    val bufferedMs: Long get() = ms(buf.size())

    /** Feed one frame; answers a finished segment's PCM when a pause or the cap closes one, else null. */
    fun feed(pcm: ByteArray, len: Int): ByteArray? {
        if (len < 2) return null
        val loud = rms(pcm, len) >= silenceRms
        if (buf.size() == 0 && !loud) return null
        buf.write(pcm, 0, len)
        val frame = ms(len).toDouble()
        if (loud) {
            silentMs = 0.0
            voicedMs += frame
        } else {
            silentMs += frame
        }
        if (bufferedMs >= maxMs) return take()
        if (silentMs >= silenceMs) {
            if (voicedMs >= minMs) return take()
            reset()
        }
        return null
    }

    /** End of listening: the segment in progress if it holds enough voice, else nothing. */
    fun flush(): ByteArray? = if (voicedMs >= minMs) take() else { reset(); null }

    private fun take(): ByteArray = buf.toByteArray().also { reset() }

    private fun reset() {
        buf.reset()
        silentMs = 0.0
        voicedMs = 0.0
    }

    private fun ms(bytes: Int): Long = bytes / 2 * 1000L / sampleRate

    companion object {
        /** Root mean square of [len] bytes of 16-bit LE samples, in sample units (0..32768). */
        fun rms(pcm: ByteArray, len: Int): Double {
            val n = len / 2
            if (n == 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) {
                val s = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort().toDouble()
                sum += s * s
            }
            return sqrt(sum / n)
        }

        /** 0..1 for the level meter. */
        fun level(pcm: ByteArray, len: Int): Float = (rms(pcm, len) / 32768.0).coerceIn(0.0, 1.0).toFloat()
    }
}

/** A RIFF/WAVE container around raw PCM — the format OpenRouter's `input_audio` takes. */
object Wav {
    fun encode(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bits: Int = 16): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun int(v: Int) = repeat(4) { out.write((v ushr (8 * it)) and 0xff) }
        fun short(v: Int) = repeat(2) { out.write((v ushr (8 * it)) and 0xff) }
        val block = channels * bits / 8
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); int(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII)); int(16); short(1); short(channels)
        int(sampleRate); int(sampleRate * block); short(block); short(bits)
        out.write("data".toByteArray(Charsets.US_ASCII)); int(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }
}
