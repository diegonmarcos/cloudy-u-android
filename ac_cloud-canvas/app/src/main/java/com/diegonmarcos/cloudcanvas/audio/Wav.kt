package com.diegonmarcos.cloudcanvas.audio

/** A 16-bit PCM WAV writer: the 44-byte RIFF header, then the interleaved samples little-endian. */
object WavEncoder {
    fun encode(pcm: ShortArray, sampleRate: Int, channels: Int): ByteArray {
        val dataLen = pcm.size * 2
        val out = ByteArray(44 + dataLen)
        fun str(at: Int, s: String) { for (i in s.indices) out[at + i] = s[i].code.toByte() }
        fun i32(at: Int, v: Int) { for (k in 0..3) out[at + k] = (v ushr (8 * k)).toByte() }
        fun i16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v ushr 8).toByte() }
        str(0, "RIFF"); i32(4, 36 + dataLen); str(8, "WAVE")
        str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, channels); i32(24, sampleRate)
        i32(28, sampleRate * channels * 2); i16(32, channels * 2); i16(34, 16)
        str(36, "data"); i32(40, dataLen)
        for (i in pcm.indices) i16(44 + i * 2, pcm[i].toInt())
        return out
    }
}
