package com.diegonmarcos.hardtechcad

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** #1631 the render diagnostics (GlbInspector, CadDiag, XrayReport) and #1632 the Drone model: real files, real parse, no mocks of the pipeline. */
class DiagTest {
    private val app = listOf(File("."), File("app")).first { File(it, "src/main/assets/cad/models.json").isFile }
    private val assets = File(app, "src/main/assets")

    private fun glb(json: String, bin: ByteArray, declaredExtra: Int = 0): ByteArray {
        val j = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val b = bin + ByteArray((4 - bin.size % 4) % 4)
        val total = 12 + 8 + j.size + 8 + b.size
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("glTF".toByteArray()).putInt(2).putInt(total + declaredExtra)
        bb.putInt(j.size).putInt(0x4E4F534A).put(j)
        bb.putInt(b.size).putInt(0x004E4942).put(b)
        return bb.array()
    }

    private val tri = """{"asset":{"version":"2.0"},"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],
        "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},{"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],
        "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],"buffers":[{"byteLength":44}]}"""

    @Test fun inspectorAcceptsAValidGlbAndCountsItsTriangles() {
        val r = GlbInspector.inspect(ByteArrayInputStream(glb(tri, ByteArray(44))))
        assertTrue(r.error, r.ok)
        assertEquals(1, r.triangles)
        assertEquals(3, r.vertices)
        assertEquals(64, r.sha256.length)
    }

    @Test fun inspectorNamesEachFailedCheck() {
        assertEquals("not a GLB (bad magic)", GlbInspector.inspect(ByteArrayInputStream("this is not a glb file at all".toByteArray())).error)
        assertTrue(GlbInspector.inspect(ByteArrayInputStream("version https://git-lfs.github.com/spec/v1\noid sha256:00\n".toByteArray())).error.contains("LFS pointer"))
        assertTrue(GlbInspector.inspect(ByteArrayInputStream(ByteArray(5))).error.contains("shorter"))
        val cut = glb(tri, ByteArray(44)).copyOf(60)
        assertTrue(GlbInspector.inspect(ByteArrayInputStream(cut)).error, GlbInspector.inspect(ByteArrayInputStream(cut)).let { !it.ok && it.error.isNotEmpty() })
        assertTrue(GlbInspector.inspect(ByteArrayInputStream(glb(tri, ByteArray(44), declaredExtra = 8))).error.contains("header says"))
        val draco = tri.replace("\"asset\"", "\"extensionsUsed\":[\"KHR_draco_mesh_compression\"],\"asset\"")
        assertTrue(GlbInspector.inspect(ByteArrayInputStream(glb(draco, ByteArray(44)))).error.contains("draco", ignoreCase = true))
        val req = tri.replace("\"asset\"", "\"extensionsRequired\":[\"X\"],\"asset\"")
        assertTrue(GlbInspector.inspect(ByteArrayInputStream(glb(req, ByteArray(44)))).error.contains("requires"))
        assertTrue(GlbInspector.inspect(ByteArrayInputStream(glb(tri, ByteArray(20)))).error.contains("runs past the BIN"))
        assertTrue(GlbInspector.inspect(ByteArrayInputStream(glb("{not json", ByteArray(44)))).error.contains("does not parse"))
    }

