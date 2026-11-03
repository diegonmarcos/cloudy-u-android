package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserBookmark
import com.diegonmarcos.superapp.browser.BrowserBookmarkOps
import com.diegonmarcos.superapp.browser.BrowserFavourites
import com.diegonmarcos.superapp.browser.FavSeed
import com.diegonmarcos.superapp.browser.FavSeedItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #893 Fav = the bookmarks: the seed's merge rules and the view toggle. */
class BrowserFavouritesTest {

    private val seed = FavSeed(1, listOf(
        FavSeedItem("Gitea", "https://git.diegonmarcos.com", "Public"),
        FavSeedItem("Kg", "http://kg-store:8001", "Private (mesh)"),
    ))

    @Test
    fun `first run adds every seed entry into its group folder`() {
        val (list, seen) = BrowserFavourites.merge(emptyList(), seed, emptySet(), 5L)
        assertEquals(setOf("Public", "Private (mesh)"), list.map { it.folder }.toSet())
        assertEquals(setOf("https://git.diegonmarcos.com", "http://kg-store:8001"), seen)
    }

    @Test
    fun `a second run changes nothing, a user's edit is kept, a deleted entry stays deleted`() {
        var (list, seen) = BrowserFavourites.merge(emptyList(), seed, emptySet(), 5L)
        list = BrowserBookmarkOps.add(list, BrowserBookmark("https://git.diegonmarcos.com", "My git", "mine", 9L))   // edited
        list = BrowserBookmarkOps.remove(list, "http://kg-store:8001")                                                // deleted
        val again = BrowserFavourites.merge(list, seed, seen, 6L)
        assertEquals(list, again.first)
        assertEquals("My git", again.first.first { it.url == "https://git.diegonmarcos.com" }.title)
        assertTrue(again.first.none { it.url == "http://kg-store:8001" })
    }

    @Test
    fun `a newer seed adds only its new entries`() {
        val (list, seen) = BrowserFavourites.merge(emptyList(), seed, emptySet(), 5L)
        val v2 = FavSeed(2, seed.items + FavSeedItem("Dagu", "https://workflows.diegonmarcos.com", "Public"))
        val (next, _) = BrowserFavourites.merge(list, v2, seen, 7L)
        assertEquals(3, next.size)
        assertEquals("https://workflows.diegonmarcos.com", next.first { it.title == "Dagu" }.url)
    }

    @Test
    fun `a bookmark the user already has is not duplicated by the seed`() {
        val mine = listOf(BrowserBookmark("https://git.diegonmarcos.com", "Mine", "", 1L))
        val (list, _) = BrowserFavourites.merge(mine, seed, emptySet(), 5L)
        assertEquals(1, list.count { it.url == "https://git.diegonmarcos.com" })
        assertEquals("Mine", list.first { it.url == "https://git.diegonmarcos.com" }.title)
    }

    @Test
    fun `the view is list or grid, defaults to list, and toggles`() {
        assertEquals("list", BrowserFavourites.normView(null))
        assertEquals("list", BrowserFavourites.normView("junk"))
        assertEquals("grid", BrowserFavourites.normView("grid"))
        assertEquals("grid", BrowserFavourites.toggled("list"))
        assertEquals("list", BrowserFavourites.toggled("grid"))
    }

    @Test
    fun `sections put the top level first and the folders sorted, and tiles get a letter`() {
        val l = listOf(BrowserBookmark("u1", "b", "Z"), BrowserBookmark("u2", "a", ""), BrowserBookmark("u3", "c", "A"))
        assertEquals(listOf("", "A", "Z"), BrowserFavourites.sections(l).map { it.first })
        assertEquals("G", BrowserFavourites.letter("gitea", "https://x"))
        assertEquals("X", BrowserFavourites.letter("", "https://x.example"))
    }

    @Test
    fun `the seed parses from the baked json and drops blanks and duplicates`() {
        val o = JSONObject("""{"version":3,"items":[{"title":"A","url":"https://a"},{"url":""},{"title":"A2","url":"https://a"}]}""")
        val s = FavSeed.parse(o)
        assertEquals(3, s.version)
        assertEquals(1, s.items.size)
        assertEquals(FavSeed.EMPTY, FavSeed.parse(null))
    }

    // ── the Fav page's live filter ──

