package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** #1401 the three states as the Perms page names them; stored as ask/allow/deny. */
val PERM_STATE_LABELS = listOf(BrowserSitePolicy.ASK to "Ask", BrowserSitePolicy.ALLOW to "Allow", BrowserSitePolicy.DENY to "Block")

/** #1401 one site with rules of its own, as the Sites Permissions page lists it. */
data class PermSite(val host: String, val summary: String)

/**
 * #1401 THE ONE PERMS COMPONENT. With a [host] it is the page's Perms (Browsing Tools > Site Perms): each permission's
 * state for that site, the global default beside it, Reset when the site has a rule of its own. Without a host it is
 * Configs > Sites Permissions: one default per permission, then [sites] (the sites with overrides, Edit / Reset each).
 * The list is [BrowserSitePolicy.ordered]: the most-used first, then all the rest. A permission WebView cannot enforce
 * is listed, its states are not offered, and its reason is shown.
 */
@Composable
fun BrowserPermsScreen(
    title: String,
    host: String?,
    perms: List<BrowserSitePerm>,
    stateOf: (BrowserSitePerm) -> String,
    defaultOf: (BrowserSitePerm) -> String,
    hasOwn: (BrowserSitePerm) -> Boolean,
    onSet: (BrowserSitePerm, String) -> Unit,
    onReset: (BrowserSitePerm) -> Unit,
    onClose: () -> Unit,
    sites: List<PermSite> = emptyList(),
    onEditSite: (String) -> Unit = {},
    onResetSite: (String) -> Unit = {},
) {
    val p = LocalKitPalette.current
    Column(
        Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(16.dp)
            .testTag(if (host == null) "browser:perms:defaults" else "browser:perms:site"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("← Back") }
            Column(Modifier.weight(1f)) {
                Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
                Text(host ?: "Default for every site", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        BrowserPermsList(host, perms, stateOf, defaultOf, hasOwn, onSet, onReset, sites, onEditSite, onResetSite)
    }
}

/**
 * #1402 the Perms list itself, no header and no scroll of its own: the Perms page and Configs > Permissions (inline, inside the
 * page's own scroll) both draw it.
 */
@Composable
fun ColumnScope.BrowserPermsList(
    host: String?,
    perms: List<BrowserSitePerm>,
    stateOf: (BrowserSitePerm) -> String,
    defaultOf: (BrowserSitePerm) -> String,
    hasOwn: (BrowserSitePerm) -> Boolean,
    onSet: (BrowserSitePerm, String) -> Unit,
    onReset: (BrowserSitePerm) -> Unit,
    sites: List<PermSite> = emptyList(),
    onEditSite: (String) -> Unit = {},
    onResetSite: (String) -> Unit = {},
) {
    val p = LocalKitPalette.current
    val tick = remember { mutableIntStateOf(0) }
    val ordered = BrowserSitePolicy.ordered(perms)
    run {
        tick.intValue.let { _ ->
            var top: Boolean? = null
            ordered.forEach { perm ->
                if (perm.top != top) {
                    top = perm.top
                    HorizontalDivider(color = p.hairline)
                    Text(if (perm.top) "Most used" else "All the rest", color = p.textSecondary, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp).testTag("browser:perms:group:${if (perm.top) "top" else "rest"}"))
                }
                PermRow(perm, host, stateOf, defaultOf, hasOwn, onSet = { v -> onSet(perm, v); tick.intValue++ }, onReset = { onReset(perm); tick.intValue++ })
            }
        }
        if (host == null) {
            HorizontalDivider(color = p.hairline)
            Text("Sites with overrides", color = p.textSecondary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp).testTag("browser:perms:sites"))
            if (sites.isEmpty()) Text("No site has a permission of its own yet.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            sites.forEach { s ->
                Row(Modifier.fillMaxWidth().testTag("browser:perms:site:${s.host}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(s.host, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(s.summary, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    TextButton({ onEditSite(s.host) }, Modifier.testTag("browser:perms:site:${s.host}:edit")) { Text("Edit") }
                    TextButton({ onResetSite(s.host) }, Modifier.testTag("browser:perms:site:${s.host}:reset")) { Text("Reset") }
                }
            }
        }
    }
}

@Composable
private fun PermRow(
    perm: BrowserSitePerm,
    host: String?,
    stateOf: (BrowserSitePerm) -> String,
    defaultOf: (BrowserSitePerm) -> String,
    hasOwn: (BrowserSitePerm) -> Boolean,
    onSet: (String) -> Unit,
    onReset: () -> Unit,
) {
    val p = LocalKitPalette.current
    val label = { v: String -> PERM_STATE_LABELS.firstOrNull { it.first == v }?.second ?: v }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("browser:perm:${perm.id}")) {
        Text(perm.label, color = if (perm.webview) p.textPrimary else p.textSecondary, style = MaterialTheme.typography.bodyLarge)
        if (!perm.webview) {
            Text(perm.why.ifBlank { "Not supported in WebView." }, color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("browser:perm:${perm.id}:unsupported"))
            return@Column
        }
        val cur = stateOf(perm)
        Text(
            if (host == null) "Default for every site" else "Default: ${label(defaultOf(perm))}" + if (hasOwn(perm)) " · this site: ${label(cur)}" else " · this site follows it",
            color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PERM_STATE_LABELS.forEach { (v, l) ->
                val on = v == cur
                Text(l, color = if (on) p.accent else p.textPrimary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.border(if (on) 2.dp else 1.dp, if (on) p.accent else p.hairline, RoundedCornerShape(8.dp))
                        .clickable { onSet(v) }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("browser:perm:${perm.id}:$v"))
            }
            if (host != null && hasOwn(perm)) TextButton(onReset, Modifier.testTag("browser:perm:${perm.id}:reset")) { Text("Reset") }
        }
    }
}
