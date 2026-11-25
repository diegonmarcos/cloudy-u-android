package com.diegonmarcos.superapp.network.mesh

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.superapp.R
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * #877 THE Cloud Mesh page: libs:bottomnav's [PageTabs] strip over the declared tabs
 * (`ui.mesh_page.tabs`, #1074: Status / Profile / Logs), the notice line under it, and the open tab's
 * declared sections (`pages`) drawn one after the other in a single scroll. One composable per section id.
 * The strip takes no colour or size from here - the lib owns its look.
 *
 * [ticker] false leaves polling to the caller (tests drive [MeshStore.poll] themselves).
 */
@Composable
fun MeshScreen(store: MeshStore, modifier: Modifier = Modifier, ticker: Boolean = true) {
    if (ticker) MeshTicker(store)
    val tabs = store.decl.tabs.map { NavPage(it.id, declLabel("tab", it.id, it.label), it.icon) }
    val open = store.decl.tab(store.tab)
    Column(modifier.fillMaxSize().testTag("mesh:screen")) {
        PageTabs(tabs, store.tab, onSelect = { store.tab = it.id }, underTopChrome = false, flushTop = true)
        // #1928 the second strip: the open tab's subpages (a tab with a single subpage has no strip)
        open?.takeIf { it.subpages.size > 1 }?.let { t ->
            val subs = t.subpages.map { NavPage(it.id, declLabel("sub", it.id, it.label), t.icon) }
            PageTabs(subs, store.subOf(t.id)?.id, onSelect = { store.sub[t.id] = it.id }, underTopChrome = false, flushTop = true)
        }
        MeshNotice(store)
        val sp = store.subOf(store.tab)
        Column(Modifier.weight(1f).fillMaxWidth().testTag(MeshTags.tab(store.tab)).verticalScroll(rememberScrollState())) {
            if (open == null) MText(store.tab, Modifier.padding(MeshDensity.dp(MeshDensity.S8)), muted = true)
            if (sp != null) MeshSubpage(sp, store)
            else open?.sections?.forEachIndexed { i, id ->
                val label = store.decl.page(id)?.label ?: id
                if (i > 0) MHeader(declLabel("page", id, label), Modifier.padding(horizontal = MeshDensity.dp(MeshDensity.S8)))
                MeshSection(id, store)
            }
        }
    }
}

/**
 * #1928 One subpage: its "Updated" time and, when it declares a `report`, Copy / Export of its part of the status dump, then the
 * sections it names (each shows only what the subpage lists).
 */
@Composable
private fun MeshSubpage(sp: MeshDecl.Subpage, store: MeshStore) {
    Column(Modifier.testTag(MeshTags.sub(sp.id))) {
        if (sp.stamp.isNotBlank() || sp.report.isNotEmpty()) {
            val at = store.stampMs(sp.stamp)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = MeshDensity.dp(MeshDensity.S8), vertical = MeshDensity.dp(MeshDensity.S4)),
                horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6)), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sp.stamp.isNotBlank()) MText(
                    stringResource(R.string.mesh_updated, if (at == 0L) stringResource(R.string.mesh_updated_never) else MeshFormat.clock(at)),
                    Modifier.weight(1f).testTag(MeshTags.stamp(sp.id)), size = MeshDensity.T_CAPTION, mono = true, muted = true,
                )
                if (sp.report.isNotEmpty()) for (cid in listOf("section_copy", "section_export")) store.decl.control(cid)?.let { c ->
                    MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine, sp.id) }, Modifier.testTag(MeshTags.control(c.id)))
                }
            }
        }
        sp.sections.forEachIndexed { i, id ->
            if (i > 0) MHeader(declLabel("page", id, store.decl.page(id)?.label ?: id), Modifier.padding(horizontal = MeshDensity.dp(MeshDensity.S8)))
            MeshSection(id, store, sp)
        }
    }
}

/** One declared section, drawn as a block of its tab's scroll; [sub] (#1928) narrows it to what one subpage lists. */
@Composable
fun MeshSection(id: String, store: MeshStore, sub: MeshDecl.Subpage? = null) {
    when (id) {
        "status" -> MeshStatusPage(store, embedded = true, sub = sub)
        "peers" -> MeshPeersPage(store, embedded = true)
        "profiles" -> MeshProfilesPage(store, embedded = true, sub = sub)
        "routes" -> MeshRoutesPage(store, embedded = true)
        "controls" -> MeshControlsPage(store, embedded = true, sub = sub)
        "log" -> MeshLogPage(store, embedded = true)
        else -> MText(id, Modifier.padding(MeshDensity.dp(MeshDensity.S8)), muted = true)
    }
}

@Composable
private fun MeshNotice(store: MeshStore) {
    if (store.notice.isBlank() && !store.busy) return
    MText(
        (if (store.busy) "… " else "") + store.notice,
        Modifier.fillMaxWidth().padding(horizontal = MeshDensity.dp(MeshDensity.S8)).testTag(MeshTags.NOTICE),
        size = MeshDensity.T_CAPTION, mono = true, muted = true, maxLines = 3,
    )
}

/**
 * The stats ticker: polls the engine every `poll_ms` while - and only while - the page's lifecycle is
 * RESUMED. Leaving the screen, locking the phone or switching app pauses it; the loop is cancelled, not
 * slept, so nothing runs in the background.
 */
@Composable
fun MeshTicker(store: MeshStore) {
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(resumed) {
        if (!store.shouldPoll(resumed)) return@LaunchedEffect
        while (isActive) {
            withContext(Dispatchers.IO) { store.poll() }
            delay(store.decl.pollMs)
        }
    }
}
