package com.diegonmarcos.hardtechcad

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * #1631 the render diagnostics of the CAD viewer: pure JVM (no Android class), so the whole chain from a bundled GLB to the report is unit-tested.
 * Nothing here is a proxy: [GlbInspector] reads the real asset bytes, [CadDiag] holds what the real viewer page reported, and [XrayReport] only
 * formats what the probes measured (the EGL/GLES probe and the viewer's own `window.cadDiag()` are the two live sources, read in ui/XrayPage.kt).
 */
object GlbInspector {
    /** What reading one GLB found. [ok] false names the failed check in [error]; the other fields hold whatever was read before it failed. */
    data class Result(
        val ok: Boolean,
        val error: String = "",
        val bytes: Long = 0,
        val sha256: String = "",
        val version: Int = 0,
        val declaredLength: Long = 0,
        val jsonBytes: Long = 0,
        val binBytes: Long = 0,
        val meshes: Int = 0,
        val vertices: Long = 0,
        val triangles: Long = 0,
        val boundsMin: List<Double> = emptyList(),
        val boundsMax: List<Double> = emptyList(),
        val extensionsRequired: List<String> = emptyList(),
        val extensionsUsed: List<String> = emptyList(),
    )

    private const val MAX_JSON = 64L * 1024 * 1024

    /** The same checks as tools/check_models.py (glTF 2.0 binary, JSON + BIN chunks, buffer views inside the BIN, no extension the viewer cannot decode, triangles > 0). */
    fun inspect(input: InputStream): Result {
        val md = MessageDigest.getInstance("SHA-256")
        val din = DataInputStream(DigestInputStream(input, md))
        var total = 0L
        fun hex() = md.digest().joinToString("") { "%02x".format(it) }
        try {
            val head = ByteArray(12)
            val got = readFully(din, head)
            total += got
            if (got < 12) return Result(false, "shorter than a GLB header ($got bytes)", bytes = total)
            if (String(head, 0, 4, Charsets.ISO_8859_1).startsWith("vers")) return Result(false, "an LFS pointer, not the file", bytes = total)
            if (String(head, 0, 4, Charsets.ISO_8859_1) != "glTF") return Result(false, "not a GLB (bad magic)", bytes = total)
            val version = le32(head, 4).toInt()
            val declared = le32(head, 8)
            if (version != 2) return Result(false, "glTF version $version, not 2", bytes = total, version = version, declaredLength = declared)
            val ch = ByteArray(8)
            val g2 = readFully(din, ch)
            total += g2
            if (g2 < 8) return Result(false, "no JSON chunk", bytes = total, version = version, declaredLength = declared)
            val jlen = le32(ch, 0)
            if (le32(ch, 4) != 0x4E4F534AL) return Result(false, "first chunk is not JSON", bytes = total, version = version, declaredLength = declared)
            if (jlen > MAX_JSON) return Result(false, "JSON chunk of $jlen bytes is not credible", bytes = total, version = version, declaredLength = declared)
            val jb = ByteArray(jlen.toInt())
            val g3 = readFully(din, jb)
            total += g3
            if (g3 < jlen) return Result(false, "truncated inside the JSON chunk ($g3 of $jlen bytes)", bytes = total, version = version, declaredLength = declared)
            // the BIN chunk header, then drain the rest (counting, hashing)
            val bh = ByteArray(8)
            val g4 = readFully(din, bh)
            total += g4
            var bin = 0L
            if (g4 == 8 && le32(bh, 4) == 0x004E4942L) bin = le32(bh, 0)
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = din.read(buf)
                if (n < 0) break
                total += n
            }
            val digest = hex()
            val base = Result(false, bytes = total, sha256 = digest, version = version, declaredLength = declared, jsonBytes = jlen, binBytes = bin)
            if (declared != total) return base.copy(error = "header says $declared bytes but the file has $total (truncated?)")
            val j = try { JSONObject(String(jb, Charsets.UTF_8)) } catch (e: Exception) { return base.copy(error = "JSON chunk does not parse: ${e.message}") }
            val req = strs(j.optJSONArray("extensionsRequired"))
            val used = strs(j.optJSONArray("extensionsUsed"))
            val withExt = base.copy(extensionsRequired = req, extensionsUsed = used)
            if (req.isNotEmpty()) return withExt.copy(error = "requires ${req.joinToString(", ")}, which the viewer cannot decode")
            for (k in listOf("KHR_draco_mesh_compression", "EXT_meshopt_compression"))
                if (k in used) return withExt.copy(error = "uses $k, which the viewer does not decode")
            val views = j.optJSONArray("bufferViews") ?: JSONArray()
            for (i in 0 until views.length()) {
                val v = views.getJSONObject(i)
                if (v.optLong("byteOffset", 0) + v.getLong("byteLength") > bin) return withExt.copy(error = "bufferView $i runs past the BIN chunk")
            }
            val acc = j.optJSONArray("accessors") ?: JSONArray()
            var verts = 0L
            var tris = 0L
            val lo = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
            val hi = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
            val meshes = j.optJSONArray("meshes") ?: JSONArray()
            for (mi in 0 until meshes.length()) {
                val prims = meshes.getJSONObject(mi).optJSONArray("primitives") ?: continue
                for (pi in 0 until prims.length()) {
                    val p = prims.getJSONObject(pi)
                    if (p.optInt("mode", 4) != 4) continue
                    val pa = acc.getJSONObject(p.getJSONObject("attributes").getInt("POSITION"))
                    verts += pa.getLong("count")
                    tris += (if (p.has("indices")) acc.getJSONObject(p.getInt("indices")).getLong("count") else pa.getLong("count")) / 3
                    val mn = pa.optJSONArray("min"); val mx = pa.optJSONArray("max")
                    if (mn == null || mx == null || mn.length() < 3 || mx.length() < 3) return withExt.copy(error = "a POSITION accessor has no min/max")
                    for (k in 0..2) {
                        val a = mn.getDouble(k); val b = mx.getDouble(k)
                        if (a.isNaN() || b.isNaN() || a.isInfinite() || b.isInfinite()) return withExt.copy(error = "a POSITION accessor has no finite min/max")
                        lo[k] = minOf(lo[k], a); hi[k] = maxOf(hi[k], b)
                    }
                }
            }
            val r = withExt.copy(meshes = meshes.length(), vertices = verts, triangles = tris, boundsMin = lo.toList(), boundsMax = hi.toList())
            if (tris <= 0) return r.copy(error = "parses but holds $tris triangles")
            if ((0..2).none { hi[it] > lo[it] }) return r.copy(error = "every vertex is at one point")
            return r.copy(ok = true)
        } catch (e: Exception) {
            return Result(false, "reading failed: ${e.javaClass.simpleName}: ${e.message}", bytes = total)
        }
    }

    private fun readFully(i: InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) break; n += r }
        return n
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun strs(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
}

