package com.diegonmarcos.superapp.configs

import android.app.Application
import androidx.compose.ui.test.hasText
import com.diegonmarcos.superapp.adbdebug.AdbShellPage
import com.diegonmarcos.superapp.adbdebug.ChannelAction
import com.diegonmarcos.superapp.adbdebug.ChannelFacts
import com.diegonmarcos.superapp.adbdebug.ChannelMode
import com.diegonmarcos.superapp.adbdebug.ChannelState
import com.diegonmarcos.superapp.adbdebug.ConnectPlan
import com.diegonmarcos.superapp.adbdebug.LaunchVia
import com.diegonmarcos.superapp.adbdebug.SetupChecklist
import com.diegonmarcos.superapp.adbdebug.WdAuditLog
import com.diegonmarcos.superapp.adbdebug.WdKeeperStatus
import com.diegonmarcos.superapp.shellbroker.AuditLog
import com.diegonmarcos.superapp.shellclient.AdbLink
import com.diegonmarcos.superapp.shellclient.ShellStatus
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1550 field parity for SuperApp Configs > ADB Shell. The manifest (test/resources/parity/adb-shell-parity.json) lists every field
 * the richest earlier page showed. `ui` fields must be RENDERED by the page (all sections open, the console hosted); `model` fields
 * must be produced by the declared models from a fixture. One missing field fails, with the whole missing list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w600dp-h4000dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdbShellParityTest : KitPageHarness() {
    private val manifest = JSONObject(File("src/test/resources/parity/adb-shell-parity.json").readText())

    private val state = AdbPageState(
        status = ShellStatus(true, true, "local-server", "ok", 1_000L, adb = AdbLink.WIRELESS_OFF),
        wireless = false, paired = true, port = "37123", keeperOn = true,
        keeper = WdKeeperStatus.Snapshot(true, false, false, true, true, false, 500L, "keeper"),
        callers = listOf(AdbShellModel.CallerRow("a.store", "3 rules", 5L)),
        audit = listOf(AuditLog.Entry(5L, "a.store", "id -u", "ok", 0, 4, "binder")),
        keeperLog = listOf(WdAuditLog.Entry(6L, false, true, "user", "-")),
    )

    private val facts = ChannelFacts(ChannelMode.AUTO, 1_000L, devOptions = true, wirelessDebug = false, onWifi = true,
        notificationsAllowed = true, adbPaired = true, serverPort = 8023, serverRunning = true, serverUid = "uid 2000")

    /** The Status cockpit's chain in the default (Local Server) mode: its rows, actions and sub-lights are fields too. */
    private val cockpit = AdbCockpitModel.steps(AdbCockpitModel.Inputs(facts.copy(mode = ChannelMode.LOCAL_SERVER), AdbCockpitModel.PermsDigest(2, emptyList())))

    private fun model(src: String): List<String> = when (src) {
        "layers" -> ChannelState.rows(facts).map { it.label }
        "layer_ids" -> ChannelState.rows(facts).map { it.id }
        "setup" -> SetupChecklist.items(facts, LaunchVia.ADB).map { it.label }
        "keeper_chips" -> WdKeeperStatus.chips(state.keeper!!, "SuperApp") { "1m" }.map { it.label }
        "modes" -> ChannelMode.values().map { it.id }
        "launch_via" -> LaunchVia.values().map { it.title }
        "methods_auto" -> ConnectPlan.methods(ChannelMode.AUTO, LaunchVia.ADB, true).map { it.title }
        "methods_local" -> ConnectPlan.methods(ChannelMode.LOCAL_SERVER, LaunchVia.ADB, true).map { it.title }
        "plan_local" -> ConnectPlan.steps(ChannelMode.LOCAL_SERVER, LaunchVia.ADB, true).map { it.label }
        "plan_shizuku" -> ConnectPlan.steps(ChannelMode.SHIZUKU, LaunchVia.ADB, true).map { it.label }
        "cockpit_acts" -> cockpit.flatMap { s -> s.acts.map { "${s.id}:${it.what.name}" } }
        "cockpit_subs" -> cockpit.flatMap { s -> s.subs.map { "${s.id}:${it.label}" } }
        "actions" -> ChannelAction.values().map { it.label }
        "adb_links" -> AdbLink.values().map { it.label }
        else -> error("unknown model source $src")
    }

    @Test fun `every field of the richest ADB Shell page is present`() {
        showKit { AdbShellContent(state, AdbPageActions(), AdbShellModel.ORDER.map { it.id }.toSet(), console = { AdbShellPage() }) }
        compose.waitForIdle()
        val fields = manifest.getJSONArray("fields")
        val missing = ArrayList<String>()
        for (i in 0 until fields.length()) {
            val f = fields.getJSONObject(i)
            val ok = when (f.getString("kind")) {
                "ui" -> {
                    val any = f.getJSONArray("any")
                    (0 until any.length()).any { k ->
                        compose.onAllNodes(hasText(any.getString(k), substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    }
                }
                "model" -> f.getString("value") in model(f.getString("source"))
                else -> false
            }
            if (!ok) missing += "${f.getString("group")}: ${f.getString("field")}"
        }
        assertTrue("ADB Shell fields missing (${missing.size}/${fields.length()}): $missing", missing.isEmpty())
    }
}
