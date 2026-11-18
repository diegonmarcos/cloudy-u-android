package com.diegonmarcos.cloudcanvas.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import java.io.File

/** #1809 the Oboe output of libcanvasaudio.so (app/src/main/cpp/canvas_audio_jni.cpp). */
object OboeNative {
    val available: Boolean get() = SfNative.available
    @JvmStatic external fun open(rate: Int, exclusive: Boolean, ringBursts: Int): Long
    /** [rate, framesPerBurst, bufferSizeInFrames, ringCapacityFrames, exclusive, lowLatency, float, aaudio] */
    @JvmStatic external fun info(h: Long): IntArray
    @JvmStatic external fun write(h: Long, lr: FloatArray, frames: Int): Int
    @JvmStatic external fun latencyMs(h: Long): Double
    @JvmStatic external fun underruns(h: Long): Long
    @JvmStatic external fun close(h: Long)
}

/** What the output is, for the Play page's engine line and files/audio-latency.txt. */
data class OutStatus(
    val path: String,
    val rate: Int,
    val burst: Int,
    val bufferFrames: Int,
    val ringFrames: Int,
    val exclusive: Boolean,
    val lowLatency: Boolean,
    val float: Boolean,
    val latencyMs: Double = -1.0,
    val underruns: Long = 0,
) {
    /** One line: "Oboe AAudio, LowLatency, Exclusive, float, 48000 Hz, burst 96, 14.2 ms, 0 under-runs". */
    fun line(): String = buildString {
        append(path).append(", ").append(if (lowLatency) "LowLatency" else "default latency").append(", ").append(if (exclusive) "Exclusive" else "Shared")
        append(", ").append(if (float) "float" else "16-bit").append(", ").append(rate).append(" Hz, burst ").append(burst)
        append(", ").append(if (latencyMs >= 0) "%.1f ms".format(latencyMs) else "latency pending").append(", ").append(underruns).append(" under-runs")
    }
}

/**
 * The low-latency output. #1809 first choice: an Oboe stream (LowLatency, Exclusive, float, stereo, at the device's native rate) fed
 * through libcanvasaudio.so's ring; fallback: the #1787 AudioTrack in PERFORMANCE_MODE_LOW_LATENCY (16-bit, two bursts of buffer) when
 * the native library is missing or the stream cannot be opened. One thread at urgent-audio priority pulls [MixEngine.render] either way.
 * No microphone is opened: loops are recorded as notes.
 */
class AudioOut(private val ctx: Context) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** The output now (null while stopped). */
    @Volatile var status: OutStatus? = null
        private set

    /** The engine's sample rate: the device's native output rate (48000 when it does not say). */
    val sampleRate: Int = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

    private val burstHint: Int = ((ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256).coerceIn(64, 1024)

    fun start(engine: MixEngine) {
        if (running) return
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val decl = runCatching { SfDecl.load(ctx.assets) }.getOrNull()
            // Oboe while it keeps working (a disconnect reopens it once per loop pass); the AudioTrack when it never opens
            while (running) {
                if (!(OboeNative.available && runOboe(engine, decl?.exclusive ?: true, decl?.ringBursts ?: 3))) { runAudioTrack(engine); break }
            }
            status = null
        }, "canvas-audio").also { it.start() }
    }

    /** False when no Oboe stream could be opened; true when it ran until [stop] or until the device went away. */
    private fun runOboe(engine: MixEngine, exclusive: Boolean, ringBursts: Int): Boolean {
        val h = runCatching { OboeNative.open(sampleRate, exclusive, ringBursts) }.getOrDefault(0L)
        if (h == 0L) return false
        try {
            val i = OboeNative.info(h)
            var st = OutStatus(if (i[7] == 1) "Oboe AAudio" else "Oboe OpenSL ES", i[0], i[1], i[2], i[3], i[4] == 1, i[5] == 1, i[6] == 1)
            status = st
            val block = i[1].coerceIn(32, 2048)
            val l = FloatArray(block)
            val r = FloatArray(block)
            val lr = FloatArray(block * 2)
            var frames = 0L
            var nextReport = sampleRate.toLong()
            var latencyWritten = false
            while (running) {
                engine.render(l, r, block)
                for (k in 0 until block) { lr[k * 2] = l[k]; lr[k * 2 + 1] = r[k] }
                if (OboeNative.write(h, lr, block) < 0) return true
                frames += block
                if (frames >= nextReport) {
                    nextReport += sampleRate / 2
                    st = st.copy(latencyMs = OboeNative.latencyMs(h), underruns = OboeNative.underruns(h))
                    status = st
                    if (!latencyWritten && st.latencyMs >= 0) { latencyWritten = true; writeLatency(st) }
                }
            }
        } finally {
            OboeNative.close(h)
        }
        return true
    }

    private fun runAudioTrack(engine: MixEngine) {
        val block = burstHint
        val t = try {
            val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(min, block * 4 * 2))
                .build()
        } catch (e: Exception) {
            return
        }
        val st = OutStatus("AudioTrack", sampleRate, block, block * 2, 0, exclusive = false, lowLatency = true, float = false)
        status = st
        val l = FloatArray(block)
        val r = FloatArray(block)
        val pcm = ShortArray(block * 2)
        try {
            t.play()
            // two bursts of buffer: the smallest the device keeps without under-runs
            runCatching { t.setBufferSizeInFrames(block * 2) }
            var frames = 0L
            var written = false
            while (running) {
                engine.render(l, r, block)
                for (i in 0 until block) {
                    pcm[i * 2] = (l[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    pcm[i * 2 + 1] = (r[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                if (t.write(pcm, 0, pcm.size) < 0) break
                frames += block
                if (!written && frames > sampleRate) {
                    written = true
                    val s2 = st.copy(latencyMs = (t.bufferSizeInFrames * 1000.0 / sampleRate), underruns = t.underrunCount.toLong())
                    status = s2; writeLatency(s2)
                }
            }
        } catch (e: Exception) {
            // the device went away: the page stays usable, silent
        } finally {
            runCatching { t.stop() }
            runCatching { t.release() }
        }
    }

    /** files/audio-latency.txt: the output line once the stream reports its latency (the emulator job reads it with run-as). */
    private fun writeLatency(st: OutStatus) {
        runCatching { File(ctx.filesDir, "audio-latency.txt").writeText(st.line() + "\n") }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}
