package com.diegonmarcos.superapp.image.mlkit

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1805 CONTRACT 4, `scene`: what Cloud Camera > Objects draws over a camera frame or a photo, fully
 * on device, in one answer —
 *
 *  - objects: every detected object with its box, name and confidence, from the request's
 *    `detector` (efficientdet = MediaPipe Tasks Object Detector over EfficientDet-Lite0, yolo11n =
 *    YOLO11n on the LiteRT Interpreter; recognition.json::scene names the default);
 *  - text as a first-class class: with `text`, every text region the Latin recognizer finds on the
 *    frame is an object too, class `text`, labelled with scene.text_label ("Text");
 *  - ocr: with `ocr`, the whole frame read by ML Kit Text Recognition v2 with the five bundled
 *    script models (libs:ml-l-image-ocr), merged per region ([SceneMath.mergeScripts]) into
 *    blocks, lines and words, each with its box, on a larger copy of the frame (scene.ocr_side).
 *
 * Every box is in the pixels of the frame as sized for detection (`width` x `height`), the OCR's
 * too, so a caller maps one coordinate space onto its view. Throttling is the caller's: it asks for
 * `text` at its text cadence and for `ocr` only when the last frame had text (or once, for a frozen
 * frame, whose layout it then caches). Never throws: a failure is {"ok": false, "error"}.
 *
 * `scene_bench` runs the same backends [runs] times over one image and answers each detector's
 * latency (mean, p50, p90, fps) with what it found, and the OCR pass's: the emulator benchmark.
 */
