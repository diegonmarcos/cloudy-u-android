package com.diegonmarcos.cloudwebserver.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.diegonmarcos.cloudwebserver.ContextProbe
import com.diegonmarcos.cloudwebserver.Decl
import com.diegonmarcos.cloudwebserver.HealthFix
import com.diegonmarcos.cloudwebserver.HealthState
import com.diegonmarcos.cloudwebserver.KeepAliveHealth

/**
 * #1724 Home > Keep-alive health: each requirement of the keep-alive service with its real state (OK / MISSING / UNKNOWN) and a Fix
 * button. A fix opens Configs > Perms, the ONE place that asks and grants (system dialog, Settings page, Samsung battery page, ADB with
 * the state read back; perms guard P7). Re-read on every resume, so coming back shows the new state. Never throws.
 */
@Composable
fun KeepAliveHealthCard(tick: Int, open: (String, String?) -> Unit) {
    val ctx = LocalContext.current
    var local by remember { mutableStateOf(0) }
    val owner = ctx as? LifecycleOwner   // MainActivity: LocalContext is the activity itself
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) local++ }
        owner?.lifecycle?.addObserver(o)
        onDispose { owner?.lifecycle?.removeObserver(o) }
    }
    val rows = remember(tick, local) { runCatching { KeepAliveHealth.evaluate(ContextProbe(ctx)) }.getOrDefault(emptyList()) }
    DenseCard("Keep-alive health", "c3_home_keepalive", badges = {
        val miss = rows.any { it.state == HealthState.MISSING }
        Badge(KeepAliveHealth.summary(rows), if (miss) Tone.Warn else Tone.Up)
    }) {
        rows.forEach { r ->
            DRow(r.label, "${stateWord(r.state)} · ${r.detail}", up = r.state == HealthState.OK, tag = "c3_ka_${r.id}")
            if (r.fix != HealthFix.NONE && r.state != HealthState.OK) DActs { DAct("Fix in Perms", "c3_ka_fix_${r.id}") { open(Decl.SECTION_CONFIGS, Decl.PAGE_PERMS) } }
        }
        DNote("Fix opens Configs > Perms: Grant (system dialog or page) or over ADB, and the state is read back.")
    }
}

private fun stateWord(s: HealthState) = when (s) { HealthState.OK -> "OK"; HealthState.MISSING -> "MISSING"; HealthState.UNKNOWN -> "?"; HealthState.NA -> "n/a" }
