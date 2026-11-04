package com.diegonmarcos.cloudshopping.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudshopping.R
import com.diegonmarcos.cloudshopping.Row
import com.diegonmarcos.cloudshopping.Shop
import com.diegonmarcos.cloudshopping.ShopData
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.uikit.KitSearchBar
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * One page: its own filter tabs on top (the shared top strip, tabs from shop.json) over its rows. [pageId] null is the Search view: a search
 * field, the Search tabs and the matches of every page. The Feed page (kind `feed`) lists the deals of every other page.
 */
@Composable
fun ShopPage(data: ShopData, groupOf: Map<String, String>, shop: NavSection, pageId: String?) {
    val page = pageId?.let { data.pages[it] }
    val tabs = if (pageId == null) data.searchTabs else page?.tabs.orEmpty()
    val key = pageId ?: "search"
    val picked = remember { mutableStateMapOf<String, String>() }
    var query by rememberSaveable { mutableStateOf("") }
    val tabId = picked[key] ?: tabs.firstOrNull()?.id
    val labels = remember(shop) { shop.allPages().associate { it.id to it.label } }
    val rows = if (pageId == null) Shop.search(data, query, tabId, groupOf)
        else if (page?.kind == Shop.KIND_FEED) Shop.feed(data, pageId, tabId, groupOf)
        else Shop.rows(data, pageId, tabId, groupOf)
    Column(Modifier.fillMaxSize().testTag(Tags.PAGE)) {
        if (pageId == null) {
            Box(Modifier.padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight)) {
                KitSearchBar(query, { query = it }, onSubmit = {}, modifier = Modifier.testTag(Tags.SEARCH), placeholder = stringResource(R.string.search_hint))
            }
        }
        NavBarTopSecondRow(
            pages = tabs.map { NavPage(it.id, it.label, it.icon) },
            selectedId = tabId,
            onSelect = { picked[key] = it.id },
            modifier = Modifier.testTag(Tags.TABS),
            underTopChrome = false,
        )
        if (rows.isEmpty()) {
            KitStoreCaption(stringResource(R.string.empty), Modifier.fillMaxWidth().padding(KitStoreRow.padH).testTag(Tags.EMPTY), color = LocalKitPalette.current.textSecondary, align = TextAlign.Center)
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag(Tags.ROWS)) {
                items(rows, key = { it.item.id }) { r -> ShopRow(r, data.currency, showPage = pageId == null || page?.kind == Shop.KIND_FEED, label = labels[r.pageId].orEmpty()) }
            }
        }
    }
}

/** One listing as the Cloud Store's dense row (ui-kit KitStoreRow): state dot, name over a caption, the price and the saving at the end. */
@Composable
private fun ShopRow(r: Row, currency: String, showPage: Boolean, label: String) {
    val p = LocalKitPalette.current
    val item = r.item
    KitStoreRowLine(Modifier.testTag(Tags.row(item.id))) {
        KitStoreDot(if (item.isDeal) "●" else "○", if (item.isDeal) KitStoreRow.UP else p.textSecondary)
        Column(Modifier.weight(1f)) {
            KitStoreName(item.title, color = p.textPrimary)
            KitStoreCaption(if (showPage) "$label · ${item.subtitle}" else item.subtitle, color = p.textSecondary)
        }
        Column(horizontalAlignment = Alignment.End) {
            KitStoreCaption(Shop.price(item, currency), color = p.textPrimary, mono = true)
            if (item.isDeal) KitStoreCaption(stringResource(R.string.saving, item.discount), color = KitStoreRow.UP, mono = true)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(KitStoreRow.RULE))
}
