package com.diegonmarcos.cloudme

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * #1906 the link of every social account and company on Pers and Prof, DECLARED in data/links/me-links.json (generated from the SoT by
 * data/regen-me-links.py, shipped as an asset). Each Pers/Prof page whose fragment carries a `link` gets an "Open <site>" action, and a
 * menu lists every entry of its section: every row is clickable to its real URL (ACTION_VIEW, so Instagram and LinkedIn open in their own
 * app when installed, the browser otherwise). The embedded mySocials mirror stays the page content.
 */
object MeLinks {
    data class Entry(val id: String, val section: String, val kind: String, val label: String, val url: String)

    fun parse(text: String): List<Entry> = JSONObject(text).getJSONArray("entries").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.getString("id"), it.getString("section"), it.getString("kind"), it.getString("label"), it.getString("url")) }
    }

    fun load(ctx: Context): List<Entry> = runCatching { parse(ctx.assets.open("me-links.json").bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())

    /** The rows of the menu on a page of [section]: every entry of that section. */
    fun menuFor(all: List<Entry>, section: String): List<Entry> = all.filter { it.section == section }

    /** The entry the page's own `link` is, for the "Open <site>" label. */
    fun barFor(all: List<Entry>, link: String): Entry? = all.firstOrNull { it.url == link }

    fun open(ctx: Context, url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { }
    }
}

/** The page (WebPage, the mirror) with its "Open <site>" action and the section's link menu; a page without a `link` is the bare WebPage. */
@Composable
fun MeLinkedWeb(block: JSONObject, sectionId: String, pageId: String, modifier: Modifier) {
    val link = block.optString("link")
    if (link.isBlank()) { WebPage(block, modifier, pageId = pageId); return }
    val ctx = LocalContext.current
    val all = remember { MeLinks.load(ctx) }
    val label = MeLinks.barFor(all, link)?.label ?: link
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        WebPage(block, Modifier, pageId = pageId)
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalAlignment = Alignment.End) {
            if (menu) Surface(tonalElevation = 6.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                Column {
                    for (e in MeLinks.menuFor(all, sectionId)) TextButton(onClick = { menu = false; MeLinks.open(ctx, e.url) }) { Text(e.label) }
                }
            }
            Row {
                FilledTonalButton(onClick = { MeLinks.open(ctx, link) }) { Text("Open $label") }
                TextButton(onClick = { menu = !menu }) { Text("Links") }
            }
        }
    }
}
