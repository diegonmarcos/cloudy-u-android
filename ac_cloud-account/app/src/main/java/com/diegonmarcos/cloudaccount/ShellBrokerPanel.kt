package com.diegonmarcos.cloudaccount

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellStatus

/** #1512 The lines the shell panel shows for the broker's state (pure, unit-tested). */
object ShellBrokerLines {
    fun of(s: ShellStatus?): List<String> = when {
        s == null -> listOf("Checking the SuperApp's shell broker...")
        !s.brokerInstalled -> listOf("Broker: not installed.", "Install Cloud SuperApp: it owns the one privileged shell.")
        s.armed -> listOf("Broker: armed (${s.channel}).")
        else -> listOf("Broker: reachable, not armed.", s.detail.ifBlank { "Arm it in the SuperApp." })
    }
}

/**
 * #1512 The privileged shell of this app is the SuperApp's shell broker (libs:shell-client), not a channel of its own: this panel
 * reports its state and opens the SuperApp's arm flow. Nothing to pair, reconnect or keep alive here. Used by Connect > ADB Shell
 * and by Configs > Runbook's channel bar.
 */
@Composable
fun ShellBrokerPanel(onChange: () -> Unit = {}) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf<ShellStatus?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ShellClient.status(ctx) }
        onChange()
    }
    Column(Modifier.padding(16.dp).testTag("shell-broker-panel")) {
        ShellBrokerLines.of(status).forEach { Text(it, color = MaterialTheme.colorScheme.onSurface) }
        Row {
            Button(onClick = { ShellClient.arm(ctx); tick++ }, content = { Text("Arm in SuperApp") })
            Button(onClick = { tick++ }, modifier = Modifier.padding(start = 8.dp), content = { Text("Re-check") })
        }
        Text("Installs of Cloud Store and the setup runbook's grants run through the SuperApp's shell broker, under this app's own allowlist entry.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