/** One model's static asset check: found, bytes, parse, hash against the declaration, the path actually used. */
data class ModelAsset(
    val id: String,
    val name: String,
    val format: String,
    /** `mesh` / `brep` / ... or `store-only`. */
    val route: String,
    val bundled: Boolean,
    /** Path under assets/ (bundled) or the repo-relative path fetched at runtime. */
    val path: String,
    /** The URL the viewer page is given for it (bundled: the asset-loader URL; else the cache handler URL). */
    val url: String,
    val declaredSize: Long,
    val declaredSha256: String,
    val found: Boolean,
    val inspect: GlbInspector.Result?,
    val note: String = "",
) {
    val hashOk: Boolean? get() = inspect?.takeIf { it.sha256.isNotEmpty() }?.let { declaredSha256.isEmpty() || it.sha256 == declaredSha256 }
    val sizeOk: Boolean? get() = inspect?.let { declaredSize <= 0 || it.bytes == declaredSize }
}

/** What the viewer sessions of this process reported for one model (the host side of the bridge: ready / shown / painted / error events). */
data class LiveRec(
    val id: String,
    val name: String,
    val lib: String,
    val format: String,
    val url: String,
    val state: String,
    val events: List<String>,
    val shownMs: Long = -1,
    val triangles: Long = -1,
    val paintedPct: Double = -1.0,
    val paintedGl: String = "",
    val error: String = "",
    val viewW: Int = -1,
    val viewH: Int = -1,
    val viewShown: Boolean? = null,
    val viewHwAccel: Boolean? = null,
    /** The last `window.cadDiag()` of that viewer page (JSON text) and when it was read. */
    val js: String = "",
    val jsAt: Long = 0,
    val beganAt: Long = 0,
)

