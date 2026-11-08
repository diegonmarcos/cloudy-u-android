package com.diegonmarcos.superapp.configs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.diegonmarcos.superapp.adbdebug.ChannelOps
import com.diegonmarcos.superapp.perms.PermsLink
import com.diegonmarcos.superapp.perms.RowChip
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import com.diegonmarcos.superapp.shellclient.ShellStatus
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Re-read key: bumps on resume (no polling). */
@Composable
private fun resumeTick(): Int {
    var tick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    return tick
}

/** Shell tab: the terminal (input, output, tabs) and the command bookmarks (the snippet palette with its starred favourites) from [AdbTerminal]. */
@Composable
internal fun AdbTermTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ShellStatus?>(null) }
    var bump by remember { mutableStateOf(0) }
    val tick = resumeTick()
    LaunchedEffect(tick, bump) { status = withContext(Dispatchers.IO) { runCatching { ShellBroker.status(ctx) }.getOrNull() } }
    val rearm: () -> Unit = { scope.launch(Dispatchers.IO) { runCatching { ChannelOps.reconnect(ctx) }; bump++ } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("adb:tab:shell")) {
        AdbTerminal(status, onReconnect = rearm, outputHeight = KitStoreRow.step(520))
    }
}

/** Perms tab: the permission checker, every permission the catalogue applies here with its granted / not state; the full page is one tap away. */
@Composable
internal fun AdbPermsTab() {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    var rows by remember { mutableStateOf<List<PermRowState>?>(null) }
    val tick = resumeTick()
    LaunchedEffect(tick) { rows = withContext(Dispatchers.IO) { runCatching { readPermRows(ctx, null) }.getOrDefault(emptyList()) } }
    val all = rows
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("adb:tab:perms")) {
        if (all == null) { KitStoreRowLine { KitStoreCaption("Reading permissions...", color = p.textSecondary) }; return@Column }
        PermsSummaryBar(all, ctx, null) { PermsLink.open(ctx, "adb-shell", emptyList()) }
        val page = pageRows(ctx, all).filter { it.counted }.sortedBy { if (it.status.state == com.diegonmarcos.superapp.perms.PermState.GRANTED) 1 else 0 }
        for (r in page) KitStoreRowLine(Modifier.testTag("adb:perm:${r.entry.id}")) {
            val ink = when (r.chip) { RowChip.GRANTED -> p.ok; RowChip.MISSING, RowChip.DENIED_PERMANENTLY -> p.bad; RowChip.NEEDS_SHELL -> p.warn; else -> p.textSecondary }
            KitStoreDot("●", ink)
            Column(Modifier.weight(1f)) {
                KitStoreName(r.entry.label, color = p.textPrimary)
                if (r.reason.isNotBlank()) KitStoreCaption(r.reason, color = p.textSecondary)
            }
            KitStoreCaption(r.chip.word, color = ink)
        }
        KitStoreRowLine { KitStoreQuick("Open full Perms page ›", KitStoreRow.BLUE_FILL, Modifier.testTag("adb:perms-open")) { PermsLink.open(ctx, "adb-shell", emptyList()) } }
    }
}
