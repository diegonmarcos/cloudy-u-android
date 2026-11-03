package org.fossify.phone.sim

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.fleetconfigs.ConfigsCard
import com.diegonmarcos.superapp.fleetconfigs.ConfigsRow
import com.diegonmarcos.superapp.fleetconfigs.ConfigsSubheading
import com.diegonmarcos.superapp.fleetconfigs.ConfigsTable
import com.diegonmarcos.superapp.perms.PermsActivity
import com.diegonmarcos.superapp.themebg.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * #1415 the SIM pages, drawn with the shared Configs v2 card / row / table (libs:ui-nav-bars ConfigsCard, ConfigsRow,
 * ConfigsTable), so no row is wider than the screen. Configs ▸ SIM, Configs ▸ Status and Configs ▸ Usage are tabs of
 * the one Configs (DialerConfigsActivity); the SIMs bottom tab is [SimsOverviewPage] (each SIM's card plus its usage).
 */
object SimPages {
    const val REFRESH_MS = 3000L
    val PERM_IDS: Array<String> = arrayOf("phone_state", "phone_numbers", "sms_read", "usage_access")
    const val TAG_SIM = "dialer_cfg_sim"
    const val TAG_STATUS = "dialer_cfg_status"
    const val TAG_USAGE = "dialer_cfg_usage"
    const val TAG_SIMS = "dialer_sims"
}

private fun title(c: SimCard) = "${if (c.embedded) "eSIM" else "SIM"} ${c.slot + 1} · ${c.displayName ?: c.carrier ?: ""}"

@Composable
private fun PageList(tag: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        content()
        item { Spacer(Modifier.height(96.dp)) }
    }
}

/** No READ_PHONE_STATE: say so and grant through libs:perms. */
@Composable
fun SimPermsGate(c: ThemeColors, body: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val reader = remember { SimReader(ctx) }
    if (reader.hasPhoneState()) { body(); return }
    Column(Modifier.padding(12.dp)) {
        ConfigsCard("Missing permission Phone state", c, icon = Icons.Filled.SimCard) {
            Button(onClick = { ctx.startActivity(Intent(ctx, PermsActivity::class.java).putExtra(PermsActivity.EXTRA_IDS, SimPages.PERM_IDS)) }) { Text("Grant") }
        }
    }
}

@Composable
fun SimCardsPage(c: ThemeColors) = SimPermsGate(c) {
    val ctx = LocalContext.current
    val reader = remember { SimReader(ctx) }
    val store = remember { SimManualStore(ctx) }
    var cards by remember { mutableStateOf<List<SimCard>>(emptyList()) }
    LaunchedEffect(Unit) { cards = withContext(Dispatchers.IO) { reader.cards() } }
    PageList(SimPages.TAG_SIM) {
        if (cards.isEmpty()) item { ConfigsCard("No active SIM", c, icon = Icons.Filled.SimCard) {} }
        items(cards, key = { SimFields.key(it) }) { card -> SimCardView(card, store, c, editable = true) }
    }
}

@Composable
private fun SimCardView(card: SimCard, store: SimManualStore, c: ThemeColors, editable: Boolean) {
    val key = SimFields.key(card)
    var manual by remember(key) { mutableStateOf(store.load(key)) }
    var reveal by remember(key) { mutableStateOf(false) }
    var editing by remember(key) { mutableStateOf(false) }
    ConfigsCard(title(card), c, icon = Icons.Filled.SimCard) {
        SimFields.rows(card, manual, reveal).forEach { r ->
            ConfigsRow(r.label, r.value, c, onClick = if (r.secret) ({ reveal = !reveal }) else null)
        }
        if (editable) {
            Row {
                TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide ids" else "Reveal ids") }
                TextButton(onClick = { editing = !editing }) { Text(if (editing) "Close" else "Edit plan") }
            }
            if (editing) ManualEditor(manual) { m -> manual = m; store.save(key, m); editing = false }
        }
    }
}

