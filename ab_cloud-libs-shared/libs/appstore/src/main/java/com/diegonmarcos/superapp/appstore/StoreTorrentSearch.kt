package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatePill
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Store > Search > Torrent: the torrent source subpage. The sources are the host's declared indexers
 * ([StoreTorrentHost.Host.sources]); each is searched on its own coroutine with its own state, so one failing never blanks the
 * others, and a result goes to the TORRENT handler ([StoreTorrentHost.Host.download]) - never to the APK install path the
 * Cloudy and Android subpages use.
 */
object StoreTorrentSearch {
    const val TAG_PAGE = "store-search:torrent"
    const val TAG_NO_HOST = "store-search:torrent:no-host"
    fun sourceTag(id: String) = "store-search:torrent:source:$id"
    fun groupTag(id: String) = "store-search:torrent:group:$id"
    const val NOTE_TAG = "store-search:torrent:note"
    const val TABLE_TAG = "store-search:torrent:table"
    fun headerTag(c: StoreTorrentHost.Col) = "store-search:torrent:col:${c.name.lowercase()}"
    fun rowTag(i: Int) = "store-search:torrent:row:$i"
    fun downloadTag(id: String, i: Int) = "store-search:torrent:download:$id:$i"

    private sealed class St {
        object Off : St()
        object Busy : St()
        class Done(val hits: List<StoreTorrentHost.Hit>) : St()
        class Failed(val why: String) : St()
    }

