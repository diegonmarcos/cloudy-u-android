package com.diegonmarcos.cloudcanvas

import com.diegonmarcos.cloudcanvas.enhance.AiSettings
import com.diegonmarcos.cloudcanvas.enhance.EngineRoute
import com.diegonmarcos.cloudcanvas.enhance.EnhanceRequest
import com.diegonmarcos.superapp.decisions.link.ImageReply
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1799 the Draw Enhancer's call site against a FAKE decisions engine: the run without an own key goes through the engine's
 * image use canvas_enhance (the decisions-use guard D4 names this test), the picture comes back as the Enhancer's own result,
 * and every refusal or failure is surfaced in the words the Enhancer used when it made the call itself (rule 16: nothing a
 * user could read before is lost), plus one sentence per new way the engine can refuse. The app never reads the key.
 */
class EnhanceEngineTest {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        return dir!!
    }

    private class Fake(val reply: (String, String, ByteArray) -> ImageReply) : EngineRoute.Engine {
        val calls = ArrayList<Triple<String, String, ByteArray>>()
        override fun image(model: String, prompt: String, picture: ByteArray): ImageReply {
            calls.add(Triple(model, prompt, picture))
            return reply(model, prompt, picture)
        }
    }

    private fun refused(reason: String, kind: String? = null, message: String? = null, detail: String = ""): Fake = Fake { _, _, _ ->
        val o = JSONObject().put("ok", false).put("use", EngineRoute.USE).put("reason", reason).put("detail", detail)
        kind?.let { o.put("kind", it) }
        message?.let { o.put("message", it) }
        ImageReply.parse(o, null)
    }

    private fun failure(r: EnhanceRequest.Result): EnhanceRequest.Result.Failure = r as EnhanceRequest.Result.Failure

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 9)

    // ── the picture comes back ─────────────────────────────────────────────
    @Test fun aRunSendsModelPromptAndPictureAndReturnsTheEnginesPicture() {
        val out = byteArrayOf(1, 2, 3, 4)
        val fake = Fake { _, _, _ -> ImageReply.parse(JSONObject().put("ok", true).put("mime", "image/png").put("bytes", 4), out) }
        val r = EngineRoute.run(fake, "qwen/qwen-image-3", "make it better", png)
        assertTrue(r is EnhanceRequest.Result.Image)
        assertTrue((r as EnhanceRequest.Result.Image).bytes.contentEquals(out))
        assertEquals(1, fake.calls.size)
        assertEquals("qwen/qwen-image-3", fake.calls[0].first)
        assertEquals("make it better", fake.calls[0].second)
        assertTrue(fake.calls[0].third.contentEquals(png))
    }

    @Test fun anOkWithNoPictureIsAFailureNotAnEmptyImage() {
        val r = EngineRoute.run(Fake { _, _, _ -> ImageReply.parse(JSONObject().put("ok", true), ByteArray(0)) }, "m/x", "p", png)
        assertTrue(r is EnhanceRequest.Result.Failure)
    }

    // ── the messages the Enhancer showed before the engine made the call (rule 16) ──
    @Test fun theModelApisOwnFailuresReachTheUserUnchanged() {
        // the engine answers the API's failure with the same kind and message the Enhancer's own parser read
        for ((code, body) in listOf(401 to """{"error":{"message":"bad key"}}""", 402 to """{"error":{"message":"no credit"}}""",
            404 to """{"error":{"message":"no such model"}}""", 500 to "", 200 to """{"choices":[{"message":{}}]}""")) {
            val before = failure(EnhanceRequest.parse(code, body))
            val engineReason = if (before.kind == EnhanceRequest.Kind.EMPTY) "empty" else "http"
            val now = failure(EngineRoute.run(refused(engineReason, before.kind.name, before.message), "m/x", "p", png))
            assertEquals("HTTP $code", before.kind, now.kind)
            assertEquals("HTTP $code", before.message, now.message)
        }
    }

    @Test fun noKeyIsTheSentenceTheEnhancerShowedBefore() {
        val f = failure(EngineRoute.run(refused("no_key"), "m/x", "p", png))
        assertEquals("No OpenRouter key. Add one in Configs > AI, or sign in to the fleet Account.", f.message)
        assertEquals(EnhanceRequest.Kind.AUTH, f.kind)
    }

    @Test fun noNetworkIsTheSentenceTheEnhancerShowedBefore() {
        for (reason in listOf("offline", "network")) {
            val f = failure(EngineRoute.run(refused(reason, "OFFLINE", if (reason == "network") "The request did not complete (SocketTimeoutException)." else null), "m/x", "p", png))
            assertEquals(EnhanceRequest.Kind.OFFLINE, f.kind)
        }
        assertEquals("No network. Connect and try again.", failure(EngineRoute.run(refused("offline"), "m/x", "p", png)).message)
        assertEquals("The request did not complete (SocketTimeoutException).",
            failure(EngineRoute.run(refused("network", "OFFLINE", "The request did not complete (SocketTimeoutException)."), "m/x", "p", png)).message)
    }

    // ── the engine's own refusals each have a sentence ─────────────────────
    @Test fun everyEngineRefusalIsSurfacedWithItsOwnSentence() {
        val reasons = listOf("no_engine", "no_image_policy", "unknown_use", "misconfigured", "app_not_allowed", "disabled", "no_consent",
            "over_budget", "app_quota", "rate_limited", "breaker_open", "metered", "battery_saver", "bad_model", "prompt_too_large",
            "image_too_large", "output_too_large", "empty", "bad_request", "exception", "write_failed", "malformed")
        val texts = HashSet<String>()
        for (reason in reasons) {
            val f = failure(EngineRoute.run(refused(reason), "m/x", "p", png))
            assertTrue(reason, f.message.isNotBlank())
            texts.add(f.message)
        }
        // the known reasons are told apart; an unknown one names itself
        assertTrue(texts.size >= reasons.size - 6)
        assertTrue(failure(EngineRoute.run(refused("something_new"), "m/x", "p", png)).message.contains("something_new"))
        assertEquals(EnhanceRequest.Kind.LIMIT, failure(EngineRoute.run(refused("over_budget"), "m/x", "p", png)).kind)
    }

    @Test fun aMissingOrOldEngineSaysWhatToDo() {
        val f = failure(EngineRoute.run(refused("no_engine", detail = "the decisions engine x is not installed — install it from Store ▸ Cloud Constellation ▸ Libs"), "m/x", "p", png))
        assertTrue(f.message, f.message.contains("not installed"))
        val thrown = failure(EngineRoute.run(Fake { _, _, _ -> throw IllegalStateException("binder died") }, "m/x", "p", png))
        assertTrue(thrown.message, thrown.message.contains("binder died"))
    }

    // ── Configs > AI's key line from the engine's status ───────────────────
    private fun status(token: Boolean = true, served: Boolean = true, enabled: Boolean = true, allowed: Boolean = true, consent: Boolean = true): JSONObject {
        val use = JSONObject().put("enabled", enabled).put("allowed_for_app", allowed).put("consent", consent)
        return JSONObject().put("ok", true).put("token", token)
            .put("image", JSONObject().put("served", served).put("uses", JSONObject().put(EngineRoute.USE, use)))
    }

    @Test fun theKeyLineReadsTheEngineNeverTheKey() {
        val ok = AiSettings.fleet(status())
        assertEquals(AiSettings.Source.FLEET, ok.source)
        assertNull("the app holds no fleet key", ok.key)
        assertEquals(AiSettings.Source.NONE, AiSettings.fleet(status(token = false)).source)
        assertEquals("no openrouter key in the fleet Account", AiSettings.fleet(status(token = false)).why)
        assertEquals(AiSettings.Source.NONE, AiSettings.fleet(status(served = false)).source)
        assertEquals(AiSettings.Source.NONE, AiSettings.fleet(status(enabled = false)).source)
        assertEquals(AiSettings.Source.NONE, AiSettings.fleet(status(allowed = false)).source)
        assertEquals(AiSettings.Source.NONE, AiSettings.fleet(status(consent = false)).source)
        assertEquals(AiSettings.Source.NONE, AiSettings.fleet(JSONObject().put("ok", true).put("token", true)).source)
        val none = AiSettings.fleet(JSONObject().put("ok", false).put("reason", "no_engine").put("detail", "install it"))
        assertEquals("install it", none.why)
    }

    // ── the declaration and the sources ────────────────────────────────────
    @Test fun theCallSiteNamesTheDeclaredImageUseForThisApp() {
        val repo = root().parentFile
        val decl = JSONObject(File(repo, "fleet-private-data/decisions.json").readText())
        val use = decl.getJSONObject("image").getJSONObject("uses").getJSONObject(EngineRoute.USE)
        val apps = use.getJSONArray("apps")
        assertTrue((0 until apps.length()).any { apps.getString(it) == "com.diegonmarcos.cloudcanvas" })
        assertTrue(use.getString("fallback_test").endsWith("EnhanceEngineTest.kt::EnhanceEngineTest"))
        val route = File(root(), "app/src/main/java/com/diegonmarcos/cloudcanvas/enhance/EngineRoute.kt").readText()
        assertTrue(route.contains("DecisionsLink.image(ctx, \"canvas_enhance\""))
    }

    @Test fun noSourceReadsTheFleetKey() {
        // #1865 the app module and every canvas library (the sections moved out of the app)
        val files = CanvasSources.kotlin()
        assertTrue(files.size > 30)
        assertTrue(files.none { it.readText().contains("revealAiKey(") || it.readText().contains("TextToolsClient") })
        val gradle = File(root(), "app/build.gradle").readText()
        assertFalse(gradle.contains("':libs:text-tools'"))
        assertTrue(gradle.contains("':libs:decisions-link'"))
    }

    // ── rule 16: every Enhancer option is still there ──────────────────────
    @Test fun everyEnhancerOptionIsKept() {
        val ui = File(root(), "app/src/main/java/com/diegonmarcos/cloudcanvas/ui")
        val configs = File(ui, "AiConfigsPage.kt").readText()
        for (label in listOf("OpenRouter key", "Own key (overrides the fleet key)", "Save key", "Use the fleet key", "Model per task",
            "Using this app's own key", "Using the fleet Account's OpenRouter key", "No key yet")) {
            assertTrue(label, configs.contains(label))
        }
        val ctl = File(ui, "EnhanceController.kt").readText()
        for (field in listOf("var input ", "var inputName ", "var presetId ", "var custom ", "var promptText ", "var promptEdited ",
            "var promptOpen ", "var running ", "var result ", "var error ", "var note ", "fun useDrawing(", "fun load(", "fun refreshPrompt(",
            "fun pick(", "fun task(", "fun estimate(", "fun run(", "fun saveTo(", "fun sendToDraw(", "EnhanceRequest.overCap(",
            "No image model is available. Open Configs > AI.", "The returned image could not be read.", "EnhanceClient.run(ctx, own,")) {
            assertTrue(field, ctl.contains(field))
        }
    }
}
