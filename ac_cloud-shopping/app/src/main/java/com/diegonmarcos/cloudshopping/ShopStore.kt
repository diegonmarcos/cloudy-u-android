package com.diegonmarcos.cloudshopping

import android.content.Context

/**
 * Reads the mock data bundled as assets/shop/shop.json; nothing is fetched. The file lives in the DEBUG source set only (#1547: mock data
 * never ships in a release build), so a release build has no asset and shows empty pages instead of failing.
 */
object ShopStore {
    const val ASSET = "shop/shop.json"

    fun load(ctx: Context): ShopData =
        runCatching { Shop.parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() }) }.getOrElse { Shop.parse("{}") }
}
