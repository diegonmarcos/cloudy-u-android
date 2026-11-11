package com.diegonmarcos.cloudwriter.core

import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class OpenRouterTest {
    private class FakeHttp(var code: Int = 200, var reply: String = "{}", var fail: Throwable? = null) : Http {
        val urls = mutableListOf<String>()
        val tokens = mutableListOf<String?>()
        val sent = mutableListOf<String?>()
        val timeouts = mutableListOf<Int>()

        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            urls += url
            tokens += token
            sent += body
            timeouts += timeoutMs
            fail?.let { throw it }
            return Http.Response(code, reply)
        }
    }

    private val cfg = OpenRouterConfig("https://or.test/chat", "https://or.test/models", 1234, 77)
    private val token = listOf("t", "o", "k", "e", "n").joinToString("") + "-123"

    private fun reply(content: Any): String =
        JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString()

    @Test fun `a chat body carries the model, the cap and both messages`() {
        val b = OpenRouter.chatBody("m/x", "sys", "hello", 50)
        assertEquals("m/x", b.getString("model"))
        assertEquals(50, b.getInt("max_tokens"))
        val msgs = b.getJSONArray("messages")
        assertEquals(2, msgs.length())
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("sys", msgs.getJSONObject(0).getString("content"))
        assertEquals("user", msgs.getJSONObject(1).getString("role"))
        assertEquals("hello", msgs.getJSONObject(1).getString("content"))
    }

    @Test fun `a transcription body sends the prompt and the wav as input_audio`() {
        val wav = byteArrayOf(1, 2, 3, 4)
        val b = OpenRouter.transcribeBody("g/f", "write it down", wav, 9)
        assertEquals("g/f", b.getString("model"))
        assertEquals(9, b.getInt("max_tokens"))
        val msg = b.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        val parts = msg.getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("write it down", parts.getJSONObject(0).getString("text"))
        assertEquals("input_audio", parts.getJSONObject(1).getString("type"))
        val audio = parts.getJSONObject(1).getJSONObject("input_audio")
        assertEquals("wav", audio.getString("format"))
        assertEquals(Base64.getEncoder().encodeToString(wav), audio.getString("data"))
    }

    @Test fun `no token sends nothing`() {
        val http = FakeHttp()
        assertEquals(Routing.NO_TOKEN, OpenRouter.call(http, cfg, null, JSONObject()).error)
        assertEquals(Routing.NO_TOKEN, OpenRouter.call(http, cfg, " ", JSONObject()).error)
        assertTrue(http.urls.isEmpty())
    }

    @Test fun `a call posts to the chat url with the token and the timeout, and trims the answer`() {
        val http = FakeHttp(reply = reply("  hola \n"))
        val body = OpenRouter.chatBody("m", "s", "u", 5)
        val o = OpenRouter.call(http, cfg, token, body)
        assertEquals("hola", o.text)
        assertEquals("https://or.test/chat", http.urls.single())
        assertEquals(token, http.tokens.single())
        assertEquals(1234, http.timeouts.single())
        assertEquals(body.toString(), http.sent.single())
    }

    @Test fun `failures come back as reasons`() {
        val http = FakeHttp(code = 401, reply = JSONObject().put("error", JSONObject().put("message", "bad key")).toString())
        assertEquals("HTTP 401: bad key", OpenRouter.call(http, cfg, token, JSONObject()).error)
        http.code = 200
        http.reply = JSONObject().put("error", JSONObject().put("message", "overloaded")).toString()
        assertEquals("no message content: overloaded", OpenRouter.call(http, cfg, token, JSONObject()).error)
        http.code = 199
        assertTrue(OpenRouter.call(http, cfg, token, JSONObject()).error!!.startsWith("HTTP 199"))
        http.code = 300
        assertTrue(OpenRouter.call(http, cfg, token, JSONObject()).error!!.startsWith("HTTP 300"))
        http.fail = IOException("down")
        assertEquals("network: IOException: down", OpenRouter.call(http, cfg, token, JSONObject()).error)
        http.fail = IOException()
        assertEquals("network: IOException", OpenRouter.call(http, cfg, token, JSONObject()).error)
    }

    @Test fun `2xx edges are success`() {
        val http = FakeHttp(code = 299, reply = reply("x"))
        assertEquals("x", OpenRouter.call(http, cfg, token, JSONObject()).text)
    }

    @Test fun `translate and transcribe send their own bodies under the configured cap`() {
        val http = FakeHttp(reply = reply("ok"))
        assertEquals("ok", OpenRouter.translate(http, cfg, token, "m/t", "into Spanish", "hello").text)
        val t = JSONObject(http.sent[0]!!)
        assertEquals("m/t", t.getString("model"))
        assertEquals(77, t.getInt("max_tokens"))
        assertEquals("into Spanish", t.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("hello", t.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("ok", OpenRouter.transcribe(http, cfg, token, "m/s", "p", byteArrayOf(9)).text)
        val s = JSONObject(http.sent[1]!!)
        assertEquals("m/s", s.getString("model"))
        assertEquals(77, s.getInt("max_tokens"))
        assertEquals("input_audio", s.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"))
    }

    @Test fun `content reads a string or joins text parts`() {
        assertEquals("abc", OpenRouter.content(reply("abc")))
        assertEquals("", OpenRouter.content(reply("")))
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", "a"))
            .put(JSONObject().put("type", "image"))
            .put(JSONObject().put("type", "text").put("text", "b"))
        assertEquals("ab", OpenRouter.content(reply(parts)))
        assertNull(OpenRouter.content(reply(JSONArray().put(JSONObject().put("type", "image")))))
        assertNull(OpenRouter.content(reply(42)))
        assertNull(OpenRouter.content("not json"))
        assertNull(OpenRouter.content("{}"))
        assertNull(OpenRouter.content(JSONObject().put("choices", JSONArray()).toString()))
        assertNull(OpenRouter.content(JSONObject().put("choices", JSONArray().put(JSONObject())).toString()))
    }

    private fun model(id: String, ins: List<String>, outs: List<String>, prompt: String?, name: String? = null): JSONObject {
        val m = JSONObject().put("id", id)
            .put("architecture", JSONObject().put("input_modalities", JSONArray(ins)).put("output_modalities", JSONArray(outs)))
        if (name != null) m.put("name", name)
        if (prompt != null) m.put("pricing", JSONObject().put("prompt", prompt))
        return m
    }

    private val catalogue = JSONObject().put("data", JSONArray()
        .put(model("openrouter/auto", listOf("text", "audio"), listOf("text"), "-1"))
        .put(model("g/flash", listOf("text", "image", "audio"), listOf("text"), "0.0000003", "Flash"))
        .put(model("g/lite", listOf("text", "audio"), listOf("text"), "0.0000001"))
        .put(model("z/glm", listOf("text"), listOf("text"), "0.0000002"))
        .put(model("o/voice", listOf("text", "audio"), listOf("text", "audio"), null))
        .put(model("i/img", listOf("text"), listOf("image"), "0.000001"))
        .put(JSONObject().put("name", "no id"))).toString()

    @Test fun `the catalogue parses rows, scales the price and drops routers and id-less rows`() {
        val all = OpenRouter.catalogue(catalogue)
        assertEquals(listOf("g/flash", "g/lite", "z/glm", "o/voice", "i/img"), all.map { it.id })
        val flash = all.first { it.id == "g/flash" }
        assertEquals("Flash", flash.name)
        assertTrue(flash.audioIn && flash.textIn && flash.textOut)
        assertEquals(0.3, flash.promptUsdPerMillion!!, 1e-9)
        assertEquals("g/lite", all.first { it.id == "g/lite" }.name)
        assertNull(all.first { it.id == "o/voice" }.promptUsdPerMillion)
        val img = all.first { it.id == "i/img" }
        assertEquals(false, img.textOut)
        assertEquals(false, img.audioIn)
        assertTrue(OpenRouter.catalogue("nope").isEmpty())
        assertTrue(OpenRouter.catalogue("{}").isEmpty())
    }

    @Test fun `speech models hear and answer in text, cheapest first, unpriced last`() {
        val all = OpenRouter.catalogue(catalogue)
        assertEquals(listOf("g/lite", "g/flash", "o/voice"), OpenRouter.speechModels(all).map { it.id })
        assertEquals(listOf("g/lite", "z/glm", "g/flash", "o/voice"), OpenRouter.textModels(all).map { it.id })
    }

    @Test fun `equal prices order by id`() {
        val all = listOf(
            CatalogueModel("b", "b", true, true, true, 1.0),
            CatalogueModel("a", "a", true, true, true, 1.0),
        )
        assertEquals(listOf("a", "b"), OpenRouter.speechModels(all).map { it.id })
    }
}
