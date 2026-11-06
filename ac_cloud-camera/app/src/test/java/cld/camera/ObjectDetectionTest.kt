package cld.camera

import cld.camera.objects.EngineObjectDetector
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.vision.Detection
import com.diegonmarcos.superapp.vision.FrameResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The engine's answer in the vision lib's shape, the confidence slider and the snapshot's JSON. */
class ObjectDetectionTest {
    private val engineJson = """{"ok":true,"mode":"objects","model":"mlkit-objects","width":480,"height":640,
        "boxes":[{"label":"chair","p":0.82,"x":10,"y":20,"w":100,"h":150,"id":7},
                 {"label":"cup","p":0.35,"x":200,"y":300,"w":40,"h":50}]}"""

    @Test fun engineBoxesBecomeDetections() {
        val r = EngineObjectDetector.toFrameResult(Recognition.parse(engineJson, "objects"))
        assertEquals(480, r.frameWidth); assertEquals(640, r.frameHeight); assertEquals("mlkit-objects", r.model)
        assertEquals(2, r.detections.size)
        val chair = r.detections[0]
        assertEquals("chair", chair.label); assertEquals(0.82f, chair.confidence, 0.001f); assertEquals(7, chair.trackingId)
        assertEquals(10f, chair.left, 0f); assertEquals(20f, chair.top, 0f); assertEquals(110f, chair.right, 0f); assertEquals(170f, chair.bottom, 0f)
        assertNull(r.detections[1].trackingId)
    }

    @Test fun captionShowsLabelConfidenceAndTrackingId() {
        assertEquals("chair 82% #7", Detection("chair", 0.82f, 7, 0f, 0f, 1f, 1f).caption())
        assertEquals("cup 35%", Detection("cup", 0.35f, null, 0f, 0f, 1f, 1f).caption())
    }

    @Test fun thresholdSliderFiltersByConfidence() {
        val r = EngineObjectDetector.toFrameResult(Recognition.parse(engineJson, "objects"))
        assertEquals(2, r.atLeast(0.3f).size)
        assertEquals(listOf("chair"), r.atLeast(0.5f).map { it.label })
        assertEquals(0, r.atLeast(0.9f).size)
    }

    @Test fun snapshotJsonCarriesTheDetectionsAboveTheThreshold() {
        val r = EngineObjectDetector.toFrameResult(Recognition.parse(engineJson, "objects"))
        val j = r.toJson(0.5f)
        assertEquals(1, j.getInt("cloud_objects"))
        assertEquals(480, j.getJSONObject("frame").getInt("width"))
        val d = j.getJSONArray("detections")
        assertEquals(1, d.length())
        assertEquals("chair", d.getJSONObject(0).getString("label"))
        assertEquals(7, d.getJSONObject(0).getInt("id"))
        assertEquals(110.0, d.getJSONObject(0).getJSONObject("box").getDouble("right"), 0.001)
    }

    @Test fun anEmptyFrameStillSerialises() {
        val j = FrameResult(0, 0, emptyList()).toJson(0.4f)
        assertEquals(0, j.getJSONArray("detections").length())
    }
}
