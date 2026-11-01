package org.fossify.phone.sim

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.perms.PermsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Configs ▸ SIM (one card per slot, manual fields) and Configs ▸ Status (live per-SIM state, auto-refresh). */
class SimConfigsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_SIM
        val reader = SimReader(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    if (!reader.hasPhoneState()) MissingPerms() else when (page) { PAGE_STATUS -> StatusPage(reader); PAGE_USAGE -> UsagePage(reader); else -> SimPage(reader) }
                }
            }
        }
    }

    @Composable
    private fun MissingPerms() = Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Missing permission Phone state", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { startActivity(Intent(this@SimConfigsActivity, PermsActivity::class.java).putExtra(PermsActivity.EXTRA_IDS, PERM_IDS)) }) { Text("Grant") }
    }

    @Composable
    private fun SimPage(reader: SimReader) {
        val store = remember { SimManualStore(this) }
        var cards by remember { mutableStateOf<List<SimCard>>(emptyList()) }
        LaunchedEffect(Unit) { cards = withContext(Dispatchers.IO) { reader.cards() } }
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Configs ▸ SIM", style = MaterialTheme.typography.headlineSmall) }
            if (cards.isEmpty()) item { Text("No active SIM") }
            items(cards, key = { SimFields.key(it) }) { c -> SimCardView(c, store) }
        }
    }

    @Composable
    private fun SimCardView(c: SimCard, store: SimManualStore) {
        val key = SimFields.key(c)
        var manual by remember(key) { mutableStateOf(store.load(key)) }
        var reveal by remember(key) { mutableStateOf(false) }
        var editing by remember(key) { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("${if (c.embedded) "eSIM" else "SIM"} ${c.slot + 1} · ${c.displayName ?: c.carrier ?: ""}", style = MaterialTheme.typography.titleMedium)
                SimFields.rows(c, manual, reveal).forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).let { if (r.secret) it.clickable { reveal = !reveal } else it }) {
                        Text(r.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(r.value, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
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
            fun Modifier.full() = this.fillMaxWidth()
            OutlinedTextField(m.provider, { m = m.copy(provider = it) }, Modifier.full(), label = { Text("Provider") })
            OutlinedTextField(m.plan, { m = m.copy(plan = it) }, Modifier.full(), label = { Text("Plan") })
            OutlinedTextField(m.monthlyPrice, { m = m.copy(monthlyPrice = it) }, Modifier.full(), label = { Text("Monthly price") })
            OutlinedTextField(m.currency, { m = m.copy(currency = it) }, Modifier.full(), label = { Text("Currency") })
            OutlinedTextField(m.dataAllowance, { m = m.copy(dataAllowance = it) }, Modifier.full(), label = { Text("Data allowance") })
            OutlinedTextField(m.renewalDate, { m = m.copy(renewalDate = it) }, Modifier.full(), label = { Text("Renewal date") })
            OutlinedTextField(m.number, { m = m.copy(number = it) }, Modifier.full(), label = { Text("Number (if hidden)") })
            OutlinedTextField(m.pin, { m = m.copy(pin = it) }, Modifier.full(), label = { Text("PIN") }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(m.puk, { m = m.copy(puk = it) }, Modifier.full(), label = { Text("PUK") }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(m.apnNotes, { m = m.copy(apnNotes = it) }, Modifier.full(), label = { Text("APN notes") })
            OutlinedTextField(m.support, { m = m.copy(support = it) }, Modifier.full(), label = { Text("Support contact") })
            Button(onClick = { onSave(m) }) { Text("Save") }
        }
    }

    @Composable
    private fun StatusPage(reader: SimReader) {
        var list by remember { mutableStateOf<List<SimStatus>>(emptyList()) }
        LaunchedEffect(Unit) {
            while (true) {
                list = withContext(Dispatchers.IO) { reader.status() }
                delay(REFRESH_MS)
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Configs ▸ Status (every ${REFRESH_MS / 1000} s)", style = MaterialTheme.typography.headlineSmall) }
            if (list.isEmpty()) item { Text("No active SIM") }
            items(list, key = { it.slot }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("SIM ${s.slot + 1} · ${s.label}", style = MaterialTheme.typography.titleMedium)
                        StatusFormat.rows(s).forEach { r ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(r.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(r.value, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun UsagePage(reader: SimReader) {
        val store = remember { SimManualStore(this) }
        val usage = remember { SimUsageReader(this) }
        var data by remember { mutableStateOf<List<Triple<SimCard, SimManual, List<Pair<UsagePeriod, UsageTotals>>>>>(emptyList()) }
        LaunchedEffect(Unit) {
            data = withContext(Dispatchers.IO) {
                reader.cards().map { c ->
                    val m = store.load(SimFields.key(c))
                    val periods = SimUsage.periods(java.time.LocalDate.now(), m.renewalDate)
                    val since = periods.minOf { it.startMillis }
                    val ev = usage.events(c, since)
                    Triple(c, m, periods.map { p -> p to SimUsage.totals(ev, p, usage.dataBytes(c, p)) })
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Configs ▸ Usage", style = MaterialTheme.typography.headlineSmall) }
            if (data.isEmpty()) item { Text("No active SIM") }
            items(data, key = { SimFields.key(it.first) }) { (c, m, rows) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("SIM ${c.slot + 1} · ${c.displayName ?: c.carrier ?: ""}", style = MaterialTheme.typography.titleMedium)
                        if (m.dataAllowance.isNotBlank()) Text("Allowance: ${m.dataAllowance}", style = MaterialTheme.typography.bodySmall)
                        rows.forEach { (p, t) ->
                            Text(p.label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                            SimUsage.rows(t, m.dataAllowance).forEach { r ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(r.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Text(r.value, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_SIM = "sim"
        const val PAGE_STATUS = "status"
        const val PAGE_USAGE = "usage"
        const val REFRESH_MS = 3000L
        val PERM_IDS: Array<String> = arrayOf("phone_state", "phone_numbers", "sms_read", "usage_access")

        fun intent(context: Context, page: String): Intent =
            Intent(context, SimConfigsActivity::class.java).putExtra(EXTRA_PAGE, page)
    }
}
