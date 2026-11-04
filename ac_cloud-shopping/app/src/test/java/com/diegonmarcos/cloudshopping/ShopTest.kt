package com.diegonmarcos.cloudshopping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mock data against the declared pages: what build.json::ui names is what shop.json holds, every tab has rows, the Feed aggregates. */
class ShopTest {
    private val d = Data.data
    private val g = Data.groupOf()
    private fun lists() = d.pages.filter { it.value.kind == Shop.KIND_LIST }

    @Test fun theNavigationIsOneSideMenuWithTheOwnersFiveGroupsAndOneSearchItem() {
        assertEquals("vertical_side_menu", Data.navMode())
        assertEquals(listOf("search"), Data.bottomNav())
        assertFalse("the one bottom item has no label", d.bottomLabels)
        assertEquals(
            listOf(
                "feed" to listOf("home"),
                "things" to listOf("groceries", "electronics", "clothing"),
                "services" to listOf("transport", "hotels"),
                "assets" to listOf("real_estate", "business", "jobs"),
                "entertainment" to listOf("events"),
            ),
            Data.groups(),
        )
    }

    @Test fun everyDeclaredPageHasDataAndEveryDataPageIsDeclared() {
        assertEquals(Data.pages().toSet(), d.pages.keys)
        assertEquals(Shop.KIND_FEED, d.pages.getValue("home").kind)
        assertEquals(Data.pages().size - 1, lists().size)
    }

    @Test fun everyPageHasFilterTabsAndAnUnfilteredFirstTab() {
        for ((id, p) in d.pages) {
            assertTrue("$id has at least two tabs", p.tabs.size >= 2)
            assertEquals("$id tab ids are unique", p.tabs.size, p.tabs.map { it.id }.toSet().size)
            val first = p.tabs.first()
            assertTrue("$id first tab keeps everything", first.tag.isEmpty() && first.group.isEmpty() && !first.deal && first.minDiscount == 0)
        }
        assertTrue(d.searchTabs.size >= 2)
    }

    @Test fun noTabIsDeadAndEveryListPageHasMockRows() {
        for ((id, p) in lists()) {
            assertTrue("$id has at least 5 rows", p.items.size >= 5)
            for (t in p.tabs) assertTrue("$id/${t.id} shows rows", Shop.rows(d, id, t.id, g).isNotEmpty())
        }
        for (t in d.pages.getValue("home").tabs) assertTrue("home/${t.id} shows rows", Shop.feed(d, "home", t.id, g).isNotEmpty())
        for (t in d.searchTabs) assertTrue("search/${t.id} shows rows", Shop.search(d, "", t.id, g).isNotEmpty())
    }

    @Test fun itemsAreWellFormed() {
        val items = lists().values.flatMap { it.items }
        assertEquals("item ids are unique across pages", items.size, items.map { it.id }.toSet().size)
        assertTrue(items.all { it.price > 0.0 && it.title.isNotBlank() && it.subtitle.isNotBlank() })
        assertTrue("an earlier price is above the price", items.all { it.oldPrice == null || it.oldPrice > it.price })
    }

    @Test fun theFeedAggregatesTheDealsOfEveryPageBiggestSavingFirst() {
        val feed = Shop.feed(d, "home", "all", g)
        assertEquals(lists().keys, feed.map { it.pageId }.toSet())
        assertTrue(feed.all { it.item.isDeal })
        assertEquals(feed.map { it.item.discount }, feed.map { it.item.discount }.sortedDescending())
        assertEquals(lists().values.sumOf { p -> p.items.count { it.isDeal } }, feed.size)
        assertTrue(Shop.feed(d, "home", "top", g).all { it.item.discount >= 30 })
        assertTrue(Shop.feed(d, "home", "services", g).all { g[it.pageId] == "services" })
        assertEquals(setOf("transport", "hotels"), Shop.feed(d, "home", "services", g).map { it.pageId }.toSet())
    }

    @Test fun aPageTabFiltersByTagAndDeal() {
        assertTrue(Shop.rows(d, "groceries", "fresh", g).all { "fresh" in it.item.tags })
        assertTrue(Shop.rows(d, "groceries", "deals", g).all { it.item.isDeal })
        assertTrue(Shop.rows(d, "groceries", "deals", g).size < Shop.rows(d, "groceries", "all", g).size)
        assertEquals(Shop.rows(d, "groceries", "all", g), Shop.rows(d, "groceries", "no-such-tab", g))
        assertTrue(Shop.rows(d, "no-such-page", "all", g).isEmpty())
    }

    @Test fun searchLooksAcrossEveryPage() {
        val all = Shop.search(d, "", "all", g)
        assertEquals(lists().values.sumOf { it.items.size }, all.size)
        val paris = Shop.search(d, "paris", "all", g)
        assertTrue(paris.size >= 3 && paris.map { it.pageId }.toSet().size >= 3)
        assertTrue(paris.all { r -> "paris" in (r.item.title + r.item.subtitle).lowercase() })
        assertEquals("every word must match", Shop.search(d, "paris hotel", "all", g), Shop.search(d, "  PARIS   hotel ", "all", g))
        assertTrue(Shop.search(d, "zzzz-nothing", "all", g).isEmpty())
        assertTrue(Shop.search(d, "paris", "assets", g).all { g[it.pageId] == "assets" })
    }

    @Test fun discountMaths() {
        assertEquals(0, Shop.discount(10.0, null))
        assertEquals(0, Shop.discount(10.0, 10.0))
        assertEquals(0, Shop.discount(10.0, 8.0))
        assertEquals(25, Shop.discount(75.0, 100.0))
        assertEquals(22, Shop.discount(2.49, 3.20))
    }

    @Test fun pricesAreShownWithTheirUnit() {
        val banana = lists().getValue("groceries").items.first()
        assertEquals("2.49 EUR/kg", Shop.price(banana, d.currency))
        assertEquals("385000 EUR", Shop.price(lists().getValue("real_estate").items.first { it.id == "re-3" }, d.currency))
        assertEquals("7 EUR", Shop.price(banana.copy(price = 7.0, unit = ""), d.currency))
    }

    @Test fun parseToleratesMissingOptionalParts() {
        val s = Shop.parse("""{"pages":{"p":{"tabs":[{"id":"a"}],"items":[{"id":"x","price":1}]}}}""")
        assertTrue(s.bottomLabels)
        assertEquals(Shop.KIND_LIST, s.pages.getValue("p").kind)
        assertEquals(1, Shop.rows(s, "p", null, emptyMap()).size)
    }
}
