package com.diegonmarcos.cloudsuperintel

import com.diegonmarcos.superapp.ai.AiAttachment
import com.diegonmarcos.superapp.ai.AiBackend
import com.diegonmarcos.superapp.ai.AiImageFlow
import com.diegonmarcos.superapp.ai.AiImageStore
import com.diegonmarcos.superapp.ai.AiImages
import com.diegonmarcos.superapp.ai.AiMedia
import com.diegonmarcos.superapp.ai.AiMemoryPrefs
import com.diegonmarcos.superapp.ai.AiMemorySessions
import com.diegonmarcos.superapp.ai.AiModel
import com.diegonmarcos.superapp.ai.AiProtocol
import com.diegonmarcos.superapp.ai.AiReply
import com.diegonmarcos.superapp.ai.AiRequest
import com.diegonmarcos.superapp.ai.AiSink
import com.diegonmarcos.superapp.ai.AiStandardHost
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/** Image in, image out (and text to image, and image to text): which model answers, what is sent, and the returned image parsed and kept. */
class ImageFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private val tabs = LibAi.tabs()
    private val flow = AiImageFlow(tabs)
    private val drawer = tabs.entries(tabs.imageFlow.imageTab).first { it.chatCapable && it.outputsImage }.id
    private val texter = "anthropic/claude-sonnet-5.5"

    @Test fun aPickedModelThatDrawsIsUsedAsPicked() {
        val p = flow.plan("make it blue", true, drawer)
        assertEquals(AiImageFlow.Kind.IMAGE, p.kind)
        assertEquals(drawer, p.model)
    }

    @Test fun whenNoImageModelIsPickedTheImageTabChoosesOneThatTakesTheAttachedImage() {
        val p = flow.plan("remove the background", true, texter)
        assertEquals(AiImageFlow.Kind.IMAGE, p.kind)
        val chosen = tabs.entries(tabs.imageFlow.imageTab).first { it.id == p.model }
        assertTrue(chosen.chatCapable && chosen.outputsImage && chosen.takes("image"))
        assertEquals("the first chat-capable Image entry that reads images", tabs.entries(tabs.imageFlow.imageTab).first { it.chatCapable && it.outputsImage && it.takes("image") }.id, p.model)
    }

    @Test fun textToImageNeedsNoAttachmentAndPicksFromTheImageTab() {
        val p = flow.plan("draw a lighthouse at dusk", false, texter)
        assertEquals(AiImageFlow.Kind.IMAGE, p.kind)
        assertTrue(tabs.entries(tabs.imageFlow.imageTab).any { it.id == p.model && it.outputsImage })
        assertEquals(AiImageFlow.Kind.TEXT, flow.plan("explain how lighthouses work", false, texter).kind)
        assertEquals(AiImageFlow.Kind.TEXT, flow.plan("hello", false, texter).kind)
    }

    @Test fun anImageWithAQuestionIsVisionAndKeepsAModelThatReadsImages() {
        val p = flow.plan("what is in this picture?", true, texter)
        assertEquals(AiImageFlow.Kind.VISION, p.kind)
        assertEquals(texter, p.model)
        val blind = tabs.entries(tabs.imageFlow.textTab).first { !it.takes("image") }.id
        val q = flow.plan("describe this", true, blind)
        assertEquals(AiImageFlow.Kind.VISION, q.kind)
        assertTrue(tabs.entries(tabs.imageFlow.textTab).first { it.id == q.model }.takes("image"))
    }

    @Test fun theReturnedImageIsParsedFromTheCompletionsMessage() {
        val png = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0))
        val body = """{"choices":[{"message":{"role":"assistant","content":"Here you go","images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,$png"}}]}}],"usage":{"prompt_tokens":5,"completion_tokens":7}}"""
        val r = AiProtocol.parseWhole(200, body)
        assertEquals("Here you go", r.text)
        assertEquals(listOf("data:image/png;base64,$png"), r.images)
        val (mime, bytes) = AiImages.decodeDataUrl(r.images.single())!!
        assertEquals("image/png", mime)
        assertEquals(10, bytes.size)
        assertEquals("image/png", AiImages.sniff(bytes))
        assertNull(AiImages.decodeDataUrl("https://x/y.png"))
        assertNull(AiImages.decodeDataUrl("data:image/png,rawtext"))
        val parts = """{"choices":[{"message":{"content":[{"type":"text","text":"A"},{"type":"image_url","image_url":{"url":"data:image/png;base64,$png"}}]}}]}"""
        val q = AiProtocol.parseWhole(200, parts)
        assertEquals("A", q.text)
        assertEquals(1, q.images.size)
        assertTrue(AiProtocol.parseWhole(200, """{"choices":[{"message":{"content":"plain"}}]}""").images.isEmpty())
    }

    @Test fun anImageIsKeptAsAFileTheChatCanShowSaveShareAndReattach() {
        val png = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0))
        val saved = AiImageStore(tmp.newFolder("imgs")).save("data:image/png;base64,$png")
        assertNotNull(saved)
        assertTrue(saved!!.image && saved.name.endsWith(".png") && File(saved.path).length() == 10L)
        assertNull(AiImageStore(tmp.newFolder("none")) { null }.save("https://example.invalid/x.png"))
    }

    @Test fun theBodyAsksForAnImageAndCarriesTheAttachedPicture() {
        val cfg = LibAi.config()
        val req = AiRequest(drawer, listOf(), stream = false, modalities = tabs.imageFlow.modalities)
        val o = JSONObject(AiProtocol.body(cfg, req, drawer, false, true))
        assertEquals(listOf("image", "text"), (0 until o.getJSONArray("modalities").length()).map { o.getJSONArray("modalities").getString(it) })
        assertTrue(!JSONObject(AiProtocol.body(cfg, AiRequest(texter, listOf()), texter, false, true)).has("modalities"))
    }

    private class Recorder(val reply: AiReply) : AiBackend {
        var seen: AiRequest? = null
        override val id = "rec"
        override fun models() = emptyList<AiModel>()
        override fun chat(req: AiRequest, onDelta: (String) -> Unit): AiReply { seen = req; return reply }
    }

    @Test fun theWholeRoundTripGoesToTheImageModelAndTheAnswerHoldsTheImage() {
        val cfg = LibAi.config()
        val png = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0))
        val rec = Recorder(AiReply("done", images = listOf("data:image/png;base64,$png")))
        val gateway = Recorder(AiReply("gateway"))
        val store = AiMemorySessions()
        val host = AiStandardHost(
            cfg, store, AiMemoryPrefs(texter), gateway = gateway, agents = emptyList(),
            media = AiMedia(rec, flow, AiImageStore(tmp.newFolder("out")), tabs.imageFlow.modalities),
        )
        val input = tmp.newFile("in.png").also { it.writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2)) }
        val a = AiAttachment("a1", "in.png", "image/png", input.path, 10)
        val out = host.sendWith(host.newSession(texter, 1), "make it look like a watercolour", listOf(a), texter, false, 2, AiSink.None)
        assertNull(out.error)
        val seen = rec.seen!!
        assertNull("the gateway is not used for an image message", gateway.seen)
        assertEquals(flow.plan("make it look like a watercolour", true, texter).model, seen.model)
        assertEquals(listOf("image", "text"), seen.modalities)
        assertEquals(false, seen.stream)
        val last = seen.wire!!.getJSONObject(seen.wire!!.length() - 1).getJSONArray("content")
        assertEquals("image_url", last.getJSONObject(1).getString("type"))
        val answer = out.session.messages.last()
        assertEquals("done", answer.content)
        assertEquals(1, answer.attachments.size)
        assertTrue(answer.attachments.single().image)
        // The next turn re-attaches the returned image: it is a file like any other.
        val again = host.sendWith(out.session, "now add a moon", listOf(answer.attachments.single()), texter, false, 3, AiSink.None)
        assertNull(again.error)
        assertEquals("image_url", rec.seen!!.wire!!.getJSONObject(rec.seen!!.wire!!.length() - 1).getJSONArray("content").getJSONObject(1).getString("type"))
    }

    @Test fun aPlainMessageStaysOnTheGateway() {
        val cfg = LibAi.config()
        val rec = Recorder(AiReply("media"))
        val gateway = Recorder(AiReply("gateway"))
        val host = AiStandardHost(
            cfg, AiMemorySessions(), AiMemoryPrefs(texter), gateway = gateway,
            agents = listOf(com.diegonmarcos.superapp.ai.AiAgent("a", "A", "sub")),
            media = AiMedia(rec, flow, AiImageStore(tmp.newFolder("o2")), tabs.imageFlow.modalities),
        )
        val out = host.sendWith(host.newSession(texter, 1), "hello there", emptyList(), texter, false, 2, AiSink.None)
        assertNull(out.error)
        assertNull(rec.seen)
        assertEquals("gateway", out.session.messages.last().content)
    }
}
