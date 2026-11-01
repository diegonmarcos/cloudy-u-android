package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserBookmark
import com.diegonmarcos.superapp.browser.BrowserListFilter
import com.diegonmarcos.superapp.browser.BrowserSearchEngine
import com.diegonmarcos.superapp.browser.BrowserSuggest
import com.diegonmarcos.superapp.browser.BrowserVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1410 the Tabs/History search bar filters by title and URL; the Search page box reuses the address bar's suggester. */
class BrowserSearchBarsTest {

    private val visits = listOf(
        BrowserVisit("https://kotlinlang.org/docs", "Docs", 3),
        BrowserVisit("https://news.example/x", "Learning Kotlin", 2),
        BrowserVisit("https://other.example", "Other", 1),
    )

    @Test
    fun `filter matches title or url, case-insensitive, blank keeps all`() {
        assertEquals(listOf("https://kotlinlang.org/docs", "https://news.example/x"), BrowserListFilter.visits("KOTLIN", visits).map { it.url })
        assertEquals(listOf("https://other.example"), BrowserListFilter.visits(" other.ex ", visits).map { it.url })
        assertEquals(visits, BrowserListFilter.visits("  ", visits))
        assertTrue(BrowserListFilter.visits("zzz", visits).isEmpty())
        assertTrue(BrowserListFilter.matches("doc", "Docs", ""))
        assertTrue(BrowserListFilter.matches("lang", "", "https://kotlinlang.org"))
    }

    @Test
    fun `search page box ranks history and bookmarks with the address bar suggester`() {
        val ddg = BrowserSearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q={q}", "")
        val s = BrowserSuggest.sections("kotlin", emptyList(), visits, ddg, now = 10, favourites = listOf(BrowserBookmark("https://kotlin.fav", "Kotlin fav")))
        assertTrue(s.history.any { it.url == "https://kotlinlang.org/docs" })
        assertTrue(s.favourites.any { it.url == "https://kotlin.fav" })
    }

    @Test
    fun `search page reuses the address bar component and refresh, no new suggester`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "ab_cloud-libs-shared").isDirectory }
        val lib = File(root, "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser")
        val host = File(lib, "BrowserHostFragment.kt").readText()
        val page = host.substringAfter("private fun showSearchPage()").substringBefore("private fun showSearchWith()")
        assertTrue(page.contains("BrowserSearchBox("))
        assertTrue(page.contains("refreshSuggestions(st"))
        val box = File(lib, "BrowserSuggestPanel.kt").readText().substringAfter("fun BrowserSearchBox(")
        assertTrue("the dropdown is the address bar's SuggestRows", box.contains("SuggestRows(rows"))
        assertTrue(box.contains("SuggestRow.build(state.sections"))
        assertTrue(host.contains("BrowserListFilter.tabs(q") && host.contains("BrowserListFilter.visits(q"))
    }
}
