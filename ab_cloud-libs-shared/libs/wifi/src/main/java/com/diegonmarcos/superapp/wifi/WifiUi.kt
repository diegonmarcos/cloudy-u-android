package com.diegonmarcos.superapp.wifi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.diegonmarcos.superapp.perms.PermsLink
import com.diegonmarcos.superapp.perms.PermsNeeds
import com.diegonmarcos.superapp.perms.rememberPermsAsk
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uiicons.UiIcons
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The five tabs of the WiFi page (#1263). [id] is the page id suffix a host declares (`wifi-scan`, ...). */
enum class WifiTab(val id: String, val label: String) {
    Scan("scan", "Scan"), Channels("channels", "Channels"), Link("link", "Link"), Tools("tools", "Tools"), Log("log", "Log");

    companion object { fun of(id: String?): WifiTab = entries.firstOrNull { it.id == id } ?: Scan }
}

internal val WifiOk = Color(0xFF7FC98F)
internal val WifiWarn = Color(0xFFE2B85C)
internal val WifiBad = Color(0xFFE5737A)
internal val WifiBlue = Color(0xFF7FB2E5)

/** Layout facts of the host window: wide (landscape / tablet, >= 600 dp) puts lists in two columns and draws taller charts. */
internal data class WifiLayout(val wide: Boolean) { val chart: Dp get() = if (wide) 260.dp else 190.dp }

/**
 * The entry composable (#1263): one tab of the analyzer over the shared [controller]. A host draws it in a scrolling container of
 * its own chrome (the body is a LazyColumn, so a scroll sink sees it scroll); [onOpenTab] lets a tab send the user on (a Locate /
 * Range button goes to Tools). [bottomClearance] is the space the host's bottom bar needs below the last item.
 */
@Composable
fun WifiPanel(
    controller: WifiController,
    tab: WifiTab,
    modifier: Modifier = Modifier,
    bottomClearance: Dp = 96.dp,
    onOpenTab: (WifiTab) -> Unit = {},
) {
    val s by controller.state.collectAsState()
    DisposableEffect(controller) { controller.attach(); onDispose { controller.detach() } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val layout = WifiLayout(maxWidth >= 600.dp)
        LazyColumn(Modifier.fillMaxSize().testTag("wifi:${tab.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("gate") { WifiGate(controller, s) }
            when (tab) {
                WifiTab.Scan -> scanTab(controller, s, now, layout, onOpenTab)
                WifiTab.Channels -> channelsTab(controller, s, now, layout)
                WifiTab.Link -> linkTab(controller, s, now, layout)
                WifiTab.Tools -> toolsTab(controller, s, now, layout)
                WifiTab.Log -> logTab(controller, s, now, layout)
            }
            item("end") { Spacer(Modifier.height(bottomClearance)) }
        }
    }
}

// ---- shared bits ----

@Composable internal fun t(id: Int, vararg a: Any): String = stringResource(id, *a)

@Composable
internal fun WCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable internal fun WTitle(text: String, icon: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { WIcon(icon, MaterialTheme.colorScheme.primary, 16); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable internal fun WMuted(text: String, modifier: Modifier = Modifier, mono: Boolean = false, max: Int = Int.MAX_VALUE) {
    Text(text, modifier, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = if (mono) FontFamily.Monospace else null, maxLines = max, overflow = TextOverflow.Ellipsis)
}

@Composable internal fun WPill(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val m = modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f))
    Text(text, (if (onClick != null) m.clickable { onClick() } else m).padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 10.sp, color = color, maxLines = 1)
}

@Composable internal fun WChoice(label: String, selected: Boolean, tag: String = label, onClick: () -> Unit) {
    val c = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(label, Modifier.testTag("wifi:chip:$tag").clip(RoundedCornerShape(50)).background(c.copy(alpha = if (selected) 0.22f else 0.08f)).clickable { onClick() }
        .padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 11.sp, color = c)
}

@Composable internal fun WButton(label: String, enabled: Boolean = true, primary: Boolean = false, tag: String = label, onClick: () -> Unit) {
    if (primary) Button(onClick, Modifier.testTag("wifi:$tag"), enabled = enabled) { Text(label, fontSize = 12.sp) }
    else OutlinedButton(onClick, Modifier.testTag("wifi:$tag"), enabled = enabled, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Text(label, fontSize = 12.sp) }
}

@Composable internal fun WIcon(name: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, size: Int = 18) {
    Icon(UiIcons.vector(name), null, Modifier.size(size.dp), tint = tint)
}

@Composable internal fun WRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable internal fun WSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Switch(on, onChange); Spacer(Modifier.width(6.dp)); WMuted(label) }
}

