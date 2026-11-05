package com.diegonmarcos.superapp.ocr

import org.json.JSONArray
import org.json.JSONObject

/** Everything the `<name>.json` says besides the OCR itself; the app fills it from the file, MediaStore and the device. */
data class ScreenshotMeta(
    val name: String, val path: String, val size: Long, val sha256: String, val md5: String?, val sha1: String?, val mime: String,
    val width: Int, val height: Int, val createdMs: Long?, val modifiedMs: Long, val takenMs: Long?,
    val exif: Map<String, String>, val xmp: String?,
    val mediaStore: Map<String, Any?>, val sourceApp: String?,
    val device: Map<String, Any?>,
    val colours: List<Pair<String, Double>>,
    val labels: List<Pair<String, Double>>,
    val engines: Map<String, String>,
    val processedMs: Long,
)

object SidecarJson {
    const val SCHEMA = "cloud-drive.screenshot-ocr/1"
    val REQUIRED = listOf("schema", "file", "exif", "mediastore", "device", "colours", "ocr", "labels", "engines", "processed_ms")

    fun build(m: ScreenshotMeta, r: OcrResult): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("file", JSONObject().apply {
            put("name", m.name); put("path", m.path); put("size", m.size); put("mime", m.mime)
            put("sha256", m.sha256); m.sha1?.let { put("sha1", it) }; m.md5?.let { put("md5", it) }
            put("width", m.width); put("height", m.height)
            m.createdMs?.let { put("created_ms", it) }; put("modified_ms", m.modifiedMs)
        })
        put("exif", JSONObject(m.exif))
        m.xmp?.let { put("xmp", it) }
        put("mediastore", JSONObject().apply { m.mediaStore.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }; m.takenMs?.let { put("captured_ms", it) } })
        m.sourceApp?.let { put("source_app", it) }
        put("device", JSONObject().apply { m.device.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
        put("colours", JSONArray(m.colours.map { (hex, share) -> JSONObject().put("hex", hex).put("share", round(share)) }))
        put("ocr", ocr(r))
        put("labels", JSONArray(m.labels.map { (l, p) -> JSONObject().put("label", l).put("confidence", round(p)) }))
        put("engines", JSONObject(m.engines))
        put("processed_ms", m.processedMs)
    }

    fun ocr(r: OcrResult): JSONObject = JSONObject().apply {
        put("engine", r.engine); put("engine_version", r.engineVersion)
        put("width", r.width); put("height", r.height)
        r.meanConfidence?.let { put("mean_confidence", round(it)) }
        put("languages", JSONArray(r.languages))
        put("text", r.blocks.joinToString("\n") { it.text })
        put("blocks", JSONArray(r.blocks.map { b ->
            JSONObject().put("text", b.text).put("bbox", JSONArray(b.box.list())).put("lines", JSONArray(b.lines.map { line(it) }))
        }))
        put("tables", JSONArray(r.tables.map { t ->
            JSONObject().put("bbox", JSONArray(t.box.list())).put("rows", t.rows.size).put("columns", t.columns).put("cells", JSONArray(t.rows.flatten().map { c ->
                JSONObject().put("row", c.row).put("col", c.col).put("text", c.text).put("bbox", JSONArray(c.box.list()))
            }))
        }))
    }

    private fun line(l: OcrLine) = JSONObject().put("text", l.text).put("bbox", JSONArray(l.box.list())).apply {
        l.confidence?.let { put("confidence", round(it)) }
        put("words", JSONArray(l.words.map { w -> JSONObject().put("text", w.text).put("bbox", JSONArray(w.box.list())).put("estimated", w.estimated).apply { w.confidence?.let { c -> put("confidence", round(c)) } } }))
    }

    private fun round(d: Double) = Math.round(d * 10000.0) / 10000.0

    /** Problems with a sidecar document; empty = it matches the schema. */
    fun validate(o: JSONObject): List<String> {
        val p = ArrayList<String>()
        REQUIRED.filter { !o.has(it) }.forEach { p.add("missing $it") }
        if (o.optString("schema") != SCHEMA) p.add("schema")
        o.optJSONObject("file")?.let { f ->
            listOf("name", "path", "size", "sha256", "mime", "width", "height", "modified_ms").filter { !f.has(it) }.forEach { p.add("file.$it") }
            if (f.optString("sha256").length != 64) p.add("file.sha256 length")
        }
        o.optJSONObject("ocr")?.let { c ->
            listOf("engine", "languages", "blocks", "tables", "text").filter { !c.has(it) }.forEach { p.add("ocr.$it") }
            val blocks = c.optJSONArray("blocks") ?: JSONArray()
            for (i in 0 until blocks.length()) {
                val b = blocks.getJSONObject(i)
                if (b.optJSONArray("bbox")?.length() != 4) p.add("blocks[$i].bbox")
                val ls = b.optJSONArray("lines") ?: JSONArray()
                for (j in 0 until ls.length()) if (!ls.getJSONObject(j).has("words")) p.add("blocks[$i].lines[$j].words")
            }
            val ts = c.optJSONArray("tables") ?: JSONArray()
            for (i in 0 until ts.length()) {
                val cells = ts.getJSONObject(i).optJSONArray("cells") ?: JSONArray()
                for (j in 0 until cells.length()) if (!cells.getJSONObject(j).let { it.has("row") && it.has("col") }) p.add("tables[$i].cells[$j]")
            }
        }
        return p
    }
}
