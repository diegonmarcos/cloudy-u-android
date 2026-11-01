package com.diegonmarcos.capitalfinterm.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.Holding
import com.diegonmarcos.capitalfinterm.Holdings
import com.diegonmarcos.capitalfinterm.R
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.KitTextButton
import java.util.Locale

/** The `holdings` kind: a simple local list (ticker, quantity, unit cost) kept in this app's private preferences; nothing is fetched or priced.
 *  New rows are entered in a bottom sheet opened by the Add button (same ModalBottomSheet pattern as DashboardPage's alert sheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingsPanel() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("holdings", Context.MODE_PRIVATE) }
    var rows by remember { mutableStateOf(Holdings.parse(prefs.getString("rows", "[]").orEmpty())) }
    var ticker by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    fun save(list: List<Holding>) {
        rows = list
        prefs.edit().putString("rows", Holdings.toJson(list)).apply()
    }
    KitCard {
        KitSectionHeader(stringResource(R.string.holdings_title), stringResource(R.string.holdings_sub))
        KitButton(onClick = { adding = true }, modifier = Modifier.testTag(Tags.ADD_SHEET)) { Text(stringResource(R.string.holdings_add)) }
    }
    if (adding) {
        ModalBottomSheet(onDismissRequest = { adding = false; rejected = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KitSectionHeader(stringResource(R.string.holdings_add), stringResource(R.string.holdings_sub))
                OutlinedTextField(
                    value = ticker, onValueChange = { ticker = it }, singleLine = true,
                    label = { Text(stringResource(R.string.holdings_ticker)) },
                    modifier = Modifier.fillMaxWidth().testTag(Tags.TICKER),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quantity, onValueChange = { quantity = it }, singleLine = true,
                        label = { Text(stringResource(R.string.holdings_quantity)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag(Tags.QUANTITY),
                    )
                    OutlinedTextField(
                        value = cost, onValueChange = { cost = it }, singleLine = true,
                        label = { Text(stringResource(R.string.holdings_cost)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag(Tags.COST),
                    )
                }
                if (rejected) Text(stringResource(R.string.holdings_rejected), color = MaterialTheme.colorScheme.error)
                KitButton(onClick = {
                    val next = Holdings.add(rows, ticker, quantity, cost)
                    if (next == null) {
                        rejected = true
                    } else {
                        save(next)
                        ticker = ""
                        quantity = ""
                        cost = ""
                        adding = false
                        rejected = false
                    }
                }) { Text(stringResource(R.string.holdings_add)) }
            }
        }
    }
    if (rows.isEmpty()) {
        Text(stringResource(R.string.holdings_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        KitCard {
            rows.forEachIndexed { i, h ->
                KitSettingsRow(
                    title = h.ticker,
                    subtitle = stringResource(R.string.holdings_line, fmt(h.quantity), fmt(h.cost)),
                    trailing = { KitTextButton(onClick = { save(Holdings.remove(rows, i)) }) { Text(stringResource(R.string.holdings_remove)) } },
                )
            }
            Text(stringResource(R.string.holdings_total, fmt(Holdings.costBasis(rows))))
        }
    }
}

private fun fmt(x: Double): String = String.format(Locale.ROOT, "%,.2f", x)
