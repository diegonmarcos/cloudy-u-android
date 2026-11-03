package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.scraper.ScraperApi
import com.diegonmarcos.superapp.scraper.ScraperApiException
import com.diegonmarcos.superapp.scraper.YtAs
import com.diegonmarcos.superapp.scraper.YtRequest
import com.diegonmarcos.superapp.scraper.YtTarget
import com.diegonmarcos.superapp.scraper.YtTool
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** #1428 / #1429 the YouTube Download tool: link parsing, request building, and the scrappers-api client. */
class ScraperYouTubeToolTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun `every YouTube link form gives the video id`() {
        for (u in listOf("https://www.youtube.com/watch?v=$id", "youtube.com/watch?feature=x&v=$id&t=3", "https://m.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id", "https://youtu.be/$id?si=abc", "https://www.youtube.com/shorts/$id", " https://youtu.be/$id ")) {
            assertEquals(u, id, YtTool.videoId(u))
        }
    }

    @Test fun `other hosts, playlists and bad ids are refused`() {
        for (u in listOf("https://evil.com/watch?v=$id", "https://youtube.com.evil.com/watch?v=$id", "https://www.youtube.com/playlist?list=PL1",
            "https://www.youtube.com/watch?v=short", "https://youtu.be/", "")) {
            assertNull(u, YtTool.videoId(u))
        }
    }

    @Test fun `video asks the API for video at the picked height`() {
        val b = YtTool.body(YtRequest("https://youtu.be/$id", YtAs.VIDEO, 720, YtTarget.DOWNLOADS))
        assertEquals("https://www.youtube.com/watch?v=$id", b.getString("url"))
        assertEquals("video", b.getString("mode"))
        assertEquals(720, b.getInt("quality"))
    }

    @Test fun `audio asks for the audio stream at the picked bitrate`() {
        val b = YtTool.body(YtRequest("https://youtu.be/$id", YtAs.AUDIO, 128, YtTarget.EXTERNAL))
        assertEquals("audio", b.getString("mode"))
        assertEquals(128, b.getInt("quality"))
        assertFalse(b.has("quality") && YtTool.body(YtRequest("https://youtu.be/$id", YtAs.AUDIO, null, YtTarget.EXTERNAL)).has("quality"))
    }

    @Test fun `audio from video fetches a small video for the phone to extract, or asks the API to extract`() {
        val r = YtRequest("https://youtu.be/$id", YtAs.AUDIO_FROM_VIDEO, null, YtTarget.CHOOSE)
        assertTrue(YtTool.extractsOnDevice(r))
        val b = YtTool.body(r)
        assertEquals("video", b.getString("mode"))
        assertEquals(YtTool.EXTRACT_SOURCE_QUALITY, b.getInt("quality"))
        val s = YtTool.body(r, serverExtract = true)
        assertEquals("audio_extract", s.getString("mode"))
        assertFalse(s.has("quality"))
        assertFalse(YtTool.extractsOnDevice(r.copy(saveAs = YtAs.AUDIO)))
    }

    @Test fun `problems block a bad link or an off-ladder quality`() {
        assertEquals(emptyList<String>(), YtTool.problems(YtRequest("https://youtu.be/$id", YtAs.VIDEO, 1080, YtTarget.DOWNLOADS)))
        assertEquals(1, YtTool.problems(YtRequest("https://vimeo.com/1", YtAs.VIDEO, 1080, YtTarget.DOWNLOADS)).size)
        assertEquals(1, YtTool.problems(YtRequest("https://youtu.be/$id", YtAs.VIDEO, 128, YtTarget.DOWNLOADS)).size)
        assertEquals(1, YtTool.problems(YtRequest("https://youtu.be/$id", YtAs.AUDIO, 1080, YtTarget.DOWNLOADS)).size)
        assertEquals(emptyList<Int>(), YtTool.qualities(YtAs.AUDIO_FROM_VIDEO))
    }

    @Test fun `names for the Choose picker and the legal notice`() {
        assertEquals("youtube-$id.mp4", YtTool.suggestedName(YtRequest("https://youtu.be/$id", YtAs.VIDEO, null, YtTarget.CHOOSE)))
        assertEquals("youtube-$id.m4a", YtTool.suggestedName(YtRequest("https://youtu.be/$id", YtAs.AUDIO_FROM_VIDEO, null, YtTarget.CHOOSE)))
        assertTrue(YtTool.NOTICE.startsWith("Only download content you have rights to"))
    }

    private lateinit var server: MockWebServer
    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { server.shutdown() }

    @Test fun `the client POSTs the JSON body with the bearer and streams the named file back`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4")
            .setHeader("Content-Disposition", "attachment; filename=\"My_Video [$id].mp4\"").setBody("MP4DATA"))
        val api = ScraperApi(listOf(server.url("/scrappers").toString()), bearer = "tok")
        val body = JSONObject().put("url", "u").put("mode", "video")
        val got = api.downloadYoutube(body) { input, d -> Triple(String(input.readBytes()), d.fileName, d.mime) }
        assertEquals(Triple("MP4DATA", "My_Video [$id].mp4", "video/mp4"), got)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/scrappers/download/youtube", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("video", JSONObject(req.body.readUtf8()).getString("mode"))
    }

    @Test fun `no bearer header without a token`() {
        server.enqueue(MockResponse().setBody("x"))
        ScraperApi(listOf(server.url("/").toString())).downloadYoutube(JSONObject()) { i, _ -> i.readBytes() }
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `an API error carries its code and FastAPI's detail, and does not try the next base`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"detail\":\"url must be an https YouTube video link\"}"))
        val second = MockWebServer().apply { start() }
        try {
            ScraperApi(listOf(server.url("/").toString(), second.url("/").toString())).downloadYoutube(JSONObject()) { _, _ -> Unit }
            fail("expected an error")
        } catch (e: ScraperApiException) {
            assertEquals(400, e.code)
            assertEquals("url must be an https YouTube video link", e.message)
        } finally {
            assertEquals(0, second.requestCount); second.shutdown()
        }
    }

    @Test fun `a base that does not answer falls through to the next (mesh first, public second)`() {
        val dead = MockWebServer().apply { start() }
        val deadUrl = dead.url("/").toString(); dead.shutdown()
        server.enqueue(MockResponse().setBody("ok"))
        val out = ScraperApi(listOf(deadUrl, server.url("/").toString())).downloadYoutube(JSONObject()) { i, _ -> String(i.readBytes()) }
        assertEquals("ok", out)
        assertEquals(1, server.requestCount)
        assertTrue(ScraperApi.DEFAULT_BASES.first().contains(".mesh."))
    }

    @Test fun `Content-Disposition names`() {
        assertEquals("a b.mp4", ScraperApi.fileNameOf("attachment; filename*=UTF-8''a%20b.mp4", "f"))
        assertEquals("x.m4a", ScraperApi.fileNameOf("attachment; filename=x.m4a", "f"))
        assertEquals("evil.sh", ScraperApi.fileNameOf("attachment; filename=\"../../evil.sh\"", "f"))
        assertEquals("f", ScraperApi.fileNameOf(null, "f"))
    }
}
