package com.diegonmarcos.capitalfinterm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The source catalog (data/sources.json) parsed, and every FinTerm entry held to a declared source. */
class CatalogTest {
    private val sources = Catalog.parse(DataFiles.sourcesJson)

    /** (tab id, entry title) of every dashboard entry build.json declares, section titles aside. */
    private fun finTermEntries(): List<Pair<String, String>> {
        val stacks = JSONObject(DataFiles.buildJson).getJSONObject("ui").getJSONObject("stacks")
        val nav = JSONObject(DataFiles.buildJson).getJSONObject("ui").getJSONArray("bottom_nav")
        return (0 until nav.length()).map { nav.getString(it) }.flatMap { tab ->
            val arr = stacks.getJSONArray(tab)
            (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("kind", "entry") == Pages.KIND_DASHBOARD }.map { tab to it.getString("title") }
        }
    }

    @Test fun theCatalogParsesWithTheFourCategories() {
        assertTrue("catalog is empty", sources.size >= 12)
        assertEquals(listOf("FI-Macro", "Equity-Exchange", "Options", "News-Alt"), Catalog.categories(DataFiles.sourcesJson, sources))
        assertEquals("ids are unique", sources.size, sources.map { it.id }.toSet().size)
    }

    @Test fun everySourceIsFullyDeclared() {
        for (s in sources) {
            assertTrue("${s.id} name", s.name.isNotBlank())
            assertTrue("${s.id} coverage", s.coverage.isNotBlank())
            assertTrue("${s.id} auth type ${s.authType}", s.authType in Catalog.AUTH_TYPES)
            assertTrue("${s.id} has an endpoint, a fleet endpoint, or is user mapped", s.endpoint.isNotBlank() || s.userEndpoint || s.fleetEndpoint.isNotBlank())
            assertTrue("${s.id} feeds something", s.feeds.isNotEmpty())
            if (s.needsKey) assertNotNull("${s.id} is keyed so it says where the key goes", s.keyIn)
            if (s.connector.isNotEmpty()) {
                assertTrue("${s.id} connector ${s.connector} is not implemented", s.connector in Providers.CONNECTORS)
                assertEquals("${s.id} a live connector needs no key", Catalog.AUTH_NONE, s.authType)
            }
        }
        assertTrue(sources.any { it.id == "fred" && it.authType == Catalog.AUTH_NONE && it.hasConnector })
        assertTrue(sources.any { it.authType == Catalog.AUTH_PAID })
    }

    @Test fun everyFinTermEntryMapsToAtLeastOneDeclaredSource() {
        val entries = finTermEntries()
        assertEquals("entries read from build.json", 34, entries.size) // #1447 PM A1, B1, C1, D1, D0 (#1478 A2 dropped); #1612 EQ DM+EM merged into World Indices Equity
        assertEquals(emptyList<Pair<String, String>>(), Catalog.unmapped(sources, entries))
    }

    @Test fun everyFeedNamesAnEntryThatExists() {
        val entries = finTermEntries()
        for (s in sources) for (f in s.feeds) for (e in f.entries) {
            val ok = if (e == "*") entries.any { it.first == f.tab } else (f.tab to e) in entries
            assertTrue("${s.id} feeds ${f.tab}/$e which is not a FinTerm entry", ok)
        }
    }

    @Test fun anUnmappedEntryIsReported() {
        assertEquals(listOf("cb" to "Nowhere"), Catalog.unmapped(sources.filter { !it.primary }, listOf("cb" to "Yield Curves", "cb" to "Nowhere")))
    }

    @Test fun aJsonNullIsEmptyNotTheTextNull() {
        val s = Catalog.parse("""{"sources":[{"id":"x","name":"X","category":"c","coverage":"","endpoint":"","docs":"","connector":null,"auth":{"type":"none"},"feeds":[]}]}""")
        assertEquals("", s[0].connector)
        assertTrue(Catalog.parse("not json").isEmpty())
    }
}
