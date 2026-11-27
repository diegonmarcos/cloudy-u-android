package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.Exchange
import com.diegonmarcos.cloudcalc.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import java.time.ZoneId

/** The test tags of Finance > Exchange (#1945). */
object ExchangeTags {
    const val SOURCE_CARD = "ex_source_card"
    const val SOURCE_CODE = "ex_source_code"
    const val AMOUNT = "ex_amount"
    const val ADD = "ex_add"
    const val REFRESH = "ex_refresh"
    const val UPDATED = "ex_updated"
    const val MID_MARKET = "ex_mid_market"
    const val INFO = "ex_info"
    const val INFO_BODY = "ex_info_body"
    const val PICKER_SEARCH = "ex_picker_search"
    const val PICKER_CLOSE = "ex_picker_close"
    fun target(code: String) = "ex_target_$code"
    fun targetCode(code: String) = "ex_target_code_$code"
    fun targetAmount(code: String) = "ex_target_amount_$code"
    fun targetRate(code: String) = "ex_target_rate_$code"
    fun remove(code: String) = "ex_remove_$code"
    fun pickerItem(code: String) = "ex_pick_$code"
}

/** Which chip opened the picker. */
private sealed interface Pick {
    data object Source : Pick
    data class Target(val index: Int) : Pick
    data object Add : Pick
}

/**
 * Finance > Exchange (#1945): the source card ("You convert", currency, editable amount), a divider, one card per target
 * (converted amount and the unit rate line), "Add another currency", and the footer (refresh, last-updated time, mid-market note).
 * Everything a user picks persists; the rates are the engine's mid-market table, cached with their time.
 */
