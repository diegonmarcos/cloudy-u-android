package com.diegonmarcos.cloudwriter.core

import com.diegonmarcos.superapp.decisions.EngineKeyedHttp
import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * #1801 Cloud Writer's model routes hold no key: WriterRoutes hands every body to an EngineKeyedHttp (the AI-key owner
 * engine's relay, use writer_routes), so the call goes out with no token, the answer is read as before, and an engine
 * refusal (no engine, no key) is the route's failure with the engine's reason, so the route falls back on device.
 */
class WriterRelayTest {

    private val cfg = OpenRouterConfig("https://or.test/chat", "https://or.test/models", 1234, 77)

    @Test
    fun `a keyed transport is called with no token and its answer is read`() {
        val tokens = ArrayList<String?>()
        val bodies = ArrayList<String?>()
        val keyed = object : EngineKeyedHttp {
            override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
                tokens.add(token); bodies.add(body)
                return Http.Response(200, """{"choices":[{"message":{"content":" Hallo "}}]}""")
            }
        }
        val o = OpenRouter.translate(keyed, cfg, null, "m/x", "to German", "hello")
        assertTrue(o.toString(), o.ok)
        assertEquals("Hallo", o.text)
        assertEquals(listOf<String?>(null), tokens)
        assertEquals("m/x", JSONObject(bodies.single()!!).getString("model"))
        assertFalse(bodies.single()!!.contains("Bearer"))
    }

    @Test
    fun `an engine refusal is the route's failure with its reason, and a plain transport still needs a token`() {
        val refused = object : EngineKeyedHttp {
            override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response =
                throw IOException("No OpenRouter key in the AI keys engine")
        }
        val f = OpenRouter.transcribe(refused, cfg, null, "m/x", "transcribe", ByteArray(8))
        assertFalse(f.ok)
        assertTrue(f.error.orEmpty(), f.error.orEmpty().contains("No OpenRouter key"))
        val plain = object : Http {
            override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response = throw AssertionError("not sent")
        }
        val n = OpenRouter.translate(plain, cfg, null, "m/x", "s", "t")
        assertEquals(Routing.NO_TOKEN, n.error)
        assertNull(n.text)
    }

    @Test
    fun `the engine serves Writer's use from the keyboard that compiles it`() {
        val u = JSONObject(File("../../../fleet-private-data/decisions.json").readText())
            .getJSONObject("relay").getJSONObject("uses").getJSONObject("writer_routes")
        assertTrue(u.getJSONArray("apps").toString().contains("com.diegonmarcos.cloudkeyboard"))
        assertTrue(u.getJSONArray("endpoints").toString().contains("chat"))
        val src = File("../writer-ui/src/main/java/com/diegonmarcos/cloudwriter/WriterRoutes.kt").readText()
        assertTrue(src.contains("const val RELAY_USE = \"writer_routes\""))
        assertFalse("WriterRoutes reads no key", src.contains("revealAiKey("))
    }
}
