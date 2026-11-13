package com.diegonmarcos.cloudwebserver.owner

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.cloudwebserver.ui.Act
import com.diegonmarcos.cloudwebserver.ui.Acts
import com.diegonmarcos.cloudwebserver.ui.Card
import com.diegonmarcos.cloudwebserver.ui.Note
import com.diegonmarcos.cloudwebserver.ui.PageColumn
import com.diegonmarcos.superapp.adbdebug.AdbShellPage
import com.diegonmarcos.superapp.adbdebug.ChannelLog
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.system.AdbReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #1682 P2b the REAL ADB owner side, compiled only when adb-owner.json names this app (P3 cut-over) or by the non-shipped CI
 * verify (C3_ADB_OWNER_VERIFY=1). It hosts the one ADB Shell: the lib's page (pairing + its notification, embedded adb, local
 * server / embedded / Shizuku modes), the readiness source (libs:adb-shell-core AdbReadiness, pushed, no polling), Connect with
 * auto-fix (the lib page's own Connect), the Tester (`id -u` over the broker) and the channel log. The AdbShellServer stays
 * loopback + token inside the lib; nothing here is reachable from the Rust HTTP / SSHd / API / mesh routes.
 */
object AdbOwnerSide {
    const val active: Boolean = true
    const val why: String = ""

    /**
     * Process start: own the broker's change callback (AdbReadiness). Connect with auto-fix is the lib ADB Shell page's own control
     * (adb-shell-one-place: the page is the only place that connects), drawn below; the Status card's Connect re-checks. Never throws.
     */
    fun install(ctx: Context) {
        runCatching { com.diegonmarcos.superapp.system.AdbReadinessOwner.install() }
    }

    @Composable
    fun Page() {
        val ctx = LocalContext.current
        val ready by AdbReadiness.state.collectAsState()
        val scope = rememberCoroutineScope()
        var tester by remember { mutableStateOf("") }
        var logText by remember { mutableStateOf(ChannelLog.shared.asText()) }
        DisposableEffect(Unit) {
            val l: () -> Unit = { logText = ChannelLog.shared.asText() }
            ChannelLog.shared.addListener(l)
            onDispose { ChannelLog.shared.removeListener(l) }
        }
        PageColumn("c3_adb_owner") {
            Card("Status", "c3_adb_owner_status") {
                Note(ready.linkText())
                Acts {
                    Act("Connect", "c3_adb_owner_connect") { AdbReadiness.connect(ctx) {} }
                    Act("Re-check", "c3_adb_owner_recheck") { scope.launch { withContext(Dispatchers.IO) { AdbReadiness.check(ctx, fresh = true) } } }
                }
            }
            Card("Tester", "c3_adb_owner_tester") {
                Acts {
                    Act("Run id -u", "c3_adb_owner_test") {
                        scope.launch {
                            tester = withContext(Dispatchers.IO) {
                                runCatching {
                                    when (val r = ShellBroker.exec(ctx, ShellBroker.SELF, "id -u")) {
                                        is ShellResult.Ok -> "exit ${r.exitCode} via ${r.source}: ${r.stdout.trim()}"
                                        else -> r.toString()
                                    }
                                }.getOrElse { "tester failed (${it.javaClass.simpleName})" }
                            }
                            AdbReadiness.check(ctx)
                        }
                    }
                }
                if (tester.isNotEmpty()) Note(tester)
            }
            Card("ADB Shell", "c3_adb_owner_page") {
                Column(Modifier.fillMaxWidth()) { AdbShellPage() }
            }
            Card("Logs", "c3_adb_owner_logs") { Note(logText.ifBlank { "no channel log yet" }) }
        }
    }
}