@Composable
fun ExchangeMode(mode: Declarations.Mode) {
    val api = LocalCalcApi.current
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("cloud_calc", Context.MODE_PRIVATE) }
    val spec = remember(mode.id) { Exchange.spec(mode) }
    val store = remember { Exchange.Store(prefs) }
    val rates = remember { Exchange.Rates(api, Exchange.RateCache(prefs), spec.base, mode.options) }
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(store.load(Exchange.defaults(mode))) }
    /** What the input box (state.active) shows while it is typed in. */
    var text by remember { mutableStateOf(Exchange.editText(state.amount, locale)) }
    var table by remember { mutableStateOf(Exchange.RateCache(prefs).load()?.let { Exchange.Table(it.perBase, it.updatedMs, false) } ?: Exchange.Table(emptyMap(), 0L, false)) }
    var refreshing by remember { mutableStateOf(false) }
    /** The last refresh failed: sticky, so a plain read finishing after it cannot hide the offline state. */
    var failed by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<Pick?>(null) }
    var info by remember { mutableStateOf(false) }
    val currencies = remember(mode.id) { Exchange.currencies(api, spec.base, listOf(state.source) + state.targets) }

    fun edit(next: Exchange.State) { state = next; store.save(next) }

    /** Box [i] becomes the input (it keeps the value it showed); a box already the input is left alone. */
    fun activate(i: Int) {
        if (i == state.active) return
        val next = Exchange.activate(state, table.perBase, i)
        text = Exchange.editText(Exchange.parseDecimal(next.amount, java.util.Locale.ROOT)?.setScale(spec.amountDecimals, java.math.RoundingMode.HALF_UP)?.toPlainString() ?: "", locale)
        edit(next)
    }

    /** The user typed [v] in box [i]: that box is the input, every other box is recomputed from it. */
    fun type(i: Int, v: String) {
        if (i != state.active) activate(i)
        text = v
        Exchange.parseDecimal(v, locale)?.let { edit(Exchange.typed(state, i, Exchange.canonical(it))) }
    }

    /** A read and a refresh may finish in either order: the newer time and the union of the rates win. */
    fun apply(t: Exchange.Table) { table = Exchange.Table(table.perBase + t.perBase, maxOf(table.updatedMs, t.updatedMs), t.offline) }

    // The table follows the codes on screen (read, no fetch); once on open the engine is refreshed when its rates are stale.
    val codes = listOf(state.source) + state.targets
    LaunchedEffect(codes) { apply(withContext(Dispatchers.IO) { rates.read(codes) }) }
    LaunchedEffect(Unit) {
        refreshing = true
        val t = withContext(Dispatchers.IO) { rates.refresh(listOf(state.source) + state.targets, force = false) }
        failed = t.offline; apply(t)
        refreshing = false
    }

    val values = Exchange.values(state, table.perBase, Exchange.parseDecimal(text, locale))
    fun boxText(i: Int) = if (i == state.active) text else Exchange.amountText(values[i], locale, spec.amountDecimals)
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        ExchangeCard(Modifier.testTag(ExchangeTags.SOURCE_CARD), active = state.active == 0) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(stringResource(R.string.ex_you_convert), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CurrencyChip(state.source, Modifier.testTag(ExchangeTags.SOURCE_CODE)) { pick = Pick.Source }
                }
                AmountField(
                    boxText(0), { type(0, it) }, { activate(0) }, state.active == 0,
                    MaterialTheme.typography.headlineMedium, Modifier.weight(1f).padding(start = CalcMetrics.gutter).testTag(ExchangeTags.AMOUNT),
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = CalcMetrics.gutter), color = MaterialTheme.colorScheme.outline)
        state.targets.forEachIndexed { i, code ->
            androidx.compose.runtime.key(code) {
                // The rate line reads from the input currency; the input card itself reads against the first box.
                val isIn = state.active == i + 1
                val from = if (isIn) code else state.boxes[state.active]
                val to = if (isIn) state.source else code
                TargetCard(
                    code = code,
                    amountText = boxText(i + 1),
                    onAmount = { type(i + 1, it) },
                    onFocus = { activate(i + 1) },
                    active = state.active == i + 1,
                    rateLine = Exchange.rateLine(from, to, Exchange.rate(table.perBase, from, to), locale, spec.rateSignificant),
                    onPick = { pick = Pick.Target(i) },
                    onRemove = { edit(Exchange.removeTarget(state, code)) },
                )
            }
            Spacer(Modifier.size(CalcMetrics.gap))
        }
        if (state.targets.size < spec.maxTargets) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { pick = Pick.Add }, modifier = Modifier.testTag(ExchangeTags.ADD)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(CalcMetrics.exchangeIcon))
                    Text(stringResource(R.string.ex_add), Modifier.padding(start = CalcMetrics.gap), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = CalcMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (!refreshing) scope.launch {
                        refreshing = true
                        val t = withContext(Dispatchers.IO) { rates.refresh(listOf(state.source) + state.targets, force = true) }
                        failed = t.offline; apply(t)
                        refreshing = false
                    }
                },
                modifier = Modifier.testTag(ExchangeTags.REFRESH),
            ) {
                if (refreshing) CircularProgressIndicator(Modifier.size(CalcMetrics.modeIcon), strokeWidth = CalcMetrics.stroke)
                else Icon(Icons.Filled.Refresh, stringResource(R.string.ex_refresh), Modifier.size(CalcMetrics.modeIcon), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val when_ = if (table.updatedMs > 0) stringResource(R.string.ex_last_updated, Exchange.stamp(table.updatedMs, zone, locale)) else stringResource(R.string.ex_last_updated_never)
            Text(
                if (failed) stringResource(R.string.ex_offline) + " · " + when_ else when_,
                Modifier.weight(1f).padding(start = CalcMetrics.small).testTag(ExchangeTags.UPDATED),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.ex_mid_market), Modifier.testTag(ExchangeTags.MID_MARKET), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { info = true }, modifier = Modifier.testTag(ExchangeTags.INFO)) {
                Icon(Icons.Outlined.Info, stringResource(R.string.ex_info), Modifier.size(CalcMetrics.modeIcon), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    pick?.let { p ->
        androidx.activity.compose.BackHandler { pick = null }
        val taken = if (p is Pick.Add) state.targets + state.source else emptyList()
        val current = when (p) { Pick.Source -> state.source; is Pick.Target -> state.targets.getOrNull(p.index).orEmpty(); Pick.Add -> "" }
        CurrencyPicker(
            rows = currencies.filter { it.name !in taken && it.name != current },
            onDismiss = { pick = null },
            onPick = { code ->
                when (p) {
                    Pick.Source -> edit(Exchange.setSource(state, code))
                    is Pick.Target -> edit(Exchange.setTarget(state, p.index, code))
                    Pick.Add -> edit(Exchange.addTarget(state, code, spec.maxTargets))
                }
                pick = null
            },
        )
    }
    }
    if (info) AlertDialog(
        onDismissRequest = { info = false },
        confirmButton = { TextButton(onClick = { info = false }) { Text(stringResource(R.string.ex_close)) } },
        title = { Text(stringResource(R.string.ex_info)) },
        text = { Text(stringResource(R.string.ex_info_body, spec.sourceName), Modifier.testTag(ExchangeTags.INFO_BODY)) },
    )
}

/** A rounded dark card. */
@Composable
private fun ExchangeCard(modifier: Modifier = Modifier, active: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(CalcMetrics.exchangeCorner), color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (active) BorderStroke(CalcMetrics.stroke, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(Modifier.padding(horizontal = CalcMetrics.gutter + CalcMetrics.gap, vertical = CalcMetrics.gutter)) { content() }
    }
}

/** A target card: swipe it away or long-press for Remove. */
@Composable
private fun TargetCard(
    code: String, amountText: String, onAmount: (String) -> Unit, onFocus: () -> Unit, active: Boolean, rateLine: String, onPick: () -> Unit, onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { v -> if (v != SwipeToDismissBoxValue.Settled) { onRemove(); true } else false })
    SwipeToDismissBox(swipe, backgroundContent = {}, modifier = Modifier.testTag(ExchangeTags.target(code))) {
        ExchangeCard(Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }), active = active) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CurrencyChip(code, Modifier.testTag(ExchangeTags.targetCode(code)), onLongClick = { menu = true }, onClick = onPick)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    AmountField(amountText, onAmount, onFocus, active, MaterialTheme.typography.titleLarge, Modifier.fillMaxWidth().testTag(ExchangeTags.targetAmount(code)))
                    Text(rateLine, Modifier.testTag(ExchangeTags.targetRate(code)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ex_remove)) }, onClick = { menu = false; onRemove() }, modifier = Modifier.testTag(ExchangeTags.remove(code)))
                }
            }
        }
    }
}

