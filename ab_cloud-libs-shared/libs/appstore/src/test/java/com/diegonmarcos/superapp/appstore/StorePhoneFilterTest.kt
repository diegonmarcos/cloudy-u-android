package com.diegonmarcos.superapp.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1791 Search > Phone's five filter rows over the central catalogue: each row alone, All, AND combinations, hits, sources, presets. */
class StorePhoneFilterTest {
    private val cat by lazy { StoreCatalogue.parse(listOf("src/main/assets/store-catalogue.json", "ab_cloud-libs-shared/libs/appstore/src/main/assets/store-catalogue.json").map(::File).first { it.isFile }.readText()) }
    private fun s(vararg p: Pair<String, String>) = StorePhoneFilter.State(mapOf(*p))
    private fun pk(st: StorePhoneFilter.State) = StorePhoneFilter.apply(cat, st).map { it.pkg }

    @Test fun allIsEverythingAndEveryRowStartsWithIt() {
        assertEquals(cat.entries.size, StorePhoneFilter.apply(cat, StorePhoneFilter.State()).size)
        for (r in cat.rows) assertEquals(cat.entries.size, StorePhoneFilter.apply(cat, s(r.id to StorePhoneFilter.ALL)).size)
        assertFalse(StorePhoneFilter.State().with("licence", "open").with("licence", "all").active)
    }

    @Test fun eachRowAloneKeepsExactlyItsValue() {
        for (r in cat.rows) for (v in r.values) {
            val got = StorePhoneFilter.apply(cat, s(r.id to v.id))
            if (v.id != "full") assertTrue("${r.id}:${v.id} is not empty", got.isNotEmpty())   // no app is recorded at the Full tier yet
            assertTrue("${r.id}:${v.id}", got.all { it.get(r.field) == v.id })
            assertEquals(cat.entries.count { it.get(r.field) == v.id }, got.size)
        }
        // the categories and the sources partition the catalogue; so do the licences and the tiers
        for (r in listOf("category", "licence", "play_integrity", "source")) assertEquals(r, cat.entries.size, cat.rows.first { it.id == r }.values.sumOf { v -> StorePhoneFilter.count(cat, StorePhoneFilter.State(), r, v.id) })
        assertEquals(cat.entries.count { it.get("category") == "games" }, StorePhoneFilter.count(cat, StorePhoneFilter.State(), "game_dimension", "2d") + StorePhoneFilter.count(cat, StorePhoneFilter.State(), "game_dimension", "3d"))
    }

    @Test fun rowsCombineWithAnd() {
        val openGames = pk(s("category" to "games", "licence" to "open"))
        assertEquals(25 + 2, openGames.size)   // the 25 open games of the games list + Open Sudoku and PPSSPP (recommendation rows)
        val g3 = pk(s("category" to "games", "licence" to "closed", "game_dimension" to "3d", "source" to "aurora"))
        assertTrue(g3.isNotEmpty() && g3.all { p -> cat.entries.first { it.pkg == p }.let { it.get("licence") == "closed" && it.get("game_dimension") == "3d" && it.get("source") == "aurora" } })
        assertEquals(listOf("com.nianticlabs.pokemongo"), pk(s("play_integrity" to "mid", "category" to "games")))
        assertEquals(emptyList<String>(), pk(s("licence" to "open", "play_integrity" to "full")))
        assertEquals(emptyList<String>(), pk(s("category" to "tools", "game_dimension" to "2d")))   // a dimension only exists on games
        assertTrue(pk(s("source" to "playstore")).all { cat.entries.first { e -> e.pkg == it }.get("licence") == "closed" })
        assertEquals(cat.entries.filter { it.get("source") == "other" }.map { it.pkg }.toSet(), pk(s("licence" to "open", "source" to "other")).toSet())   // GitHub-release games are open
        // tightening a row never adds an app
        assertTrue(pk(s("licence" to "open", "category" to "games")).toSet().subsetOf(pk(s("licence" to "open")).toSet()))
    }

    private fun Set<String>.subsetOf(o: Set<String>) = o.containsAll(this)

    @Test fun theFleetsOwnPackagesAreExcluded() {
        val first = cat.entries.first().pkg
        assertFalse(first in StorePhoneFilter.apply(cat, StorePhoneFilter.State(), setOf(first)).map { it.pkg })
    }

    @Test fun presetsAreTheFormerSubpages() {
        assertEquals("open", StorePhoneFilter.preset(StorePhoneSearch.OPEN).of("licence")); assertEquals("closed", StorePhoneFilter.preset(StorePhoneSearch.CLOSED).of("licence"))
        assertEquals("games", StorePhoneFilter.preset(StorePhoneSearch.GAMES).of("category")); assertFalse(StorePhoneFilter.preset(StorePhoneSearch.INTEGRITY).active)
        // the old Open source recommendations and the old Games list both live in a preset's view
        assertTrue(pk(StorePhoneFilter.preset(StorePhoneSearch.OPEN)).containsAll(listOf("org.videolan.vlc", "net.minetest.minetest")))
        assertTrue(pk(StorePhoneFilter.preset(StorePhoneSearch.CLOSED)).containsAll(listOf("com.chase.sig.android", "com.activision.callofduty.shooter")))
        assertTrue(pk(StorePhoneFilter.preset(StorePhoneSearch.OPEN)).none { it in pk(StorePhoneFilter.preset(StorePhoneSearch.CLOSED)) })
        assertEquals(cat.entries.size, pk(StorePhoneFilter.preset(StorePhoneSearch.OPEN)).size + pk(StorePhoneFilter.preset(StorePhoneSearch.CLOSED)).size)
    }

