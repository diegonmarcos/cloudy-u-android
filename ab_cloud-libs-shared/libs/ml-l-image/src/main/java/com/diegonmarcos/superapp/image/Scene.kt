package com.diegonmarcos.superapp.image.mlkit

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1805 the engine's `scene` answer (contract 4) for Cloud Camera > Objects: the objects of one
 * frame (text regions among them, class [TEXT]), and, when the request asked for it, the frame's
 * OCR layout: blocks, lines and words, each with its box. Every box is in the pixels of a
 * [width] x [height] frame. [timings] is the engine's own split (decode, objects, text, ocr).
 */
data class Scene(
    val ok: Boolean,
    val error: String?,
    val detector: String,
    val model: String,
    val width: Int,
    val height: Int,
    val objects: List<Obj>,
    val textPresent: Boolean,
    val ocr: Ocr?,
    val timings: Map<String, Long>,
    val latencyMs: Long,
    val frameId: Long?,
) {
    data class Obj(val cls: String, val label: String, val p: Double, val x: Float, val y: Float, val w: Float, val h: Float) {
        val isText: Boolean get() = cls == TEXT
    }
    data class Word(val text: String, val p: Double, val x: Float, val y: Float, val w: Float, val h: Float)
    data class Line(val text: String, val p: Double, val x: Float, val y: Float, val w: Float, val h: Float, val words: List<Word>)
    data class Block(val text: String, val script: String, val lang: String, val p: Double, val x: Float, val y: Float, val w: Float, val h: Float, val lines: List<Line>)
    data class Ocr(val scripts: List<String>, val errors: Map<String, String>, val text: String, val blocks: List<Block>)

    companion object {
        const val TEXT = "text"
        const val OBJECT = "object"

        fun failed(why: String) = Scene(false, why, "", "", 0, 0, emptyList(), false, null, emptyMap(), 0, null)

        private fun objs(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }

        private fun f(o: JSONObject, k: String) = o.optDouble(k, 0.0).toFloat()

        fun parse(json: String): Scene {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return failed("the image engine answered with something that is not JSON")
            val err = o.optString("error").takeIf { !o.isNull("error") && it.isNotBlank() }
            if (err != null || !o.optBoolean("ok", false)) return failed(err ?: "the image engine did not answer scene")
            val ocr = o.optJSONObject("ocr")?.let { r ->
                Ocr(
                    scripts = r.optJSONArray("scripts")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                    errors = r.optJSONObject("errors")?.let { e -> e.keys().asSequence().associateWith { e.optString(it) } }.orEmpty(),
                    text = r.optString("text"),
                    blocks = objs(r.optJSONArray("blocks")).map { b ->
                        Block(b.optString("text"), b.optString("script"), b.optString("lang"), b.optDouble("p", 0.0), f(b, "x"), f(b, "y"), f(b, "w"), f(b, "h"),
                            objs(b.optJSONArray("lines")).map { l ->
                                Line(l.optString("text"), l.optDouble("p", 0.0), f(l, "x"), f(l, "y"), f(l, "w"), f(l, "h"),
                                    objs(l.optJSONArray("words")).map { w -> Word(w.optString("text"), w.optDouble("p", 0.0), f(w, "x"), f(w, "y"), f(w, "w"), f(w, "h")) })
                            })
                    },
                )
            }
            return Scene(
                ok = true, error = null,
                detector = o.optString("detector"), model = o.optString("model"),
                width = o.optInt("width"), height = o.optInt("height"),
                objects = objs(o.optJSONArray("objects")).map { Obj(it.optString("cls", OBJECT), it.optString("label"), it.optDouble("p", 0.0), f(it, "x"), f(it, "y"), f(it, "w"), f(it, "h")) },
                textPresent = o.optBoolean("text_present"),
                ocr = ocr,
                timings = o.optJSONObject("timings")?.let { t -> t.keys().asSequence().associateWith { t.optLong(it) } }.orEmpty(),
                latencyMs = o.optLong("latency_ms"),
                frameId = if (o.isNull("frame_id") || !o.has("frame_id")) null else o.optLong("frame_id"),
            )
        }

        /** recognition.json's scene block. */
        fun decl(decl: JSONObject = RecognitionConfig.json): JSONObject = decl.getJSONObject("scene")

        /** Detector id → its label, in declared order. */
        fun detectors(decl: JSONObject = RecognitionConfig.json): Map<String, String> =
            decl(decl).getJSONObject("detectors").let { d -> d.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { d.getString(it) } }

        fun defaultDetector(decl: JSONObject = RecognitionConfig.json): String = decl(decl).getString("default_detector")

        /**
         * One scene request: [detector] (one of [detectors]), whether to find [objects], text
         * regions ([text]) and the full OCR layout ([ocr]), and the declaration's scene block.
         * [frameId] comes back in the answer, so a caller ties a layout to the frame it read.
         */
        fun request(detector: String, objects: Boolean, text: Boolean, ocr: Boolean, frameId: Long? = null, decl: JSONObject = RecognitionConfig.json): JSONObject {
            require(detector in detectors(decl)) { "unknown scene detector $detector (one of ${detectors(decl).keys})" }
            return JSONObject().put("detector", detector).put("objects", objects).put("text", text).put("ocr", ocr)
                .put("frame_id", frameId ?: JSONObject.NULL).put("scene", JSONObject(decl(decl).toString()))
        }

        /** The scene_bench request: every detector, the declared runs, the OCR timed too. */
        fun benchRequest(runs: Int? = null, decl: JSONObject = RecognitionConfig.json): JSONObject {
            val s = decl(decl)
            val b = s.optJSONObject("bench") ?: JSONObject()
            return JSONObject().put("detectors", JSONArray(detectors(decl).keys.toList())).put("runs", runs ?: b.optInt("runs", 30))
                .put("warmup", b.optInt("warmup", 5)).put("ocr_runs", b.optInt("ocr_runs", 3)).put("ocr", true).put("scene", JSONObject(s.toString()))
        }
    }
}
