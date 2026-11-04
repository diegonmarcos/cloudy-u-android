package com.diegonmarcos.superapp.scraper

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.superapp.themebg.ThemeColors
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** #1468 one section of the SelfHosted page, by id. */
data class SelfHostedSection(val id: String, val label: String, val about: String)

/** #1468 the SelfHosted page's sections as data: Music (moved here from its own tab, #1460), the YouTube tool and the fleet's scrappers-api. */
object SelfHostedCatalog {
    const val MUSIC = "music"
    const val YOUTUBE = "youtube"
    const val SCRAPPERS_API = "scrappers_api"

    val sections = listOf(
        SelfHostedSection(MUSIC, "Music", "Spotify, YouTube Music, Deezer, Apple Music: read, match, sync, export"),
        SelfHostedSection(YOUTUBE, "YouTube Download", "Video or audio through the scrappers-api"),
        SelfHostedSection(SCRAPPERS_API, "scrappers-api", "The fleet container behind the heavy tools"),
    )
}

/** #1468 SelfHosted: one dense Store row per section; the open one fills the page below. Music is a section here, not a Scrapers tab. */
@Composable
fun SelfHostedPage(c: ThemeColors) {
    var open by remember { mutableStateOf(SelfHostedCatalog.MUSIC) }
    Column(Modifier.fillMaxSize().testTag("selfhosted:page")) {
        for (s in SelfHostedCatalog.sections) KitStoreRowLine(onClick = { open = if (open == s.id) "" else s.id }, modifier = Modifier.testTag("selfhosted:section:${s.id}")) {
            KitStoreDot(if (open == s.id) "▾" else "▸", c.accent)
            Column(Modifier.weight(1f)) {
                KitStoreName(s.label, color = c.ink)
                KitStoreCaption(s.about, color = c.inkDim)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (open) {
                SelfHostedCatalog.MUSIC -> MusicPage(c)
                SelfHostedCatalog.YOUTUBE -> YouTubeToolPage(c)
                SelfHostedCatalog.SCRAPPERS_API -> ScrappersApiSection(c)
            }
        }
    }
}

/** The scrappers-api bases (mesh first, public second) with a reachability check: any HTTP answer counts as reachable. */
@Composable
private fun ScrappersApiSection(c: ThemeColors) {
    val scope = rememberCoroutineScope()
    val state = remember { mutableStateMapOf<String, String>() }
    fun check(base: String) = scope.launch {
        state[base] = "checking…"
        state[base] = withContext(Dispatchers.IO) {
            runCatching { MusicHttp(maxTries = 1).get(base); "reachable" }
                .getOrElse { if (it is MusicHttpException) "reachable (HTTP ${it.code})" else "unreachable: ${it.message}" }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("selfhosted:api")) {
        items(ScraperApi.DEFAULT_BASES, key = { it }) { base ->
            KitStoreRowLine {
                Column(Modifier.weight(1f)) {
                    KitStoreName(base.substringAfter("://"), color = c.ink)
                    KitStoreCaption(state[base] ?: "not checked", color = c.inkDim, modifier = Modifier.testTag("selfhosted:api:status"))
                }
                TextButton(onClick = { check(base) }) { Text("Check", color = c.accent) }
            }
        }
        item(key = "note") { KitStoreRowLine { KitStoreCaption("Mesh first, public second. YouTube extraction (yt-dlp) runs here, not on the phone.", color = c.inkDim) } }
    }
}
