package com.diegonmarcos.cloudshopping

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** One filter tab of a page (assets/shop/shop.json): which items it keeps. A tab naming no filter keeps everything. */
data class Tab(
    val id: String, val label: String, val icon: String,
    val tag: String = "", val group: String = "", val deal: Boolean = false, val minDiscount: Int = 0,
)

/** One mock listing: a price, an optional earlier price (a deal when above the price) and the tags the tabs filter by. */
data class Item(
    val id: String, val title: String, val subtitle: String, val price: Double, val unit: String,
    val oldPrice: Double?, val tags: List<String>,
) {
    val discount: Int get() = Shop.discount(price, oldPrice)
    val isDeal: Boolean get() = discount > 0
}

/** The data of one page: `feed` aggregates the deals of every other page, `list` shows [items]. */
data class PageData(val kind: String, val tabs: List<Tab>, val items: List<Item>)

/** A row on screen: the item and the page it comes from. */
data class Row(val pageId: String, val item: Item)

data class ShopData(
    val bottomLabels: Boolean, val currency: String, val searchTabs: List<Tab>, val pages: Map<String, PageData>,
)

/** The mock data and what is done with it: parse, filter, aggregate the Feed, search. Pure, so the JVM suite covers it. */
object Shop {
    const val KIND_FEED = "feed"
    const val KIND_LIST = "list"

    fun parse(json: String): ShopData {
        val root = JSONObject(json)
        val chrome = root.optJSONObject("chrome") ?: JSONObject()
        val pages = LinkedHashMap<String, PageData>()
        val ps = root.optJSONObject("pages") ?: JSONObject()
        for (id in ps.keys()) {
            val p = ps.getJSONObject(id)
            pages[id] = PageData(p.optString("kind", KIND_LIST), tabs(p.optJSONArray("tabs")), items(p.optJSONArray("items")))
        }
        return ShopData(
            chrome.optBoolean("bottom_labels", true), chrome.optString("currency"),
            tabs(root.optJSONObject("search")?.optJSONArray("tabs")), pages,
        )
    }

    private fun objects(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }

    private fun tabs(a: JSONArray?): List<Tab> = objects(a).map { o ->
        Tab(o.getString("id"), o.optString("label"), o.optString("icon"), o.optString("tag"), o.optString("group"), o.optBoolean("deal"), o.optInt("min_discount"))
    }

    private fun items(a: JSONArray?): List<Item> = objects(a).map { o ->
        val tags = o.optJSONArray("tags")
        Item(
            o.getString("id"), o.optString("title"), o.optString("subtitle"), o.getDouble("price"), o.optString("unit"),
            if (o.has("old_price")) o.getDouble("old_price") else null,
            (0 until (tags?.length() ?: 0)).map { tags!!.getString(it) },
        )
    }

    /** The whole-percent saving of [price] against [old]; 0 when there is no earlier price or it is not above the price. */
    fun discount(price: Double, old: Double?): Int =
        if (old == null || old <= 0.0 || old <= price) 0 else ((old - price) / old * 100.0).roundToInt()

    /** True when [item] of a page in [group] passes every filter [tab] names. */
    fun keeps(tab: Tab, item: Item, group: String): Boolean =
        (tab.tag.isEmpty() || tab.tag in item.tags) && (tab.group.isEmpty() || tab.group == group) &&
            (!tab.deal || item.isDeal) && (tab.minDiscount <= 0 || item.discount >= tab.minDiscount)

    private fun selected(tabs: List<Tab>, tabId: String?): Tab = tabs.firstOrNull { it.id == tabId } ?: tabs.firstOrNull() ?: Tab("", "", "")

    /** A list page's rows under one of its tabs, in the data's order. */
    fun rows(data: ShopData, pageId: String, tabId: String?, groupOf: Map<String, String>): List<Row> {
        val page = data.pages[pageId] ?: return emptyList()
        val tab = selected(page.tabs, tabId)
        return page.items.filter { keeps(tab, it, groupOf[pageId].orEmpty()) }.map { Row(pageId, it) }
    }

    /** Every deal of every page, the biggest saving first, under one of the Feed's tabs. */
    fun feed(data: ShopData, pageId: String, tabId: String?, groupOf: Map<String, String>): List<Row> {
        val tab = selected(data.pages[pageId]?.tabs.orEmpty(), tabId)
        return data.pages.filter { it.value.kind == KIND_LIST }.flatMap { (id, p) ->
            p.items.filter { it.isDeal && keeps(tab, it, groupOf[id].orEmpty()) }.map { Row(id, it) }
        }.sortedByDescending { it.item.discount }
    }

    /** Every item of every page whose title, subtitle or tag holds [query] (case-insensitive, each word), under one of the Search tabs. */
    fun search(data: ShopData, query: String, tabId: String?, groupOf: Map<String, String>): List<Row> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val tab = selected(data.searchTabs, tabId)
        return data.pages.filter { it.value.kind == KIND_LIST }.flatMap { (id, p) ->
            p.items.filter { keeps(tab, it, groupOf[id].orEmpty()) && words.all { w -> matches(it, w) } }.map { Row(id, it) }
        }
    }

    private fun matches(item: Item, word: String): Boolean =
        item.title.lowercase().contains(word) || item.subtitle.lowercase().contains(word) || item.tags.any { it.lowercase().contains(word) }

    /** A price as shown: whole numbers without decimals, otherwise two, then the unit. */
    fun price(item: Item, currency: String): String {
        val n = if (item.price % 1.0 == 0.0) item.price.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, item.price)
        return listOf(n, item.unit.ifEmpty { currency }).joinToString(" ")
    }
}
