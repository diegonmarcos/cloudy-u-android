package com.diegonmarcos.superapp.profile

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup ▸ perms (account redesign spec 4.9): draws [PermsPlan] for the working profile.
 * Per app: granted / denied counts, every runtime permission and special grant, a Grant per row;
 * Grant all at the top runs [PermsPlan.apply] over the shell channel. No channel: the page says so and
 * offers the runbook's shell step. A NEEDS_USER row opens its Settings page; nothing pretends.
 */
object PermsPageTags {
    const val PAGE = "account:setup:perms"
    const val GRANT_ALL = "account:setup:perms:grant-all"
    const val NO_CHANNEL = "account:setup:perms:no-channel"
}

@Composable
fun AccountPermsPage(go: (section: String, page: String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var plan by remember { mutableStateOf<PermsPlan.Plan?>(null) }
    var channel by remember { mutableStateOf<ShellChannel?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            val ch = runCatching { ShellChannels.active(ctx) }.getOrNull()
            val profile = DeviceVault(ctx).working()?.optJSONObject("profile")
            val p = PermsPlan.plan(ctx, profile, ch)
            withContext(Dispatchers.Main) { channel = ch; plan = p }
        }
    }

    fun work(block: suspend () -> List<String>) {
        if (busy) return
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { listOf("failed: ${it.message}") } }
            lines = out; busy = false; tick++
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp).testTag(PermsPageTags.PAGE), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            KitStatusBanner(plan?.summary() ?: "reading every app's grants…",
                when { plan == null -> KitState.BUSY; plan?.todo.isNullOrEmpty() -> KitState.OK; else -> KitState.WARN }, tag = "perms")
        }
        item {
            KitCard(Modifier.fillMaxWidth()) {
                val ch = channel
                if (ch == null) {
                    KitListRow("No shell channel", secondary = "Grant all needs one: arm the shell broker in the SuperApp",
                        pill = "none" to KitState.BAD, tag = "perms:channel")
                    KitActionBar(listOf(KitAction("Open ADB Shell", PermsPageTags.NO_CHANNEL) { go("setup", "adb-shell") }))
                } else KitListRow("Shell channel", secondary = ch.name(), pill = "ready" to KitState.OK, tag = "perms:channel")
                KitActionBar(listOf(KitAction(if (busy) "Granting…" else "Grant all (${plan?.todo?.size ?: 0} from the profile)",
                    PermsPageTags.GRANT_ALL, !busy && plan != null && ch != null) {
                    plan?.let { p -> work { PermsPlan.apply(ctx, p, ch).lines.map { it.text() } } }
                }))
                lines.forEach { l -> SaidBanner(l, "perms:line") }
            }
        }
        val p = plan
        if (p != null) {
            if (p.global.isNotEmpty()) item {
                KitSectionHeader("Device", "", eyebrow = true)
                KitCard(Modifier.fillMaxWidth()) {
                    p.global.forEach { ItemRow(it, busy, channel) { i -> work { listOf(PermsPlan.applyOne(ctx, i, channel).text()) } } }
                }
            }
            items(p.apps, key = { it.pkg }) { a ->
                KitCard(Modifier.fillMaxWidth()) {
                    KitListRow(a.label, secondary = a.pkg + if (a.fleet) " · fleet" else "", tag = a.pkg,
                        leading = a.label.take(1).uppercase(),
                        pill = "${a.granted} granted · ${a.denied} denied" to (if (a.denied == 0) KitState.OK else KitState.WARN))
                    a.items.forEach { ItemRow(it, busy, channel) { i -> work { listOf(PermsPlan.applyOne(ctx, i, channel).text()) } } }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: PermsPlan.Item, busy: Boolean, channel: ShellChannel?, grant: (PermsPlan.Item) -> Unit) {
    val ctx = LocalContext.current
    val pill = when (item.granted) {
        true -> "granted" to KitState.OK
        false -> "denied" to (if (item.wanted) KitState.WARN else KitState.IDLE)
        null -> "unknown" to KitState.IDLE
    }
    KitListRow(item.name.substringAfterLast('.'), secondary = item.kind.id + (if (item.wanted) " · in the profile" else "") + " · " + item.name,
        pill = pill, tag = "${item.pkg}:${item.kind.id}:${item.name}") {
        if (item.granted != true) {
            if (item.kind == PermsPlan.Kind.USER) {
                TextButton(onClick = {
                    runCatching { ctx.startActivity(PermsPlan.settingsIntent(item)) }
                        .onFailure { Toast.makeText(ctx, "No settings screen", Toast.LENGTH_SHORT).show() }
                }) { Text("needs the user ↗") }
            } else {
                TextButton(enabled = !busy && (channel != null || item.kind == PermsPlan.Kind.VERIFIER), onClick = { grant(item) }) { Text("Grant") }
            }
        }
    }
}