    @Test fun everyDeclaredBundledModelParsesAndMatchesItsDeclaration() {
        val doc = JSONObject(File(assets, "cad/models.json").readText())
        val models = doc.getJSONArray("models")
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val v = m.getJSONObject("view")
            val r = File(assets, v.getString("asset")).inputStream().use { GlbInspector.inspect(it) }
            assertTrue(m.getString("id") + ": " + r.error, r.ok)
            assertEquals(m.getString("id") + " bytes", v.getLong("size"), r.bytes)
            assertEquals(m.getString("id") + " sha256", v.getString("sha256"), r.sha256)
            assertEquals(m.getString("id") + " triangles", m.getJSONObject("stats").getLong("triangles"), r.triangles)
        }
    }

    @Test fun theDroneIsAddedAndNoModelWasRemoved() {
        val ids = JSONObject(File(assets, "cad/models.json").readText()).getJSONArray("models").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        // field parity (#1632, rule 16): the two models that existed before stay, the Drone is new
        assertTrue(ids.containsAll(listOf("one-lilo", "campidoglio")))
        assertTrue("drone" in ids)
        val d = JSONObject(File(assets, "cad/models.json").readText()).getJSONArray("models").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "drone" } }
        assertEquals("glb", d.getString("format"))
        assertEquals("mechanics", d.getString("category"))
        assertTrue("a civilian model: " + d.getString("name"), d.getString("name").contains("civilian"))
        assertTrue(d.getJSONObject("view").getString("asset").endsWith("drone.view.glb"))
        // small: no baked-data bloat beyond the precedent (the other two views are 0.5 MB and 7.7 MB)
        assertTrue(File(assets, "cad/models/drone.view.glb").length() < 300_000)
        // the declared top-level and per-model keys of the previous schema are all still present on every model
        val keys = listOf("id", "name", "category", "library", "software", "format", "path", "source", "licence", "size", "sha256", "updated", "units", "view", "stats", "bbox", "material_count", "layer_count", "component_count", "conversion_log")
        val models = JSONObject(File(assets, "cad/models.json").readText()).getJSONArray("models")
        for (i in 0 until models.length()) for (k in keys) assertTrue(models.getJSONObject(i).getString("id") + " lacks " + k, models.getJSONObject(i).has(k))
    }

    @Test fun theViewerPageCarriesTheDiagnosticsHooks() {
        val dir = listOf(File("../../ab_cloud-libs-shared/libs/cad-core/src/main/assets/cad"), File("../ab_cloud-libs-shared/libs/cad-core/src/main/assets/cad")).first { it.isDirectory }
        val html = File(dir, "viewer.html").readText()
        val js = File(dir, "viewer.js").readText()
        for (needle in listOf("window.cadDiag", "failIfMajorPerformanceCaveat", "unmaskedRenderer", "readPixels", "webgl2")) assertTrue("viewer.html lacks $needle", needle in html)
        for (needle in listOf("D.frames++", "D.firstFrameMs", "renderer creation failed", "webglcontextrestored", "D.err(")) assertTrue("viewer.js lacks $needle", needle in js)
        // the public API the host already used is untouched
        for (needle in listOf("window.cadOpen", "window.cadFit", "window.cadWireframe", "window.cadSnapshot", "window.cadPaint")) assertTrue("viewer.js lost $needle", needle in js)
    }

    private fun goodJs() = JSONObject("""{"stage":"shown","stageMs":900,"ageMs":5000,"moduleLoaded":true,"rendererCreated":true,"ready":true,"contextLost":false,"restored":0,"frames":3,
        "firstFrameMs":850,"lastFrameMs":900,"lastError":"","errors":[],"opened":"/assets/cad/models/drone.view.glb","loadMs":800,"shownMs":900,"triangles":1096,
        "probe":{"attempts":["webgl2 (hardware only): created"],"hardware":true,"kind":"webgl2","drawOk":true,"unmaskedRenderer":"Adreno (TM) 740","maxTexture":16384,"uint32Index":true}}""").toString()

    private fun snap(egl: GlProbe = GlProbe(true, true, "done", "", listOf("GL_RENDERER" to "Adreno (TM) 740")), js: String = goodJs(), models: List<ModelAsset> = emptyList(),
                     live: List<LiveRec> = emptyList(), wv: String = "com.google.android.webview 130.0") =
        XraySnapshot("t", listOf("App" to "x"), listOf("Model" to "m"), egl, listOf("WebView package" to wv), js, 10, models, live)

    private fun asset(found: Boolean = true, ok: Boolean = true, err: String = "") = ModelAsset("drone", "Drone", "glb", "mesh", true, "cad/models/drone.view.glb", "/assets/cad/models/drone.view.glb", 100, "a".repeat(64), found,
        if (found) GlbInspector.Result(ok, err, bytes = 100, sha256 = "a".repeat(64), version = 2, triangles = 5) else null)

    @Test fun aHealthyPipelineHasNoFailingStage() {
        val s = snap(models = listOf(asset()), live = listOf(LiveRec("drone", "Drone", "mesh", "glb", "/assets/x", "shown", emptyList(), shownMs = 900, triangles = 1096, paintedPct = 12.5, viewW = 300, viewH = 300, viewShown = true, viewHwAccel = true)))
        assertFalse(XrayReport.stages(s).any { it.status == "FAIL" })
        assertTrue(XrayReport.verdict(s), XrayReport.verdict(s).startsWith("Every stage"))
    }

    @Test fun eachBrokenStageIsNamedAsTheFirstFailure() {
        assertTrue(XrayReport.verdict(snap(egl = GlProbe(true, false, "eglCreateContext (GLES3)", "EGL_NO_CONTEXT", emptyList()))).contains("1 EGL"))
        assertTrue(XrayReport.verdict(snap(wv = "none (WebView.getCurrentWebViewPackage() is null)")).contains("2 WebView"))
        assertTrue(XrayReport.verdict(snap(js = "")).contains("3 viewer page"))
        val noGl = JSONObject(goodJs()).also { it.put("probe", JSONObject().put("error", "no WebGL context could be created").put("attempts", org.json.JSONArray(listOf("webgl2: null")))) }.toString()
        assertTrue(XrayReport.verdict(snap(js = noGl)).contains("3 WebGL"))
        val noModule = JSONObject(goodJs()).also { it.put("moduleLoaded", false).put("lastError", "Failed to resolve module specifier three") }.toString()
        assertTrue(XrayReport.verdict(snap(js = noModule)).contains("4 viewer module"))
        assertTrue(XrayReport.verdict(snap(models = listOf(asset(found = false)))).contains("5 bundled model assets"))
        assertTrue(XrayReport.verdict(snap(models = listOf(asset(ok = false, err = "bad magic")))).contains("bad magic"))
        val painted0 = LiveRec("drone", "Drone", "mesh", "glb", "u", "shown", emptyList(), triangles = 10, paintedPct = 0.0, paintedGl = "SwiftShader")
        assertTrue(XrayReport.verdict(snap(live = listOf(painted0))).contains("painted 0.0%"))
        val zero = LiveRec("drone", "Drone", "mesh", "glb", "u", "shown", emptyList(), triangles = 10, paintedPct = 5.0, viewW = 0, viewH = 0)
        assertTrue(XrayReport.verdict(snap(live = listOf(zero))).contains("surface"))
        assertTrue(XrayReport.verdict(snap(live = listOf(LiveRec("drone", "Drone", "mesh", "glb", "u", "failed", emptyList(), error = "fetch: 404")))).contains("fetch: 404"))
    }

    @Test fun aSoftwareRendererIsAWarningNotAFailure() {
        val soft = snap(egl = GlProbe(true, true, "done", "", listOf("GL_RENDERER" to "Google SwiftShader"), software = true))
        assertTrue(XrayReport.stages(soft).first().status == "WARN")
    }

    @Test fun theReportCarriesEveryFieldAsMarkdownAndJson() {
        val s = snap(egl = GlProbe(true, true, "done", "", listOf("GL_VENDOR" to "Qualcomm", "GL_RENDERER" to "Adreno (TM) 740", "GL_VERSION" to "OpenGL ES 3.2")), models = listOf(asset()),
            live = listOf(LiveRec("drone", "Drone", "mesh", "glb", "u", "shown", listOf("0 ms  cadOpen u"), shownMs = 5, triangles = 1096, paintedPct = 3.0)))
        val md = XrayReport.markdown(s)
        for (n in listOf("Verdict", "GL_RENDERER: Adreno (TM) 740", "Adreno (TM) 740", "WebView package", "drone", "/assets/cad/models/drone.view.glb", "firstFrameMs", "cadOpen u", "Pipeline stages")) assertTrue("markdown lacks $n", n in md)
        val j = JSONObject(XrayReport.json(s))
        for (k in listOf("verdict", "stages", "app", "device", "webview", "egl", "viewerJs", "models", "live")) assertTrue("json lacks $k", j.has(k))
        assertEquals("drone", j.getJSONArray("models").getJSONObject(0).getString("id"))
        assertNotNull(j.getJSONObject("egl").getJSONObject("fields").getString("GL_VENDOR"))
    }

    @Test fun theRecorderKeepsEventsPerModel() {
        CadDiag.clear()
        var t = 1000L
        CadDiag.clock = { t }
        CadDiag.begin("drone", "Drone", "mesh", "glb", "/assets/x")
        t = 1250
        CadDiag.shown("drone", 1096)
        CadDiag.painted("drone", 12.5, 300, 300, "Adreno")
        CadDiag.view("drone", 300, 300, true, true)
        val r = CadDiag.snapshot().single()
        assertEquals("shown", r.state)
        assertEquals(250, r.shownMs)
        assertEquals(12.5, r.paintedPct, 0.0)
        assertEquals(300, r.viewW)
        assertTrue(r.events.any { it.contains("shown") })
        CadDiag.failed("drone", "boom")
        assertEquals("boom", CadDiag.snapshot().single().error)
        CadDiag.clear()
        CadDiag.clock = { System.currentTimeMillis() }
    }
}
