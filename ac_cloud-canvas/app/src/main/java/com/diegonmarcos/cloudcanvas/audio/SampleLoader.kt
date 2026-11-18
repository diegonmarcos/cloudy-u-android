package com.diegonmarcos.cloudcanvas.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * Decodes the bundled Ogg Vorbis samples (assets/samples) into a [SampleBank] once per process, on a background thread, with the
 * platform's MediaExtractor + MediaCodec (Vorbis decodes on every Android the app supports, API 26+). About 38 MB of 16-bit mono PCM
 * for the piano and 2 MB for the kit. Until it is done [SampleBank.current] is null and the synthesized voices play.
 */
object SampleLoader {
    private const val TAG = "CanvasSamples"
    @Volatile private var started = false

    /** Starts the load once; [done] runs on the loader thread with the number of samples decoded and the number that failed. */
    fun loadAsync(ctx: Context, done: (ok: Int, failed: Int) -> Unit = { _, _ -> }) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Thread({
            var ok = 0
            var failed = 0
            fun one(path: String): Sample? = runCatching { decode(app, path) }.onFailure { Log.w(TAG, "cannot decode $path: $it") }.getOrNull()
                .also { if (it != null) ok++ else failed++ }
            val drums = LinkedHashMap<Drum, Layered>()
            for (d in SampleBank.DRUM_FILES.keys) {
                val layers = (1..SampleBank.DRUM_LAYERS).mapNotNull { one(SampleBank.drumAsset(d, it)) }
                if (layers.isNotEmpty()) drums[d] = Layered(layers)
            }
            // the kit first (small): the pads are real while the piano still decodes
            SampleBank.current = SampleBank(emptyMap(), drums)
            val piano = LinkedHashMap<Int, Layered>()
            for (root in SampleBank.PIANO_ROOTS) {
                val layers = (1..SampleBank.PIANO_LAYERS).mapNotNull { one(SampleBank.pianoAsset(root, it)) }
                if (layers.isNotEmpty()) piano[root] = Layered(layers)
            }
            SampleBank.current = SampleBank(piano, drums)
            Log.i(TAG, "samples decoded: $ok, failed: $failed")
            done(ok, failed)
        }, "canvas-samples").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    /** One asset to mono 16-bit PCM at its own rate (a stereo source is averaged). */
    fun decode(ctx: Context, path: String): Sample {
        val ex = MediaExtractor()
        ctx.assets.openFd(path).use { fd -> ex.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        try {
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            val out = ByteArrayOutputStream()
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inDone = false
                var outDone = false
                while (!outDone) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        codec.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset); buf.get(bytes, 0, info.size)
                        out.write(bytes)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            val sb = java.nio.ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val all = ShortArray(sb.remaining()).also { sb.get(it) }
            return Sample(Pcm.toMono(all, channels), rate)
        } finally {
            ex.release()
        }
    }
}

/** PCM helpers the decoder uses, kept pure for the JVM suite. */
object Pcm {
    /** Interleaved [channels]-channel PCM to mono (the channel average). */
    fun toMono(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        val n = pcm.size / channels
        return ShortArray(n) { f -> (0 until channels).sumOf { pcm[f * channels + it].toInt() }.div(channels).toShort() }
    }
}
