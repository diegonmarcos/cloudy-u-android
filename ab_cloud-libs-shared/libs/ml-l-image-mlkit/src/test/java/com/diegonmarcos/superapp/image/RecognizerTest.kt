package com.diegonmarcos.superapp.image.mlkit

import android.graphics.Bitmap
import com.diegonmarcos.superapp.decisions.Http
import com.diegonmarcos.superapp.decisions.UrlHttp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * #772 the engine's recognizer: BOTH routes over the SAME golden images (src/test/resources/
 * golden). The on-device route's ML Kit half cannot run off a phone, so a recording fake stands
 * in for it; everything around it — decoding, the working size, the dominant colours, ZXing,
 * the result shape — is the real code. The OpenRouter route talks to a MOCK DECISIONS SERVER on
 * loopback over the real UrlHttp (the request is addressed to an https endpoint, as the engine
 * insists, and redirected to the mock). Fallback, the route switch and every refusal included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecognizerTest {
    /** A raw-socket mock: com.sun.net.httpserver is not on an Android unit test's classpath. */
    private lateinit var server: ServerSocket
    private val bodies = mutableListOf<JSONObject>()
    private val auth = mutableListOf<String?>()
    @Volatile private var reply: Triple<Int, String, Long> = Triple(200, "{}", 0)

    private class FakeOnDevice : Recognizer.OnDevice {
        var calls = 0
        override fun labels(bitmap: Bitmap, minConfidence: Double, max: Int): List<Recognizer.Label> {
            calls++
            return listOf(Recognizer.Label("Fruit", 0.92), Recognizer.Label("Food", 0.81), Recognizer.Label("Table", 0.4))
                .filter { it.p >= minConfidence }.take(max)
        }
        override fun objects(bitmap: Bitmap, max: Int): List<Recognizer.Box> =
            listOf(Recognizer.Box("Food", 0.7, 1, 2, bitmap.width / 2, bitmap.height / 2)).take(max)
    }

    private val onDevice = FakeOnDevice()
    private var account: String? = null
    private lateinit var recognizer: Recognizer

    /** Every https request goes to the loopback mock instead. */
    private val toMock = object : Http {
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response =
            UrlHttp.send("http://127.0.0.1:${server.localPort}/api/alpha/decisions", token, body, timeoutMs)
    }

    @Before fun up() {
        server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { sock.use { serve(it) } }
            }
        }
        recognizer = Recognizer(ImageScanner(), onDevice, toMock, { account })
    }

    private fun serve(sock: java.net.Socket) {
        val input = sock.getInputStream().buffered()
        fun line(): String = generateSequence { input.read().takeIf { it >= 0 } }.takeWhile { it != '\n'.code }
            .map { it.toByte() }.toList().toByteArray().toString(Charsets.UTF_8).trimEnd('\r')
        line()
        val headers = generateSequence { line().takeIf { it.isNotEmpty() } }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { var n = 0; while (n < it.size) n += input.read(it, n, it.size - n).also { r -> if (r < 0) return } }
        synchronized(bodies) {
            body.toString(Charsets.UTF_8).takeIf { it.isNotEmpty() }?.let { bodies += JSONObject(it) }
            auth += headers["authorization"]
        }
        val (code, text, delay) = reply
        if (delay > 0) Thread.sleep(delay)
        val b = text.toByteArray()
        runCatching {
            sock.getOutputStream().apply {
                write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(b); flush()
            }
        }
    }

    @After fun down() = server.close()

    private fun golden(name: String): ByteArray = javaClass.getResourceAsStream("/golden/$name")!!.readBytes()

    /** The shape RecognitionConfig.request builds, from the shipped recognition.json. */
    private fun request(route: String, token: String? = null, fallback: Boolean = true): JSONObject {
        val decl = JSONObject(java.io.File("../ml-l-image/recognition.json").readText())
        return JSONObject().put("route", route).put("model", "typesafe/jev-1.13").put("fallback", fallback).put("context", "on the kitchen table")
            .put("ml", JSONObject(decl.getJSONObject("ml").toString()).put("ocr", false))
            .put("colours", decl.getJSONObject("colours")).put("openrouter", decl.getJSONObject("openrouter"))
            .apply { if (token != null) put("token", token) }
    }

    private fun categoryAnswer() = JSONObject().put("answers", JSONObject()
        .put("category", JSONObject().put("type", "choice").put("choice", "food").put("probabilities", JSONObject().put("food", 0.7).put("plant", 0.2).put("other", 0.1)))
        .put("damaged", JSONObject().put("type", "noul").put("noul", 0.15))).put("usage", JSONObject().put("cost", 0.00004)).toString()

    // ── the on-device route ──────────────────────────────────────────────────────────────────

    @Test fun `ml route - labels, boxes and colours of a golden image in the uniform shape`() {
        val r = recognizer.recognize(golden("red.png"), request("ml"))
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals("ml", r.getString("route"))
        assertEquals("ml", r.getString("requested"))
        assertFalse(r.getBoolean("fell_back"))
        assertEquals(listOf("Fruit", "Food"), r.getJSONArray("labels").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("label") } })
        assertEquals(0.92, r.getJSONArray("labels").getJSONObject(0).getDouble("p"), 0.0)
        assertEquals(1, r.getJSONArray("boxes").length())
        assertEquals(32, r.getJSONArray("boxes").getJSONObject(0).getInt("w"))
        val c = r.getJSONArray("colours").getJSONObject(0)
        assertEquals("#E53935", c.getString("hex"))
        assertEquals("red", c.getString("name"))
        assertEquals(1.0, c.getDouble("share"), 0.0)
        assertEquals("", r.getString("text"))
        assertTrue(r.isNull("barcode"))
        assertEquals(64, r.getInt("width"))
        assertEquals(0, r.getJSONArray("errors").length())
        assertTrue("no network on the on-device route", bodies.isEmpty())
    }

    @Test fun `two colours come back in share order, named in the declared vocabulary`() {
        val cs = recognizer.recognize(golden("blue_yellow.png"), request("ml")).getJSONArray("colours")
        assertEquals(2, cs.length())
        assertEquals("blue", cs.getJSONObject(0).getString("name"))
        assertEquals(0.75, cs.getJSONObject(0).getDouble("share"), 0.0)
        assertEquals("#FDD835", cs.getJSONObject(1).getString("hex"))
        assertEquals("yellow", cs.getJSONObject(1).getString("name"))
    }

    @Test fun `a large photo is recognised at the working size and nothing is used after it is recycled`() {
        val r = recognizer.recognize(golden("large_green.png"), request("ml"))
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals(1024, r.getInt("width"))
        assertEquals(768, r.getInt("height"))
        assertEquals("green", r.getJSONArray("colours").getJSONObject(0).getString("name"))
    }

    @Test fun `the declared thresholds reach the on-device models`() {
        val req = request("ml")
        req.getJSONObject("ml").put("min_confidence", 0.9).put("max_objects", 0)
        val r = recognizer.recognize(golden("red.png"), req)
        assertEquals(1, r.getJSONArray("labels").length())
        assertEquals(0, r.getJSONArray("boxes").length())
    }

    @Test fun `an on-device model failure is reported, the rest still answers`() {
        val broken = Recognizer(ImageScanner(), object : Recognizer.OnDevice {
            override fun labels(bitmap: Bitmap, minConfidence: Double, max: Int): List<Recognizer.Label> = throw IllegalStateException("no model")
            override fun objects(bitmap: Bitmap, max: Int): List<Recognizer.Box> = emptyList()
        }, toMock, { null })
        val r = broken.recognize(golden("red.png"), request("ml"))
        assertTrue(r.getBoolean("ok"))
        assertEquals("labels: no model", r.getJSONArray("errors").getString(0))
        assertEquals("red", r.getJSONArray("colours").getJSONObject(0).getString("name"))
    }

    // ── the OpenRouter route, same golden images ─────────────────────────────────────────────

    @Test fun `openrouter route - the request is a choice over the declared categories with the photo and the colours`() {
        reply = Triple(200, categoryAnswer(), 0)
        account = listOf("sk", "or", "account-0001").joinToString("-")
        val r = recognizer.recognize(golden("red.png"), request("openrouter"))
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals("openrouter", r.getString("route"))
        assertFalse(r.getBoolean("fell_back"))
        assertEquals(0, onDevice.calls)
        val sent = bodies.single()
        assertEquals("typesafe/jev-1.13", sent.getString("model"))
        val parts = sent.getJSONArray("state")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        val text = parts.getJSONObject(0).getString("text")
        assertTrue(text, text.contains("\"name\":\"red\"") && text.contains("context: on the kitchen table"))
        assertTrue(parts.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
        val decl = JSONObject(java.io.File("../ml-l-image/recognition.json").readText()).getJSONObject("openrouter")
        val q = sent.getJSONObject("questions")
        assertEquals(decl.getJSONObject("categories").keys().asSequence().toSet(), q.getJSONObject("category").getJSONObject("criteria").keys().asSequence().toSet())
        assertEquals("choice", q.getJSONObject("category").getString("type"))
        assertEquals(setOf("category", "text_present", "damaged", "ripe"), q.keys().asSequence().toSet())
        assertEquals(setOf("unripe", "ripe", "overripe", "not_produce"), q.getJSONObject("ripe").getJSONObject("criteria").keys().asSequence().toSet())
        assertEquals("Bearer $account", auth.single())
        val labels = r.getJSONArray("labels")
        assertEquals("food", labels.getJSONObject(0).getString("label"))
        assertEquals(0.7, labels.getJSONObject(0).getDouble("p"), 0.0)
        assertEquals(0.85, r.getJSONObject("answers").getJSONArray("damaged").getJSONObject(0).getDouble("p"), 1e-9)
        assertEquals(0.00004, r.getDouble("cost"), 0.0)
        assertEquals("red", r.getJSONArray("colours").getJSONObject(0).getString("name"))
    }

    @Test fun `a token in the request is never used (#1801 the key is the AI-key owner engine's)`() {
        reply = Triple(200, categoryAnswer(), 0)
        account = "acct-token-zzzz"
        recognizer.recognize(golden("blue_yellow.png"), request("openrouter", token = "caller-token-9999"))
        assertEquals("Bearer acct-token-zzzz", auth.single())
    }

    @Test fun `no token - no call, the on-device route answers and says why`() {
        val r = recognizer.recognize(golden("red.png"), request("openrouter"))
        assertTrue(r.getBoolean("ok"))
        assertEquals("ml", r.getString("route"))
        assertEquals("openrouter", r.getString("requested"))
        assertTrue(r.getBoolean("fell_back"))
        assertTrue(r.getString("reason"), r.getString("reason").startsWith("no OpenRouter token"))
        assertTrue(bodies.isEmpty())
        assertEquals(1, onDevice.calls)
        assertEquals("Fruit", r.getJSONArray("labels").getJSONObject(0).getString("label"))
    }

    @Test fun `an HTTP error and a timeout fall back too`() {
        account = "k"
        reply = Triple(500, """{"error":{"message":"overloaded"}}""", 0)
        val e = recognizer.recognize(golden("red.png"), request("openrouter"))
        assertTrue(e.getBoolean("fell_back"))
        assertEquals("HTTP 500: overloaded", e.getString("reason"))
        reply = Triple(200, categoryAnswer(), 2500)
        val req = request("openrouter")
        req.getJSONObject("openrouter").put("timeout_ms", 1000)
        val t = recognizer.recognize(golden("red.png"), req)
        assertTrue(t.getBoolean("fell_back"))
        assertTrue(t.getString("reason"), t.getString("reason").startsWith("network: "))
    }

    @Test fun `with fallback off a failed route is a failed result, not an on-device one`() {
        val r = recognizer.recognize(golden("red.png"), request("openrouter", fallback = false))
        assertFalse(r.getBoolean("ok"))
        assertTrue(r.getString("error").startsWith("no OpenRouter token"))
        assertEquals(0, onDevice.calls)
    }

    @Test fun `an http endpoint is refused before any token travels`() {
        account = "k"
        val req = request("openrouter")
        req.getJSONObject("openrouter").put("endpoint", "http://openrouter.example/d")
        val r = recognizer.recognize(golden("red.png"), req)
        assertTrue(r.getBoolean("fell_back"))
        assertTrue(r.getString("reason").contains("not https"))
        assertTrue(bodies.isEmpty())
    }

    @Test fun `unknown route, undecodable bytes and a malformed request fail soft`() {
        assertEquals("unknown route pixie", recognizer.recognize(golden("red.png"), request("ml").put("route", "pixie")).getString("error"))
        assertEquals(ImageScanner.CANNOT_DECODE, recognizer.recognize("not an image".toByteArray(), request("ml")).getString("error"))
        assertFalse(JSONObject(recognizer.json(golden("red.png"), "not json")).getBoolean("ok"))
        assertTrue(JSONObject(recognizer.json(golden("red.png"), request("ml").toString())).getBoolean("ok"))
    }

    @Test fun `a transparent image has no colours`() {
        assertEquals(0, recognizer.recognize(golden("transparent.png"), request("ml")).getJSONArray("colours").length())
    }

    @Test fun `the catalogue keeps decision models and says which take images`() {
        val body = JSONObject().put("data", JSONArray()
            .put(JSONObject().put("id", "a/vision").put("architecture", JSONObject().put("output_modalities", JSONArray().put("decisions")).put("input_modalities", JSONArray().put("text").put("image"))).put("pricing", JSONObject().put("prompt", "0.0000001")))
            .put(JSONObject().put("id", "b/text").put("architecture", JSONObject().put("output_modalities", JSONArray().put("decisions")).put("input_modalities", JSONArray().put("text"))).put("pricing", JSONObject().put("prompt", "-1")))
            .put(JSONObject().put("id", "c/chat").put("architecture", JSONObject().put("output_modalities", JSONArray().put("text"))))).toString()
        val c = Recognizer.catalogue(body)
        assertEquals(2, c.length())
        assertTrue(c.getJSONObject(0).getBoolean("images"))
        assertEquals(1e-7, c.getJSONObject(0).getDouble("prompt_price"), 0.0)
        assertFalse(c.getJSONObject(1).getBoolean("images"))
        assertTrue(c.getJSONObject(1).isNull("prompt_price"))
    }
}
