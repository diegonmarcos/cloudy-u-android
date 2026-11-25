package com.diegonmarcos.superapp.image.mlkit

import android.graphics.Bitmap
import com.diegonmarcos.superapp.decisions.EngineKeyedHttp
import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1801 the image scan's OpenRouter route holds no key: with the service's shape (an EngineKeyedHttp, no token source) the
 * Decisions request goes out with no token for the AI-key owner engine to key (use image_scan_recognize), a caller's
 * request "token" never travels, and an engine refusal (no engine, no key) falls back on device with the engine's reason,
 * where a missing token used to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecognizerRelayTest {

    private val onDevice = object : Recognizer.OnDevice {
        override fun labels(bitmap: Bitmap, minConfidence: Double, max: Int) = listOf(Recognizer.Label("Fruit", 0.92))
        override fun objects(bitmap: Bitmap, max: Int) = emptyList<Recognizer.Box>()
    }

    private val tokens = ArrayList<String?>()
    private val bodies = ArrayList<String>()
    @Volatile private var refuse: String? = null

    private val keyed = object : EngineKeyedHttp {
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            tokens.add(token); body?.let { bodies.add(it) }
            refuse?.let { throw java.io.IOException(it) }
            return Http.Response(200, JSONObject().put("answers", JSONObject()
                .put("category", JSONObject().put("type", "choice").put("choice", "food").put("probabilities", JSONObject().put("food", 0.7).put("plant", 0.2).put("other", 0.1)))
                .put("damaged", JSONObject().put("type", "noul").put("noul", 0.15))).toString())
        }
    }

    private val recognizer = Recognizer(ImageScanner(), onDevice, keyed, { null })

    private fun golden(name: String): ByteArray = javaClass.getResourceAsStream("/golden/$name")!!.readBytes()

    private fun request(token: String? = null): JSONObject {
        val decl = JSONObject(java.io.File("../ml-l-image/recognition.json").readText())
        return JSONObject().put("route", "openrouter").put("model", "typesafe/jev-1.13").put("fallback", true).put("context", "")
            .put("ml", JSONObject(decl.getJSONObject("ml").toString()).put("ocr", false))
            .put("colours", decl.getJSONObject("colours")).put("openrouter", decl.getJSONObject("openrouter"))
            .apply { if (token != null) put("token", token) }
    }

    @Test fun `the route answers through the engine with no token on this side`() {
        val r = recognizer.recognize(golden("red.png"), request(token = listOf("caller", "token", "9999").joinToString("-")))
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals("openrouter", r.getString("route"))
        assertFalse(r.getBoolean("fell_back"))
        assertEquals(listOf<String?>(null), tokens)
        assertFalse("a caller's token never travels", bodies.single().contains("caller-token"))
    }

    @Test fun `an engine refusal falls back on device with the engine's reason`() {
        refuse = "No OpenRouter key in the AI keys engine"
        val r = recognizer.recognize(golden("red.png"), request())
        assertTrue(r.getBoolean("ok"))
        assertEquals("ml", r.getString("route"))
        assertTrue(r.getBoolean("fell_back"))
        assertTrue(r.getString("reason"), r.getString("reason").contains("No OpenRouter key"))
    }

    @Test fun `the declared use is the one the engine serves the image scan`() {
        val u = JSONObject(java.io.File("../../../fleet-private-data/decisions.json").readText())
            .getJSONObject("relay").getJSONObject("uses").getJSONObject("image_scan_recognize")
        assertTrue(u.getJSONArray("apps").toString().contains("com.diegonmarcos.cloudlib.mllimagemlkit"))
        assertTrue(u.getJSONArray("endpoints").toString().contains("decisions"))
    }
}
