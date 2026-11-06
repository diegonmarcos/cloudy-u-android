package org.fossify.phone.sim

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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

@Composable
internal fun PageList(tag: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
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

/** Configs ▸ SIM: the same SIM list and per-SIM sub-pages as the SIMs bottom tab. */
@Composable
fun SimCardsPage(c: ThemeColors) = SimsOverviewPage(c)

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

internal data class SimUsageBlock(val card: SimCard, val manual: SimManual, val rows: List<Pair<UsagePeriod, UsageTotals>>)

@Composable
internal fun rememberUsage(): List<SimUsageBlock> {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<List<SimUsageBlock>>(emptyList()) }
    LaunchedEffect(Unit) {
        data = withContext(Dispatchers.IO) {
            val reader = SimReader(ctx)
            val store = SimManualStore(ctx)
            val usage = SimUsageReader(ctx)
            reader.cards().map { card ->
                val m = store.load(SimFields.key(card))
                if (!card.active) return@map SimUsageBlock(card, m, emptyList())
                val periods = SimUsage.periods(java.time.LocalDate.now(), m.renewalDate)
                val ev = usage.events(card, periods.minOf { it.startMillis })
                SimUsageBlock(card, m, periods.map { p -> p to SimUsage.totals(ev, p, usage.dataBytes(card, p)) })
            }
        }
    }
    return data
}

@Composable
internal fun UsageCard(b: SimUsageBlock, c: ThemeColors) {
    ConfigsCard("Usage · ${SimFields.title(b.card)}", c, icon = Icons.Filled.DataUsage) {
        if (b.manual.dataAllowance.isNotBlank()) ConfigsRow("Allowance", b.manual.dataAllowance, c)
        if (b.rows.isEmpty()) ConfigsRow("Usage", "n/a (profile is inactive)", c)
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
        items(data.filter { it.card.active }, key = { SimFields.key(it.card) }) { b -> UsageCard(b, c) }
    }
}
