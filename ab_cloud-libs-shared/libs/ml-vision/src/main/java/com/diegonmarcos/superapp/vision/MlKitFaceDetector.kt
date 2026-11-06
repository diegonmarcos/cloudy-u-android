package com.diegonmarcos.superapp.vision

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

/**
 * ML Kit Face Detection, the BUNDLED model (com.google.mlkit:face-detection, Apache-2.0 SDK): on-device, no Play Services
 * download, no network. Landmarks and the face contour are on; with contours ML Kit reports the most prominent face.
 * Call [detect] off the main thread (it waits for the task).
 */
class MlKitFaceDetector : FaceDetector {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build(),
    )

    override fun detect(frame: Bitmap): List<FaceInfo> =
        Tasks.await(detector.process(InputImage.fromBitmap(frame, 0))).map { toInfo(it) }

    override fun close() = detector.close()

    private fun toInfo(f: Face): FaceInfo {
        fun lm(type: Int): Pt? = f.getLandmark(type)?.position?.let { Pt(it.x, it.y) }
        val landmarks = LinkedHashMap<String, Pt>()
        lm(FaceLandmark.LEFT_EYE)?.let { landmarks["left_eye"] = it }
        lm(FaceLandmark.RIGHT_EYE)?.let { landmarks["right_eye"] = it }
        lm(FaceLandmark.NOSE_BASE)?.let { landmarks["nose"] = it }
        lm(FaceLandmark.MOUTH_LEFT)?.let { landmarks["mouth_left"] = it }
        lm(FaceLandmark.MOUTH_RIGHT)?.let { landmarks["mouth_right"] = it }
        lm(FaceLandmark.MOUTH_BOTTOM)?.let { landmarks["mouth_bottom"] = it }
        lm(FaceLandmark.LEFT_CHEEK)?.let { landmarks["left_cheek"] = it }
        lm(FaceLandmark.RIGHT_CHEEK)?.let { landmarks["right_cheek"] = it }
        lm(FaceLandmark.LEFT_EAR)?.let { landmarks["left_ear"] = it }
        lm(FaceLandmark.RIGHT_EAR)?.let { landmarks["right_ear"] = it }
        val b = f.boundingBox
        return FaceInfo(
            Box(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()),
            f.headEulerAngleZ, f.headEulerAngleY, f.headEulerAngleX, landmarks,
            f.getContour(FaceContour.FACE)?.points?.map { Pt(it.x, it.y) }.orEmpty(),
        )
    }
}
