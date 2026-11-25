package com.diegonmarcos.cloudme

import android.content.Context
import org.json.JSONObject

/**
 * #1906 the link of every Pers/Prof/Proj site, DECLARED in data/web/me-links.json (generated from the SoT by data/regen-me-links.py,
 * shipped as an asset). #1911 it is the link TABLE only: each tab's page file embeds the same URL in the page WebView (WebPage), and
 * there is no Open button or Links panel.
 */
object MeLinks {
    data class Entry(val id: String, val section: String, val kind: String, val label: String, val url: String)

    fun parse(text: String): List<Entry> = JSONObject(text).getJSONArray("entries").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.getString("id"), it.getString("section"), it.getString("kind"), it.getString("label"), it.getString("url")) }
    }

    fun load(ctx: Context): List<Entry> = runCatching { parse(ctx.assets.open("me-links.json").bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())
}
