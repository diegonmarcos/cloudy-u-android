package com.diegonmarcos.superapp.decisions.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1801 the fleet's AI key book: the fleet slot, the per-app override, the hint, and a status that never carries a key. */
class KeyBookTest {

    private val key = Fixtures.SECRET
    private val other = Fixtures.SHORT_SECRET + "wxyz"
    private val app = Fixtures.APP

    @Test
    fun `an empty book holds nothing`() {
        val b = KeyBook(MemoryStore())
        assertNull(b.get("openrouter"))
        assertNull(b.get("openrouter", app))
        assertFalse(b.present("openrouter"))
        assertEquals("", b.hint("openrouter"))
        assertTrue(b.overrides("openrouter").isEmpty())
    }

    @Test
    fun `the fleet key is set, read trimmed, replaced and cleared`() {
        val s = MemoryStore()
        val b = KeyBook(s)
        assertTrue(b.set("openrouter", "  $key  "))
        assertEquals(key, b.get("openrouter"))
        assertTrue(b.present("openrouter"))
        assertEquals(key.takeLast(4), b.hint("openrouter"))
        assertTrue(b.set("openrouter", other))
        assertEquals(other, b.get("openrouter"))
        assertTrue(b.clear("openrouter"))
        assertNull(b.get("openrouter"))
        assertFalse(b.present("openrouter"))
        assertFalse("cleared slot is removed, not blanked", s.read()!!.has("openrouter"))
    }

    @Test
    fun `a blank value clears and a blank provider is refused`() {
        val b = KeyBook(MemoryStore())
        b.set("openrouter", key)
        assertTrue(b.set("openrouter", "   "))
        assertNull(b.get("openrouter"))
        assertFalse(b.set("", key))
        assertFalse(b.set("  ", key))
        assertNull(b.get(""))
    }

    @Test
    fun `the app override comes first and falls back to the fleet key`() {
        val b = KeyBook(MemoryStore())
        b.set("openrouter", key)
        assertEquals(key, b.get("openrouter", app))
        b.set("openrouter", other, app)
        assertEquals(other, b.get("openrouter", app))
        assertEquals("another app still spends the fleet key", key, b.get("openrouter", "com.x.other"))
        assertEquals(key, b.get("openrouter"))
        assertEquals(key, b.get("openrouter", " "))
        assertTrue(b.present("openrouter", app))
        assertFalse(b.present("openrouter", "com.x.other"))
        assertEquals(listOf(app), b.overrides("openrouter"))
        assertTrue(b.overrides("other").isEmpty())
        b.clear("openrouter", app)
        assertEquals(key, b.get("openrouter", app))
        assertTrue(b.overrides("openrouter").isEmpty())
    }

    @Test
    fun `an override alone serves its app only`() {
        val b = KeyBook(MemoryStore())
        b.set("openrouter", key, app)
        assertEquals(key, b.get("openrouter", app))
        assertNull(b.get("openrouter"))
        assertNull(b.get("openrouter", "com.x.other"))
        assertFalse(b.present("openrouter"))
    }

    @Test
    fun `a short key shows no hint`() {
        val b = KeyBook(MemoryStore())
        b.set("p", "12345678901")
        assertTrue(b.present("p"))
        assertEquals("", b.hint("p"))
        b.set("p", "123456789012")
        assertEquals("9012", b.hint("p"))
        assertEquals(12, KeyBook.MIN_FOR_HINT)
        assertEquals(4, KeyBook.HINT_CHARS)
    }

    @Test
    fun `the book persists through its store`() {
        val s = MemoryStore()
        KeyBook(s).set("openrouter", key)
        KeyBook(s).set("openrouter", other, app)
        val again = KeyBook(s)
        assertEquals(key, again.get("openrouter"))
        assertEquals(other, again.get("openrouter", app))
    }

    @Test
    fun `status says presence and the hint and never the key`() {
        val b = KeyBook(MemoryStore())
        b.set("openrouter", key)
        b.set("openrouter", other, app)
        val st = b.status(linkedMapOf("openrouter" to "OpenRouter", "none" to "None"))
        assertTrue(st.getBoolean("ok"))
        val text = st.toString()
        assertFalse("the status carries no key", text.contains(key) || text.contains(other))
        val ps = st.getJSONArray("providers")
        assertEquals(2, ps.length())
        val p = ps.getJSONObject(0)
        assertEquals("openrouter", p.getString("id"))
        assertEquals("OpenRouter", p.getString("label"))
        assertTrue(p.getBoolean("key_present"))
        assertEquals(key.takeLast(4), p.getString("key_hint"))
        val o = p.getJSONArray("overrides").getJSONObject(0)
        assertEquals(app, o.getString("app"))
        assertTrue(o.getBoolean("key_present"))
        assertEquals(other.takeLast(4), o.getString("key_hint"))
        val n = ps.getJSONObject(1)
        assertFalse(n.getBoolean("key_present"))
        assertEquals("", n.getString("key_hint"))
        assertEquals(0, n.getJSONArray("overrides").length())
    }

    @Test
    fun `an unreadable store reads as empty`() {
        val broken = object : KvStore {
            override fun read(): JSONObject? = null
            override fun write(doc: JSONObject) {}
        }
        val b = KeyBook(broken)
        assertTrue(b.set("openrouter", key))
        assertNull(b.get("openrouter"))
        assertFalse(b.present("openrouter"))
    }

    @Test
    fun `a one-time import stores into an empty slot, the engine's key wins, a malformed value is refused`() {
        val b = KeyBook(MemoryStore())
        assertEquals(KeyBook.Import.STORED, b.importOnce("openrouter", "  $key  "))
        assertEquals(key, b.get("openrouter"))
        assertEquals("the engine's key wins", KeyBook.Import.KEPT_ENGINE, b.importOnce("openrouter", other))
        assertEquals(key, b.get("openrouter"))
        assertEquals(KeyBook.Import.STORED, b.importOnce("openrouter", other, app))
        assertEquals(other, b.get("openrouter", app))
        assertEquals(KeyBook.Import.KEPT_ENGINE, b.importOnce("openrouter", key, app))
        assertEquals(other, b.get("openrouter", app))
        for (bad in listOf(null, "", "   ", "a b", "x".repeat(KeyBook.MAX_KEY_CHARS + 1))) assertEquals(KeyBook.Import.REFUSED, b.importOnce("p2", bad))
        assertEquals(KeyBook.Import.REFUSED, b.importOnce("", key))
        assertFalse(b.present("p2"))
        assertEquals(KeyBook.Import.STORED, b.importOnce("p2", "x".repeat(KeyBook.MAX_KEY_CHARS)))
        assertEquals("stored", KeyBook.Import.STORED.wire); assertEquals("kept_engine", KeyBook.Import.KEPT_ENGINE.wire); assertEquals("refused", KeyBook.Import.REFUSED.wire)
    }
}