    @Composable
    fun Content(ctx: Context, asked: String) {
        val p = LocalKitPalette.current
        val host = StoreTorrentHost.host
        if (host == null) {
            KitEmptyState("Torrent sources live in Cloud Store", "This app embeds the Store without a BitTorrent engine. Open Cloud Store to search and download torrents.",
                modifier = Modifier.testTag(TAG_NO_HOST))
            return
        }
        val sources = remember(host) { host.sources() }
        val off = remember { mutableStateListOf<String>() }
        val states = remember { mutableStateMapOf<String, St>() }
        val scope = rememberCoroutineScope()
        var retry by remember { mutableStateOf(0) }
        var sortCol by remember { mutableStateOf(StoreTorrentHost.Col.SEEDERS) }
        var sortDesc by remember { mutableStateOf(true) }
        var notes by remember { mutableStateOf<String?>(null) }
        val offKey = off.sorted().joinToString(",")
        LaunchedEffect(asked, offKey, host, retry) {
            states.clear()
            if (asked.isEmpty()) return@LaunchedEffect
            coroutineScope {
                for (s in sources) {
                    if (s.id in off) { states[s.id] = St.Off; continue }
                    states[s.id] = St.Busy
                    launch {
                        val r = withContext(Dispatchers.IO) { runCatching { host.search(s.id, asked) } }
                        states[s.id] = r.fold({ St.Done(it) }, { St.Failed(it.message ?: it.javaClass.simpleName) })
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().testTag(TAG_PAGE), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                sources.forEach { s ->
                    StoreSearchPage.SourcePill(s.label, s.id !in off, sourceTag(s.id)) { if (s.id in off) off.remove(s.id) else off.add(s.id) }
                }
            }
            if (asked.isEmpty()) {
                Text("Search ${sources.size} torrent sources: " + sources.joinToString(", ") { it.label } + ". A result downloads with the Torrent engine into " + host.folderNote() + ".",
                    color = p.textSecondary, fontSize = KitDensity.caption)
                sources.forEach { Text("${it.label}: ${it.blurb}", color = p.textSecondary, fontSize = KitDensity.caption) }
                return@Column
            }
            val busy = states.values.count { it is St.Busy }
            val found = states.values.filterIsInstance<St.Done>().sumOf { it.hits.size }
            if (busy > 0) KitStatusBanner("Searching $busy of ${sources.size - off.size} torrent sources…", KitState.BUSY, tag = "store-search:torrent:status")
            else if (states.isNotEmpty() && found == 0 && states.values.none { it is St.Failed })
                KitEmptyState("No torrents found", "Nothing matched \"$asked\" in the enabled torrent sources.")
            else Text("$found torrents for \"$asked\"", color = p.textSecondary, fontSize = KitDensity.caption)
            // One status line per source (searching / found / failed with Retry), then ONE table over every hit found.
            sources.forEach { s ->
                val st = states[s.id] ?: return@forEach
                Column(Modifier.fillMaxWidth().testTag(groupTag(s.id)), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 4)) {
                    Row {
                        when (st) {
                            is St.Done -> KitStatePill(if (st.hits.isEmpty()) "none" else "${st.hits.size} found", if (st.hits.isEmpty()) KitState.IDLE else KitState.OK)
                            is St.Busy -> KitStatePill("searching", KitState.BUSY)
                            is St.Failed -> KitStatePill("failed", KitState.BAD)
                            is St.Off -> KitStatePill("off", KitState.IDLE)
                        }
                        Text(s.label, color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = KitDensity.small))
                    }
                    if (st is St.Failed) KitStatusBanner("${s.label}: ${st.why}", KitState.BAD, tag = "store-search:torrent:failed:${s.id}",
                        action = KitAction("Retry", "store-search:torrent:retry:${s.id}") { retry++ })
                }
            }
            val all = states.values.filterIsInstance<St.Done>().flatMap { it.hits }
            if (all.isNotEmpty()) {
                val rows = StoreTorrentHost.sorted(all, sortCol, sortDesc)
                ResultTable(rows, sortCol, sortDesc, { c -> if (c == sortCol) sortDesc = !sortDesc else { sortCol = c; sortDesc = StoreTorrentHost.defaultDescending(c) } }) { i, h ->
                    notes = "adding…"
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) { runCatching { host.download(h) }.getOrElse { it.message ?: it.javaClass.simpleName } }
                        notes = h.title.take(40) + ": " + msg
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    }
                }
                notes?.let { Text(it, color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.testTag(NOTE_TAG)) }
            }
        }
    }

    private fun width(c: StoreTorrentHost.Col?): Int = when (c) {
        StoreTorrentHost.Col.NAME -> 15; StoreTorrentHost.Col.SIZE -> 5; StoreTorrentHost.Col.SEEDERS -> 3; StoreTorrentHost.Col.LEECHERS -> 3
        StoreTorrentHost.Col.SOURCE -> 8; StoreTorrentHost.Col.AGE -> 3; StoreTorrentHost.Col.CATEGORY -> 6; StoreTorrentHost.Col.FILES -> 3; null -> 4
    } * StoreDensity.S12

    /**
     * The results as one columnar table: Name, Size, S (seeders), L (leechers), Source, Age, Category, Files and the Add column
     * (magnet or fetched .torrent to the engine). The header stays fixed above the rows (the rows scroll under it) and both slide
     * sideways together; a header click sorts by that column. Compact rows, hairline rules, "—" for a field the indexer lacks.
     */
    @Composable
    private fun ResultTable(rows: List<StoreTorrentHost.Hit>, sortCol: StoreTorrentHost.Col, desc: Boolean, onSort: (StoreTorrentHost.Col) -> Unit, onAdd: (Int, StoreTorrentHost.Hit) -> Unit) {
        val p = LocalKitPalette.current
        @Composable fun cell(c: StoreTorrentHost.Col?, text: String, modifier: Modifier = Modifier, bold: Boolean = false, tag: String? = null) {
            val num = c != null && !c.textFirst
            Text(text, color = p.textPrimary, fontSize = KitDensity.caption, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = if (num) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
                modifier = modifier.width(sdp(width(c))).padding(horizontal = sdp(StoreDensity.S4), vertical = sdp(StoreDensity.S2)).let { if (tag != null) it.testTag(tag) else it })
        }
        @Composable fun rule() = Box(Modifier.fillMaxWidth().height(StoreDensity.STROKE.dp).background(Ink.RULE.ink()))
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(TABLE_TAG)) {
            Row(Modifier.background(Ink.CARD.ink())) {
                StoreTorrentHost.Col.values().forEach { c ->
                    cell(c, c.label + if (c == sortCol) (if (desc) " ▼" else " ▲") else "", Modifier.clickable { onSort(c) }, bold = true, tag = headerTag(c))
                }
                cell(null, "Add", bold = true)
            }
            rule()
            Column(Modifier.heightIn(max = sdp(StoreDensity.SHEET)).verticalScroll(rememberScrollState())) {
                rows.forEachIndexed { i, h ->
                    Row(Modifier.testTag(rowTag(i)), verticalAlignment = Alignment.CenterVertically) {
                        cell(StoreTorrentHost.Col.NAME, h.title, bold = true)
                        cell(StoreTorrentHost.Col.SIZE, if (h.sizeBytes >= 0) StoreTorrentHost.sizeText(h.sizeBytes) else StoreTorrentHost.DASH)
                        cell(StoreTorrentHost.Col.SEEDERS, StoreTorrentHost.seedText(h.seeders))
                        cell(StoreTorrentHost.Col.LEECHERS, StoreTorrentHost.seedText(h.leechers))
                        cell(StoreTorrentHost.Col.SOURCE, h.sourceLabel.ifEmpty { h.sourceId })
                        cell(StoreTorrentHost.Col.AGE, StoreTorrentHost.ageText(h.addedAt))
                        cell(StoreTorrentHost.Col.CATEGORY, h.category ?: StoreTorrentHost.DASH)
                        cell(StoreTorrentHost.Col.FILES, StoreTorrentHost.filesText(h.files))
                        cell(null, "Add", Modifier.clickable { onAdd(i, h) }, bold = true, tag = downloadTag(h.sourceId, i))
                    }
                    rule()
                }
            }
        }
    }
}