@Composable
private fun ManualEditor(initial: SimManual, onSave: (SimManual) -> Unit) {
    var m by remember { mutableStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val f = Modifier.fillMaxWidth()
        OutlinedTextField(m.provider, { m = m.copy(provider = it) }, f, label = { Text("Provider") })
        OutlinedTextField(m.plan, { m = m.copy(plan = it) }, f, label = { Text("Plan") })
        OutlinedTextField(m.monthlyPrice, { m = m.copy(monthlyPrice = it) }, f, label = { Text("Monthly price") })
        OutlinedTextField(m.currency, { m = m.copy(currency = it) }, f, label = { Text("Currency") })
        OutlinedTextField(m.dataAllowance, { m = m.copy(dataAllowance = it) }, f, label = { Text("Data allowance") })
        OutlinedTextField(m.renewalDate, { m = m.copy(renewalDate = it) }, f, label = { Text("Renewal date") })
        OutlinedTextField(m.number, { m = m.copy(number = it) }, f, label = { Text("Number (if hidden)") })
        OutlinedTextField(m.pin, { m = m.copy(pin = it) }, f, label = { Text("PIN") }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(m.puk, { m = m.copy(puk = it) }, f, label = { Text("PUK") }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(m.apnNotes, { m = m.copy(apnNotes = it) }, f, label = { Text("APN notes") })
        OutlinedTextField(m.support, { m = m.copy(support = it) }, f, label = { Text("Support contact") })
        Button(onClick = { onSave(m) }) { Text("Save") }
    }
}

@Composable
fun SimStatusPage(c: ThemeColors) = SimPermsGate(c) {
    val ctx = LocalContext.current
    val reader = remember { SimReader(ctx) }
    var list by remember { mutableStateOf<List<SimStatus>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            list = withContext(Dispatchers.IO) { reader.status() }
            delay(SimPages.REFRESH_MS)
        }
    }
    PageList(SimPages.TAG_STATUS) {
        item {
            ConfigsCard("Live status (every ${SimPages.REFRESH_MS / 1000} s)", c, icon = Icons.Filled.NetworkCell) {
                if (list.isEmpty()) ConfigsRow("SIM", "No active SIM", c)
                else ConfigsTable(StatusFormat.TABLE_HEADER, StatusFormat.table(list), c)
            }
        }
        items(list, key = { it.slot }) { s ->
            ConfigsCard("SIM ${s.slot + 1} · ${s.label}", c, icon = Icons.Filled.NetworkCell) {
                StatusFormat.rows(s).forEach { r -> ConfigsRow(r.label, r.value, c) }
            }
        }
    }
}

private data class SimUsageBlock(val card: SimCard, val manual: SimManual, val rows: List<Pair<UsagePeriod, UsageTotals>>)

@Composable
private fun rememberUsage(): List<SimUsageBlock> {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<List<SimUsageBlock>>(emptyList()) }
    LaunchedEffect(Unit) {
        data = withContext(Dispatchers.IO) {
            val reader = SimReader(ctx)
            val store = SimManualStore(ctx)
            val usage = SimUsageReader(ctx)
            reader.cards().map { card ->
                val m = store.load(SimFields.key(card))
                val periods = SimUsage.periods(java.time.LocalDate.now(), m.renewalDate)
                val ev = usage.events(card, periods.minOf { it.startMillis })
                SimUsageBlock(card, m, periods.map { p -> p to SimUsage.totals(ev, p, usage.dataBytes(card, p)) })
            }
        }
    }
    return data
}

@Composable
private fun UsageCard(b: SimUsageBlock, c: ThemeColors) {
    ConfigsCard("Usage · ${title(b.card)}", c, icon = Icons.Filled.DataUsage) {
        if (b.manual.dataAllowance.isNotBlank()) ConfigsRow("Allowance", b.manual.dataAllowance, c)
        b.rows.firstOrNull()?.let { (p, t) ->
            ConfigsSubheading(p.label, c)
            SimUsage.rows(t, b.manual.dataAllowance).forEach { r -> ConfigsRow(r.label, r.value, c) }
        }
        ConfigsSubheading("By period", c)
        ConfigsTable(SimUsage.TABLE_HEADER, SimUsage.table(b.rows), c)
    }
}

@Composable
fun SimUsagePage(c: ThemeColors) = SimPermsGate(c) {
    val data = rememberUsage()
    PageList(SimPages.TAG_USAGE) {
        if (data.isEmpty()) item { ConfigsCard("No active SIM", c, icon = Icons.Filled.DataUsage) {} }
        items(data, key = { SimFields.key(it.card) }) { b -> UsageCard(b, c) }
    }
}

/** The SIMs bottom tab: every SIM's card (read only; edit in Configs ▸ SIM) followed by its usage. */
@Composable
fun SimsOverviewPage(c: ThemeColors) = SimPermsGate(c) {
    val ctx = LocalContext.current
    val store = remember { SimManualStore(ctx) }
    val data = rememberUsage()
    PageList(SimPages.TAG_SIMS) {
        if (data.isEmpty()) item { ConfigsCard("No active SIM", c, icon = Icons.Filled.SimCard) {} }
        data.forEach { b ->
            item(key = "card_" + SimFields.key(b.card)) { SimCardView(b.card, store, c, editable = false) }
            item(key = "usage_" + SimFields.key(b.card)) { UsageCard(b, c) }
        }
    }
}
