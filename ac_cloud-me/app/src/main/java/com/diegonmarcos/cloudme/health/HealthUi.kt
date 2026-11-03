package com.diegonmarcos.cloudme.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import com.diegonmarcos.cloudme.R
import com.diegonmarcos.superapp.health.HealthConnectGateway

/** Small me_*-palette building blocks of the Health pages. Pages are ONE vertical scroller (HealthPage); nothing here scrolls vertically. */
@Composable internal fun ink() = colorResource(R.color.me_text)
@Composable internal fun dim() = colorResource(R.color.me_text_dim)
@Composable internal fun accent() = colorResource(R.color.me_primary)

internal val Good = Color(0xFF34D399)
internal val Warn = Color(0xFFF59E0B)
internal val Bad = Color(0xFFF43F5E)

@Composable
internal fun HCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().padding(top = 12.dp).clip(shape).background(colorResource(R.color.me_surface))
            .border(1.dp, colorResource(R.color.me_outline), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
    ) { content() }
}

@Composable internal fun HTitle(text: String, sub: String = "") {
    Text(text, color = ink(), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    if (sub.isNotBlank()) Text(sub, color = dim(), fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
}

@Composable internal fun HHead(text: String) =
    Text(text, color = ink(), fontSize = 15.sp, fontWeight = FontWeight.Bold)

@Composable internal fun HText(text: String, color: Color = dim(), size: Int = 13, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = size.sp, lineHeight = (size + 6).sp, modifier = modifier.padding(top = 4.dp))

@Composable internal fun HRow(label: String, value: String, valueColor: Color = ink()) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, color = dim(), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable internal fun HEmpty(text: String) = HCard { HText(text) }

@Composable
internal fun Pill(text: String, selected: Boolean = false, color: Color? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val bg = if (selected) (color ?: accent()) else colorResource(R.color.me_surface_high)
    Text(
        text, color = if (selected) colorResource(R.color.me_on_primary) else ink(), fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(end = 8.dp, top = 8.dp).clip(shape).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A single-choice strip: one horizontal row (the only scroll it owns is horizontal, so it never nests the page's vertical one). */
@Composable
internal fun Choice(options: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        options.forEach { (id, label) -> Pill(label, id == selected) { onPick(id) } }
    }
}

@Composable
internal fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' }.take(7)) }, singleLine = true,
        label = { Text(label) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = fieldColors(), modifier = modifier.padding(top = 8.dp),
    )
}

@Composable
internal fun TextBox(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, colors = fieldColors(),
        modifier = modifier.fillMaxWidth().padding(top = 8.dp), minLines = 3,
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = ink(), unfocusedTextColor = ink(), cursorColor = accent(),
    focusedBorderColor = accent(), unfocusedBorderColor = colorResource(R.color.me_outline),
    focusedLabelColor = accent(), unfocusedLabelColor = dim(),
)

@Composable
internal fun Tick(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked, { onChange(it) }, colors = CheckboxDefaults.colors(checkedColor = accent(), uncheckedColor = dim(), checkmarkColor = colorResource(R.color.me_on_primary)))
        Text(label, color = ink(), fontSize = 14.sp)
    }
}

@Composable
internal fun Slide(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Slider(
        value = value, onValueChange = onChange, valueRange = range, steps = steps,
        colors = SliderDefaults.colors(thumbColor = accent(), activeTrackColor = accent(), inactiveTrackColor = colorResource(R.color.me_outline)),
    )
}

/**
 * A tracker section fed by Health Connect: says honestly when Health Connect is missing, when permission is not granted
 * (with the grant button) and when it is loading; [content] only ever sees real data.
 */
@Composable
internal fun <T> HcPanel(needed: Set<String>, load: suspend (HealthConnectClient) -> T, content: @Composable (T) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val state by produceState<HcState<T>>(HcState.Loading, tick) { value = HealthHc.guarded(ctx, needed, load) }
    val launcher = rememberLauncherForActivityResult(HealthConnectGateway.requestPermissionsContract()) { tick++ }
    when (val s = state) {
        HcState.Loading -> HEmpty("Reading Health Connect...")
        is HcState.Unavailable -> HEmpty(s.why)
        is HcState.NeedPermission -> HCard {
            HText("Health Connect has not been allowed to share this with Cloud Me yet.")
            Pill("Allow in Health Connect", true) { launcher.launch(s.missing) }
        }
        is HcState.Ready -> content(s.data)
    }
}

/**
 * A tracker page's data: Health Connect's when it holds any; otherwise [mock] (HealthMock, badged "mock data") with a banner on top
 * and the [sources] card at the bottom saying why (Health Connect missing, permission not granted, or nothing written yet) and how to
 * connect each source. [content] gets the data and whether it is mock. [key] re-reads (a range change).
 */
@Composable
internal fun <T> HealthData(
    needed: Set<String>, sources: List<HealthSources.Source>, key: Any?, load: suspend (HealthConnectClient) -> T,
    empty: (T) -> Boolean, mock: () -> T, content: @Composable (T, Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val state by produceState<HcState<T>>(HcState.Loading, tick, key) { value = HealthHc.guarded(ctx, needed, load) }
    val launcher = rememberLauncherForActivityResult(HealthConnectGateway.requestPermissionsContract()) { tick++ }
    val s = state
    if (s is HcState.Loading) { HText("Reading Health Connect..."); return }
    if (s is HcState.Ready && !empty(s.data)) { content(s.data, false); SourceCard(sources, null, null); return }
    val why = when (s) {
        is HcState.Unavailable -> s.why
        is HcState.NeedPermission -> "Cloud Me is not allowed to read this from Health Connect yet."
        else -> "Health Connect holds no data for this yet."
    }
    MockBanner(why)
    content(remember(key) { mock() }, true)
    SourceCard(sources, why, (s as? HcState.NeedPermission)?.let { p -> { launcher.launch(p.missing) } })
}