/** The process-wide record of the viewer sessions. Written by ui/ModelViewer.kt, read by the X-Ray App page. */
object CadDiag {
    const val PROBE_ID = "(probe)"
    var clock: () -> Long = { System.currentTimeMillis() }
    private val recs = LinkedHashMap<String, LiveRec>()
    @Volatile var version: Int = 0
        private set

    @Synchronized fun begin(id: String, name: String, lib: String, format: String, url: String) {
        val t = clock()
        recs[id] = LiveRec(id, name, lib, format, url, "opening", listOf("0 ms  cadOpen $url"), beganAt = t)
        version++
    }

    @Synchronized private fun edit(id: String, f: (LiveRec) -> LiveRec) {
        val r = recs[id] ?: LiveRec(id, id, "", "", "", "no session", emptyList(), beganAt = clock())
        recs[id] = f(r)
        version++
    }

    private fun LiveRec.log(text: String): LiveRec {
        val e = (events + "${clock() - beganAt} ms  $text").takeLast(40)
        return copy(events = e)
    }

    @Synchronized fun event(id: String, text: String) = edit(id) { it.log(text) }
    @Synchronized fun state(id: String, s: String) = edit(id) { it.copy(state = s).log("state $s") }
    @Synchronized fun shown(id: String, triangles: Long) = edit(id) { it.copy(state = "shown", triangles = triangles, shownMs = clock() - it.beganAt).log("shown, $triangles triangles") }
    @Synchronized fun painted(id: String, pct: Double, w: Int, h: Int, gl: String) =
        edit(id) { it.copy(paintedPct = pct, paintedGl = gl).log("painted $pct% of ${w}x$h ($gl)") }
    @Synchronized fun failed(id: String, why: String) = edit(id) { it.copy(state = "failed", error = why).log("error: $why") }
    @Synchronized fun view(id: String, w: Int, h: Int, shown: Boolean, hw: Boolean) = edit(id) { it.copy(viewW = w, viewH = h, viewShown = shown, viewHwAccel = hw) }
    @Synchronized fun js(id: String, json: String) = edit(id) { it.copy(js = json, jsAt = clock()) }

    @Synchronized fun snapshot(): List<LiveRec> = recs.values.toList()
    @Synchronized fun clear() { recs.clear(); version++ }
}

/** One step of the pipeline in the verdict: OK, WARN (works, but not as a GPU would) or FAIL, with the real detail that decided it. */
data class Stage(val name: String, val status: String, val detail: String)

/** The EGL/GLES probe's outcome (read on a pbuffer context of its own); [fields] are the facts it read, in order. */
data class GlProbe(val ran: Boolean, val ok: Boolean, val stage: String, val error: String, val fields: List<Pair<String, String>>, val software: Boolean = false)

/** Everything the X-Ray App page measured at one moment. */
data class XraySnapshot(
    val takenAt: String,
    val app: List<Pair<String, String>>,
    val device: List<Pair<String, String>>,
    val egl: GlProbe,
    val webview: List<Pair<String, String>>,
    /** The newest `window.cadDiag()` JSON of any viewer page (probe or model viewer), "" when no page has answered. */
    val viewerJs: String,
    val viewerJsAgeMs: Long,
    val models: List<ModelAsset>,
    val live: List<LiveRec>,
)

object XrayReport {
    private fun js(s: String): JSONObject? = runCatching { JSONObject(s) }.getOrNull()

