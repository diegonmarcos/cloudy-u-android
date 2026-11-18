package com.diegonmarcos.superapp.decisions.link

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1799 the image reply the engine's own suite pins (decisions-core ImageEngineTest), read by the client, and the wire it rides. */
class ImageReplyTest {
    private val pic = byteArrayOf(1, 2, 3)

    @Test fun anOkWithItsPictureIsAPicture() {
        val r = ImageReply.parse(JSONObject("""{"ok":true,"use":"canvas_enhance","mime":"image/webp","bytes":3,"cost":0.04,"latency_ms":9}"""), pic)
        assertTrue(r.ok)
        assertArrayEquals(pic, r.picture)
        assertEquals("image/webp", r.mime)
        assertEquals("", r.reason)
        assertEquals(0.04, r.cost!!, 0.0)
        assertNull(r.kind)
        assertNull(r.message)
    }

    @Test fun anOkWithoutAPictureIsMalformedNeverAnEmptyImage() {
        for (p in listOf<ByteArray?>(null, ByteArray(0))) {
            val r = ImageReply.parse(JSONObject("""{"ok":true}"""), p)
            assertFalse(r.ok)
            assertNull(r.picture)
            assertEquals(Outcome.MALFORMED, r.reason)
        }
        assertEquals("image/png", ImageReply.parse(JSONObject("{}"), null).mime)
    }

    @Test fun aRefusalKeepsItsReasonKindMessageAndDetail() {
        val r = ImageReply.parse(JSONObject("""{"ok":false,"reason":"http","kind":"AUTH","message":"bad key","detail":"d","cost":null}"""), pic)
        assertFalse(r.ok)
        assertNull("a refusal never hands over a picture", r.picture)
        assertEquals("http", r.reason)
        assertEquals("AUTH", r.kind)
        assertEquals("bad key", r.message)
        assertEquals("d", r.detail)
        assertNull(r.cost)
        val bare = ImageReply.parse(JSONObject("""{"ok":false}"""), null)
        assertEquals(Outcome.MALFORMED, bare.reason)
        assertNull(bare.kind)
        assertNull(bare.message)
        assertEquals(Outcome.NO_ENGINE, ImageReply.parse(JSONObject().put("ok", false).put("reason", Outcome.NO_ENGINE), null).reason)
    }

    @Test fun theWireAndTheDeclarationCarryTheImageCallAtContractTwo() {
        val aidl = File("src/main/aidl/com/diegonmarcos/superapp/decisions/link/IDecisionsEngine.aidl").readText()
        assertTrue(aidl.contains("String image(String request, in ParcelFileDescriptor input, in ParcelFileDescriptor output);"))
        // appended after the first two methods: their transaction codes stay what installed engines answer
        assertTrue(aidl.indexOf("String call(") < aidl.indexOf("String[] methods()"))
        assertTrue(aidl.indexOf("String[] methods()") < aidl.indexOf("String image("))
        val decl = JSONObject(File("engine-client.json").readText()).getJSONObject("engines").getJSONObject("decisions")
        assertEquals(1, decl.getInt("min_contract"))
        assertEquals(2, decl.getJSONObject("method_contracts").getInt("image"))
        val client = File("src/main/java/com/diegonmarcos/superapp/decisions/link/DecisionsLink.kt").readText()
        assertTrue(client.contains("link.check(ctx, BuildConfig.DECISIONS_IMAGE_CONTRACT)"))
    }
}
