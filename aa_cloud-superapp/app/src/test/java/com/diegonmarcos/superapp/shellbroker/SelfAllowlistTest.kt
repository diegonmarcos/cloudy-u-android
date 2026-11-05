package com.diegonmarcos.superapp.shellbroker

import com.diegonmarcos.superapp.bgpolicy.BgCmd
import com.diegonmarcos.superapp.bgpolicy.BgPrior
import com.diegonmarcos.superapp.xray.XrayProbes
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
    private val self = ShellBroker.SELF

    private fun allowed(cmd: String) = assertTrue("refused: $cmd -> ${list.decide(self, cmd)}", list.decide(self, cmd) is Allowlist.Decision.Allow)

    @Test fun everyXrayProbeIsAllowed() {
        assertTrue(XrayProbes.all.isNotEmpty())
        XrayProbes.all.forEach { allowed(it.cmd) }
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

    @Test fun theSuperAppEntryStillRefusesWhatNoFeatureNeeds() {
        for (c in listOf("rm -rf /sdcard", "sh -c id", "cat /data/data/com.example/shared_prefs/x.xml", "pm uninstall com.example.app; rm -rf /x"))
            assertTrue(c, list.decide(self, c) is Allowlist.Decision.Deny)
    }
}