    /** The pipeline in order: the first FAIL is where a model stops rendering. */
    fun stages(s: XraySnapshot): List<Stage> {
        val out = mutableListOf<Stage>()
        // 1 the device's own GPU through EGL
        out += if (!s.egl.ran) Stage("1 EGL / GLES context", "WARN", "not probed yet")
        else if (!s.egl.ok) Stage("1 EGL / GLES context", "FAIL", "stopped at ${s.egl.stage}: ${s.egl.error}")
        else if (s.egl.software) Stage("1 EGL / GLES context", "WARN", "context created, but the renderer is a SOFTWARE rasteriser (${s.egl.fields.firstOrNull { it.first == "GL_RENDERER" }?.second.orEmpty()}): a 250k-triangle model will be slow or fail")
        else Stage("1 EGL / GLES context", "OK", "hardware context: ${s.egl.fields.firstOrNull { it.first == "GL_RENDERER" }?.second.orEmpty()}")
        // 2 the WebView engine
        val wv = s.webview.firstOrNull { it.first == "WebView package" }?.second.orEmpty()
        out += if (wv.isBlank() || wv.startsWith("none")) Stage("2 WebView engine", "FAIL", "no WebView package is installed or enabled (${wv.ifBlank { "unknown" }})")
        else Stage("2 WebView engine", "OK", wv)
        // 3.. the page itself
        val d = js(s.viewerJs)
        if (d == null) {
            out += Stage("3 viewer page", "FAIL", "no viewer page has answered window.cadDiag(): the page did not load, or its script died before the diagnostics script ran (see the probe viewer below)")
        } else {
            val pr = d.optJSONObject("probe")
            val perr = pr?.optString("error").orEmpty()
            out += if (pr == null || perr.isNotEmpty()) Stage("3 WebGL context in the WebView", "FAIL", perr.ifEmpty { "no probe result" } + attempts(pr))
            else if (!pr.optBoolean("drawOk")) Stage("3 WebGL context in the WebView", "FAIL", "a ${pr.optString("kind")} context was created but a clear + readPixels did not return the clear colour (readback ${pr.optJSONArray("readback")})")
            else if (!pr.optBoolean("hardware")) Stage("3 WebGL context in the WebView", "WARN", "${pr.optString("kind")} created and draws, but only as a software context (failIfMajorPerformanceCaveat refused): ${pr.optString("unmaskedRenderer").ifEmpty { pr.optString("renderer") }}")
            else Stage("3 WebGL context in the WebView", "OK", "${pr.optString("kind")} hardware, ${pr.optString("unmaskedRenderer").ifEmpty { pr.optString("renderer") }}, max texture ${pr.optInt("maxTexture")}, uint32 indices ${pr.optBoolean("uint32Index")}")
            out += if (!d.optBoolean("moduleLoaded")) Stage("4 viewer module (three.js)", "FAIL", "viewer.js did not run: ${d.optString("lastError").ifEmpty { "no error text; stage '${d.optString("stage")}'" }}")
            else if (!d.optBoolean("rendererCreated")) Stage("4 viewer module (three.js)", "FAIL", "three.js WebGLRenderer could not be created: ${d.optString("lastError")}")
            else if (!d.optBoolean("ready")) Stage("4 viewer module (three.js)", "FAIL", "renderer created but the page never became ready (stage '${d.optString("stage")}')")
            else Stage("4 viewer module (three.js)", "OK", "module loaded, WebGLRenderer created, ready at ${d.optLong("stageMs")} ms")
            if (d.optBoolean("contextLost")) out += Stage("4b GL context", "FAIL", "the WebGL context is LOST (restored ${d.optInt("restored")} times)")
        }
        // 5 assets
        val bundled = s.models.filter { it.bundled }
        val badAsset = bundled.firstOrNull { !it.found || it.inspect?.ok != true || it.hashOk == false || it.sizeOk == false }
        out += if (bundled.isEmpty()) Stage("5 bundled model assets", "WARN", "no model is bundled in the APK")
        else if (badAsset != null) Stage("5 bundled model assets", "FAIL", "${badAsset.id}: " + assetProblem(badAsset))
        else Stage("5 bundled model assets", "OK", "${bundled.size} bundled, every one found, parsed (glTF 2.0, triangles > 0) and equal to its declared size and sha256")
        // 6 the load/draw of what was actually opened
        val opened = s.live.filter { it.id != CadDiag.PROBE_ID }
        if (opened.isEmpty()) out += Stage("6 model load and draw", "WARN", "no model has been opened in this process yet: open one in the Live test below or on a model page")
        for (r in opened) {
            val painted = r.paintedPct
            out += if (r.error.isNotEmpty()) Stage("6 ${r.id} load and draw", "FAIL", "state ${r.state}: ${r.error}")
            else if (r.state != "shown") Stage("6 ${r.id} load and draw", "FAIL", "stuck in state '${r.state}' (${r.events.lastOrNull().orEmpty()})")
            else if (painted in 0.0..0.1) Stage("6 ${r.id} load and draw", "FAIL", "loaded ${r.triangles} triangles but the canvas painted ${painted}% (nothing visible), GL ${r.paintedGl}")
            else Stage("6 ${r.id} load and draw", "OK", "shown in ${r.shownMs} ms, ${r.triangles} triangles" + (if (painted >= 0) ", ${painted}% of the canvas painted" else ""))
            if (r.viewW == 0 || r.viewH == 0) out += Stage("6 ${r.id} surface", "FAIL", "the WebView is ${r.viewW}x${r.viewH}: a zero-sized surface draws nothing")
            if (r.viewShown == false) out += Stage("6 ${r.id} surface", "FAIL", "the WebView is not shown (invisible or detached)")
            if (r.viewHwAccel == false) out += Stage("6 ${r.id} surface", "FAIL", "the WebView is not hardware accelerated")
        }
        return out
    }

