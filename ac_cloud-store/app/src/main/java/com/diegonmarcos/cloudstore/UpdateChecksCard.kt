package com.diegonmarcos.cloudstore

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.diegonmarcos.superapp.appstore.StoreDensity

/** The first ComponentActivity under [this], or null (a preview or a bare Context). */
private tailrec fun Context.activity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Re-reads the three states when the user comes back from a system screen. */
@Composable
private fun rememberRefresh(): Int {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(ctx) {
        val lifecycle = ctx.activity()?.lifecycle
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle?.addObserver(obs)
        onDispose { lifecycle?.removeObserver(obs) }
    }
    return tick
}

/**
 * #1078 Configs > Notifications: what background update checks need, one row each, with the button that asks for it
 * the way Android does: the system permission dialog (notifications) or the system settings screen (install source,
 * battery optimization). A row that is already allowed has no button.
 */
@Composable
fun UpdateChecksCard() {
    val ctx = LocalContext.current
    val tick = rememberRefresh()
    Column(Modifier.fillMaxWidth().testTag(UpdateChecks.TAG_CARD), verticalArrangement = Arrangement.spacedBy(StoreDensity.dpValue(StoreDensity.S4).dp)) {
        Text("Reliable update checks", fontSize = StoreDensity.T_TITLE.sp, color = MaterialTheme.colorScheme.onSurface)
        Text("Cloud Store checks for updates in the background. Each of these is your choice, made in Configs > Perms.",
            fontSize = StoreDensity.T_CAPTION.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        UpdateChecks.Need.values().forEach { n ->
            val ok = remember(tick, n) { UpdateChecks.granted(ctx, n) }
            val (label, why) = when (n) {
                UpdateChecks.Need.NOTIFICATIONS -> "Notifications" to "To tell you an update is ready or needs a tap."
                UpdateChecks.Need.INSTALL_SOURCE -> "Install updates" to "Lets Cloud Store hand an update to the system installer."
                UpdateChecks.Need.BATTERY -> "Battery" to "Android may pause background checks to save power. Choose \"Don't optimize\" for Cloud Store."
            }
            Row(Modifier.fillMaxWidth().testTag(UpdateChecks.row(n)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(label + if (ok) "  ·  allowed" else "", fontSize = StoreDensity.T_BODY.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(why, fontSize = StoreDensity.T_CAPTION.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!ok) TextButton(
                    onClick = { UpdateChecks.open(ctx, n) },   // #1515 granted in Configs > Perms
                    modifier = Modifier.testTag(UpdateChecks.action(n)),
                ) { Text("Fix in Perms ›") }
            }
        }
    }
}

/**
 * #1078 The in-app prompt, once: while Cloud Store is still battery-optimized, asks whether to open the system's
 * battery-optimization settings. "Not now" is final for the prompt (the Configs row stays).
 */
@Composable
fun BatteryPrompt() {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(UpdateChecks.shouldPromptBattery(ctx)) }
    if (!show) return
    AlertDialog(
        modifier = Modifier.testTag(UpdateChecks.TAG_PROMPT),
        onDismissRequest = { UpdateChecks.markBatteryPrompted(ctx); show = false },
        title = { Text("Keep update checks running?") },
        text = { Text("Android can pause Cloud Store's background update checks to save battery. You can exempt it on the system's battery screen; nothing changes unless you choose it there.") },
        confirmButton = { TextButton({
            UpdateChecks.markBatteryPrompted(ctx); show = false
            UpdateChecks.open(ctx, UpdateChecks.Need.BATTERY)
        }) { Text("Open battery settings") } },
        dismissButton = { TextButton({ UpdateChecks.markBatteryPrompted(ctx); show = false }) { Text("Not now") } },
    )
}