    @Test fun groupsKeepEveryEntryInDeclaredOrder() {
        val g = StorePhoneFilter.groupBy(cat, "category", cat.entries)
        assertEquals(listOf("tools", "services", "others", "games"), g.map { it.first.id })
        assertEquals(cat.entries.size, g.sumOf { it.second.size })
        assertEquals(listOf("none", "basic", "mid"), StorePhoneFilter.groupBy(cat, "play_integrity", cat.entries).map { it.first.id })
        assertEquals("the tiers page keeps every declared tier", listOf("none", "basic", "mid", "full"), StorePhoneFilter.groupBy(cat, "play_integrity", cat.entries, keepEmpty = true).map { it.first.id })
    }

    @Test fun aCatalogueEntryIsTheSameResultRowAsBefore() {
        val r = StorePhoneFilter.result(cat.entries.first { it.pkg == "org.stjr.srb2" })
        assertEquals(listOf("github"), r.sources); assertTrue(r.foss); assertTrue(r.icon.startsWith("https://")); assertEquals("https://github.com/SRB2-Mobile/SRB2-Android/releases", r.link)
        assertEquals(listOf("play"), StorePhoneFilter.result(cat.entries.first { it.pkg == "com.chase.sig.android" }).sources)
        assertEquals(listOf("fdroid"), StorePhoneFilter.result(cat.entries.first { it.pkg == "org.videolan.vlc" }).sources)
        assertEquals(listOf("com.chase.sig.android"), StorePhoneFilter.matchQuery(cat.entries, "chase").map { it.pkg })
        assertTrue(StorePhoneFilter.matchQuery(cat.entries, "  ").isEmpty())
    }

    private fun hit(pkg: String, vararg src: String, foss: Boolean = false) = StoreSuggest.Result("k:$pkg", pkg, pkg, "", src.toList(), foss, "", "")
    @Test fun aLiveHitOutsideTheCatalogueAnswersOnlyWhatItsSourcesCanSay() {
        val by = cat.entries.associateBy { it.pkg }
        val open = StorePhoneSearch::isOpen
        val fd = hit("x.fd", "fdroid", foss = true); val pl = hit("x.play", "play")
        assertTrue(StorePhoneFilter.matchesHit(cat, by, fd, s("licence" to "open", "source" to "fdroid"), open))
        assertFalse(StorePhoneFilter.matchesHit(cat, by, fd, s("licence" to "closed"), open))
        assertTrue(StorePhoneFilter.matchesHit(cat, by, pl, s("licence" to "closed", "source" to "aurora"), open))
        assertTrue(StorePhoneFilter.matchesHit(cat, by, pl, s("source" to "playstore"), open))
        assertFalse(StorePhoneFilter.matchesHit(cat, by, pl, s("category" to "games"), open))     // unknown: never guessed
        assertFalse(StorePhoneFilter.matchesHit(cat, by, pl, s("play_integrity" to "none"), open))
        assertTrue(StorePhoneFilter.matchesHit(cat, by, pl, StorePhoneFilter.State(), open))
        // a hit that IS in the catalogue is judged by its row, whatever source found it
        assertTrue(StorePhoneFilter.matchesHit(cat, by, hit("org.stjr.srb2", "github"), s("category" to "games", "game_dimension" to "3d"), open))
        assertFalse(StorePhoneFilter.matchesHit(cat, by, hit("org.stjr.srb2", "github"), s("game_dimension" to "2d"), open))
    }

    @Test fun searchSourcesFollowTheFilterAndTheDeclaredListAlwaysRuns() {
        val all = setOf("declared", "fdroid", "izzy", "github", "play")
        assertEquals(all, StorePhoneFilter.sources(StorePhoneFilter.State()))
        assertEquals(StorePhoneSearch.sources(StorePhoneSearch.OPEN), StorePhoneFilter.sources(StorePhoneFilter.preset(StorePhoneSearch.OPEN)))
        assertEquals(StorePhoneSearch.sources(StorePhoneSearch.CLOSED), StorePhoneFilter.sources(StorePhoneFilter.preset(StorePhoneSearch.CLOSED)))
        assertEquals(StorePhoneSearch.sources(StorePhoneSearch.GAMES), StorePhoneFilter.sources(StorePhoneFilter.preset(StorePhoneSearch.GAMES)))
        assertEquals(setOf("declared", "play"), StorePhoneFilter.sources(s("source" to "aurora")))
        assertEquals(setOf("declared", "fdroid", "izzy"), StorePhoneFilter.sources(s("source" to "fdroid")))
        assertEquals(setOf("declared", "play"), StorePhoneFilter.sources(s("play_integrity" to "mid")))
        for (r in cat.rows) for (v in r.values) assertTrue("declared runs for ${r.id}:${v.id}", "declared" in StorePhoneFilter.sources(s(r.id to v.id)))
    }

    @Test fun everyChipHasAStableTestTag() {
        assertEquals("store-search:phone-filter:category:games", StorePhoneFilter.tag("category", "games"))
        assertEquals("store-search:phone-filter:play_integrity:all", StorePhoneFilter.tag("play_integrity", StorePhoneFilter.ALL))
        val tags = cat.rows.flatMap { r -> (listOf(StorePhoneFilter.ALL) + r.values.map { it.id }).map { StorePhoneFilter.tag(r.id, it) } }
        assertEquals(tags.size, tags.toSet().size); assertEquals(21, tags.size)   // five rows: All + 4, All + 2, All + 4, All + 2, All + 4
    }
}
