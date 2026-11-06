package com.diegonmarcos.superapp.vision

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions

/**
 * ML Kit Selfie Segmentation, the BUNDLED model (com.google.mlkit:segmentation-selfie, Apache-2.0 SDK): a per-pixel person
 * confidence, on-device and offline, in stream mode for live frames. Call [segment] off the main thread.
 */
class MlKitPersonSegmenter : PersonSegmenter {
    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build(),
    )

    override fun segment(frame: Bitmap): PersonMask? {
        val m = Tasks.await(segmenter.process(InputImage.fromBitmap(frame, 0)))
        val w = m.width
        val h = m.height
        if (w <= 0 || h <= 0) return null
        val buf = m.buffer
        buf.rewind()
        val conf = FloatArray(w * h)
        buf.asFloatBuffer().get(conf)
        return PersonMask(w, h, conf)
    }

    override fun close() = segmenter.close()
}
