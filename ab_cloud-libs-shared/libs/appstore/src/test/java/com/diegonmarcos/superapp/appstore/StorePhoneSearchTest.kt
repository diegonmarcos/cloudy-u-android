package com.diegonmarcos.superapp.appstore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1774 Search > Phone as four subpages: sources, filters, Play Integrity tiers, games, price badge, and parity with the single Phone page. Pure, no device. */
class StorePhoneSearchTest {
    private fun repoFile(rel: String): File = listOf(rel, "../$rel", "../../$rel", "../../../$rel", "../../../../$rel", "../../../../../$rel").map(::File).firstOrNull { it.exists() }
        ?: error("$rel not found from ${File(".").absolutePath}")
    private fun page(): String = listOf(File("src/main/java/com/diegonmarcos/superapp/appstore/StoreSearchPage.kt"),
        File("ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreSearchPage.kt")).first { it.isFile }.readText()
    private fun res(pkg: String, vararg src: String, foss: Boolean = false, price: String = "", repo: String = "") =
        StoreSuggest.Result("k:$pkg", pkg, pkg, "", src.toList(), foss, repo, "", "", price)
    private val phoneSources = listOf(StoreSearchPage.DECLARED, "fdroid", "izzy", StoreSearchPage.GITHUB, StoreSearchPage.PLAY)

    @Test fun fourSubpagesInOrderWithTheirLabels() {
        assertEquals(listOf("phone-open", "phone-closed", "phone-integrity", "phone-games"), StorePhoneSearch.SUBS)
        assertEquals(listOf("Open source", "Non open source", "Play Integrity", "Games"), StorePhoneSearch.SUBS.map(StorePhoneSearch::label))
    }

    // ── parity: nothing of the single Phone page was removed ───────────
    @Test fun everyPhoneSourceStillRunsOnSomeSubpageAndNoneIsInvented() {
        val map = JSONObject(File(repoFile("ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-install-sources.json").path).readText())
        val arr = map.getJSONObject("search").getJSONArray("sources")
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.filter { StoreSearchPage.pageOf(it) == StoreSearchPage.PAGE_ANDROID }
        assertEquals(phoneSources.toSet(), ids.toSet())
        val union = StorePhoneSearch.SUBS.flatMap { StorePhoneSearch.sources(it) }.toSet()
        assertEquals("the subpages together run exactly the Phone sources", ids.toSet(), union)
        for (id in ids) assertTrue("$id runs on the Phone page", StoreSearchPage.runsOnPage(id, StoreSearchPage.PAGE_ANDROID, StorePhoneSearch.SUBS.first { id in StorePhoneSearch.sources(it) }))
        assertFalse("the fleet source stays Cloudy's", StorePhoneSearch.SUBS.any { StoreSearchPage.FLEET in StorePhoneSearch.sources(it) })
        // Cloudy and Torrent are untouched: a source runs on its page whatever Phone subpage is open.
        assertTrue(StoreSearchPage.runsOnPage(StoreSearchPage.FLEET, StoreSearchPage.PAGE_CLOUDY, StorePhoneSearch.GAMES))
    }

    @Test fun everyPhoneResultLandsOnAtLeastOneSubpage() {
        val sets = listOf(listOf("fdroid"), listOf("izzy"), listOf("github"), listOf("declared"), listOf("play"), listOf("fdroid", "play"), listOf("declared", "play"), listOf("play", "izzy"))
        for (src in sets) for (foss in listOf(true, false)) {
            val r = res("p.q", *src.toTypedArray(), foss = foss)
            assertTrue("$src foss=$foss is shown somewhere", StorePhoneSearch.SUBS.any { StorePhoneSearch.keep(it, r, emptySet()) })
        }
        // a repository-only GitHub hit (no package) is still an Open source result
        assertTrue(StorePhoneSearch.keep(StorePhoneSearch.OPEN, res("", "github", repo = "https://github.com/a/b"), emptySet()))
    }

    @Test fun existingActionsAndSurfacesStayInTheSource() {
        val s = page()
        // the verbs of the one result row, every one
        for (k in listOf("R.string.store_search_releases", "R.string.store_phone_open", "R.string.store_phone_install", "R.string.store_search_install_declare",
            "R.string.store_search_undeclare", "R.string.store_search_declare", "Kind.ORIGIN", "DeclaredEdits.declare(", "DeclaredEdits.undeclare(", "ExternalInstall.run(", "FleetInstall.run("))
            assertTrue("kept: $k", s.contains(k))
        // the surfaces the single Phone page drew
        for (k in listOf("PhoneRecsView(ctx, cfg, d, phoneRecs", "LandingView(ctx, cfg, phoneLanding", "Licence.values()", "store-search:source:", "store-search:retry:",
            "hideFleet = sub == PAGE_ANDROID", "StoreSearchPage.pageBlurb", "ResultGrid(", "store-search:licence:"))
            assertTrue("kept: ${k.removePrefix("StoreSearchPage.")}", s.contains(k.removePrefix("StoreSearchPage.")))
        assertEquals("one result row for every subpage", 1, Regex("""private fun ResultRow\(""").findAll(s).count())
        assertEquals("each sub is a pill", 1, Regex("""StorePhoneSearch\.SUBS\.forEach""").findAll(s).count())
    }

