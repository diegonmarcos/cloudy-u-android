package com.diegonmarcos.cloudcanvas.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process

/**
 * The low-latency output: an AudioTrack in PERFORMANCE_MODE_LOW_LATENCY at the device's native rate and burst size (AAudio underneath on Android 8+),
 * fed by one thread at urgent-audio priority that pulls [MixEngine.render]. No microphone is opened: loops are recorded as notes.
 */
class AudioOut(private val ctx: Context) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    /** The engine's sample rate: the device's native output rate (48000 when it does not say). */
    val sampleRate: Int = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

    fun start(engine: MixEngine) {
        if (running) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
        val block = burst.coerceIn(64, 1024)
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
        track = t
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val l = FloatArray(block)
            val r = FloatArray(block)
            val pcm = ShortArray(block * 2)
            try {
                t.play()
                // two bursts of buffer: the smallest the device keeps without under-runs
                runCatching { t.setBufferSizeInFrames(block * 2) }
                while (running) {
                    engine.render(l, r, block)
                    for (i in 0 until block) {
                        pcm[i * 2] = (l[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        pcm[i * 2 + 1] = (r[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    if (t.write(pcm, 0, pcm.size) < 0) break
                }
            } catch (e: Exception) {
                // the device went away: the page stays usable, silent
            } finally {
                runCatching { t.stop() }
                runCatching { t.release() }
            }
        }, "canvas-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        track = null
    }
}