    private fun attempts(pr: JSONObject?): String {
        val a = pr?.optJSONArray("attempts") ?: return ""
        return " [attempts: " + (0 until a.length()).joinToString("; ") { a.optString(it) } + "]"
    }

    private fun assetProblem(a: ModelAsset): String {
        val i = a.inspect
        return when {
            !a.found -> "asset ${a.path} NOT FOUND in the APK${if (a.note.isNotEmpty()) " (${a.note})" else ""}"
            i == null -> "asset not read"
            i.ok.not() -> "asset ${a.path} (${i.bytes} bytes) does not parse: ${i.error}"
            a.sizeOk == false -> "asset ${a.path} is ${i.bytes} bytes, declared ${a.declaredSize}"
            a.hashOk == false -> "asset ${a.path} sha256 ${i.sha256.take(12)}.. is not the declared ${a.declaredSha256.take(12)}.."
            else -> "ok"
        }
    }

    /** The first failing stage, as one line (or the all-clear). */
    fun verdict(s: XraySnapshot): String {
        val st = stages(s)
        val f = st.firstOrNull { it.status == "FAIL" }
        return if (f != null) "FAILED at stage ${f.name}: ${f.detail}"
        else if (st.any { it.status == "WARN" }) "No stage failed; warnings: " + st.filter { it.status == "WARN" }.joinToString(" | ") { it.name }
        else "Every stage of the render pipeline passed."
    }

    fun markdown(s: XraySnapshot): String {
        val b = StringBuilder()
        b.append("# Hardtech-CAD X-Ray App: render diagnostics\n\n")
        b.append("Taken ${s.takenAt}\n\n**Verdict:** ${verdict(s)}\n\n")
        b.append("## Pipeline stages\n\n| Stage | Status | Detail |\n|---|---|---|\n")
        stages(s).forEach { b.append("| ${it.name} | ${it.status} | ${it.detail.replace("|", "/").replace("\n", " ")} |\n") }
        fun kv(title: String, l: List<Pair<String, String>>) {
            b.append("\n## $title\n\n")
            l.forEach { b.append("- ${it.first}: ${it.second}\n") }
        }
        kv("App", s.app)
        kv("Device", s.device)
        kv("EGL / GLES (a pbuffer context made now on this device)", (if (s.egl.ran) listOf("probe" to (if (s.egl.ok) "context created" else "FAILED at ${s.egl.stage}: ${s.egl.error}")) else emptyList()) + s.egl.fields)
        kv("WebView", s.webview)
        b.append("\n## Viewer page (window.cadDiag(), read ${s.viewerJsAgeMs} ms ago)\n\n")
        val d = js(s.viewerJs)
        if (d == null) b.append("No viewer page has answered.\n") else b.append("```json\n${d.toString(2)}\n```\n")
        b.append("\n## Model assets\n\n| Model | Route | Bundled | Found | Bytes | Declared | Parse | Triangles | Hash | Path | URL |\n|---|---|---|---|---|---|---|---|---|---|---|\n")
        s.models.forEach { m ->
            val i = m.inspect
            b.append("| ${m.id} | ${m.route} | ${m.bundled} | ${m.found} | ${i?.bytes ?: "-"} | ${m.declaredSize} | ${if (i == null) "-" else if (i.ok) "ok" else "FAIL: " + i.error} | ${i?.triangles ?: "-"} | ${m.hashOk ?: "-"} | ${m.path} | ${m.url} |\n")
        }
        b.append("\n## Viewer sessions in this process\n\n")
        if (s.live.isEmpty()) b.append("None.\n")
        s.live.forEach { r ->
            b.append("### ${r.id}\n\n- state ${r.state}, shown after ${r.shownMs} ms, ${r.triangles} triangles, painted ${r.paintedPct}% (${r.paintedGl})\n")
            b.append("- WebView ${r.viewW}x${r.viewH}, shown ${r.viewShown}, hardware accelerated ${r.viewHwAccel}\n- error: ${r.error.ifEmpty { "none" }}\n")
            r.events.forEach { b.append("  - $it\n") }
        }
        return b.toString()
    }

