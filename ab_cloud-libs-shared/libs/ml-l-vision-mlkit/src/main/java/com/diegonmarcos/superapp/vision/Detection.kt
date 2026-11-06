package com.diegonmarcos.superapp.vision

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

/** One detected object: a label, its confidence (0..1), the tracker's id when the stream has one, and its box in IMAGE pixels. */
data class Detection(
    val label: String,
    val confidence: Float,
    val trackingId: Int?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val box: Box get() = Box(left, top, right, bottom)

    /** "chair 82% #7": the label, the confidence and the tracking id when there is one. */
    fun caption(): String = "$label ${Math.round(confidence * 100)}%" + (trackingId?.let { " #$it" } ?: "")
}

/** What a detector saw in one frame; the boxes are in the pixels of a [frameWidth] x [frameHeight] frame. */
data class FrameResult(val frameWidth: Int, val frameHeight: Int, val detections: List<Detection>, val model: String = "") {
    /** The detections a confidence slider at [min] lets through. */
    fun atLeast(min: Float): List<Detection> = detections.filter { it.confidence >= min }

    /** The JSON a snapshot carries next to its frame: size, model, threshold and every box with its label, confidence, id. */
    fun toJson(minConfidence: Float): JSONObject = JSONObject()
        .put("cloud_objects", 1)
        .put("model", model)
        .put("frame", JSONObject().put("width", frameWidth).put("height", frameHeight))
        .put("min_confidence", minConfidence.toDouble())
        .put("detections", JSONArray().apply {
            atLeast(minConfidence).forEach { d ->
                put(JSONObject().put("label", d.label).put("confidence", d.confidence.toDouble())
                    .put("id", d.trackingId ?: JSONObject.NULL)
                    .put("box", JSONObject().put("left", d.left.toDouble()).put("top", d.top.toDouble())
                        .put("right", d.right.toDouble()).put("bottom", d.bottom.toDouble())))
            }
        })
}

/** An upright, un-mirrored frame in, objects out. The fleet's engine, ML Kit or a Store ML each implement this. */
interface ObjectDetector {
    fun detect(frame: Bitmap): FrameResult
}
