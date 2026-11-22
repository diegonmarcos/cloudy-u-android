package com.diegonmarcos.cloudnav.maps.takeout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Reader

/** #1853 the streaming parser: right values, never the whole file, a clear error on bad input. */
class TakeoutJsonTest {
    @Test fun `a tree round trips with escapes, unicode, numbers and nulls`() {
        val src = """{"a":[1,2.5,-3,1e3,true,false,null],"s":"q\"b\\n\né\/","o":{"k":{}},"e":[]}"""
        val v = JsonPull(src.reader()).readValue() as Map<*, *>
        assertEquals(listOf(1L, 2.5, -3L, 1000.0, true, false, null), v["a"])
        assertEquals("q\"b\\n\né/", v["s"])
        val again = JsonPull(JsonOut.write(v).reader()).readValue()
        assertEquals(v, again)
    }

    @Test fun `a 300000 element array streams one element at a time`() {
        // the reader generates the text lazily: if the parser held the array, this would need hundreds of MB
        val gen = object : Reader() {
            var i = 0; var cur = "{\"locations\":["; var p = 0; var closed = false
            override fun read(buf: CharArray, off: Int, len: Int): Int {
                if (p >= cur.length) {
                    if (closed) return -1
                    if (i < 300_000) { cur = (if (i == 0) "" else ",") + "{\"latitudeE7\":$i,\"x\":[1,2,3]}"; i++ } else { cur = "]}"; closed = true }
                    p = 0
                }
                val n = minOf(len, cur.length - p); cur.toCharArray(buf, off, p, p + n); p += n; return n
            }
            override fun close() {}
        }
        var count = 0L; var sum = 0L; var producedAt1000 = -1
        val p = JsonPull(gen)
        p.readObject { name -> assertEquals("locations", name); p.readArray { val m = p.readValue() as Map<*, *>; count++; sum += m["latitudeE7"] as Long; if (count == 1000L) producedAt1000 = gen.i } }
        assertEquals(300_000L, count)
        assertEquals(299_999L * 300_000L / 2, sum)
        assertTrue("the 1000th element was handled while the source had produced only $producedAt1000 of 300000", producedAt1000 in 1000..1100)
    }

    @Test fun `skipValue steps over nested values without materialising`() {
        val p = JsonPull("""{"skip":{"a":[1,{"b":"}]"}]},"keep":7}""".reader())
        var kept: Any? = null
        p.readObject { n -> if (n == "keep") kept = p.readValue() else p.skipValue() }
        assertEquals(7L, kept)
    }

    @Test fun `bad input fails with the position, not silently`() {
        for (bad in listOf("""{"a":""", """{"a" 1}""", """[1,2""", """{"a":tru}""", "\"unterminated")) {
            try { JsonPull(bad.reader()).readValue(); fail("accepted $bad") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("char")) }
        }
    }

    @Test fun `a byte order mark and blank space before the document are fine`() {
        assertEquals(1L, (JsonPull("﻿ \n {\"a\":1}".reader()).readValue() as Map<*, *>)["a"])
    }
}
