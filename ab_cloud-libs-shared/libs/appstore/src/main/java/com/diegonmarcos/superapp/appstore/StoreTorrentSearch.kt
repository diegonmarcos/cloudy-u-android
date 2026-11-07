package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
internal object StoreTorrentSearch {
    const val TAG_PAGE = "store-search:torrent"
    const val TAG_NO_HOST = "store-search:torrent:no-host"
    fun sourceTag(id: String) = "store-search:torrent:source:$id"
    fun groupTag(id: String) = "store-search:torrent:group:$id"
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
        val notes = remember { mutableStateMapOf<String, String>() }
        val scope = rememberCoroutineScope()
        var retry by remember { mutableStateOf(0) }
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
                    if (st is St.Done) st.hits.forEachIndexed { i, h ->
                        val noteKey = s.id + ":" + i
                        Column(Modifier.fillMaxWidth().background(Ink.CARD.ink()).padding(KitDensity.small)) {
                            Text(h.title, color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(StoreTorrentHost.line(h), color = p.textSecondary, fontSize = KitDensity.caption)
                            Row {
                                Text("Download", color = p.textPrimary, fontSize = KitDensity.caption, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag(downloadTag(s.id, i)).background(p.accent).clickable {
                                        notes[noteKey] = "adding…"
                                        scope.launch {
                                            val msg = withContext(Dispatchers.IO) { runCatching { host.download(h) }.getOrElse { it.message ?: it.javaClass.simpleName } }
                                            notes[noteKey] = msg
                                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    }.padding(horizontal = KitDensity.small, vertical = KitDensity.small / 2))
                                notes[noteKey]?.let { Text(it, color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(start = KitDensity.small)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
