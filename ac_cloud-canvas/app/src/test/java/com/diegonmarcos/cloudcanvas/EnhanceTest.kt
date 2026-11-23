package com.diegonmarcos.cloudcanvas

import com.diegonmarcos.cloudcanvas.enhance.AiChoices
import com.diegonmarcos.cloudcanvas.enhance.Choice
import com.diegonmarcos.cloudcanvas.enhance.EnhanceRequest
import com.diegonmarcos.cloudcanvas.enhance.KeyValue
import com.diegonmarcos.cloudcanvas.enhance.Preset
import com.diegonmarcos.cloudcanvas.enhance.PresetLibrary
import com.diegonmarcos.cloudcanvas.enhance.PromptExpander
import com.diegonmarcos.cloudcanvas.enhance.Secret
import com.diegonmarcos.cloudcanvas.enhance.Task
import java.io.File
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhanceTest {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        return dir!!
    }

    /** The shipped dataset (data/fleet/app/<package>/enhance-presets.json), found from the app directory up to the repo root. */
    private fun dataset(): String {
        var dir: File? = root()
        while (dir != null && !File(dir, "data/fleet/app/com.diegonmarcos.cloudcanvas/enhance-presets.json").isFile) dir = dir.parentFile
        return File(dir!!, "data/fleet/app/com.diegonmarcos.cloudcanvas/enhance-presets.json").readText()
    }

    private val lib get() = PresetLibrary.parse(dataset())!!

    // ── preset to prompt expansion ─────────────────────────────────────────
    @Test fun theDatasetHoldsTheFivePresetsWithLongPrompts() {
        val ids = lib.presets.map { it.label }
        assertEquals(listOf("Simple enhance", "Convert to 3D", "Photorealistic", "Line art clean-up", "Colourize"), ids)
        assertTrue(lib.presets.all { it.prompt.length > 600 })
        assertEquals(Task.Convert3d, lib.preset("convert_3d")!!.task)
        assertEquals(Task.DrawEnhance, lib.preset("simple")!!.task)
        assertEquals(3, lib.qualities.size); assertEquals(3, lib.sizes.size)
    }

    @Test fun expansionFillsSizeAndQualityFromConfigs() {
        val p = lib.preset("photoreal")!!
        val out = PromptExpander.expand(p, "", lib.quality("max"), lib.size("s2048"))
        assertTrue(out.contains("2048 pixels on the long side"))
        assertTrue(out.contains("maximum"))
        assertFalse(out.contains("{size}")); assertFalse(out.contains("{quality}"))
        assertTrue(out.contains("lighting") && out.contains("artefacts"))
    }

    @Test fun expansionKeepsTheUsersWordsAndWorksWithoutAPreset() {
        val p = lib.preset("simple")!!
        assertTrue(PromptExpander.expand(p, "make the sky teal", null, null).endsWith("make the sky teal"))
        val custom = PromptExpander.expand(null, "a watercolour look", lib.quality("fast"), lib.size("s1024"))
        assertTrue(custom.startsWith("a watercolour look") && custom.contains("1024 pixels"))
        assertEquals("", PromptExpander.expand(null, "  ", null, null))
    }

    @Test fun everyPresetExpandsToADistinctPrompt() {
        val outs = lib.presets.map { PromptExpander.expand(it, "", lib.quality(null), lib.size(null)) }
        assertEquals(outs.size, outs.toSet().size)
    }

    // ── per-task model selection persisted ─────────────────────────────────
    private class Mem : KeyValue {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
    }

    @Test fun modelSelectionIsPerTaskAndPersists() {
        val kv = Mem()
        val a = AiChoices(kv)
        assertNull(a.model(Task.DrawEnhance))
        a.setModel(Task.DrawEnhance, "qwen/qwen-image-3")
        a.setModel(Task.Convert3d, "black-forest-labs/flux.2-klein-4b")
        val b = AiChoices(kv)   // a new instance over the same store: it persisted
        assertEquals("qwen/qwen-image-3", b.model(Task.DrawEnhance))
        assertEquals("black-forest-labs/flux.2-klein-4b", b.model(Task.Convert3d))
        assertNull(b.model(Task.MusicEnhance))
        b.setModel(Task.DrawEnhance, null)
        assertNull(AiChoices(kv).model(Task.DrawEnhance))
        assertEquals("black-forest-labs/flux.2-klein-4b", AiChoices(kv).model(Task.Convert3d))
    }

    @Test fun qualitySizeAndCostCapPersist() {
        val kv = Mem()
        AiChoices(kv).apply { quality = "max"; size = "s2048"; setCostCap(1.0) }
        val b = AiChoices(kv)
        assertEquals("max", b.quality); assertEquals("s2048", b.size); assertEquals(1.0, b.costCap(0.25), 0.0)
        assertEquals(0.25, AiChoices(Mem()).costCap(0.25), 0.0)
        assertFalse(Task.MusicEnhance.available)
    }

    // ── the key is never logged ────────────────────────────────────────────
    @Test fun aSecretPrintsMasked() {
        val k = Secret("sk-or-v1-abcdef1234567890")
        assertFalse(k.toString().contains("abcdef"))
        assertTrue(k.toString().endsWith("7890"))
        assertEquals("sk-or-v1-abcdef1234567890", k.reveal())
        assertFalse(Secret("short").toString().contains("short"))
        assertFalse("template: $k".contains("sk-or"))
    }

    @Test fun noSourceLogsOrPrintsAndNoKeyIsEmbedded() {
        val src = File(root(), "app/src/main")
        // #1865 the app module and every canvas library (the sections moved out of the app): no file escapes the scan
        val files = CanvasSources.kotlin()
        assertTrue(files.size > 30)
        assertTrue(files.any { it.path.contains("canvas-play") } && files.any { it.path.contains("canvas-draw") })
        val bad = Regex("""\b(Log\.[dieswv]\(|Timber\.|println\(|print\(|printStackTrace|System\.(out|err))""")
        val offenders = files.filter { f -> f.readLines().any { l -> !l.trim().startsWith("//") && !l.trim().startsWith("*") && bad.containsMatchIn(l) } }.map { it.name }
        assertEquals("no log or print call anywhere (nothing here may ever write a key): $offenders", emptyList<String>(), offenders)
        val literal = Regex("""sk-or-|Bearer [A-Za-z0-9]{8,}""")
        assertTrue(files.none { f -> literal.containsMatchIn(f.readText()) })
        // the key reaches storage only through SafeEncryptedPrefs
        assertTrue(File(src, "java/com/diegonmarcos/cloudcanvas/enhance/AiSettings.kt").readText().contains("SafeEncryptedPrefs.prefs(ctx, \"canvas_ai_secret\")"))
    }

    // ── the request builder ────────────────────────────────────────────────
    @Test fun requestCarriesModelPromptAndImageAsAMultimodalMessage() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val url = EnhanceRequest.dataUrl(png)
        assertTrue(url.startsWith("data:image/png;base64,"))
        val o = JSONObject(EnhanceRequest.body("qwen/qwen-image-3", "make it better", url))
        assertEquals("qwen/qwen-image-3", o.getString("model"))
        assertEquals("image", o.getJSONArray("modalities").getString(0))
        assertFalse(o.getBoolean("stream"))
        val msg = o.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        val parts = msg.getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("make it better", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals(url, parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertFalse(o.toString().contains("Authorization"))   // the key travels in a header, never in the body
    }

    @Test fun answerParsingReturnsTheImageOrAClassifiedError() {
        val img = byteArrayOf(9, 8, 7, 6)
        val ok = """{"choices":[{"message":{"content":"","images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,${Base64.getEncoder().encodeToString(img)}"}}]}}]}"""
        val r = EnhanceRequest.parse(200, ok)
        assertTrue(r is EnhanceRequest.Result.Image)
        assertTrue((r as EnhanceRequest.Result.Image).bytes.contentEquals(img))
        fun kind(code: Int, body: String) = (EnhanceRequest.parse(code, body) as EnhanceRequest.Result.Failure).kind
        assertEquals(EnhanceRequest.Kind.AUTH, kind(401, """{"error":{"message":"bad key"}}"""))
        assertEquals(EnhanceRequest.Kind.LIMIT, kind(402, "{}"))
        assertEquals(EnhanceRequest.Kind.LIMIT, kind(429, "{}"))
        assertEquals(EnhanceRequest.Kind.MODEL, kind(404, "{}"))
        assertEquals(EnhanceRequest.Kind.SERVER, kind(503, "oops"))
        assertEquals(EnhanceRequest.Kind.EMPTY, kind(200, """{"choices":[{"message":{"content":"sorry"}}]}"""))
        assertEquals("bad key", (EnhanceRequest.parse(401, """{"error":{"message":"bad key"}}""") as EnhanceRequest.Result.Failure).message)
    }

    @Test fun costEstimateAndCap() {
        assertEquals(0.03, EnhanceRequest.estimateUsd("image", 0.03, 1024)!!, 1e-9)
        assertEquals(0.014 * 1.048576, EnhanceRequest.estimateUsd("megapixel", 0.014, 1024)!!, 1e-9)
        assertNull(EnhanceRequest.estimateUsd("tokens", 1.0, 1024)); assertNull(EnhanceRequest.estimateUsd("image", null, 1024))
        assertTrue(EnhanceRequest.overCap(0.5, 0.25)); assertFalse(EnhanceRequest.overCap(0.1, 0.25)); assertFalse(EnhanceRequest.overCap(null, 0.25))
    }

    @Test fun datasetIsReadFromJsonNotCode() {
        assertNotNull(PresetLibrary.parse("""{"presets":[{"id":"a","label":"A","prompt":"p {size}"}],"qualities":[],"sizes":[]}"""))
        assertNull(PresetLibrary.parse("not json"))
        val l = PresetLibrary.parse("""{"presets":[{"id":"a","label":"A","prompt":"p {size}"}],"qualities":[],"sizes":[{"id":"x","label":"X","max_px":512}]}""")!!
        assertEquals("p 512 pixels on the long side", PromptExpander.expand(l.presets[0], "", null, l.sizes[0]))
        assertEquals(Preset("a", "A", "", "p {size}", Task.DrawEnhance), l.presets[0])
        assertEquals(Choice("x", "X", "", 512), l.sizes[0])
    }
}
