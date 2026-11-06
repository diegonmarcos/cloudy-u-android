package cld.camera.objects

import android.graphics.Bitmap
import cld.camera.analyzer.ImageContentScanner
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.vision.Detection
import com.diegonmarcos.superapp.vision.FrameResult
import com.diegonmarcos.superapp.vision.ObjectDetector

/**
 * #1533 the Objects page's detector: the fleet's ONE on-device image engine (Cloud-Lib-Ml-L-Image-Mlkit: ML Kit object
 * detection with tracking ids plus the bundled full-label classifier), reached through [ImageContentScanner.detectLive].
 * No frame leaves the phone; nothing is bundled twice. [error] carries why a frame could not be read (engine missing).
 */
class EngineObjectDetector(private val scanner: ImageContentScanner, private val mode: String) : ObjectDetector {
    @Volatile var error: String? = null
        private set

    override fun detect(frame: Bitmap): FrameResult {
        val r = scanner.detectLive(frame, mode)
        error = if (r.ok) null else (r.error ?: "detection failed")
        return toFrameResult(r)
    }

    companion object {
        /** The engine's answer in the vision lib's shape: boxes x,y,w,h become left,top,right,bottom in the frame's pixels. */
        fun toFrameResult(r: Recognition): FrameResult = FrameResult(
            r.width, r.height,
            r.boxes.map { b -> Detection(b.label, b.p.toFloat(), b.id, b.x.toFloat(), b.y.toFloat(), (b.x + b.w).toFloat(), (b.y + b.h).toFloat()) },
            r.model,
        )
    }
}
