package com.diegonmarcos.cloudstore

import com.diegonmarcos.superapp.torrent.Magnet
import com.diegonmarcos.superapp.torrent.TorrentEngines
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** #1681 the Torrent catalogue: the real dataset parses into the owner's categories, only legitimate entries, the hand-off, user entries, no baked data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TorrentCatalogueTest {
    private fun repoFile(rel: String): File = listOf(rel, "../$rel", "../../$rel", "../../../$rel").map(::File).firstOrNull { it.exists() }
        ?: error("$rel not found from ${File(".").absolutePath}")

    private val text by lazy { repoFile("data/fleet/torrent-catalogue.json").readText() }

    private val ownerCategories = listOf("movies", "tv", "games", "anime", "software", "ebooks", "audiobooks", "music", "os", "comics", "courses", "android")

    @Test fun `the real dataset keeps the owner's categories and every entry is usable`() {
        val c = TorrentCatalogue.parse(text)
        assertEquals(emptyList<String>(), c.problems)
        assertEquals(ownerCategories, c.categories.map { it.id })
        assertTrue(c.entries.size >= 10)
        c.entries.forEach { e ->
            assertNull(e.id, TorrentCatalogue.problem(e.name, e.magnet, e.torrentUrl))
            assertNotNull("${e.id} magnet", e.infoHash)
            assertTrue("${e.id} size", e.sizeBytes > 0)
            assertTrue("${e.id} https .torrent", e.torrentUrl!!.startsWith("https://"))
        }
        // the six distributions the ticket names are there
        listOf("ubuntu", "debian", "fedora", "linuxmint", "archlinux", "kali").forEach { d -> assertTrue(d, c.entries.any { it.id.startsWith(d) && it.category == "os" }) }
    }

    @Test fun `only legitimately distributable entries from the publishers' own hosts`() {
        val root = JSONObject(text)
        val a = root.getJSONArray("entries")
        val publisherHosts = setOf("releases.ubuntu.com", "cdimage.debian.org", "torrent.fedoraproject.org", "www.linuxmint.com", "archlinux.org", "cdimage.kali.org", "archive.org")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val id = o.getString("id")
            assertTrue("$id category", o.getString("category") in setOf("os", "movies"))
            assertTrue("$id licence", o.optString("licence").isNotBlank())
            assertTrue("$id host", java.net.URI(o.getString("torrent_url")).host in publisherHosts)
            // the magnet names exactly the info-hash computed from the publisher's .torrent
            assertEquals(id, o.getString("info_hash"), Magnet.infoHash(o.getString("magnet")))
            assertTrue("$id real 40-hex hash", Regex("^[0-9a-f]{40}$").matches(o.getString("info_hash")))
        }
    }

    @Test fun `a malformed document or entry never throws and says why`() {
        assertEquals(listOf("catalogue is not JSON"), TorrentCatalogue.parse("not json").problems)
        val c = TorrentCatalogue.parse("""{"schemaVersion":1,"categories":[{"id":"os","label":"OS"}],"entries":[
            {"id":"a","name":"A","category":"os","magnet":"magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"},
            {"id":"b","name":"B","magnet":"magnet:?dn=nohash"},
            {"id":"c","name":"","magnet":"magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"},
            {"id":"d","name":"D","torrent_url":"http://insecure/x.torrent"},
            {"id":"e","name":"E","category":"new-cat","torrent_url":"https://example.org/e.torrent"},
            {"id":"f","name":"F"}]}""")
        assertEquals(listOf("a", "e"), c.entries.map { it.id })
        assertEquals(4, c.problems.size)
        assertEquals(listOf("os", "new-cat"), c.categories.map { it.id })
        assertEquals(1, c.count("os"))
    }

    @Test fun `user entries are kept on the device, merged first and removable`() {
        val ctx = RuntimeEnvironment.getApplication()
        assertNotNull(TorrentCatalogue.addUser(ctx, "Bad", "os", "magnet:?dn=x", null))
        assertNull(TorrentCatalogue.addUser(ctx, "My ISO", "os", "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=my", null))
        val mine = TorrentCatalogue.userEntries(ctx)
        assertEquals(listOf("user-my-iso"), mine.map { it.id })
        val merged = TorrentCatalogue.merge(TorrentCatalogue.parse(text), mine)
        assertEquals("user-my-iso", merged.inCategory("os").first().id)
        assertTrue(catalogueLine(merged.entries.first(), "OS").contains("yours"))
        TorrentCatalogue.removeUser(ctx, "user-my-iso")
        assertTrue(TorrentCatalogue.userEntries(ctx).isEmpty())
    }

    @Test fun `hand-off uses the magnet first, else fetches the torrent, and the external intent carries it`() {
        val engine = TorrentEngines.get(RuntimeEnvironment.getApplication())
        var fetched: String? = null
        val withMagnet = TorrentCatalogue.parse(text).entries.first()
        TorrentCatalogue.download(engine, withMagnet) { fetched = it; null }
        assertNull("a magnet entry never fetches", fetched)
        val urlOnly = TorrentCatalogue.Entry("u", "U", "os", null, "https://example.org/u.torrent")
        val said = TorrentCatalogue.download(engine, urlOnly) { fetched = it; null }
        assertEquals("https://example.org/u.torrent", fetched)
        assertTrue(said, said.contains("could not fetch"))
        val chooser = TorrentCatalogue.externalIntent(withMagnet)!!
        val inner = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals(withMagnet.magnet, inner.dataString)
        assertNull(TorrentCatalogue.externalIntent(TorrentCatalogue.Entry("n", "N", "os", null, null)))
    }

    @Test fun `the catalogue is runtime data, never in the APK`() {
        val assets = repoFile("ac_cloud-store/app").resolve("src/main/assets")
        assertFalse(assets.walkTopDown().any { it.name.contains("torrent-catalogue") })
        val src = repoFile("ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/TorrentCatalogue.kt").readText()
        assertFalse("no magnet hash written in the code", Regex("btih:[0-9a-fA-F]{40}").containsMatchIn(src))
        assertTrue(src.contains("FleetData.get"))
        val manifest = JSONObject(repoFile("data/fleet/manifest.json").readText())
        assertTrue(manifest.getJSONObject("shared").has(TorrentCatalogue.DATASET))
    }
}
