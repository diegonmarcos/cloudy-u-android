package com.diegonmarcos.superapp.shellbroker

import com.diegonmarcos.superapp.shellbroker.Allowlist.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AllowlistTest {
    private val json = """{"aliases":{"web.pkg":"mesh"},"callers":{
      "app.a":{"allow":[{"id":"r1","re":"^dumpsys battery$"},{"id":"r2","re":"^appops set [\\w.]+ [A-Z_]+ (allow|deny)$"},{"id":"p","re":"^ps -A( \\| grep \\w+)?$","meta":"|"}]},
      "mesh":{"allow":[{"id":"m","re":"^getprop( [\\w.]+)?$"}]},
      "app.host":{"segments":true,"exact":["dumpsys alarm | head -5"],"allow":[{"id":"s1","re":"^cmd appops set [\\w.]+ [A-Z_]+ (allow|ignore)$"},{"id":"s2","re":"^am set-standby-bucket [\\w.]+ \\w+$"}]}}}"""
    private val list = Allowlist.parse(json)

    @Test fun matchingRuleAllows() {
        val d = list.decide("app.a", "dumpsys battery")
        assertTrue(d is Decision.Allow && d.rule.id == "r1")
        assertTrue(list.decide("app.a", "  appops set com.x.y RUN_IN_BACKGROUND deny ") is Decision.Allow)
    }

    @Test fun defaultDenyForAnUnknownCaller() {
        val d = list.decide("app.unknown", "dumpsys battery")
        assertTrue(d is Decision.Deny && d.reason.contains("no allowlist entry"))
    }

    @Test fun defaultDenyForAnUnmatchedCommand() {
        assertTrue(list.decide("app.a", "rm -rf /sdcard") is Decision.Deny)
        assertTrue(list.decide("app.a", "dumpsys battery set level 1") is Decision.Deny)   // full match, not a prefix
    }

    @Test fun anAllowedVerbCannotCarryAChain() {
        for (c in listOf("dumpsys battery; id", "dumpsys battery && id", "dumpsys battery | cat", "dumpsys battery `id`",
                         "dumpsys battery \$(id)", "dumpsys battery > /x", "dumpsys battery #")) assertTrue(c, list.decide("app.a", c) is Decision.Deny)
        assertTrue(list.decide("app.a", "dumpsys battery\nid") is Decision.Deny)
    }

    @Test fun aRuleMayDeclareItsOwnMeta() {
        assertTrue(list.decide("app.a", "ps -A | grep abc") is Decision.Allow)
        assertTrue(list.decide("app.a", "ps -A | grep abc; id") is Decision.Deny)
    }

    @Test fun emptyAndOversizedAreDenied() {
        assertTrue(list.decide("app.a", "   ") is Decision.Deny)
        assertTrue(list.decide("app.a", "getprop " + "a".repeat(Allowlist.MAX_CMD)) is Decision.Deny)
    }

    @Test fun theMeshIsADedicatedEntryReachedByAlias() {
        assertEquals("mesh", list.callerKey("web.pkg"))
        assertTrue(list.decide("web.pkg", "getprop ro.build.id") is Decision.Allow)
        assertTrue(list.decide("web.pkg", "dumpsys battery") is Decision.Deny)   // app.a's rule is not the mesh's
    }

    @Test fun theShippedAllowlistParsesAndDeniesByDefault() {
        val real = Allowlist.parse(File("src/main/assets/shell-allowlist.json").readText())
        assertTrue("com.diegonmarcos.superapp" in real.callers())
        // #1513 the SuperApp's self-grant of WRITE_SECURE_SETTINGS has its own exact rule
        val self = real.decide("com.diegonmarcos.superapp", "pm grant com.diegonmarcos.superapp android.permission.WRITE_SECURE_SETTINGS")
        assertTrue(self is Decision.Allow && self.rule.id == "selfgrant-wss")
        assertTrue(real.decide("com.diegonmarcos.clouddrive", "dumpsys meminfo com.x.y") is Decision.Allow)
        assertTrue(real.decide("com.diegonmarcos.clouddrive", "pm install -r /x.apk") is Decision.Deny)
        assertTrue(real.decide("com.diegonmarcos.cloudwebserver", "rm -rf /") is Decision.Deny)
        assertTrue(real.decide("com.diegonmarcos.cloudwebserver", "pm uninstall com.x.y") is Decision.Deny)
        assertTrue(real.decide("com.diegonmarcos.cloudwebserver", "appops set com.x.y RUN_IN_BACKGROUND deny") is Decision.Allow)
        assertTrue(real.decide("com.diegonmarcos.cloudbrowser", "pm install x") is Decision.Deny)
        assertTrue(real.decide("some.stranger", "getprop") is Decision.Deny)
    }

    @Test fun theSuperAppMayGrantWriteSecureSettingsToFleetPackagesOnly() {
        val real = Allowlist.parse(File("src/main/assets/shell-allowlist.json").readText())
        val su = "com.diegonmarcos.superapp"
        val ok = real.decide(su, "pm grant com.diegonmarcos.cloudmail android.permission.WRITE_SECURE_SETTINGS")
        assertTrue(ok is Decision.Allow)
        assertTrue(real.decide("com.diegonmarcos.clouddrive", "pm grant com.diegonmarcos.clouddrive android.permission.WRITE_SECURE_SETTINGS") is Decision.Deny)
    }

    @Test fun aSegmentedCallerMayChainOnlyAllowedSegments() {
        assertTrue(list.decide("app.host", "cmd appops set a.b RUN_ANY allow; am set-standby-bucket a.b active") is Decision.Allow)
        assertTrue(list.decide("app.host", "cmd appops set a.b RUN_ANY allow\nam set-standby-bucket a.b active") is Decision.Allow)
        assertTrue(list.decide("app.host", "cmd appops set a.b RUN_ANY allow; rm -rf /x") is Decision.Deny)
        assertTrue(list.decide("app.host", "cmd appops set a.b RUN_ANY allow && id") is Decision.Deny)
    }

    @Test fun anExactLineIsAllowedWhole() {
        assertTrue(list.decide("app.host", "dumpsys alarm | head -5") is Decision.Allow)
        assertTrue(list.decide("app.host", "dumpsys alarm | head -5; id") is Decision.Deny)
        assertTrue(list.decide("app.a", "dumpsys alarm | head -5") is Decision.Deny)   // exact is per caller
    }

    @Test fun theMeshEntryCoversThePeersToolsAndNothingRaw() {
        val real = Allowlist.parse(File("src/main/assets/shell-allowlist.json").readText())
        val web = "com.diegonmarcos.cloudwebserver"
        for (c in listOf("cmd package list packages -U com.x.y", "logcat -d -t 500 --uid=10234", "pm grant com.x.y android.permission.READ_LOGS",
                         "am force-stop com.x.y", "input tap 100 200", "input keyevent KEYCODE_HOME", "getprop ro.build.id",
                         "screencap -p /sdcard/Download/screencap-20261105-163000.png"))
            assertTrue(c, real.decide(web, c) is Decision.Allow)
        for (c in listOf("screencap -p /sdcard/x.png; id", "screencap -p /data/local/tmp/x.png", "logcat -d | grep x", "sh -c id", "reboot", "rm -rf /sdcard"))
            assertTrue(c, real.decide(web, c) is Decision.Deny)
    }
}
