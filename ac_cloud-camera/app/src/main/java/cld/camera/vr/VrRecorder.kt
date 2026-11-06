package cld.camera.vr

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import androidx.core.content.ContextCompat
import cld.camera.capturer.DEFAULT_MEDIA_STORE_CAPTURE_PATH
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * #1533 records the COMPOSITED frames (camera + filters) to an MP4 in DCIM/Camera: a MediaRecorder with a surface input,
 * every composited frame drawn onto that surface. Sound is recorded only when the microphone grant is already held (the
 * Configs > Perms row owns it; this class never asks).
 */
class VrRecorder(private val ctx: Context) {
    private var recorder: MediaRecorder? = null
    private var surface: Surface? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var width = 0
    private var height = 0
    private var mirrored = false
    @Volatile var recording = false
        private set

    /** Starts a recording sized to [first] (rounded to even); false when the recorder could not start. */
    fun start(first: Bitmap, mirror: Boolean): Boolean {
        if (recording) return true
        width = first.width and 1.inv()
        height = first.height and 1.inv()
        mirrored = mirror
        val stem = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_vr"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$stem.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_MEDIA_STORE_CAPTURE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val u = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        uri = u
        return try {
            val p = ctx.contentResolver.openFileDescriptor(u, "w") ?: error("cannot write $u")
            pfd = p
            val withAudio = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (withAudio) r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setVideoSize(width, height)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(6_000_000)
            r.setOutputFile(p.fileDescriptor)
            r.prepare()
            surface = r.surface
            r.start()
            recorder = r
            recording = true
            true
        } catch (e: Exception) {
            abort()
            false
        }
    }

    /** Draws one composited frame onto the recording (the same mirroring the screen shows). */
    fun add(frame: Bitmap) {
        val s = surface ?: return
        if (!recording) return
        runCatching {
            val c = s.lockCanvas(null)
            try {
                if (mirrored) c.scale(-1f, 1f, width / 2f, height / 2f)
                c.drawBitmap(frame, null, Rect(0, 0, width, height), null)
            } finally {
                s.unlockCanvasAndPost(c)
            }
        }
    }

    /** Stops and files the video; returns its Uri, or null when no frame was recorded. */
    fun stop(): Uri? {
        if (!recording) return null
        recording = false
        val r = recorder
        val u = uri
        var ok = true
        try { r?.stop() } catch (e: RuntimeException) { ok = false }
        release()
        if (u != null) {
            if (ok) ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            else ctx.contentResolver.delete(u, null, null)
        }
        uri = null
        return if (ok) u else null
    }

    private fun abort() {
        release()
        uri?.let { ctx.contentResolver.delete(it, null, null) }
        uri = null
        recording = false
    }

    private fun release() {
        runCatching { recorder?.release() }
        runCatching { surface?.release() }
        runCatching { pfd?.close() }
        recorder = null; surface = null; pfd = null
    }
}