    private val l = listOf(
        BrowserBookmark("https://git.diegonmarcos.com", "Gitea", "Public"),
        BrowserBookmark("https://diegonmarcos.github.io/leafy/", "Leafy", "GitHub Pages"),
        BrowserBookmark("https://diegonmarcos.github.io/cv_web/", "CV Web", "GitHub Pages"),
        BrowserBookmark("http://kg-store:8001", "Knowledge graph", "Private (mesh)"),
        BrowserBookmark("https://example.org/notes", "Top level", ""),
    )

    @Test
    fun `the filter matches the title or the address, case-insensitive, and every word must hit`() {
        assertTrue(BrowserFavourites.matches(l[0], "GITEA"))
        assertTrue("address", BrowserFavourites.matches(l[0], "git.diego"))
        assertTrue("title and address words together", BrowserFavourites.matches(l[1], "leafy github.io"))
        assertFalse("one word misses", BrowserFavourites.matches(l[1], "leafy gitea"))
        assertTrue("blank keeps everything", BrowserFavourites.matches(l[0], "   "))
        assertFalse(BrowserFavourites.matches(l[0], "zzz"))
    }

    @Test
    fun `filtering keeps the folder order, drops folders left empty and is the identity for a blank query`() {
        val all = BrowserFavourites.sections(l)
        assertEquals(all, BrowserFavourites.filter(all, ""))
        val f = BrowserFavourites.filter(all, "github.io")
        assertEquals(listOf("GitHub Pages"), f.map { it.first })
        assertEquals(listOf("Leafy", "CV Web"), f.single().second.map { it.title })
        assertEquals("both views draw the same list", emptyList<Any>(), BrowserFavourites.filter(all, "nothing like this"))
        assertEquals(listOf("", "Public"), BrowserFavourites.filter(all, "o").map { it.first }.filter { it in setOf("", "Public") })
    }

    // ── the seed grows: version 2 brings the GitHub Pages group to people who already have version 1 ──

    private val v1 = FavSeed(1, listOf(FavSeedItem("Gitea", "https://git.diegonmarcos.com", "Public")))
    private val pages = listOf(
        FavSeedItem("Leafy", "https://diegonmarcos.github.io/leafy/", "GitHub Pages"),
        FavSeedItem("CV Web", "https://diegonmarcos.github.io/cv_web/", "GitHub Pages"),
    )
    private val v2 = FavSeed(2, v1.items + pages)

    @Test
    fun `an existing user gets the new group appended and nothing they own is touched`() {
        var (list, seen) = BrowserFavourites.merge(emptyList(), v1, emptySet(), 1L)
        list = BrowserBookmarkOps.add(list, BrowserBookmark("https://git.diegonmarcos.com", "My git", "mine", 2L))   // edited
        list = BrowserBookmarkOps.add(list, BrowserBookmark("https://mine.example", "Mine", "", 3L))                 // own
        val (next, nextSeen) = BrowserFavourites.merge(list, v2, seen, 9L)
        assertEquals(list.size + 2, next.size)
        assertEquals("edit kept", "My git", next.first { it.url == "https://git.diegonmarcos.com" }.title)
        assertEquals("mine", next.first { it.url == "https://git.diegonmarcos.com" }.folder)
        assertTrue("own link kept", next.any { it.url == "https://mine.example" })
        assertEquals(listOf("GitHub Pages"), next.filter { it.url.contains("github.io") }.map { it.folder }.distinct())
        assertEquals(v2.items.map { it.url }.toSet(), nextSeen)
    }

    @Test
    fun `a page the user already starred is not duplicated, one they deleted from version 2 does not come back`() {
        val starred = listOf(BrowserBookmark("https://diegonmarcos.github.io/leafy/", "Leafy mine", "web", 1L))
        val (a, seenA) = BrowserFavourites.merge(starred, v2, setOf("https://git.diegonmarcos.com"), 5L)
        assertEquals(1, a.count { it.url.endsWith("/leafy/") })
        assertEquals("Leafy mine", a.first { it.url.endsWith("/leafy/") }.title)
        val without = BrowserBookmarkOps.remove(a, "https://diegonmarcos.github.io/cv_web/")
        val (b, _) = BrowserFavourites.merge(without, v2, seenA, 6L)
        assertEquals("applying again is a no-op", without, b)
    }

