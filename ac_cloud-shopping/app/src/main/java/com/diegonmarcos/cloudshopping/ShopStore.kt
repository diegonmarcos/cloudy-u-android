package com.diegonmarcos.cloudshopping

import android.content.Context

/** Reads the mock data bundled as assets/shop/shop.json; nothing is fetched. */
object ShopStore {
    const val ASSET = "shop/shop.json"

    fun load(ctx: Context): ShopData =
        Shop.parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })
}
