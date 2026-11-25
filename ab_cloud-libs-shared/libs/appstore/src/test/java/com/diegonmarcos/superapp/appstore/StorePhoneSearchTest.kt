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
        val map = JSONObject(File(System.getenv("FLEET_PRIVATE_DIR") ?: "../../../fleet-private-data", "appstore-install-sources.json").readText())
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
        for (k in listOf("CatalogueView(ctx, cfg, full, catalogue", "LandingView(ctx, cfg, phoneLanding", "Licence.values()", "store-search:source:", "store-search:retry:",
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

    // ── #1776 Games split by licence and graphics ─────────────────────
    private fun catalogue() = StorePhoneSearch.parseGames(repoFile("data/fleet/games-catalogue.json").readText())
    private fun raw() = JSONObject(repoFile("data/fleet/games-catalogue.json").readText()).getJSONArray("games")

    /** Every package of the catalogue before the split (#1765, 97 rows): nothing may be dropped (rule 16). */
    private val before = listOf(
        "com.activision.callofduty.shooter", "com.dts.freefireth", "com.dts.freefiremax",
        "com.tencent.ig", "com.supercell.brawlstars", "com.netease.mrzhna",
        "com.mojang.minecraftpe", "com.nianticlabs.pokemongo", "com.ustwo.monumentvalley2",
        "com.chucklefish.stardewvalley", "com.noodlecake.altosodyssey", "com.noodlecake.altosadventure",
        "com.tgc.sky.android", "com.playdead.limbo.full", "com.ustwo.monumentvalley",
        "com.king.candycrushsaga", "com.playrix.gardenscapes", "com.block.juggle",
        "com.dreamgames.royalmatch", "nz.co.codepoint.minimetro", "com.zeptolab.ctr.ads",
        "com.rovio.baba", "com.supercell.clashofclans", "com.supercell.clashroyale",
        "com.ea.game.pvz2_row", "com.ninjakiwi.bloonstd6", "com.lilithgame.roc.gp",
        "com.ironhidegames.android.kingdomrush", "com.miHoYo.GenshinImpact", "com.HoYoverse.hkrpgoversea",
        "com.plarium.raidlegends", "com.lilithgame.hgame.gp", "com.blizzard.diablo.immortal",
        "com.jagex.oldscape.android", "com.netmarble.sololv", "com.gameloft.android.ANMP.GloftA9HM",
        "com.gameloft.android.ANMP.GloftA8HM", "com.naturalmotion.customstreetracer2", "com.fingersoft.hillclimb",
        "com.fingersoft.hcr2", "com.nintendo.zaka", "com.vectorunit.red",
        "jp.konami.pesam", "com.ea.gp.fifamobile", "com.playdemic.golf.android",
        "com.miniclip.eightballpool", "com.firsttouchgames.dls7", "com.kiloo.subwaysurf",
        "com.imangi.templerun2", "com.yodo1.crossyroad", "com.halfbrick.jetpackjoyride",
        "com.halfbrick.fruitninjafree", "com.robtopx.geometryjump", "com.sega.sonicdash",
        "com.kitkagames.fallbuddies", "com.innersloth.spacemafia", "net.peakgames.toonblast",
        "com.playrix.homescapes", "com.roblox.client", "com.moonactive.coinmaster",
        "com.playrix.township", "com.king.farmheroessaga", "com.candywriter.bitlife",
        "com.ea.game.simcitymobile_row", "com.supercell.hayday", "com.maleo.bussimulatorid",
        "com.ludo.king", "com.chess", "com.blizzard.wtcg.hearthstone",
        "com.nvsgames.snap", "com.mobilityware.solitaire", "com.scopely.monopolygo",
        "jp.pokemon.pokemontcgp", "com.exozet.android.catan", "com.humble.SlayTheSpire",
        "com.zynga.words3", "com.matteljv.uno", "com.cdprojektred.gwent",
        "org.supertuxkart.stk", "net.minetest.minetest", "io.anuke.mindustry",
        "com.shatteredpixel.shatteredpixeldungeon", "org.wesnoth.Wesnoth", "org.develz.crawl",
        "com.gpl.rpg.AndorsTrail", "org.artsoft.rocksndiamonds", "de.saschahlusiak.freebloks",
        "name.boyle.chris.sgtpuzzles", "com.dozingcatsoftware.bouncy", "de.tobiasbielefeld.solitaire",
        "org.secuso.privacyfriendlyminesweeper", "com.agateau.tinywheels.android", "net.nullsum.freedoom",
        "de.sesu8642.feudaltactics", "com.drodin.tuxrider", "org.lufebe16.freecell",
        "org.broguece.game")

    @Test fun everyEntryCarriesOpenSourceAndDimension() {
        val a = raw()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            assertTrue("${o.getString("pkg")} has open_source", o.has("open_source") && o.get("open_source") is Boolean)
            assertTrue("${o.getString("pkg")} has dimension 2d|3d", o.optString("dimension") in listOf("2d", "3d"))
            if (o.getString("source") == "play") assertFalse("a Play-only listing is not tagged open source", o.getBoolean("open_source"))
            else assertTrue("F-Droid / GitHub rows are open source", o.getBoolean("open_source"))
        }
    }

    @Test fun nothingWasRemovedFromTheCatalogue() {
        val g = catalogue()
        assertTrue(g.problems.isEmpty())
        assertEquals(97, before.size); assertEquals(before.size, before.toSet().size)
        assertTrue("dropped: " + before.filter { p -> g.games.none { it.pkg == p } }, before.all { p -> g.games.any { it.pkg == p } })
        assertEquals(103, g.games.size)
        assertEquals(12, g.categories.size)
    }

    @Test fun theOwnersTenOpenSourceGamesArePresentWithRealPackages() {
        val g = catalogue()
        val want = mapOf(
            "io.anuke.mindustry" to ("2d" to "Strategy & Management"), "com.unciv.app" to ("2d" to "Strategy & Management"),
            "org.openttd.fdroid" to ("2d" to "Strategy & Management"), "com.shatteredpixel.shatteredpixeldungeon" to ("2d" to "RPG & Roguelike"),
            "com.cleverraven.cataclysmdda" to ("2d" to "RPG & Roguelike"), "sh.ppy.osulazer" to ("2d" to "Rhythm & Puzzle/Board"),
            "org.lichess.mobileV2" to ("2d" to "Rhythm & Puzzle/Board"), "net.minetest.minetest" to ("3d" to "Sandbox & Voxel"),
            "org.supertuxkart.stk" to ("3d" to "Racing"), "org.stjr.srb2" to ("3d" to "Platformer"))
        val pkgRe = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        for ((pkg, dg) in want) {
            val x = g.games.firstOrNull { it.pkg == pkg } ?: error("$pkg missing")
            assertTrue(pkg, pkgRe.matches(pkg) && x.openSource && x.dimension == dg.first && x.genre == dg.second)
            assertTrue("$pkg source", x.source in listOf("fdroid", "github"))
            assertTrue("$pkg store icon", x.icon.startsWith("https://"))
            assertTrue("$pkg listing", if (x.source == "fdroid") x.storeUrl == "https://f-droid.org/packages/$pkg/" else x.storeUrl.startsWith("https://github.com/"))
        }
        assertEquals(StorePhoneSearch.GENRES.toSet(), want.values.map { it.second }.toSet())
        assertTrue(g.games.first { it.pkg == "sh.ppy.osulazer" }.apk.endsWith("/sh.ppy.osulazer.apk"))
        assertEquals("github", g.games.first { it.pkg == "org.stjr.srb2" }.source)
    }

    @Test fun theSplitsAndTheirCounts() {
        val g = catalogue(); val l = g.games
        assertEquals(25, l.count { it.openSource }); assertEquals(78, l.count { !it.openSource })
        val o2 = StorePhoneSearch.split(l, true, "2d"); val o3 = StorePhoneSearch.split(l, true, "3d")
        val c2 = StorePhoneSearch.split(l, false, "2d"); val c3 = StorePhoneSearch.split(l, false, "3d")
        assertEquals(19, o2.size); assertEquals(6, o3.size); assertEquals(38, c2.size); assertEquals(40, c3.size)
        assertEquals(l.size, o2.size + o3.size + c2.size + c3.size)
        assertTrue(o3.map { it.pkg }.containsAll(listOf("net.minetest.minetest", "org.supertuxkart.stk", "org.stjr.srb2")))
        // sections inside a split: the owner's genres first, then the existing categories; every game of the split sits in exactly one
        for (s in listOf(o2, o3, c2, c3)) {
            val sec = StorePhoneSearch.sections(g, s)
            assertEquals(s.map { it.pkg }.toSet(), sec.flatMap { it.second }.map { it.pkg }.toSet())
            assertEquals(s.size, sec.sumOf { it.second.size })
        }
        assertEquals("Strategy & Management", StorePhoneSearch.sections(g, o2).first().first)
        assertTrue(StorePhoneSearch.sections(g, c2).map { it.first }.containsAll(listOf("Puzzle", "Board / Card")))
        assertTrue(StorePhoneSearch.sections(g, c3).map { it.first }.containsAll(listOf("Action", "Racing")))
    }

    @Test fun theCatalogueViewDrawsTheFilterRowsAndKeepsTheGrid() {
        val src = page()
        // #1791 the former Recommendations / Play Integrity / Games views are one filtered catalogue view with five chip rows
        for (t in listOf("StorePhoneFilter.tag(row.id, v.id)", "filter.with(row.id, v.id)", "StorePhoneFilter.apply(cat, filter, fleetPackages)", "StorePhoneFilter.groupBy(cat, if (byTier)",
            "StorePhoneFilter.matchesHit(cat, byPkg, it, filter", "StorePhoneFilter.sources(filter)", "filter = StorePhoneFilter.preset(ps)")) assertTrue(t, t in src)
        assertTrue("the catalogue view keeps the #1772 icon grid", "ResultGrid(ctx, cfg, d, group.map(StorePhoneFilter::result)" in src)
        assertTrue("GitHub is a Games source", StoreSearchPage.GITHUB in StorePhoneSearch.sources(StorePhoneSearch.GAMES))
    }
}
