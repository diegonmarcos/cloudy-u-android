package com.diegonmarcos.superapp.fleetdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/** #1547 downloadable packs: fetched once, verified, then served from the cache offline; a bad or failed download never replaces a good copy. */
class FleetPackTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val blob = "F0D1A9\tApple, Inc.\n".toByteArray()
    private val desc get() = PackDescriptor("https://x.test/p.tsv", sha(blob), blob.size.toLong())

    @Test fun downloadsOnceVerifiesAndThenAnswersOffline() {
        var hits = 0
        val c = PackCache(tmp.newFolder()) { hits++; ByteArrayInputStream(blob) }
        val f = c.resolve("wifi-oui", desc)!!
        assertEquals(String(blob), f.readText())
        assertEquals(1, hits)
        assertEquals(f.name, c.resolve("wifi-oui", desc)!!.name); assertEquals(1, hits)   // cached, no second download
        val offline = PackCache(f.parentFile) { null }
        assertEquals(String(blob), offline.resolve("wifi-oui", desc)!!.readText())          // offline, descriptor known
        assertEquals(String(blob), offline.resolve("wifi-oui", null)!!.readText())          // offline, descriptor not served either
    }

    @Test fun aCorruptDownloadIsRejectedAndTheOldCopyKept() {
        val dir = tmp.newFolder()
        val good = PackCache(dir) { ByteArrayInputStream(blob) }.resolve("p", desc)!!
        val newer = PackDescriptor("https://x.test/p2", sha("new".toByteArray()), 3)
        val got = PackCache(dir) { ByteArrayInputStream("tampered".toByteArray()) }.resolve("p", newer)
        assertEquals(good.name, got!!.name)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun nothingCachedAndOfflineIsNullNotACrash() {
        assertNull(PackCache(tmp.newFolder()) { null }.resolve("p", desc))
        assertNull(PackCache(tmp.newFolder()) { throw java.io.IOException("down") }.resolve("p", desc))
        assertNull(PackCache(tmp.newFolder()) { null }.resolve("../p", desc))
    }

    @Test fun aNewVersionReplacesTheOldOne() {
        val dir = tmp.newFolder()
        PackCache(dir) { ByteArrayInputStream(blob) }.resolve("p", desc)
        val b2 = "v2".toByteArray()
        val f = PackCache(dir) { ByteArrayInputStream(b2) }.resolve("p", PackDescriptor("https://x.test/p", sha(b2), 2))!!
        assertEquals("v2", f.readText())
        assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".pack") })
    }

    @Test fun descriptorParsing() {
        val d = PackDescriptor.parse("""{"schemaVersion":1,"url":"https://x.test/a","sha256":"${"A".repeat(64)}","bytes":5}""")
        assertEquals("a".repeat(64), d.sha256); assertEquals(5L, d.bytes)
        assertFalse(runCatching { PackDescriptor.parse("""{"url":"http://x","sha256":"${"a".repeat(64)}"}""") }.isSuccess)
    }
}
