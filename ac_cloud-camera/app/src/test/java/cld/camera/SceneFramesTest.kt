package cld.camera

import cld.camera.objects.FpsMeter
import cld.camera.objects.ObjectsSnapshot
import cld.camera.objects.SceneFrames
import cld.camera.objects.SceneThrottle
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import com.diegonmarcos.superapp.image.mlkit.Scene
import com.diegonmarcos.superapp.vision.Box
import com.diegonmarcos.superapp.vision.FrameResult
import com.diegonmarcos.superapp.vision.TextSelection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1805 the engine's scene answer as Objects draws and selects it (objects, the "Text" frames, the OCR layout), the
 * request Objects sends (from the shipped recognition.json), the live cadence (text checks, OCR only while text is in
 * view and never two at once) and the snapshot JSON that keeps the read text with the photo.
 */
class SceneFramesTest {
    /** The engine's answer shape (libs:ml-l-image-mlkit SceneDetector): two objects, a text region, an OCR layout. */
    private val answer = """
        {"ok":true,"mode":"scene","detector":"efficientdet","model":"EfficientDet-Lite0","width":480,"height":360,"frame_id":7,
         "objects":[{"cls":"object","label":"person","p":0.9,"x":10,"y":20,"w":100,"h":200},
                    {"cls":"object","label":"cup","p":0.3,"x":300,"y":200,"w":40,"h":40},
                    {"cls":"text","label":"Text","p":0.8,"x":200,"y":50,"w":120,"h":30}],
         "text_present":true,
         "ocr":{"scripts":["latin","chinese"],"errors":{"korean":"boom"},"text":"HELLO WORLD",
           "blocks":[{"text":"HELLO WORLD","script":"latin","lang":"en","p":0.9,"x":200,"y":50,"w":120,"h":30,
             "lines":[{"text":"HELLO WORLD","p":0.9,"x":200,"y":50,"w":120,"h":30,
               "words":[{"text":"HELLO","p":0.95,"x":200,"y":50,"w":55,"h":30},{"text":"WORLD","p":0.85,"x":260,"y":50,"w":60,"h":30}]}]}]},
         "timings":{"objects_ms":31,"ocr_ms":120},"latency_ms":160}
    """.trimIndent()

    @Test fun theAnswerBecomesObjectsTextFramesAndASelectableLayout() {
        val s = Scene.parse(answer)
        assertTrue(s.ok)
        assertEquals(7L, s.frameId)
        assertEquals(mapOf("objects_ms" to 31L, "ocr_ms" to 120L), s.timings)
        val objects = SceneFrames.objects(s)
        assertEquals(480, objects.frameWidth); assertEquals(360, objects.frameHeight)
        assertEquals(listOf("person", "cup"), objects.detections.map { it.label })
        assertEquals(Box(10f, 20f, 110f, 220f), objects.detections[0].box)
        assertEquals("the slider still filters objects", listOf("person"), objects.atLeast(0.4f).map { it.label })
        val text = SceneFrames.textRegions(s)
        assertEquals("Text", text.single().label)
        assertEquals("Text 80%", text.single().caption())
        val l = SceneFrames.layout(s)!!
        assertEquals(480, l.frameWidth)
        assertEquals(listOf("HELLO", "WORLD"), l.words.map { it.text })
        assertEquals("WORLD", TextSelection.tap(l, null, 290f, 65f)!!.text(l))
        assertEquals(mapOf("korean" to "boom"), s.ocr!!.errors)
    }

    @Test fun aFailedOrForeignAnswerIsAStatedFailure() {
        assertFalse(Scene.parse("""{"ok":false,"error":"unknown detector ssd"}""").ok)
        assertEquals("unknown detector ssd", Scene.parse("""{"ok":false,"error":"unknown detector ssd"}""").error)
        assertFalse(Scene.parse("not json").ok)
        val noOcr = Scene.parse("""{"ok":true,"width":10,"height":10,"objects":[],"ocr":null}""")
        assertTrue(noOcr.ok)
        assertNull(SceneFrames.layout(noOcr))
        assertNull(noOcr.frameId)
    }

    @Test fun theRequestCarriesTheDeclaredSceneBlockAndRefusesAnUnknownDetector() {
        val decl = RecognitionConfig.json
        assertEquals(setOf("efficientdet", "yolo11n"), Scene.detectors(decl).keys)
        assertTrue(Scene.defaultDetector(decl) in Scene.detectors(decl))
        val r = Scene.request("yolo11n", objects = true, text = true, ocr = false, frameId = 9)
        assertEquals("yolo11n", r.getString("detector"))
        assertTrue(r.getBoolean("text")); assertFalse(r.getBoolean("ocr"))
        assertEquals(9L, r.getLong("frame_id"))
        val s = r.getJSONObject("scene")
        assertEquals(listOf("latin", "chinese", "devanagari", "japanese", "korean"), s.getJSONArray("scripts").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals("Text", s.getString("text_label"))
        assertTrue("at least 10 fps is the target", s.getInt("target_fps") >= 10)
        assertTrue(runCatching { Scene.request("ssd", true, false, false) }.isFailure)
        val b = Scene.benchRequest()
        assertEquals(2, b.getJSONArray("detectors").length())
        assertTrue(b.getInt("runs") > 0)
    }

    @Test fun ocrRunsOnlyWhileTextIsInViewAtItsCadenceAndNeverTwiceAtOnce() {
        val t = SceneThrottle(textIntervalMs = 300, ocrIntervalMs = 1000)
        assertTrue("the first frame checks for text", t.wantText(0))
        assertFalse(t.wantText(100))
        assertTrue(t.wantText(300))
        assertFalse("no text seen: no OCR", t.startOcr(300))
        t.sawText(true)
        assertTrue(t.startOcr(310))
        assertFalse("one OCR at a time", t.startOcr(2000))
        t.ocrDone()
        assertFalse("not before the OCR interval", t.startOcr(1000))
        assertTrue(t.startOcr(1310))
        t.ocrDone()
        t.sawText(false)
        assertFalse("text gone: OCR stops", t.startOcr(5000))
        t.reset()
        assertTrue(t.wantText(5001))
    }

    @Test fun theFpsMeterCountsFramesOverItsWindow() {
        val m = FpsMeter(window = 5)
        assertEquals(0.0, m.fps(), 0.0)
        for (i in 0..10) m.tick(i * 80L)
        assertEquals(12.5, m.fps(), 1e-9)
    }

    @Test fun theSnapshotJsonKeepsTheDetectionsAndAddsTheTextRegionsAndTheReadText() {
        val s = Scene.parse(answer)
        val o = ObjectsSnapshot.withText(SceneFrames.objects(s).toJson(0.4f), SceneFrames.layout(s), SceneFrames.textRegions(s))
        assertEquals("the #1533 detections are all still there", 1, o.getJSONArray("detections").length())
        assertEquals("person", o.getJSONArray("detections").getJSONObject(0).getString("label"))
        assertEquals("Text", o.getJSONArray("text_regions").getJSONObject(0).getString("label"))
        val text = o.getJSONObject("text")
        assertEquals("HELLO WORLD", text.getString("all"))
        val words = text.getJSONArray("blocks").getJSONObject(0).getJSONArray("lines").getJSONObject(0).getJSONArray("words")
        assertEquals(2, words.length())
        assertEquals(260.0, words.getJSONObject(1).getJSONObject("box").getDouble("left"), 1e-6)
        assertTrue(ObjectsSnapshot.withText(FrameResult(0, 0, emptyList()).toJson(0f), null, emptyList()).isNull("text"))
        JSONObject(o.toString())
    }
}