    @Test fun fleetPackagesStayExcludedFromEverySubpageList() {
        val fleet = JSONObject(repoFile("aa_cloud-superapp/data/constellation-fleet.json").readText()).getJSONArray("apps").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("package") }.toSet() }
        assertTrue(fleet.isNotEmpty())
        val integrity = StorePhoneSearch.parseIntegrity(repoFile("data/fleet/store-play-integrity.json").readText())
        assertTrue("no fleet app in the integrity dataset", integrity.apps.none { it.pkg in fleet })
        val withFleet = StorePhoneSearch.Integrity(integrity.tiers, integrity.apps + StorePhoneSearch.IntApp(fleet.first(), "x", "device", "reported", ""))
        assertTrue(StorePhoneSearch.integrityResults(withFleet, fleet).none { it.pkg in fleet })
        val games = StorePhoneSearch.parseGames(repoFile("data/fleet/games-catalogue.json").readText())
        assertTrue(StorePhoneSearch.games(games, fleet).none { it.pkg in fleet })
        assertTrue(StorePhoneSearch.games(StorePhoneSearch.Games(emptyList(), listOf(StorePhoneSearch.Game(fleet.first(), "f", "", "", "c", "play", ""))), fleet).isEmpty())
        // the existing result filter is unchanged
        assertTrue(page().contains("filter { !hideFleet || it.pkg !in fleetPackages }"))
    }

    // ── subpage filters ────────────────────────────────────────────────
    @Test fun openAndNonOpenSplitByLicenceAndSource() {
        val fd = res("a.a", "fdroid", foss = true); val iz = res("b.b", "izzy", foss = true); val gh = res("c.c", "github")
        val play = res("d.d", "play", price = "Free"); val paid = res("e.e", "play", price = "$2.99"); val declaredClosed = res("f.f", "declared")
        val declaredOpen = res("g.g", "declared", foss = true); val playFoss = res("h.h", "play", foss = true)
        val none = emptySet<String>()
        for (r in listOf(fd, iz, gh, declaredOpen, playFoss)) assertTrue(r.pkg, StorePhoneSearch.keep(StorePhoneSearch.OPEN, r, none))
        for (r in listOf(play, paid, declaredClosed)) { assertFalse(r.pkg, StorePhoneSearch.keep(StorePhoneSearch.OPEN, r, none)); assertTrue(r.pkg, StorePhoneSearch.keep(StorePhoneSearch.CLOSED, r, none)) }
        for (r in listOf(fd, iz, gh, declaredOpen, playFoss)) assertFalse(r.pkg, StorePhoneSearch.keep(StorePhoneSearch.CLOSED, r, none))
    }

    @Test fun freeAndPaidBadgeIsPlaysOwnAndNeverGuessed() {
        assertEquals("Free", StorePhoneSearch.priceBadge("Free")); assertEquals("$2.99", StorePhoneSearch.priceBadge(" $2.99 "))
        assertEquals("", StorePhoneSearch.priceBadge(""))                                    // Play did not say: no claim
        val label = { id: String -> if (id == "play") "Play (Aurora)" else id }
        assertEquals("Free · Play (Aurora)", StorePhoneSearch.closedBadge(res("a.a", "play", price = "Free"), label))
        assertEquals("$2.99 · Play (Aurora)", StorePhoneSearch.closedBadge(res("a.a", "play", price = "$2.99"), label))
        assertEquals("Play (Aurora)", StorePhoneSearch.closedBadge(res("a.a", "play"), label))
        // the price travels through the merge from the source that had it
        val m = StoreSuggest.merge(listOf("declared", "play"), listOf(StoreSuggest.Hit("declared", "x.y", "X"), StoreSuggest.Hit("play", "x.y", "X", price = "Free")))
        assertEquals("Free", m.single().price)
    }

    @Test fun nonOpenKeepsAGamesAndIntegrityResultToo() {
        val g = res("com.game", "play", price = "Free")
        assertTrue(StorePhoneSearch.keep(StorePhoneSearch.GAMES, g, setOf("com.game")))
        assertFalse(StorePhoneSearch.keep(StorePhoneSearch.GAMES, res("not.game", "play"), setOf("com.game")))
        assertTrue(StorePhoneSearch.keep(StorePhoneSearch.CLOSED, g, setOf("com.game")))
        assertTrue(StorePhoneSearch.keep(StorePhoneSearch.INTEGRITY, g, emptySet()))
        assertFalse("integrity is Google Play apps", StorePhoneSearch.keep(StorePhoneSearch.INTEGRITY, res("a.a", "fdroid", foss = true), emptySet()))
    }

    // ── Play Integrity dataset ─────────────────────────────────────────
    @Test fun integrityDatasetIsDeclaredServedAndHonest() {
        val text = repoFile("data/fleet/store-play-integrity.json").readText()
        val data = StorePhoneSearch.parseIntegrity(text)
        assertTrue(data.problems.toString(), data.problems.isEmpty())
        assertEquals(listOf("basic", "device", "strong", "unknown"), data.tiers.map { it.id })
        assertEquals(listOf("MEETS_BASIC_INTEGRITY", "MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY"), data.tiers.take(3).map { it.verdict })
        assertTrue("seeded with well-known and banking apps", data.apps.size >= 15)
        assertTrue(data.apps.any { it.pkg == "com.chase.sig.android" } && data.apps.any { it.pkg == "com.google.android.apps.walletnfcrel" })
        assertTrue("every tier is a declared one", data.apps.all { a -> data.tiers.any { it.id == a.tier } })
        assertTrue("honest unknowns are present", data.apps.any { it.tier == "unknown" })
        assertTrue("every row says how sure it is", data.apps.all { it.basis in setOf("publisher-documented", "reported", "unverified") && it.note.isNotBlank() })
        assertTrue("a tier other than unknown is never 'unverified'", data.apps.filter { it.tier != "unknown" }.none { it.basis == "unverified" })
        val man = JSONObject(repoFile("data/fleet/manifest.json").readText()).getJSONObject("shared")
        assertTrue("declared in the fleet manifest", man.has(StorePhoneSearch.DATASET_INTEGRITY) && man.has(StorePhoneSearch.DATASET_GAMES))
        assertFalse("never baked into the APK", File("src/main/assets/store-play-integrity.json").exists())
    }

    @Test fun tiersGroupWithEmptyOnesKeptAndAnUnlistedAppIsUnknown() {
        val data = StorePhoneSearch.Integrity(StorePhoneSearch.DEFAULT_TIERS, listOf(
            StorePhoneSearch.IntApp("a.a", "A", "device", "reported", "n"), StorePhoneSearch.IntApp("b.b", "B", "strong", "reported", "n"),
            StorePhoneSearch.IntApp("c.c", "C", "weird", "reported", "n"), StorePhoneSearch.IntApp("d.d", "D", "unknown", "unverified", "n")))
        assertEquals("device", StorePhoneSearch.tierOf(data, "a.a"))
        assertEquals("a tier the dataset does not declare is unknown", "unknown", StorePhoneSearch.tierOf(data, "c.c"))
        assertEquals("unknown", StorePhoneSearch.tierOf(data, "zz.zz")); assertEquals("unknown", StorePhoneSearch.tierOf(null, "a.a"))
        val g = StorePhoneSearch.groupByTier(data, listOf("a.a", "b.b", "c.c", "d.d", "zz.zz")) { it }
        assertEquals(listOf("basic", "device", "strong", "unknown"), g.map { it.first.id })
        assertEquals(listOf(emptyList(), listOf("a.a"), listOf("b.b"), listOf("c.c", "d.d", "zz.zz")), g.map { it.second })
        assertTrue(StorePhoneSearch.tierLine(data, "a.a").contains("Device") && StorePhoneSearch.tierLine(data, "a.a").contains("reported"))
        assertTrue(StorePhoneSearch.tierLine(data, "zz.zz").contains("not in the dataset"))
    }

    @Test fun badDatasetsNeverThrow() {
        assertTrue(StorePhoneSearch.parseIntegrity(null).problems.isNotEmpty()); assertTrue(StorePhoneSearch.parseIntegrity("{").apps.isEmpty())
        assertTrue(StorePhoneSearch.parseGames("nope").games.isEmpty()); assertTrue(StorePhoneSearch.parseGames("").problems.isNotEmpty())
        assertEquals(StorePhoneSearch.DEFAULT_TIERS.map { it.id }, StorePhoneSearch.parseIntegrity("""{"apps":[{"pkg":"a.a"}]}""").tiers.map { it.id })
        assertEquals("unknown", StorePhoneSearch.parseIntegrity("""{"apps":[{"pkg":"a.a"}]}""").apps.single().tier)
    }

    // ── Games ──────────────────────────────────────────────────────────
    @Test fun gamesCatalogueGroupsByCategoryAndKeepsEveryGame() {
        val g = StorePhoneSearch.parseGames(repoFile("data/fleet/games-catalogue.json").readText())
        assertTrue(g.games.isNotEmpty())
        val groups = StorePhoneSearch.gamesByCategory(g, g.games)
        assertEquals(g.games.map { it.pkg }.toSet(), groups.flatMap { it.second }.map { it.pkg }.toSet())
        val r = StorePhoneSearch.gameResult(g.games.first())
        assertEquals(g.games.first().pkg, r.pkg); assertEquals(g.games.first().icon, r.icon); assertEquals(listOf(g.games.first().source), r.sources)
        val first = g.games.first()
        assertTrue(StorePhoneSearch.matchGames(g.games, first.name.take(4)).any { it.pkg == first.pkg })
        assertTrue(StorePhoneSearch.matchGames(g.games, "  ").isEmpty())
    }
}
