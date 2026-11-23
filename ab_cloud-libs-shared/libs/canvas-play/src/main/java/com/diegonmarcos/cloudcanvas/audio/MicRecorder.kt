package com.diegonmarcos.cloudcanvas.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * #1865 Record: a microphone take for an audio track. Mono float PCM at the engine's rate, read on a thread of its own while the transport
 * plays; [stop] returns what was captured (at most [maxFrames], one pass of the loop region). The caller holds the RECORD_AUDIO grant:
 * the Play page asks for it at runtime before it starts a take, and a missing grant or a busy microphone surfaces as [error].
 */
class MicRecorder(private val sampleRate: Int, private val maxFrames: Int) {
    @Volatile var error: String? = null
        private set
    @Volatile private var running = false
    private var thread: Thread? = null
    private var buf = FloatArray(0)
    @Volatile private var len = 0

    /** The level of the last chunk read (0..1), for the REC meter. */
    @Volatile var level: Float = 0f
        private set

    @SuppressLint("MissingPermission") // the Play page checks RECORD_AUDIO before calling start (PlayScreen's permission launcher)
    fun start(): Boolean {
        error = null
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (min <= 0) { error = "The microphone does not record at $sampleRate Hz"; return false }
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, min * 2)
        } catch (e: SecurityException) { error = "Microphone permission is missing"; return false }
          catch (e: IllegalArgumentException) { error = "The microphone could not be opened"; return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); error = "The microphone is busy or unavailable"; return false }
        buf = FloatArray(maxFrames.coerceAtLeast(1))
        len = 0
        running = true
        rec.startRecording()
        thread = Thread({
            val chunk = FloatArray(min / 4 + 1)
            try {
                while (running) {
                    val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue
                    var peak = 0f
                    for (i in 0 until n) {
                        val v = chunk[i]
                        if (len < buf.size) { buf[len] = v; len++ }
                        if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
                    }
                    level = peak.coerceIn(0f, 1f)
                    if (len >= buf.size) running = false
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }, "canvas-mic").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    val recording: Boolean get() = running

    /** Stops the take and returns its samples (empty when nothing was captured). */
    fun stop(): FloatArray {
        running = false
        runCatching { thread?.join(500) }
        thread = null
        level = 0f
        return buf.copyOf(len)
    }
}
