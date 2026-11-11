package com.diegonmarcos.cgomniverse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** #1631 X-Ray App: the GLB reader, the seven-stage verdict (it names the stage that failed) and the exports (one model, no field lost). */
class OmniXrayTest {
    @Before fun fresh() = OmniXray.clearForTest()

    private fun glb(json: String, declaredExtra: Int = 0): ByteArray {
        var j = json.toByteArray(); while (j.size % 4 != 0) j += ' '.code.toByte()
        val total = 12 + 8 + j.size
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x46546C67).putInt(2).putInt(total + declaredExtra).putInt(j.size).putInt(0x4E4F534A).put(j)
        return b.array()
    }

    private val goodGlb = """{"asset":{"version":"2.0"},"meshes":[{},{}],"materials":[{}],"nodes":[{},{},{}],"extensionsUsed":["KHR_materials_unlit"]}"""

    @Test fun glbHeaderIsReadLikeALoaderReadsIt() {
        val r = Glb.inspect(glb(goodGlb))
        assertTrue(r.error, r.valid)
        assertEquals(2, r.meshes); assertEquals(1, r.materials); assertEquals(3, r.nodes)
        assertEquals(listOf("KHR_materials_unlit"), r.extensionsUsed)
        assertTrue(r.needsDecoder.isEmpty())
    }

    @Test fun brokenGlbsNameTheirDefect() {
        assertTrue(Glb.inspect(ByteArray(5)).error.contains("too short"))
        assertTrue(Glb.inspect("<html>404 not found</html>".toByteArray()).error.contains("bad magic"))
        assertTrue(Glb.inspect(glb(goodGlb, declaredExtra = 8)).error.contains("declares"))
        assertTrue(Glb.inspect(glb("{not json")).error.contains("JSON"))
        assertFalse(Glb.inspect(glb("{not json")).valid)
    }

    @Test fun dracoAndKtx2AreFlaggedAsNeedingADecoder() {
        val r = Glb.inspect(glb("""{"extensionsRequired":["KHR_draco_mesh_compression","EXT_meshopt_compression"]}"""))
        assertEquals(listOf("KHR_draco_mesh_compression"), r.needsDecoder)
    }

    private val webView = OmniXray.WebViewInfo("com.google.android.webview", "130.0.1", 1)
    private fun probe(glb: Glb.Report? = Glb.inspect(glb(goodGlb)), modelFound: Boolean = true, wv: OmniXray.WebViewInfo? = webView) = OmniXray.Probe(
        1L, wv, OmniXray.Egl(true, "Adreno (TM) 740", "Qualcomm", "OpenGL ES 3.2", 8192, ""), null,
        listOf(OmniXray.AssetCheck("avatar/models/owner.glb", modelFound, if (modelFound) 3180668 else 0, if (modelFound) "" else "FileNotFoundException")),
        glb, "models/owner.glb", "avatar/models/owner.glb", "https://appassets.androidplatform.net/assets/avatar/index.html",
    )

    /** A page snapshot of a healthy scene; each test then breaks one thing. */
    private fun page(
        ctx: String = """{"type":"webgl2","ok":true,"t":300,"renderer":"Adreno (TM) 740","vendor":"Qualcomm","version":"WebGL 2.0","maxTex":8192,"software":false}""",
        canvas: String = """{"w":1080,"h":1800,"cw":393,"ch":780,"rect":[0,0,393,780],"display":"block","visibility":"visible","opacity":"1","inDom":true}""",
        req: String = """{"url":"https://appassets.androidplatform.net/assets/avatar/models/owner.glb","via":"fetch","t":900,"status":200,"ok":true,"bytes":3180668,"magic":"glTF"}""",
        frames: Int = 120, draws: Int = 900, marks: String = """{"cgc_module":200,"boot_called":250}""", err: String = "[]", lost: String = "[]", vis: String = "visible",
    ) = """{"v":1,"t":9000,"ua":"x","dpr":2.75,"inner":[393,780],"vis":"$vis","ctx":[$ctx],"lost":$lost,"err":$err,"con":[],"req":[$req],"marks":$marks,
        "frames":$frames,"draws":$draws,"firstRaf":400,"firstDraw":1500,"canvases":[$canvas]}"""

    private fun avatar(
        pageJson: String? = page(), mainError: String = "", loaded: Boolean = true, cgcError: String = "", started: Boolean = true, gone: Int = 0,
    ): OmniXray.Avatar {
        if (started) { OmniXray.avatarStarted("https://x/avatar/index.html"); OmniXray.avatarFinished("https://x/avatar/index.html") }
        if (mainError.isNotEmpty()) OmniXray.avatarError("https://x/avatar/index.html", true, -2, mainError)
        repeat(gone) { OmniXray.avatarRenderGone(true) }
        if (loaded) OmniXray.cgcEvent(JSONObject().put("type", "loaded"))
        if (cgcError.isNotEmpty()) OmniXray.cgcEvent(JSONObject().put("type", "error").put("where", "load").put("message", cgcError))
        if (pageJson != null) OmniXray.pageReport(pageJson)
        return OmniXray.avatar()
    }

    private fun failed(p: OmniXray.Probe?, a: OmniXray.Avatar): String? = OmniXray.stages(p, a).firstOrNull { it.state == OmniXray.State.FAIL }?.id

    @Test fun aHealthySceneIsAllOk() {
        val st = OmniXray.stages(probe(), avatar())
        assertEquals(7, st.size)
        assertTrue(st.toString(), st.all { it.state == OmniXray.State.OK })
        assertTrue(OmniXray.verdict(st).startsWith("All 7 stages OK"))
    }

    @Test fun noWebViewFailsTheFirstStage() = assertEquals("webview", failed(probe(wv = null), avatar()))

    @Test fun aClosedAvatarPageIsNotAFailureButIsSaid() {
        val a = avatar(pageJson = null, started = false, loaded = false)
        assertEquals(null, failed(probe(), a))
        assertTrue(OmniXray.verdict(OmniXray.stages(probe(), a)).contains("has not run yet"))
    }

    @Test fun aMainFrameErrorFailsThePageStage() = assertEquals("page", failed(probe(), avatar(pageJson = null, mainError = "net::ERR_FAILED")))

    @Test fun aDeadRenderProcessFailsThePageStage() = assertEquals("page", failed(probe(), avatar(gone = 1)))

    @Test fun aFailedContextNamesTheWebglStage() {
        val a = avatar(page(ctx = """{"type":"webgl2","ok":false,"t":300,"err":"creation error: GPU process crashed"}"""), loaded = false)
        assertEquals("webgl", failed(probe(), a))
        assertTrue(OmniXray.verdict(OmniXray.stages(probe(), a)).contains("context creation FAILED"))
    }

    @Test fun aSoftwareContextIsAWarningNamingTheRenderer() {
        val a = avatar(page(ctx = """{"type":"webgl2","ok":true,"t":300,"renderer":"ANGLE (Google, Vulkan, SwiftShader Device)","vendor":"Google","software":true}"""))
        val webgl = OmniXray.stages(probe(), a).first { it.id == "webgl" }
        assertEquals(OmniXray.State.WARN, webgl.state)
        assertTrue(webgl.detail.contains("SwiftShader"))
    }

    @Test fun aLostContextFailsTheWebglStage() {
        assertEquals("webgl", failed(probe(), avatar(page(lost = """[{"t":5000,"kind":"lost"}]"""))))
    }

    @Test fun aMissingModelInTheApkFailsTheAssetStage() {
        assertEquals("asset", failed(probe(glb = null, modelFound = false), avatar(loaded = false)))
    }

    @Test fun aModelFetchedWithA404FailsTheAssetStage() {
        val a = avatar(page(req = """{"url":"https://h/assets/avatar/models/owner.glb","via":"fetch","t":900,"status":404,"ok":false}"""), loaded = false)
        val s = OmniXray.stages(probe(), a).first { it.id == "asset" }
        assertEquals(OmniXray.State.FAIL, s.state)
        assertTrue(s.detail.contains("404"))
    }

    @Test fun aBadGlbFailsTheDecodeStage() {
        assertEquals("decode", failed(probe(glb = Glb.inspect("<html>".toByteArray())), avatar(loaded = false)))
    }

    @Test fun aLoaderRejectionFailsTheDecodeStage() {
        assertEquals("decode", failed(probe(), avatar(loaded = false, cgcError = "Unexpected token in JSON")))
    }

    @Test fun aZeroSizedOrHiddenCanvasFailsTheSurfaceStage() {
        val zero = avatar(page(canvas = """{"w":300,"h":150,"cw":0,"ch":0,"rect":[0,0,0,0],"display":"block","visibility":"visible","opacity":"1","inDom":true}"""))
        assertEquals("surface", failed(probe(), zero))
        OmniXray.clearForTest()
        val hidden = avatar(page(canvas = """{"w":1080,"h":1800,"cw":393,"ch":780,"rect":[0,0,393,780],"display":"none","visibility":"visible","opacity":"1","inDom":true}"""))
        assertEquals("surface", failed(probe(), hidden))
    }

    @Test fun noFramesOrNoDrawsFailTheDrawStage() {
        assertEquals("draw", failed(probe(), avatar(page(frames = 0, draws = 0))))
        OmniXray.clearForTest()
        val s = OmniXray.stages(probe(), avatar(page(frames = 50, draws = 0))).first { it.id == "draw" }
        assertEquals(OmniXray.State.FAIL, s.state)
        assertTrue(s.detail.contains("no WebGL draw call"))
    }

    @Test fun theVerdictNamesTheFirstFailedStage() {
        val st = OmniXray.stages(probe(), avatar(page(ctx = """{"type":"webgl2","ok":false,"t":1,"err":"x"}""", frames = 0, draws = 0)))
        assertTrue(OmniXray.verdict(st).startsWith("FAILED at stage \"WebGL context\""))
    }

    @Test fun softwareRendererNamesAreRecognised() {
        assertTrue(OmniXray.isSoftware("Google SwiftShader", null))
        assertTrue(OmniXray.isSoftware("llvmpipe (LLVM 15)", "Mesa"))
        assertFalse(OmniXray.isSoftware("Adreno (TM) 740", "Qualcomm"))
    }

    @Test fun worldsAreRecordedAndReported() {
        OmniXray.worldStarted("w1", "https://w.example/")
        OmniXray.worldHttp("w1", "https://w.example/model.glb", false, 404)
        OmniXray.worldError("w1", "https://w.example/", true, -6, "ERR_CONNECTION_REFUSED")
        OmniXray.worldDiag("w1", "WebGL: yes")
        val w = OmniXray.worlds().single()
        assertEquals("https://w.example/", w.url)
        assertEquals(1, w.httpErrors.size)
        assertTrue(w.mainError.contains("ERR_CONNECTION_REFUSED"))
        val md = OmniXray.markdown(report(probe(), avatar()))
        assertTrue(md.contains("world w1") && md.contains("WebGL: yes") && md.contains("404"))
    }

    private fun report(p: OmniXray.Probe?, a: OmniXray.Avatar) =
        OmniXray.report("CG-Omniverse", "0.1.0", "abc", "now", p, a, OmniXray.worlds(), listOf("[ERROR] boom (x.js:1)"), OmniXray.events(), listOf(OmniXray.Row("model", "Pixel")))

    /** Rule 16: the page, the Markdown and the JSON are built from ONE model; every section and row reaches both exports. */
    @Test fun everySectionAndRowReachesBothExports() {
        val r = report(probe(), avatar())
        val md = OmniXray.markdown(r)
        val js = JSONObject(OmniXray.json(r))
        assertEquals(r.sections.size, js.getJSONArray("sections").length())
        r.sections.forEach { s ->
            assertTrue("markdown lacks " + s.title, md.contains("## " + s.title))
            s.rows.forEach { row -> assertTrue("markdown lacks " + row.key, md.contains("- " + row.key + ": " + row.value)) }
        }
        listOf("stages", "device", "webview", "egl", "webgl_probe", "avatar", "assets", "requests", "errors", "cgc", "worlds", "timeline").forEach { id ->
            assertTrue("section $id missing", r.sections.any { it.id == id })
        }
        assertEquals(7, js.getJSONArray("stages").length())
        assertTrue(md.contains("verdict: " + r.verdict))
    }

    @Test fun anUnprobedReportStillBuilds() {
        val r = report(null, avatar(pageJson = null, started = false, loaded = false))
        assertTrue(OmniXray.markdown(r).contains("not probed"))
    }
}
