package com.diegonmarcos.superapp.image.mlkit

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * #1805 the scene method's engine half: YOLO11n's decode (letterbox inverse, logits, both layouts, NMS), the merge of
 * the five OCR scripts into one layout, and [SceneDetector] over the golden images with stand-ins for MediaPipe,
 * LiteRT and ML Kit — the request's flags decide what runs, every box is in the detection frame's pixels (OCR read on
 * the larger copy and scaled back), text regions are objects of class "text", and refusals are answers. The two
 * detectors themselves are checked as FILES here (pinned sha256 and size, EfficientDet's 90 metadata labels) and run
 * for real on the emulator (ac_cloud-camera/test/emulator-camera-scene.sh) and, for YOLO11n, through LiteRT's runtime
 * by lib-apks/test/test-ml-goldens.sh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneTest {
    private val labels = listOf("person", "bicycle", "car")

    // ── YOLO decode ─────────────────────────────────────────────────────────

    /** A channels-first [4 + nc] x [anchors] output with the given anchors (cx, cy, w, h, scores...). */
    private fun out(vararg anchors: FloatArray, channels: Int = 4 + labels.size): FloatArray {
        val n = anchors.size
        val a = FloatArray(channels * n)
        anchors.forEachIndexed { i, v -> v.forEachIndexed { c, x -> a[c * n + i] = x } }
        return a
    }

    @Test fun `YOLO - the letterbox is undone, overlapping boxes of a class are suppressed, others kept`() {
        // a 1280x720 frame in a 640 square: scale 0.5, 320x... 640x360, padded 140 top and bottom
        val lb = SceneMath.letterbox(1280, 720, 640)
        assertEquals(0.5f, lb.scale, 1e-6f)
        assertEquals(0, lb.padX); assertEquals(140, lb.padY)
        val o = out(
            floatArrayOf(320f, 320f, 100f, 200f, 0.9f, 0.0f, 0.1f),   // person, centre of the square
            floatArrayOf(322f, 321f, 100f, 200f, 0.8f, 0.0f, 0.0f),   // the same person, weaker: suppressed
            floatArrayOf(322f, 321f, 100f, 200f, 0.0f, 0.0f, 0.7f),   // a car on the same spot: another class, kept
            floatArrayOf(100f, 200f, 20f, 20f, 0.2f, 0.1f, 0.1f),     // under the threshold
        )
        val r = SceneMath.decodeYolo(o, 7, 4, true, labels, lb, 1280, 720, 0.35f, 0.45f, 10)
        assertEquals(listOf("person", "car"), r.map { it.label })
        val p = r[0].box
        // (320 - 50 - 0) / 0.5 = 540 .. 740; (320 - 100 - 140) / 0.5 = 160 .. 560
        assertEquals(540f, p.l, 0.01f); assertEquals(740f, p.r, 0.01f)
        assertEquals(160f, p.t, 0.01f); assertEquals(560f, p.b, 0.01f)
        assertEquals(0.9f, r[0].p, 1e-6f)
    }

    @Test fun `YOLO - logits pass a sigmoid, normalised boxes scale to the square, anchors-first layout reads the same`() {
        val lb = SceneMath.letterbox(640, 640, 640)
        val logit = 2.0f  // sigmoid 0.881
        val first = out(floatArrayOf(0.5f, 0.5f, 0.25f, 0.25f, logit, -5f, -5f))
        val r = SceneMath.decodeYolo(first, 7, 1, true, labels, lb, 640, 640, 0.5f, 0.45f, 10).single()
        assertEquals(0.8808f, r.p, 1e-3f)
        assertEquals(240f, r.box.l, 0.01f); assertEquals(400f, r.box.r, 0.01f)
        val anchorsFirst = floatArrayOf(0.5f, 0.5f, 0.25f, 0.25f, logit, -5f, -5f)
        assertEquals(r, SceneMath.decodeYolo(anchorsFirst, 7, 1, false, labels, lb, 640, 640, 0.5f, 0.45f, 10).single())
        assertTrue("a box past the frame is clamped to it",
            SceneMath.decodeYolo(out(floatArrayOf(630f, 630f, 100f, 100f, 0.9f, 0f, 0f)), 7, 1, true, labels, lb, 640, 640, 0.5f, 0.45f, 10).single().box.r <= 640f)
    }

    @Test fun `YOLO - max objects caps the answer best first, and a short output is refused`() {
        val lb = SceneMath.letterbox(640, 640, 640)
        val many = out(*Array(6) { i -> floatArrayOf(50f + 100f * i, 50f, 40f, 40f, 0.5f + i * 0.05f, 0f, 0f) })
        val r = SceneMath.decodeYolo(many, 7, 6, true, labels, lb, 640, 640, 0.3f, 0.45f, 3)
        assertEquals(3, r.size)
        assertEquals(listOf(0.75f, 0.7f, 0.65f), r.map { Math.round(it.p * 100) / 100f })
        assertTrue(runCatching { SceneMath.decodeYolo(FloatArray(5), 7, 1, true, labels, lb, 640, 640, 0.3f, 0.45f, 3) }.isFailure)
    }

    // ── OCR merge ───────────────────────────────────────────────────────────

    private fun block(text: String, l: Float, t: Float, r: Float, b: Float, script: String, p: Float = 0.9f): SceneMath.Block {
        val box = Rect(l, t, r, b)
        val words = text.split(" ").filter { it.isNotBlank() }.mapIndexed { i, w -> SceneMath.Word(w, Rect(l + i * 10, t, l + i * 10 + 9, b), p) }
        return SceneMath.Block(text, box, script, "", listOf(SceneMath.Line(text, box, p, words)))
    }

    @Test fun `OCR merge - one read per region, the richer script wins, a tie keeps Latin, reading order`() {
        val reads = mapOf(
            "latin" to listOf(block("OPEN DAILY", 10f, 10f, 200f, 40f, "latin"), block("x", 10f, 100f, 200f, 140f, "latin", 0.3f)),
            "chinese" to listOf(block("OPEN DAILY", 11f, 11f, 199f, 41f, "chinese"), block("营业时间 早上", 300f, 10f, 500f, 40f, "chinese")),
            "devanagari" to listOf(block("नमस्ते दुनिया", 10f, 100f, 200f, 140f, "devanagari")),
            "japanese" to emptyList(), "korean" to emptyList(),
        )
        val m = SceneMath.mergeScripts(reads, SceneDetector.SCRIPTS)
        assertEquals(listOf("OPEN DAILY", "营业时间 早上", "नमस्ते दुनिया"), m.map { it.text })
        assertEquals("the same Latin sign read by the Chinese model is not doubled; the tie keeps Latin", "latin", m[0].script)
        assertEquals("the Hindi region keeps the Devanagari read over the Latin model's stray letter", "devanagari", m[2].script)
    }

    @Test fun `overlap - IoU and containment`() {
        val a = Rect(0f, 0f, 10f, 10f)
        assertEquals(1f, a.iou(a), 1e-6f)
        assertEquals(0f, a.iou(Rect(20f, 20f, 30f, 30f)), 1e-6f)
        assertEquals(1f, a.containment(Rect(2f, 2f, 4f, 4f)), 1e-6f)
        assertEquals(25f / 175f, a.iou(Rect(5f, 5f, 15f, 15f)), 1e-6f)
    }

    // ── SceneDetector over the goldens ─────────────────────────────────────

    private class Fake : SceneDetector.Backends {
        val calls = mutableListOf<String>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        override fun objects(detector: String, frame: Bitmap, minConfidence: Float, max: Int, iou: Float): List<SceneObj> {
            calls += "objects $detector min=$minConfidence max=$max iou=$iou"; sizes += frame.width to frame.height
            return listOf(SceneObj(0, "person", 0.91f, Rect(10f, 20f, 110f, 220f)), SceneObj(27, "tie", 0.6f, Rect(50f, 120f, 70f, 200f)))
        }
        override fun textRegions(frame: Bitmap): List<SceneMath.Block> {
            calls += "text ${frame.width}x${frame.height}"
            return listOf(SceneMath.Block("HELLO", Rect(1f, 2f, 41f, 12f), "latin", "en", listOf(SceneMath.Line("HELLO", Rect(1f, 2f, 41f, 12f), 0.8f, listOf(SceneMath.Word("HELLO", Rect(1f, 2f, 41f, 12f), 0.8f))))))
        }
        override fun ocr(frame: Bitmap, script: String): Result<List<SceneMath.Block>> {
            calls += "ocr $script ${frame.width}x${frame.height}"; sizes += frame.width to frame.height
            if (script == "korean") return Result.failure(IllegalStateException("no korean here"))
            if (script != "latin") return Result.success(emptyList())
            val line = SceneMath.Line("HELLO WORLD", Rect(100f, 100f, 300f, 140f), 0.9f,
                listOf(SceneMath.Word("HELLO", Rect(100f, 100f, 190f, 140f), 0.95f), SceneMath.Word("WORLD", Rect(200f, 100f, 300f, 140f), 0.85f)))
            return Result.success(listOf(SceneMath.Block("HELLO WORLD", Rect(100f, 100f, 300f, 140f), "latin", "en", listOf(line))))
        }
        override fun model(detector: String) = "model-$detector"
    }

    private val fake = Fake()
    private var t = 1000L
    private val scene = SceneDetector(ImageScanner(), fake) { t.also { t += 5 } }

    private fun golden(name: String): ByteArray = javaClass.getResourceAsStream("/golden/$name")!!.readBytes()

    /** Scene.request's shape, from the shipped recognition.json. */
    private fun request(detector: String = "efficientdet", objects: Boolean = true, text: Boolean = false, ocr: Boolean = false): JSONObject =
        JSONObject().put("detector", detector).put("objects", objects).put("text", text).put("ocr", ocr).put("frame_id", 42)
            .put("scene", JSONObject(JSONObject(File("../ml-l-image/recognition.json").readText()).getJSONObject("scene").toString()))

    @Test fun `a live frame - objects only, sized to live_side, the declared thresholds reach the detector`() {
        val o = scene.scene(golden("grace_hopper.jpg"), request())
        assertTrue(o.toString(), o.getBoolean("ok"))
        assertEquals(listOf("objects efficientdet min=0.35 max=10 iou=0.45"), fake.calls)
        assertEquals(409 to 480, fake.sizes.single())
        assertEquals(409, o.getInt("width")); assertEquals(480, o.getInt("height"))
        assertEquals(42, o.getLong("frame_id"))
        assertEquals("model-efficientdet", o.getString("model"))
        val objs = o.getJSONArray("objects")
        assertEquals(2, objs.length())
        assertEquals(listOf("object", "person", 0.91, 10.0, 20.0, 100.0, 200.0),
            objs.getJSONObject(0).let { listOf(it.getString("cls"), it.getString("label"), Math.round(it.getDouble("p") * 100) / 100.0, it.getDouble("x"), it.getDouble("y"), it.getDouble("w"), it.getDouble("h")) })
        assertFalse(o.getBoolean("text_present"))
        assertTrue(o.isNull("ocr"))
        assertTrue(o.getJSONObject("timings").has("objects_ms"))
    }

    @Test fun `text is a class - every region is an object labelled Text, on the detection frame`() {
        val o = scene.scene(golden("grace_hopper.jpg"), request(detector = "yolo11n", text = true))
        assertEquals(listOf("objects yolo11n min=0.35 max=10 iou=0.45", "text 409x480"), fake.calls)
        assertTrue(o.getBoolean("text_present"))
        val text = o.getJSONArray("objects").getJSONObject(2)
        assertEquals("text", text.getString("cls"))
        assertEquals("Text", text.getString("label"))
        assertEquals(0.8, text.getDouble("p"), 1e-6)
    }

    @Test fun `the OCR reads every script on the larger copy and comes back in the detection frame's pixels`() {
        val o = scene.scene(golden("grace_hopper.jpg"), request(objects = false, ocr = true))
        // grace_hopper.jpg is 512x600: ocr_side 1280 never upscales, so OCR reads 512x600; detection is 409x480
        assertEquals(SceneDetector.SCRIPTS.map { "ocr $it 512x600" }, fake.calls)
        val ocr = o.getJSONObject("ocr")
        assertEquals(listOf("latin", "chinese", "devanagari", "japanese"), ocr.getJSONArray("scripts").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals("no korean here", ocr.getJSONObject("errors").getString("korean"))
        assertEquals("HELLO WORLD", ocr.getString("text"))
        val b = ocr.getJSONArray("blocks").getJSONObject(0)
        val s = 409.0 / 512.0
        assertEquals(100 * s, b.getDouble("x"), 0.01)
        val words = b.getJSONArray("lines").getJSONObject(0).getJSONArray("words")
        assertEquals(listOf("HELLO", "WORLD"), (0 until words.length()).map { words.getJSONObject(it).getString("text") })
        assertEquals(200 * s, words.getJSONObject(1).getDouble("x"), 0.01)
        assertEquals(0.9, b.getDouble("p"), 1e-6)
    }

    @Test fun `the benchmark times every detector and the OCR`() {
        // Scene.benchRequest's shape: the OCR is timed too (ocr: true)
        val o = scene.bench(golden("grace_hopper.jpg"), request(ocr = true).put("runs", 4).put("warmup", 1).put("ocr_runs", 2))
        assertTrue(o.toString(), o.getBoolean("ok"))
        for (d in SceneDetector.DETECTORS) {
            val r = o.getJSONObject("detectors").getJSONObject(d)
            assertEquals(4, r.getInt("runs"))
            assertEquals(5.0, r.getDouble("mean_ms"), 1e-9)
            assertEquals(200.0, r.getDouble("fps"), 1e-9)
            assertEquals(2, r.getJSONArray("detections").length())
        }
        assertEquals("1 warm-up + 4 timed runs", 5, fake.calls.count { it.startsWith("objects efficientdet") })
        val ocr = o.getJSONObject("ocr")
        assertEquals(2, ocr.getInt("runs")); assertEquals(1, ocr.getInt("blocks")); assertEquals(2, ocr.getInt("words")); assertEquals(1, ocr.getInt("text_regions"))
        // a bench that asks for no OCR times none
        val noOcr = scene.bench(golden("grace_hopper.jpg"), request(ocr = false).put("runs", 1).put("warmup", 0))
        assertTrue(noOcr.getBoolean("ok"))
        assertFalse(noOcr.has("ocr"))
    }

    @Test fun `refusals are answers - an unknown detector, an undecodable image, broken JSON`() {
        val u = scene.scene(golden("red.png"), request(detector = "ssd"))
        assertFalse(u.getBoolean("ok"))
        assertTrue(u.getString("error").contains("unknown detector ssd"))
        val g = JSONObject(scene.json("not an image".toByteArray(), request().toString()))
        assertEquals(ImageScanner.CANNOT_DECODE, g.getString("error"))
        assertFalse(JSONObject(scene.json(golden("red.png"), "{")).getBoolean("ok"))
        assertTrue(fake.calls.isEmpty())
    }

    // ── the pinned detectors, as files ──────────────────────────────────────

    private fun pinned(id: String, prop: String): Pair<JSONObject, ByteArray> {
        val pin = JSONObject(File("data/models.json").readText()).getJSONObject("models").getJSONObject(id)
        val f = File(System.getProperty(prop) ?: error("$prop is not set: the suite runs through gradle, after fetchModels"))
        val bytes = f.readBytes()
        assertEquals(pin.getLong("bytes"), bytes.size.toLong())
        assertEquals(pin.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        return pin to bytes
    }

    @Test fun `EfficientDet-Lite0 is the pinned file, with its 90 COCO labels in its metadata`() {
        val (pin, _) = pinned("scene_efficientdet", "scene.efficientdet")
        val names = ZipFile(File(System.getProperty("scene.efficientdet")!!)).use { z -> z.getInputStream(z.getEntry(pin.getString("labels_entry"))).readBytes().toString(Charsets.UTF_8) }
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(pin.getInt("classes"), names.size)
        assertEquals("person", names[0])
        assertTrue(names.contains("tie"))
    }

    @Test fun `YOLO11n is the pinned file, and the 80 labels baked for it are COCO's in order`() {
        val (pin, bytes) = pinned("scene_yolo11n", "scene.yolo")
        assertEquals("TFL3", String(bytes, 4, 4, Charsets.US_ASCII))
        val names = BuildConfig.SCENE_YOLO_LABELS.split('|')
        assertEquals(pin.getInt("classes"), names.size)
        assertEquals(listOf("person", "bicycle", "car"), names.take(3))
        assertEquals("tie", names[27]); assertEquals("toothbrush", names[79])
        assertNotNull(pin.getString("licence"))
    }
}
