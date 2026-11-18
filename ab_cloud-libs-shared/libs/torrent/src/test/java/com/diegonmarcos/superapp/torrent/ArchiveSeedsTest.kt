package com.diegonmarcos.superapp.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1790 an Internet Archive torrent's baked data-server seed goes stale when the item moves (RondoAllaTurca_201401 still names
 * ia902704 /28/items/, which no longer answers); the live servers come from archive.org/metadata and become plain-HTTP web seeds.
 */
class ArchiveSeedsTest {
    private fun meta(name: String, seeds: List<String>) = Magnet.MetaInfo("0".repeat(40), name, 1L, seeds)

    /** The shape of archive.org/metadata/RondoAllaTurca_201401 (alternate_locations FIRST, as archive.org orders it, with its own dir). */
    private val doc = """{"alternate_locations":{"servers":[{"server":"dn710908.ca.archive.org","dir":"/0/items/RondoAllaTurca_201401"}],
        "workable":[{"server":"dn710908.ca.archive.org","dir":"/0/items/RondoAllaTurca_201401"}]},"created":1763466000,"d1":"ia601607.us.archive.org","d2":"ia801607.us.archive.org",
        "dir":"/19/items/RondoAllaTurca_201401","files":[{"name":"Rondo_alla_turca_2.mp3","size":"5702577","md5":"x"}],
        "server":"ia601607.us.archive.org","workable_servers":["ia601607.us.archive.org","ia801607.us.archive.org"]}"""

    @Test fun identifierOnlyForArchiveSeededTorrents() {
        assertEquals("RondoAllaTurca_201401", ArchiveSeeds.identifier(meta("RondoAllaTurca_201401", listOf("https://archive.org/download/", "http://ia902704.us.archive.org/28/items/"))))
        assertEquals("X", ArchiveSeeds.identifier(meta("X", listOf("http://ia902704.us.archive.org/28/items/"))))
        assertNull(ArchiveSeeds.identifier(meta("ubuntu", listOf("https://cdimage.ubuntu.com/"))))
        assertNull(ArchiveSeeds.identifier(meta("ubuntu", emptyList())))
        assertNull(ArchiveSeeds.identifier(meta("x", listOf("https://notarchive.org/"))))
        assertNull(ArchiveSeeds.identifier(meta("../etc", listOf("https://archive.org/download/"))))
    }

    @Test fun liveServersBecomeHttpSeedsWithTheItemsParent() {
        assertEquals(listOf("http://ia601607.us.archive.org/19/items/", "http://ia801607.us.archive.org/19/items/"),
            ArchiveSeeds.fromMetadata(doc, "RondoAllaTurca_201401"))
    }

    /** Run 44298351157: the alternate's /0/items dir read against the item's own servers made every file a redirect. */
    @Test fun eachServerKeepsItsOwnDir() {
        val seeds = ArchiveSeeds.fromMetadata(doc, "RondoAllaTurca_201401")
        assertTrue(seeds.none { "/0/items/" in it })
        assertTrue(seeds.none { "dn710908" in it })
    }

    @Test fun alternateLocationsOnlyWhenTheItemListsNoServer() {
        val d = """{"alternate_locations":{"servers":[{"server":"dn1.ca.archive.org","dir":"/0/items/A"},{"server":"evil.example.com","dir":"/0/items/A"}],"workable":[]},"dir":"/9/items/A","files":[]}"""
        assertEquals(listOf("http://dn1.ca.archive.org/0/items/"), ArchiveSeeds.fromMetadata(d, "A"))
    }

    @Test fun malformedJsonGivesNothing() {
        assertTrue(ArchiveSeeds.fromMetadata("""{"dir":"/1/items/A","workable_servers":["ia1.us.archive.org"]""", "A").isEmpty())
        assertTrue(ArchiveSeeds.fromMetadata("not json", "A").isEmpty())
        assertEquals(listOf("http://ia1.us.archive.org/1/items/"),
            ArchiveSeeds.fromMetadata("""{"n":-1.5e3,"b":true,"z":null,"s":"a\"\u00e9\/","dir":"\/1\/items\/A","workable_servers":["ia1.us.archive.org"]}""", "A"))
    }

    @Test fun fallsBackToD1D2ServerWithoutWorkableServers() {
        val d = """{"d1":"ia1.us.archive.org","d2":"ia2.us.archive.org","server":"ia1.us.archive.org","dir":"/7/items/A"}"""
        assertEquals(listOf("http://ia1.us.archive.org/7/items/", "http://ia2.us.archive.org/7/items/"), ArchiveSeeds.fromMetadata(d, "A"))
    }

    @Test fun rejectsForeignHostsAndOddDirs() {
        assertTrue(ArchiveSeeds.fromMetadata("""{"workable_servers":["evil.example.com"],"dir":"/1/items/A"}""", "A").isEmpty())
        assertTrue(ArchiveSeeds.fromMetadata("""{"workable_servers":["ia1.us.archive.org"],"dir":"/1/items/B"}""", "A").isEmpty())
        assertTrue(ArchiveSeeds.fromMetadata("""{"workable_servers":["ia1.us.archive.org"],"dir":"/../A"}""", "A").isEmpty())
        assertTrue(ArchiveSeeds.fromMetadata("""{"workable_servers":["ia1.us.archive.org"]}""", "A").isEmpty())
        assertTrue(ArchiveSeeds.fromMetadata("{}", "A").isEmpty())
    }

    @Test fun resolveFetchesTheItemsMetadataAndSurvivesFailure() {
        val m = meta("RondoAllaTurca_201401", listOf("https://archive.org/download/"))
        var asked: String? = null
        assertEquals(2, ArchiveSeeds.resolve(m) { asked = it; doc }.size)
        assertEquals("https://archive.org/metadata/RondoAllaTurca_201401", asked)
        assertTrue(ArchiveSeeds.resolve(m) { null }.isEmpty())
        assertTrue(ArchiveSeeds.resolve(m) { error("offline") }.isEmpty())
        assertTrue(ArchiveSeeds.resolve(meta("ubuntu", listOf("https://cdimage.ubuntu.com/"))) { error("must not fetch") }.isEmpty())
    }
}