internal class SceneDetector(
    private val scanner: ImageScanner,
    private val backends: Backends,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The models, behind an interface so the suite stands in for MediaPipe, LiteRT and ML Kit. */
    interface Backends {
        fun objects(detector: String, frame: Bitmap, minConfidence: Float, max: Int, iou: Float): List<SceneObj>
        /** The Latin recognizer's blocks on [frame]: the text regions. */
        fun textRegions(frame: Bitmap): List<SceneMath.Block>
        /** One script's read of [frame]; a failure is the script's alone. */
        fun ocr(frame: Bitmap, script: String): Result<List<SceneMath.Block>>
        fun model(detector: String): String
    }

    fun json(image: ByteArray, request: String): String =
        runCatching { scene(image, JSONObject(request)).toString() }.getOrElse { failure(it.message ?: it.javaClass.simpleName).toString() }

    fun benchJson(image: ByteArray, request: String): String =
        runCatching { bench(image, JSONObject(request)).toString() }.getOrElse { failure(it.message ?: it.javaClass.simpleName).toString() }

    private class Sized(val work: Bitmap, val ocr: Bitmap, val ocrScale: Float)

    /** The decoded frame at the detection size, and the (larger, never upscaled) copy OCR reads. */
    private fun sized(loaded: Bitmap, s: JSONObject): Sized {
        val work = fit(loaded, s.optInt("live_side", 640).coerceIn(Detector.MIN_SIDE, ImageScanner.MAX_DECODE_DIMENSION))
        val ocr = fit(loaded, s.optInt("ocr_side", 1280).coerceIn(Detector.MIN_SIDE, ImageScanner.MAX_DECODE_DIMENSION))
        return Sized(work, ocr, work.width.toFloat() / ocr.width)
    }

    fun scene(image: ByteArray, req: JSONObject): JSONObject {
        val s = req.optJSONObject("scene") ?: JSONObject()
        val detector = req.optString("detector", s.optString("default_detector", EFFICIENTDET))
        if (detector !in DETECTORS) return failure("unknown detector $detector (one of $DETECTORS)")
        val t0 = clock()
        val loaded = scanner.loadBitmap(image) ?: return failure(ImageScanner.CANNOT_DECODE)
        val sz = sized(loaded, s)
        try {
            val tDecode = clock() - t0
            val out = JSONObject().put("ok", true).put("mode", "scene").put("route", Recognizer.ML)
                .put("detector", detector).put("model", backends.model(detector))
                .put("width", sz.work.width).put("height", sz.work.height).put("frame_id", req.opt("frame_id") ?: JSONObject.NULL)
            val objs = JSONArray()
            var t = clock()
            if (req.optBoolean("objects", true)) {
                backends.objects(detector, sz.work, s.optDouble("min_confidence", 0.35).toFloat(), s.optInt("max_objects", 10), s.optDouble("nms_iou", 0.45).toFloat())
                    .forEach { objs.put(obj("object", it.label, it.p, it.box)) }
            }
            val tObjects = clock() - t
            t = clock()
            var regions = 0
            if (req.optBoolean("text", false)) {
                backends.textRegions(sz.work).forEach { b -> regions++; objs.put(obj("text", s.optString("text_label", TEXT_LABEL), b.p, b.box)) }
            }
            val tText = clock() - t
            out.put("objects", objs).put("text_present", regions > 0)
            t = clock()
            out.put("ocr", if (req.optBoolean("ocr", false)) ocr(sz, scripts(s)) else JSONObject.NULL)
            val tOcr = clock() - t
            return out.put("timings", JSONObject().put("decode_ms", tDecode).put("objects_ms", tObjects).put("text_ms", tText).put("ocr_ms", tOcr))
                .put("latency_ms", clock() - t0)
        } finally {
            recycle(loaded, sz)
        }
    }

    private fun ocr(sz: Sized, scripts: List<String>): JSONObject {
        val reads = LinkedHashMap<String, List<SceneMath.Block>>()
        val errors = JSONObject()
        for (script in scripts) {
            backends.ocr(sz.ocr, script).fold({ reads[script] = it.map { b -> b.scaled(sz.ocrScale) } }, { errors.put(script, it.message ?: it.javaClass.simpleName) })
        }
        val blocks = SceneMath.mergeScripts(reads, scripts)
        return JSONObject().put("scripts", JSONArray(reads.keys.toList())).put("errors", errors)
            .put("text", blocks.joinToString("\n\n") { it.text })
            .put("blocks", JSONArray().apply { blocks.forEach { put(block(it)) } })
    }

    fun bench(image: ByteArray, req: JSONObject): JSONObject {
        val s = req.optJSONObject("scene") ?: JSONObject()
        val runs = req.optInt("runs", s.optJSONObject("bench")?.optInt("runs", 30) ?: 30).coerceIn(1, 500)
        val warmup = req.optInt("warmup", s.optJSONObject("bench")?.optInt("warmup", 5) ?: 5).coerceIn(0, 100)
        val detectors = req.optJSONArray("detectors")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: DETECTORS
        val loaded = scanner.loadBitmap(image) ?: return failure(ImageScanner.CANNOT_DECODE)
        val sz = sized(loaded, s)
        try {
            val minConfidence = s.optDouble("min_confidence", 0.35).toFloat()
            val out = JSONObject().put("ok", true).put("mode", "scene_bench").put("runs", runs).put("warmup", warmup)
                .put("width", sz.work.width).put("height", sz.work.height)
            val per = JSONObject()
            for (d in detectors) {
                if (d !in DETECTORS) { per.put(d, JSONObject().put("error", "unknown detector")); continue }
                val r = runCatching {
                    repeat(warmup) { backends.objects(d, sz.work, minConfidence, s.optInt("max_objects", 10), s.optDouble("nms_iou", 0.45).toFloat()) }
                    var last = emptyList<SceneObj>()
                    val ms = (1..runs).map {
                        val a = clock()
                        last = backends.objects(d, sz.work, minConfidence, s.optInt("max_objects", 10), s.optDouble("nms_iou", 0.45).toFloat())
                        clock() - a
                    }
                    stats(ms).put("model", backends.model(d))
                        .put("detections", JSONArray().apply { last.forEach { put(obj("object", it.label, it.p, it.box)) } })
                }.getOrElse { JSONObject().put("error", it.message ?: it.javaClass.simpleName) }
                per.put(d, r)
            }
            out.put("detectors", per)
            if (req.optBoolean("ocr", true)) {
                val ms = ArrayList<Long>()
                var text = JSONObject()
                repeat(req.optInt("ocr_runs", 3).coerceIn(1, 20)) {
                    val a = clock(); text = ocr(sz, scripts(s)); ms += clock() - a
                }
                val a = clock(); val regions = backends.textRegions(sz.work); val textMs = clock() - a
                out.put("ocr", stats(ms).put("scripts", text.optJSONArray("scripts")).put("errors", text.optJSONObject("errors"))
                    .put("blocks", text.optJSONArray("blocks")?.length() ?: 0)
                    .put("words", (0 until (text.optJSONArray("blocks")?.length() ?: 0)).sumOf { i ->
                        val ls = text.getJSONArray("blocks").getJSONObject(i).getJSONArray("lines")
                        (0 until ls.length()).sumOf { ls.getJSONObject(it).getJSONArray("words").length() }
                    })
                    .put("text", text.optString("text")).put("text_regions", regions.size).put("text_regions_ms", textMs))
            }
            return out
        } finally {
            recycle(loaded, sz)
        }
    }

    private fun stats(ms: List<Long>): JSONObject {
        val mean = ms.average()
        return JSONObject().put("runs", ms.size).put("mean_ms", mean).put("p50_ms", SceneMath.percentile(ms, 0.5))
            .put("p90_ms", SceneMath.percentile(ms, 0.9)).put("min_ms", ms.minOrNull() ?: 0).put("max_ms", ms.maxOrNull() ?: 0)
            .put("fps", if (mean > 0) 1000.0 / mean else 0.0)
    }

    private fun scripts(s: JSONObject): List<String> =
        s.optJSONArray("scripts")?.let { a -> (0 until a.length()).map { a.getString(it) }.filter { it in SCRIPTS } }?.ifEmpty { null } ?: SCRIPTS

    private fun recycle(loaded: Bitmap, sz: Sized) {
        if (sz.work !== loaded) sz.work.recycle()
        if (sz.ocr !== loaded) sz.ocr.recycle()
        loaded.recycle()
    }

    private fun failure(why: String): JSONObject = JSONObject().put("ok", false).put("mode", "scene").put("route", Recognizer.ML).put("error", why)

    companion object {
        const val EFFICIENTDET = "efficientdet"
        const val YOLO11N = "yolo11n"
        val DETECTORS = listOf(EFFICIENTDET, YOLO11N)
        /** The OCR scripts, in merge order (Latin first: a tie keeps the Latin read). */
        val SCRIPTS = listOf("latin", "chinese", "devanagari", "japanese", "korean")
        const val TEXT_LABEL = "Text"

        fun box(o: JSONObject, r: Rect): JSONObject =
            o.put("x", r.l.toDouble()).put("y", r.t.toDouble()).put("w", r.w.toDouble()).put("h", r.h.toDouble())

        fun obj(cls: String, label: String, p: Float, r: Rect): JSONObject = box(JSONObject().put("cls", cls).put("label", label).put("p", p.toDouble()), r)

        fun block(b: SceneMath.Block): JSONObject = box(JSONObject().put("text", b.text).put("script", b.script).put("lang", b.lang).put("p", b.p.toDouble()), b.box)
            .put("lines", JSONArray().apply {
                b.lines.forEach { l ->
                    put(box(JSONObject().put("text", l.text).put("p", l.p.toDouble()), l.box)
                        .put("words", JSONArray().apply { l.words.forEach { w -> put(box(JSONObject().put("text", w.text).put("p", w.p.toDouble()), w.box)) } }))
                }
            })
    }
}