    fun json(s: XraySnapshot): String {
        fun kv(l: List<Pair<String, String>>) = JSONObject().also { o -> l.forEach { o.put(it.first, it.second) } }
        val o = JSONObject()
        o.put("report", "hardtech-cad xray app").put("takenAt", s.takenAt).put("verdict", verdict(s))
        o.put("stages", JSONArray().also { a -> stages(s).forEach { a.put(JSONObject().put("name", it.name).put("status", it.status).put("detail", it.detail)) } })
        o.put("app", kv(s.app)).put("device", kv(s.device)).put("webview", kv(s.webview))
        o.put("egl", JSONObject().put("ran", s.egl.ran).put("ok", s.egl.ok).put("stage", s.egl.stage).put("error", s.egl.error).put("software", s.egl.software).put("fields", kv(s.egl.fields)))
        o.put("viewerJs", js(s.viewerJs) ?: JSONObject.NULL).put("viewerJsAgeMs", s.viewerJsAgeMs)
        o.put("models", JSONArray().also { a ->
            s.models.forEach { m ->
                val i = m.inspect
                a.put(JSONObject().put("id", m.id).put("name", m.name).put("format", m.format).put("route", m.route).put("bundled", m.bundled).put("path", m.path).put("url", m.url)
                    .put("found", m.found).put("declaredSize", m.declaredSize).put("declaredSha256", m.declaredSha256).put("note", m.note)
                    .put("hashOk", m.hashOk ?: JSONObject.NULL).put("sizeOk", m.sizeOk ?: JSONObject.NULL)
                    .put("inspect", if (i == null) JSONObject.NULL else JSONObject().put("ok", i.ok).put("error", i.error).put("bytes", i.bytes).put("sha256", i.sha256)
                        .put("version", i.version).put("jsonBytes", i.jsonBytes).put("binBytes", i.binBytes).put("meshes", i.meshes).put("vertices", i.vertices).put("triangles", i.triangles)
                        .put("boundsMin", JSONArray(i.boundsMin)).put("boundsMax", JSONArray(i.boundsMax))
                        .put("extensionsRequired", JSONArray(i.extensionsRequired)).put("extensionsUsed", JSONArray(i.extensionsUsed))))
            }
        })
        o.put("live", JSONArray().also { a ->
            s.live.forEach { r ->
                a.put(JSONObject().put("id", r.id).put("state", r.state).put("lib", r.lib).put("format", r.format).put("url", r.url).put("shownMs", r.shownMs).put("triangles", r.triangles)
                    .put("paintedPct", r.paintedPct).put("paintedGl", r.paintedGl).put("error", r.error).put("viewW", r.viewW).put("viewH", r.viewH)
                    .put("viewShown", r.viewShown ?: JSONObject.NULL).put("viewHwAccel", r.viewHwAccel ?: JSONObject.NULL).put("events", JSONArray(r.events)))
            }
        })
        return o.toString(2)
    }
}
