package com.diegonmarcos.hardtechcad.ui

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import org.json.JSONArray

/** #1695 one part of the shown model, as the viewer page reports it (window.cadParts). */
class HostPart(val name: String, val color: String, visible: Boolean) { var visible by mutableStateOf(visible) }

/** Parses the page's `JSON.stringify(cadParts())`; an empty or malformed answer is no parts (never a crash). */
internal fun parseParts(json: String?): List<HostPart> = runCatching {
    val a = JSONArray(json ?: return emptyList())
    (0 until a.length()).map { val o = a.getJSONObject(it); HostPart(o.optString("name"), o.optString("color", "#cfd4dc"), o.optBoolean("visible", true)) }
}.getOrDefault(emptyList())

private fun colorOf(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

/**
 * #1695 the Parts panel the HOST draws over the viewer. The viewer's touch surface is Compose (so a parent scroll never steals a gesture and every
 * presentation works), which means the page's own DOM button can never be tapped: this panel drives the page through cadPartVisible, with the
 * per-part checkboxes and the colour chips, and the page's own box is hidden (cadHostParts) so there is one panel, not two.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostParts(web: WebView?, shown: Boolean, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val parts = remember { mutableStateListOf<HostPart>() }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(shown, web) {
        parts.clear(); open = false
        val w = web ?: return@LaunchedEffect
        if (!shown) return@LaunchedEffect
        w.evaluateJavascript("window.cadHostParts&&window.cadHostParts(true)", null)
        repeat(5) {
            w.evaluateJavascript("JSON.stringify(window.cadParts?window.cadParts():[])") { v ->
                val list = parseParts(decodeJs(v))
                if (list.size > 1 && parts.isEmpty()) parts.addAll(list)
            }
            kotlinx.coroutines.delay(600)
        }
    }
    if (parts.size < 2) return
    fun set(i: Int, on: Boolean) { parts[i].visible = on; web?.evaluateJavascript("window.cadPartVisible&&window.cadPartVisible($i,$on)", null) }
    Column(modifier.testTag("host_parts"), horizontalAlignment = Alignment.End) {
        Text("Parts (" + parts.size + ")", color = p.textPrimary, fontSize = CadType.label, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(Color(0xFF1B212B)).border(1.dp, Color(0xFF39424F), RoundedCornerShape(6.dp))
                .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp).testTag("host_parts_toggle"))
        if (open) Column(Modifier.padding(top = 4.dp).fillMaxWidth(0.9f).heightIn(max = 300.dp).background(Color(0xF0141920), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF39424F), RoundedCornerShape(6.dp)).padding(6.dp).testTag("host_parts_body")) {
            val colours = parts.map { it.color }.distinct()
            if (colours.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                colours.forEach { c ->
                    val on = parts.any { it.color == c && it.visible }
                    Box(Modifier.size(26.dp).background(colorOf(c).copy(alpha = if (on) 1f else 0.35f), RoundedCornerShape(4.dp))
                        .border(2.dp, Color(0xFFD8DEE8), RoundedCornerShape(4.dp)).clickable { parts.forEachIndexed { i, q -> if (q.color == c) set(i, !on) } })
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                parts.forEachIndexed { i, q ->
                    Row(Modifier.fillMaxWidth().clickable { set(i, !q.visible) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = q.visible, onCheckedChange = { set(i, it) }, modifier = Modifier.testTag("part_check:$i"))
                        Box(Modifier.size(10.dp).background(colorOf(q.color), RoundedCornerShape(2.dp)))
                        Text(q.name, color = p.textPrimary, fontSize = CadType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}
