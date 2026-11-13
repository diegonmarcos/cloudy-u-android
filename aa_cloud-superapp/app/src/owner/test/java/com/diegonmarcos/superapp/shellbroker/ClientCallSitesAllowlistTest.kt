package com.diegonmarcos.superapp.shellbroker

import com.diegonmarcos.superapp.battery.BatteryPrivileged
import com.diegonmarcos.superapp.launcher.PermAccess
import com.diegonmarcos.superapp.network.AlwaysOnVpn
import com.diegonmarcos.superapp.system.PackageVerifier
import com.diegonmarcos.superapp.system.QuickDisplay
import com.diegonmarcos.superapp.xray.LocationUseView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1682 P3 step 3: the SuperApp's allowlist entry against the call sites that moved from the in-process broker to libs:shell-client.
 * Every literal command those sites run is allowed under the entry (a refusal would be a silently empty answer), and the entry
 * declares the output door at the 8 MB the in-process calls had, plus the audit grant the X-Ray shell counters read.
 */
class ClientCallSitesAllowlistTest {
    private val list = Allowlist.parse(File("../../ab_cloud-libs-shared/libs/shell-broker/src/main/assets/shell-allowlist.json").readText())
    private val self = "com.diegonmarcos.superapp"
    private fun allowed(cmd: String) = assertTrue("refused: $cmd -> ${list.decide(self, cmd)}", list.decide(self, cmd) is Allowlist.Decision.Allow)

    @Test fun batteryLibCommandsAreAllowed() { allowed(BatteryPrivileged.COMMAND); allowed("dumpsys battery") }
    @Test fun permAccessDashboardIsAllowed() = allowed(PermAccess.DASHBOARD_CMD)
    @Test fun locationAppopsIsAllowed() = allowed(LocationUseView.APPOPS_CMD)
    // #1682 P3 step 4e: Always-on VPN, Private DNS, Quick display and System display run through the client too
    @Test fun alwaysOnVpnCommandsAreAllowed() {
        for (on in listOf(true, false)) AlwaysOnVpn.commands(on, "com.diegonmarcos.cloudmesh").forEach { allowed(it) }
        allowed(AlwaysOnVpn.lockdownCommand(true)); allowed(AlwaysOnVpn.lockdownCommand(false))
        allowed("settings get secure always_on_vpn_app"); allowed("settings get secure always_on_vpn_lockdown")
    }
    @Test fun quickDisplayCommandsAreAllowed() { allowed(QuickDisplay.fontCommand(1.1f)); allowed(QuickDisplay.densityCommand(420)) }
    @Test fun privateDnsAndNightModeAreAllowed() {
        allowed("settings get global private_dns_mode"); allowed("settings put global private_dns_mode hostname")
        allowed("settings put global private_dns_specifier dns.example.com")
        allowed("cmd uimode night yes"); allowed("cmd uimode night no")
    }
    @Test fun playProtectCommandsAreAllowed() { for (scan in listOf(true, false)) PackageVerifier.commands(scan).forEach { allowed(it) } }
    @Test fun wirelessDebuggingSwitchCommandIsAllowed() { allowed("settings put global adb_wifi_enabled 1"); allowed("settings put global adb_wifi_enabled 0") }
    @Test fun outputDoorKeepsTheInProcessBudget() = assertEquals(8 * 1024 * 1024, list.outCap(self))
    @Test fun auditGrantIsTheOwnerHostsOnly() {
        assertTrue(list.mayAudit(self))
        assertEquals(listOf(self, "owner"), list.callers().filter { list.mayAudit(it) })   // owner = the logical identity that extends the SuperApp entry
    }

    // #1682 the privileged plane sent through the client (ClientPlaneWorker -> PrivilegedPlaneRun): every literal it runs is on the entry
    @Test fun planeDeviceTuningCommandsAreAllowed() {
        // DeviceTuning.entries() decodes a BuildConfig field with android.util.Base64 (a stub in a plain JVM test): read the declared file itself
        val entries = org.json.JSONObject(File("../data/device-tuning.json").readText()).getJSONArray("entries")
        assertTrue("device tuning declares entries", entries.length() > 0)
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            allowed(e.getJSONObject("verify").getString("cmd") + " 2>&1")
            val apply = e.optJSONArray("apply"); for (k in 0 until (apply?.length() ?: 0)) allowed(apply!!.getString(k) + " 2>&1")
        }
    }
    @Test fun planeWhitelistAndSelfGrantsAreAllowed() {
        allowed("dumpsys deviceidle whitelist +com.diegonmarcos.cloudmail 2>&1")
        allowed("pm grant com.diegonmarcos.superapp android.permission.DUMP")
        allowed("pm grant com.diegonmarcos.superapp android.permission.WRITE_SECURE_SETTINGS")
    }
}