    @Test
    fun `merging the same version twice is idempotent`() {
        val (l1, s1) = BrowserFavourites.merge(emptyList(), v2, emptySet(), 1L)
        val (l2, s2) = BrowserFavourites.merge(l1, v2, s1, 2L)
        assertEquals(l1, l2); assertEquals(s1, s2)
    }

    // ── the address bar's Favorites section ──

    @Test
    fun `suggestions rank a start-of-title match above a substring, cap, and skip what is already shown`() {
        val fav = listOf(
            BrowserBookmark("https://x.example/leaf-notes", "Notes", "", 1L),            // url substring
            BrowserBookmark("https://diegonmarcos.github.io/leafy/", "Leafy", "GitHub Pages", 2L),  // title starts
        )
        assertEquals(listOf("Leafy", "Notes"), BrowserFavourites.suggest(fav, "leaf", 5).map { it.title })
        assertEquals(listOf("Leafy"), BrowserFavourites.suggest(fav, "leaf", 1).map { it.title })
        assertEquals(listOf("Notes"), BrowserFavourites.suggest(fav, "leaf", 5, skip = setOf("https://diegonmarcos.github.io/leafy/")).map { it.title })
        assertTrue(BrowserFavourites.suggest(fav, "  ", 5).isEmpty())
    }

    // ── #1422 filters, 8 columns, collapsible groups ──

    private fun bm(url: String, folder: String) = BrowserBookmark(url, url, folder, 1L)

    @Test
    fun `the grid has eight columns`() {
        assertEquals(8, BrowserFavourites.GRID_COLUMNS)
        val rows = BrowserFavourites.gridRows((1..17).toList())
        assertEquals(listOf(8, 8, 1), rows.map { it.size })
    }

    @Test
    fun `a collapsed group keeps its header and draws no item`() {
        val secs = BrowserFavourites.sections(listOf(bm("a", "GH Pages/Lnktree"), bm("b", "GH Pages/Lnktree"), bm("c", "Tools/Web")))
        val gs = BrowserFavourites.groups(secs, setOf("GH Pages/Lnktree"))
        val gh = gs.first { it.folder == "GH Pages/Lnktree" }
        assertTrue(gh.collapsed); assertEquals(2, gh.count); assertTrue(gh.items.isEmpty())
        assertEquals(1, gs.first { it.folder == "Tools/Web" }.items.size)
        val c = BrowserFavourites.toggle(emptySet(), "Tools/Web")
        assertEquals(setOf("Tools/Web"), c)
        assertEquals(emptySet<String>(), BrowserFavourites.toggle(c, "Tools/Web"))
    }

    @Test
    fun `a chip shows only its folders and headers show the sub-folder`() {
        val secs = BrowserFavourites.sections(listOf(bm("a", "GH Pages/Lnktree"), bm("c", "Tools/Web"), bm("d", "")))
        assertEquals(listOf("GH Pages/Lnktree"), BrowserFavourites.byFilter(secs, "GH Pages").map { it.first })
        assertEquals(3, BrowserFavourites.byFilter(secs, null).size)
        assertEquals("Lnktree", BrowserFavourites.subOf("GH Pages/Lnktree"))
        assertEquals("GH Pages", BrowserFavourites.filterOf("GH Pages/Lnktree"))
    }

    @Test
    fun `legacy seed folders move once, the filters and legacy groups parse, a user's folder stays`() {
        val o = JSONObject("""{"version":3,"legacy_groups":["Public"],"items":[{"title":"G","url":"https://g","group":"Cloudy Public/Data"}],
            "filters":[{"id":"gh_pages","label":"GH Pages"},{"id":"webserver","label":"Webserver","open":"http://emulated.app/__app__/tab/pages"}]}""")
        val s = FavSeed.parse(o)
        assertEquals(listOf("GH Pages", "Webserver"), s.filters.map { it.label })
        assertEquals("http://emulated.app/__app__/tab/pages", s.filters[1].open)
        assertEquals(null, s.filters[0].open)
        assertEquals(setOf("Public"), s.legacyGroups)
        val (moved, _) = BrowserFavourites.merge(listOf(bm("https://g", "Public")), s, setOf("https://g"), 2L)
        assertEquals("Cloudy Public/Data", moved.single().folder)
        val (kept, _) = BrowserFavourites.merge(listOf(bm("https://g", "Mine")), s, setOf("https://g"), 2L)
        assertEquals("Mine", kept.single().folder)
    }
}