@Composable internal fun WKv(k: String, v: String, color: Color? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        WMuted(k, Modifier.weight(0.42f))
        Text(v.ifEmpty { "-" }, Modifier.weight(0.58f), fontSize = 11.sp, color = color ?: MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
    }
}

@Composable internal fun WField(value: String, label: String, tag: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, modifier.testTag("wifi:field:$tag"), singleLine = true, label = { Text(label, fontSize = 11.sp) })
}

internal fun signalColor(th: RssiThresholds, rssi: Int): Color = when (th.grade(rssi)) { 4, 3 -> WifiOk; 2 -> WifiWarn; else -> WifiBad }

internal fun securityColor(sec: Security): Color = when { sec.level == 0 || sec.weak -> WifiBad; sec.level >= 4 -> WifiOk; sec.level == 2 -> WifiBlue; else -> WifiWarn }

internal fun ago(ms: Long): String = when { ms < 1500 -> "1s"; ms < 60_000 -> "${ms / 1000}s"; ms < 3_600_000 -> "${ms / 60_000}m"; else -> "${ms / 3_600_000}h" }

internal fun clock(ms: Long): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(ms))

internal fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("wifi", text))
}

internal fun openSettings(ctx: Context, action: String, data: Uri? = null) {
    runCatching { ctx.startActivity(Intent(action).apply { if (data != null) setData(data); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
}

/** Runtime permissions, location services and the Wi-Fi switch: what every tab starts with while something is missing. */
@Composable
internal fun WifiGate(c: WifiController, s: WifiState) {
    val ctx = LocalContext.current
    when {
        !s.supported -> WCard { WTitle(t(R.string.wifi_unsupported), "router") }
        // #1274 the lib never asks: a compact banner deep-links to the app's Permissions page, highlighting its entries.
        s.missing.isNotEmpty() -> {
            val need = remember(s.missing) { PermsNeeds.missing(ctx, "wifi") }
            WCard(Modifier.testTag("wifi:perms-banner")) {
                WTitle(t(R.string.wifi_perm_title), "key")
                WMuted(t(R.string.wifi_perm_body))
                WMuted(if (need.isNotEmpty()) PermsNeeds.labels(need) else WifiPermissions.describe(s.missing), mono = true)
                // #1406 Grant asks right here through libs:perms; Open Permissions goes to the host's Permissions page.
                val ask = rememberPermsAsk { c.refreshEnv() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (need.isNotEmpty()) WButton(t(R.string.wifi_grant), primary = true, tag = "grant-perms") { ask(need) }
                    WButton(t(R.string.wifi_open_perms), primary = need.isEmpty(), tag = "open-perms") {
                        PermsLink.open(ctx, "Wi-Fi", need.map { it.id }.ifEmpty { listOf("fine_location", "coarse_location", "nearby_wifi") })
                    }
                }
            }
        }
        !s.locationOn -> WCard {
            WTitle(t(R.string.wifi_location_off_title), "map")
            WMuted(t(R.string.wifi_location_off_body))
            WButton(t(R.string.wifi_open_settings), primary = true, tag = "location") { openSettings(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
        }
        !s.enabled -> WCard {
            WTitle(t(R.string.wifi_off_title), "router")
            WButton(t(R.string.wifi_turn_on), primary = true, tag = "wifi-on") {
                openSettings(ctx, if (android.os.Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
            }
        }
    }
    if (s.error.isNotEmpty()) WPill(s.error, WifiWarn)
}

// ---- Scan ----

@Composable
internal fun ThrottleCard(c: WifiController, s: WifiState, now: Long) {
    val ctx = LocalContext.current
    WCard(Modifier.testTag("wifi:throttle")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WButton(t(R.string.wifi_scan), primary = true, enabled = s.missing.isEmpty(), tag = "scan") { c.scanNow() }
            WSwitch(t(R.string.wifi_auto), s.auto) { c.setAuto(it) }
        }
        WMuted(t(R.string.wifi_last_scan, if (s.lastScanAt == 0L) "-" else ago(now - s.lastScanAt), s.aps.size))
        WTitle(t(R.string.wifi_throttle_title))
        when (s.throttleSetting) {
            false -> WPill(t(R.string.wifi_throttle_off), WifiOk)
            else -> {
                val left = s.throttleRemaining.coerceAtMost(c.throttle.limit)
                WPill(t(R.string.wifi_throttle_state, left, c.throttle.limit, if (s.nextScanAt <= now) t(R.string.wifi_now) else clock(s.nextScanAt)), if (left > 0) WifiOk else WifiWarn)
                if (s.throttleSetting == null) WMuted(t(R.string.wifi_throttle_unknown))
            }
        }
        WMuted(t(R.string.wifi_throttle_body))
        WButton(t(R.string.wifi_dev_options), tag = "dev-options") { openSettings(ctx, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
        val byBand = s.aps.groupBy { it.band }
        WRow { listOf(Band.B24, Band.B5, Band.B6, Band.B60).forEach { b -> byBand[b]?.let { WPill("${b.label}: ${it.size}", WifiBlue) } } }
        if (s.ouiSize > 0) WMuted(t(R.string.wifi_oui, s.ouiSize))
    }
}

@Composable
internal fun FilterCard(c: WifiController, s: WifiState) {
    val q = s.query
    WCard(Modifier.testTag("wifi:filters")) {
        WField(q.text, t(R.string.wifi_search), "search", { c.setQuery(q.copy(text = it)) }, Modifier.fillMaxWidth())
        WRow {
            listOf(Band.B24, Band.B5, Band.B6).forEach { b ->
                WChoice(b.label, b in q.bands, "band-${b.id}") { c.setQuery(q.copy(bands = if (b in q.bands) q.bands - b else q.bands + b)) }
            }
        }
        WRow {
            WMuted(t(R.string.wifi_sort))
            ApSort.entries.forEach { o -> WChoice(o.label, q.sort == o, "sort-${o.name}") { c.setQuery(q.copy(sort = o)) } }
        }
        WRow {
            WChoice(t(R.string.wifi_group), q.groupBySsid, "group") { c.setQuery(q.copy(groupBySsid = !q.groupBySsid)) }
            WChoice(t(R.string.wifi_hide_hidden), q.hideHidden, "hide-hidden") { c.setQuery(q.copy(hideHidden = !q.hideHidden)) }
            WChoice(t(R.string.wifi_stale), q.hideStaleMs > 0, "stale") { c.setQuery(q.copy(hideStaleMs = if (q.hideStaleMs > 0) 0 else c.cfg.staleMs)) }
            WChoice(t(R.string.wifi_open_only), q.openOnly, "open") { c.setQuery(q.copy(openOnly = !q.openOnly)) }
            WChoice(t(R.string.wifi_rtt_only), q.rttOnly, "rtt") { c.setQuery(q.copy(rttOnly = !q.rttOnly)) }
        }
        WMuted(t(R.string.wifi_min_signal, q.minRssi))
        Slider(q.minRssi.toFloat(), { c.setQuery(q.copy(minRssi = it.roundToInt())) }, valueRange = -100f..-30f)
    }
}

private fun LazyListScope.scanTab(c: WifiController, s: WifiState, now: Long, layout: WifiLayout, open: (WifiTab) -> Unit) {
    item("throttle") { ThrottleCard(c, s, now) }
    item("filters") { FilterCard(c, s) }
    val shown = ApFilter.apply(s.aps, s.query, now)
    item("count") { WTitle(t(R.string.wifi_networks, shown.size), "router") }
    if (shown.isEmpty()) item("empty") { WMuted(t(R.string.wifi_no_aps)) }
    val rowsOf: (List<AccessPoint>) -> List<List<AccessPoint>> = { if (layout.wide) it.chunked(2) else it.map { a -> listOf(a) } }
    if (s.query.groupBySsid) {
        ApFilter.groups(shown).forEach { (ssid, members) ->
            item("g:$ssid:${members.first().bssid}") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WTitle(ssid); WPill("${members.size}", WifiBlue); WPill("${members.maxOf { it.smoothed }.roundToInt()} dBm", signalColor(c.cfg.rssi, members.maxOf { it.rssi }))
                }
            }
            items(rowsOf(members), key = { "r:" + it.joinToString { a -> a.bssid } }) { row -> ApRowPair(c, s, row, now, open) }
        }
    } else items(rowsOf(shown), key = { "r:" + it.joinToString { a -> a.bssid } }) { row -> ApRowPair(c, s, row, now, open) }
}

@Composable
private fun ApRowPair(c: WifiController, s: WifiState, row: List<AccessPoint>, now: Long, open: (WifiTab) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { ap -> Box(Modifier.weight(1f)) { ApRow(c, s, ap, now, open) } }
    }
}

@Composable
internal fun ApRow(c: WifiController, s: WifiState, ap: AccessPoint, now: Long, open: (WifiTab) -> Unit) {
    val ctx = LocalContext.current
    val expanded = s.selected == ap.bssid
    val col = apColor(ap.bssid)
    val th = c.cfg.rssi
    val connected = s.link.bssid.equals(ap.bssid, true)
    WCard(Modifier.testTag("wifi:ap:${ap.bssid}").clickable { c.select(if (expanded) null else ap.bssid) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(col))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(ap.displaySsid + if (connected) "  •" else "", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (ap.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                WMuted("${ap.bssid}  ${ap.vendor}", mono = true, max = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${ap.smoothed.roundToInt()} dBm", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = signalColor(th, ap.rssi))
                Sparkline(ap.history.map { it.rssi }, col)
            }
        }
        WRow {
            WPill("${ap.band.label} ch ${ap.channel}", WifiBlue)
            WPill("${ap.widthMhz} MHz", WifiBlue)
            if (ap.std != WifiStd.Unknown) WPill("${ap.std.gen} ${ap.std.label}".trim(), WifiBlue)
            WPill(ap.security.label, securityColor(ap.security))
            if (ap.security.pmf != Pmf.None) WPill(ap.security.pmf.label, WifiOk)
            if (ap.security.wps) WPill("WPS", WifiWarn)
            if (ap.security.ft) WPill("802.11r", WifiBlue)
            if (ap.rtt80211mc) WPill("RTT mc", WifiOk)
            if (ap.rtt80211az) WPill("RTT az", WifiOk)
            if (ap.mldMac != null || ap.mloLinks.isNotEmpty()) WPill("MLO ${ap.mloLinks.size}", WifiOk)
            if (ap.passpoint) WPill("Passpoint", WifiBlue)
            if (ap.randomizedBssid) WPill("LAA", WifiBlue)
            WPill(t(R.string.wifi_age, ago(ap.ageMs(now))), if (ap.ageMs(now) > c.cfg.staleMs) WifiWarn else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            WKv("Freq / centre", "${ap.freqMhz} / ${ap.centerMhz} MHz" + if (ap.centerFreq1 > 0) " (+${ap.centerFreq1})" else "")
            WKv(t(R.string.wifi_l_quality), "${th.quality(ap.rssi)}% · SNR ~${th.snr(ap.rssi)} dB")
            WKv(t(R.string.wifi_l_security), (ap.security.kinds.ifEmpty { listOf("Open") } + ap.security.ciphers).joinToString(" "))
            WKv("Interference", "${ChannelRating.interference(ap, s.aps)}/100")
            ap.mldMac?.let { WKv(t(R.string.wifi_l_mld), "$it (link ${ap.mloLinkId})") }
            ap.mloLinks.forEach { l -> WKv("  link ${l.linkId}", "${l.bssid} ${l.band.label} ch ${l.channel}") }
            WMuted(t(R.string.wifi_first_seen, clock(ap.firstSeenMs)))
            WMuted(ap.capabilities, mono = true)
            WRow {
                WButton(t(R.string.wifi_locate), tag = "locate") { c.setLocator(true, ap.bssid); open(WifiTab.Tools) }
                if (ap.rtt80211mc) WButton(t(R.string.wifi_range), tag = "range") { c.startRtt(listOf(ap.bssid), true); open(WifiTab.Tools) }
                WButton(t(R.string.wifi_heat_target), tag = "heat") { c.heatTarget(ap.bssid); open(WifiTab.Log) }
                WButton(t(R.string.wifi_copy), tag = "copy") { copy(ctx, "${ap.ssid} ${ap.bssid} ch${ap.channel} ${ap.rssi}dBm ${ap.security.label}") }
            }
        }
    }
}
