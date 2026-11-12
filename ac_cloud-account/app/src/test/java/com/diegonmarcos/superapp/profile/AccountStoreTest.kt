package com.diegonmarcos.superapp.profile

import android.app.Application
import com.diegonmarcos.superapp.profile.AccountStore.Slot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #778 — the three files side by side: one pattern, metadata that names the body, tampering seen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val body = JSONObject().put("about", JSONObject().put("profile", JSONObject().put("name", "Ada")))

    @Test fun `each slot round-trips with its own metadata`() {
        val store = AccountStore(tmp.root, AccountStore.PlainIo)
        for (slot in Slot.values()) assertNull("$slot starts empty", store.read(slot))
        store.write(Slot.S, body, "GitHub · WebAuth", "2026-10-02T10:00:00Z")
        store.write(Slot.L, JSONObject(body.toString()).put("x", 1), "edited on this phone", "2026-10-02T11:00:00Z")
        val s = store.read(Slot.S)!!
        assertEquals(Slot.S, s.meta.slot)
        assertEquals("GitHub · WebAuth", s.meta.source)
        assertEquals("2026-10-02T10:00:00Z", s.meta.at)
        assertEquals(AccountDrift.sha256(body), s.meta.sha256)
        assertTrue(s.intact)
        assertEquals(AccountDrift.canonical(body), AccountDrift.canonical(s.body))
        assertEquals(1, store.read(Slot.L)!!.body.getInt("x"))
        assertNull("R untouched", store.read(Slot.R))
    }

    @Test fun `one pattern on disk - _meta, body, and apps for R`() {
        val store = AccountStore(tmp.root, AccountStore.PlainIo)
        val apps = JSONObject().put("about", JSONObject().put("status", "reachable"))
        store.write(Slot.R, body, "runtime", "t", apps)
        val onDisk = JSONObject(java.io.File(tmp.root, "R.json").readText())
        assertEquals(setOf("_meta", "body", "apps"), onDisk.keys().asSequence().toSet())
        assertEquals("R", onDisk.getJSONObject("_meta").getString("file"))
        assertEquals("reachable", store.read(Slot.R)!!.apps!!.getJSONObject("about").getString("status"))
        assertEquals(onDisk.toString(), JSONObject(store.export(Slot.R)!!).toString())
    }

    @Test fun `a body edited behind the metadata reads as not intact`() {
        val store = AccountStore(tmp.root, AccountStore.PlainIo)
        store.write(Slot.L, body, "edited", "t")
        val f = java.io.File(tmp.root, "L.json")
        f.writeText(f.readText().replace("Ada", "Eve"))
        val l = store.read(Slot.L)!!
        assertFalse(l.intact)
        assertEquals("Eve", l.body.getJSONObject("about").getJSONObject("profile").getString("name"))
    }

    @Test fun `the bytes go through the Io it was given`() {
        val seen = mutableListOf<String>()
        val io = object : AccountStore.Io {
            val files = HashMap<String, ByteArray>()
            override fun read(file: java.io.File) = files[file.name]
            override fun write(file: java.io.File, bytes: ByteArray) { seen += file.name; files[file.name] = bytes }
        }
        val store = AccountStore(tmp.root, io)
        store.write(Slot.S, body, "x", "t")
        assertEquals(listOf("S.json"), seen)
        assertFalse("nothing reached the disk directly", java.io.File(tmp.root, "S.json").exists())
        assertTrue(store.read(Slot.S)!!.intact)
        store.delete(Slot.S)
    }
}
