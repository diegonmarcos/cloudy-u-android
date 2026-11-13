package com.diegonmarcos.superapp.shellbroker

import com.diegonmarcos.superapp.bgpolicy.BgCmd
import com.diegonmarcos.superapp.bgpolicy.BgPrior
import com.diegonmarcos.superapp.xray.XrayProbes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1510 The SuperApp's own features now run through the shell broker under the SuperApp's allowlist entry, where a refused command
 * is a silently empty answer. So every command those features can issue is proven allowed HERE, from the shipped data file:
 * the X-Ray probes (`exact` lines) and every Background policy / Super Saving command shape (`segments`).
 */
class SelfAllowlistTest {
    private val list = Allowlist.parse(File("../../ab_cloud-libs-shared/libs/shell-broker/src/main/assets/shell-allowlist.json").readText())
    private val self = "com.diegonmarcos.superapp"

    private fun allowed(cmd: String) = assertTrue("refused: $cmd -> ${list.decide(self, cmd)}", list.decide(self, cmd) is Allowlist.Decision.Allow)

    @Test fun everyXrayProbeIsAllowed() {
        assertTrue(XrayProbes.all.isNotEmpty())
        XrayProbes.all.forEach { allowed(it.cmd) }
        // the 13-tracker reorganisation: every NEW tracker probe is declared and has its exact rule
        for (id in listOf("df", "diskstats", "dropbox", "connectivity", "telephony", "procs", "pkgperms")) allowed(XrayProbes.all.single { it.id == id }.cmd)
        assertEquals("probe ids are unique", XrayProbes.all.size, XrayProbes.all.map { it.id }.toSet().size)
    }

    @Test fun everyBackgroundPolicyCommandShapeIsAllowed() {
        val p = "com.example.app"
        listOf(BgCmd.allow(p), BgCmd.restrict(p), BgCmd.forceStop(p), BgCmd.component(p, "com.example.app.Rcv\$Inner", true),
            BgCmd.component(p, "com.example.app.Svc", false), BgCmd.readPkgs(listOf(p, "com.example.b")), BgCmd.READ_GLOBAL,
            BgCmd.FORCE_IDLE, BgCmd.UNFORCE_IDLE, BgCmd.settingGet("global", "zen_mode"), BgCmd.settingPut("secure", "some_key-1", "3"),
            BgCmd.settingDelete("global", "some.key"),
            BgCmd.putBack(p, BgPrior("ignore", "default", 45, false)), BgCmd.putBack(p, BgPrior("allow", "allow", 10, true)),
        ).forEach { allowed(it) }
    }

    /** #1519 Grant via shell: the SuperApp grants ITSELF each runtime / adb-only permission of the Perms catalogue by an exact rule, and Location on. */
    @Test fun theSuperAppMayGrantItselfEveryCataloguePermissionAndSwitchLocationOn() {
        val cat = org.json.JSONObject(File("../../ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json").readText()).getJSONArray("entries")
        val perms = (0 until cat.length()).map { cat.getJSONObject(it) }
            .filter { it.getString("kind") in setOf("runtime", "adb_only") && it.optString("permission").startsWith("android.permission.") }
            .map { it.getString("permission") }
        assertTrue(perms.size > 20)
        for (perm in perms) {
            val d = list.decide(self, "pm grant $self $perm")
            assertTrue("refused: $perm", d is Allowlist.Decision.Allow)
            val id = (d as Allowlist.Decision.Allow).rule.id
            assertTrue("$perm is not covered by an exact self-grant rule (got $id)", id == "selfgrant-wss" || id == "selfgrant-runtime")
        }
        allowed("cmd location set-location-enabled true")
        assertTrue(list.decide(self, "pm grant $self android.permission.SOME_UNLISTED_PERMISSION_XYZ").let { d -> !(d is Allowlist.Decision.Allow && d.rule.id == "selfgrant-runtime") })
    }

    @Test fun theSuperAppEntryStillRefusesWhatNoFeatureNeeds() {
        for (c in listOf("rm -rf /sdcard", "sh -c id", "cat /data/data/com.example/shared_prefs/x.xml", "pm uninstall com.example.app; rm -rf /x"))
            assertTrue(c, list.decide(self, c) is Allowlist.Decision.Deny)
    }
}
