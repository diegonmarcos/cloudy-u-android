package com.diegonmarcos.superapp.vision

import android.graphics.Bitmap
import java.io.Closeable

/** A point in image pixels. */
data class Pt(val x: Float, val y: Float)

/**
 * One detected face in IMAGE pixels. [landmarks] are keyed by the fleet's names (left_eye, right_eye, nose, mouth_left,
 * mouth_right, mouth_bottom, left_cheek, right_cheek, left_ear, right_ear; whichever the detector found), [contour] is the
 * face oval when the detector gave one, and [eulerZ] is the head's roll as ML Kit reports it (counter-clockwise positive),
 * used only when both eyes were not found.
 */
data class FaceInfo(
    val box: Box,
    val eulerZ: Float = 0f,
    val eulerY: Float = 0f,
    val eulerX: Float = 0f,
    val landmarks: Map<String, Pt> = emptyMap(),
    val contour: List<Pt> = emptyList(),
)

/** An upright frame in, faces with landmarks and contours out. ML Kit Face Detection (bundled) implements it. */
interface FaceDetector : Closeable {
    fun detect(frame: Bitmap): List<FaceInfo>
}

/** Per-pixel confidence (0..1) that a pixel belongs to a person, [width] x [height] row-major. */
class PersonMask(val width: Int, val height: Int, val confidence: FloatArray)

/** An upright frame in, a person mask out. ML Kit Selfie Segmentation (bundled) implements it. */
interface PersonSegmenter : Closeable {
    fun segment(frame: Bitmap): PersonMask?
}

/** The face in another pixel scale (a detection made on a shrunk copy, mapped back to the full frame by [k]). */
fun FaceInfo.scaled(k: Float): FaceInfo = FaceInfo(
    Box(box.left * k, box.top * k, box.right * k, box.bottom * k), eulerZ, eulerY, eulerX,
    landmarks.mapValues { (_, p) -> Pt(p.x * k, p.y * k) }, contour.map { Pt(it.x * k, it.y * k) },
)
