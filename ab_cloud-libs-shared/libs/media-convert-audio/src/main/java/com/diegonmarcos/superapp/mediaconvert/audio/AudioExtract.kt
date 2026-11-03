package com.diegonmarcos.superapp.mediaconvert.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * #1428 the audio track of a video file, remuxed into its own container: AAC -> .m4a (MPEG-4),
 * Opus/Vorbis -> .webm. No decode and no re-encode, so it is fast and lossless on any phone.
 * A codec MediaMuxer cannot hold returns null from [plan]; the caller then asks the API to extract.
 */
object AudioExtract {
    data class Plan(val track: Int, val muxerFormat: Int, val extension: String, val mime: String)

    /** The first audio track and the container it fits, or null when none fits. */
    fun plan(trackMimes: List<String?>): Plan? {
        val i = trackMimes.indexOfFirst { it?.startsWith("audio/") == true }
        if (i < 0) return null
        return when (trackMimes[i]) {
            MediaFormat.MIMETYPE_AUDIO_AAC -> Plan(i, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "m4a", "audio/mp4")
            MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_VORBIS ->
                Plan(i, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm", "audio/webm")
            else -> null
        }
    }

    /** BLOCKING: remuxes [src]'s audio into [dstWithoutExt].<ext>; returns the file and its MIME. */
    fun extract(src: File, dstWithoutExt: File): Pair<File, String> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src.absolutePath)
            val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            val p = plan(mimes) ?: throw IllegalStateException("no audio track this phone can remux (${mimes.joinToString()})")
            val out = File(dstWithoutExt.path + "." + p.extension)
            val mux = MediaMuxer(out.absolutePath, p.muxerFormat)
            try {
                ex.selectTrack(p.track)
                val fmt = ex.getTrackFormat(p.track)
                val dst = mux.addTrack(fmt)
                val size = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1 shl 20
                val buf = ByteBuffer.allocate(size.coerceAtLeast(64 * 1024))
                val info = MediaCodec.BufferInfo()
                mux.start()
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    info.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(dst, buf, info)
                    ex.advance()
                }
                mux.stop()
            } finally {
                runCatching { mux.release() }
            }
            return out to p.mime
        } finally {
            ex.release()
        }
    }
}