/** An editable amount: the input box is drawn in the accent colour with a caret, an output box in the plain colour; selected semantics = the input. */
@Composable
private fun AmountField(value: String, onChange: (String) -> Unit, onFocus: () -> Unit, active: Boolean, style: TextStyle, modifier: Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() }.semantics { selected = active },
        textStyle = style.copy(
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End, fontWeight = FontWeight.Medium,
        ),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** Round flag, code and a chevron: opens the picker. */
@Composable
private fun CurrencyChip(code: String, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = CalcMetrics.small), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(CalcMetrics.exchangeFlag).clip(CircleShape).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Text(Exchange.flag(code), style = MaterialTheme.typography.titleLarge)
        }
        Text(code, Modifier.padding(start = CalcMetrics.gap), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(CalcMetrics.exchangeIcon))
    }
}

/** The searchable currency list, drawn over the page (no window of its own). */
@Composable
private fun CurrencyPicker(rows: List<com.diegonmarcos.cloudcalc.Logic.Item>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val titles = remember(rows) { rows.associate { it.name to it.title } }
    val shown = Exchange.filter(rows.map { it.name }, titles, query)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(CalcMetrics.gutter)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ex_pick), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(ExchangeTags.PICKER_CLOSE)) { Text(stringResource(R.string.ex_close)) }
            }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(vertical = CalcMetrics.gap).testTag(ExchangeTags.PICKER_SEARCH),
                singleLine = true, placeholder = { Text(stringResource(R.string.ex_search)) },
            )
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it }) { code ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(code) }.padding(vertical = CalcMetrics.gap).testTag(ExchangeTags.pickerItem(code)), verticalAlignment = Alignment.CenterVertically) {
                        Text(Exchange.flag(code), style = MaterialTheme.typography.titleLarge)
                        Text(code, Modifier.padding(start = CalcMetrics.gap), fontWeight = FontWeight.Medium)
                        Text(titles[code].orEmpty(), Modifier.padding(start = CalcMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}
