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
      "app.host":{"segments":true,"exact":["dumpsys alarm | head -5"],"allow":[{"id":"s1","chain":true,"re":"^cmd appops set [\\w.]+ [A-Z_]+ (allow|ignore)$"},{"id":"s2","chain":true,"re":"^am set-standby-bucket [\\w.]+ \\w+$"},{"id":"s3","re":"^id$"}]}}}"""
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
        val real = Allowlist.parse(File("../../../fleet-private-data/shell-allowlist.json").readText())
        assertTrue("com.diegonmarcos.superapp" in real.callers())
        // #1513 the SuperApp's self-grant of WRITE_SECURE_SETTINGS has its own exact rule
        val self = real.decide("com.diegonmarcos.superapp", "pm grant com.diegonmarcos.superapp android.permission.WRITE_SECURE_SETTINGS")
        assertTrue(self is Decision.Allow && self.rule.id == "selfgrant-wss")
        assertTrue(real.decide("com.diegonmarcos.clouddrive", "dumpsys meminfo com.x.y") is Decision.Allow)
        assertTrue(real.decide("com.diegonmarcos.clouddrive", "pm clear com.x.y") is Decision.Deny)
        assertTrue(real.decide("com.diegonmarcos.cloudwebserver", "rm -rf /") is Decision.Deny)
        assertTrue(real.decide("com.diegonmarcos.cloudwebserver", "pm uninstall com.x.y") is Decision.Deny)
        assertTrue(real.decide("com.diegonmarcos.cloudwebserver", "appops set com.x.y RUN_IN_BACKGROUND deny") is Decision.Allow)
        assertTrue(real.decide("com.diegonmarcos.cloudbrowser", "pm install x") is Decision.Deny)
        assertTrue(real.decide("some.stranger", "getprop") is Decision.Deny)
    }

    @Test fun theSuperAppMayGrantWriteSecureSettingsToFleetPackagesOnly() {
        val real = Allowlist.parse(File("../../../fleet-private-data/shell-allowlist.json").readText())
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
        // #1748 s3 is allowed alone but declares no `chain`: it never joins a chain
        assertTrue(list.decide("app.host", "id") is Decision.Allow)
        assertTrue(list.decide("app.host", "cmd appops set a.b RUN_ANY allow; id") is Decision.Deny)
        assertTrue(list.decide("app.host", "id\ncmd appops set a.b RUN_ANY allow") is Decision.Deny)
    }

    @Test fun anExactLineIsAllowedWhole() {
        assertTrue(list.decide("app.host", "dumpsys alarm | head -5") is Decision.Allow)
        assertTrue(list.decide("app.host", "dumpsys alarm | head -5; id") is Decision.Deny)
        assertTrue(list.decide("app.a", "dumpsys alarm | head -5") is Decision.Deny)   // exact is per caller
    }

    @Test fun theMeshEntryCoversThePeersToolsAndNothingRaw() {
        val real = Allowlist.parse(File("../../../fleet-private-data/shell-allowlist.json").readText())
        val web = "com.diegonmarcos.cloudwebserver"
        for (c in listOf("cmd package list packages -U com.x.y", "logcat -d -t 500 --uid=10234", "pm grant com.x.y android.permission.READ_LOGS",
                         "am force-stop com.x.y", "input tap 100 200", "input keyevent KEYCODE_HOME", "getprop ro.build.id",
                         "screencap -p /sdcard/Download/screencap-20261105-163000.png"))
            assertTrue(c, real.decide(web, c) is Decision.Allow)
        for (c in listOf("screencap -p /sdcard/x.png; id", "screencap -p /data/local/tmp/x.png", "logcat -d | grep x", "sh -c id", "reboot", "rm -rf /sdcard"))
            assertTrue(c, real.decide(web, c) is Decision.Deny)
    }

    // #1682 the logical owner identity extends the SuperApp's rules and adds its own; the Webserver's package (mesh relay) gets neither.
    @Test fun ownerExtendsTheSuperAppAndTheRelayStaysRestricted() {
        val f = listOf("../../../fleet-private-data/shell-allowlist.json", "fleet-private-data/shell-allowlist.json")
            .map { java.io.File(it) }.firstOrNull { it.isFile } ?: return
        val a = Allowlist.parse(f.readText())
        val sa = "com.diegonmarcos.superapp"
        assertTrue(a.decide("owner", "id -u") is Allowlist.Decision.Allow)
        assertTrue(a.decide("owner", "pm grant com.diegonmarcos.cloudwebserver android.permission.WRITE_SECURE_SETTINGS") is Allowlist.Decision.Allow)
        assertTrue(a.decide("owner", "rm -rf /data") is Allowlist.Decision.Deny)
        for (c in listOf("dumpsys battery", "getprop")) assertEquals(a.decide(sa, c)::class, a.decide("owner", c)::class)
        assertTrue(!a.mayKeeper("com.diegonmarcos.cloudwebserver") && !a.mayAudit("com.diegonmarcos.cloudwebserver") && a.outCap("com.diegonmarcos.cloudwebserver") == 0)   // the relay identity gets none of the owner grants
    }

    // #1744 adopt: Cloud Store becomes the installer of record of a fleet package; only that one command, only to Cloud Store
    @Test fun adoptCommandIsAllowedForTheStoreAndTheSuperAppAndNothingElse() {
        val f = listOf("../../../fleet-private-data/shell-allowlist.json", "fleet-private-data/shell-allowlist.json")
            .map { java.io.File(it) }.firstOrNull { it.isFile } ?: return
        val a = Allowlist.parse(f.readText())
        val ok = "pm set-installer com.diegonmarcos.cloudmail com.diegonmarcos.cloudstore"
        for (who in listOf("com.diegonmarcos.cloudstore", "com.diegonmarcos.superapp", "owner")) {
            assertTrue(who, a.decide(who, ok) is Decision.Allow)
            assertTrue(who, a.decide(who, "$ok 2>&1") is Decision.Allow)
            assertTrue(who, a.decide(who, "pm set-installer com.diegonmarcos.cloudmail com.evil.store") is Decision.Deny)
            assertTrue(who, a.decide(who, "$ok; id") is Decision.Deny)
            assertTrue(who, a.decide(who, "$ok | id") is Decision.Deny)
        }
        assertTrue(a.decide("com.diegonmarcos.cloudbrowser", ok) is Decision.Deny)
        assertTrue(a.decide("com.diegonmarcos.cloudwebserver", ok) is Decision.Deny)
    }

    // #1748 no entry accepts an allowed command with anything appended: each rule matches the WHOLE command; a chain exists only
    // on a `segments` entry and only between rules that declare `chain` (Background policy / Super Saving), never an install,
    // grant, adopt, role or `id` rule.
    private fun real(): Allowlist? = listOf("../../../fleet-private-data/shell-allowlist.json", "fleet-private-data/shell-allowlist.json")
        .map { java.io.File(it) }.firstOrNull { it.isFile }?.let { Allowlist.parse(it.readText()) }

    @Test fun everyEntryRefusesEveryChainingAndInjectionForm() {
        val a = real() ?: return
        val samples = listOf("pm set-installer com.diegonmarcos.cloudmail com.diegonmarcos.cloudstore", "getprop", "dumpsys battery", "echo shell-ok",
            "settings get global x", "pm list packages", "id -u", "screencap -p /sdcard/Download/screencap-20261105-163000.png")
        val tails = listOf("; id", ";id", " && id", " || id", " | id", " & id", " `id`", " \$(id)", "\nid", "\n id", " > /sdcard/x", " >> /sdcard/x",
            " 2>/sdcard/x", " < /etc/passwd", "; rm -rf /sdcard", "\\", " #", " {id}", " (id)")
        var covered = 0
        for (who in a.callers() + "com.diegonmarcos.cloudwebserver") {
            val base = samples.firstOrNull { a.decide(who, it) is Decision.Allow } ?: continue
            covered++
            for (t in tails) assertTrue("$who accepted '$base$t'", a.decide(who, base + t) is Decision.Deny)
            assertTrue("$who accepted a leading chain", a.decide(who, "id; $base") is Decision.Deny)
        }
        assertTrue("covered $covered entries", covered >= 6)
    }

    @Test fun aChainedSegmentsEntryOnlyChainsDeclaredRules() {
        val a = real() ?: return
        val adopt = "pm set-installer com.diegonmarcos.cloudmail com.diegonmarcos.cloudstore"
        for (who in listOf("com.diegonmarcos.superapp", "owner")) {
            // declared Background policy chain: still allowed
            val bg = "cmd appops set com.example.app RUN_ANY_IN_BACKGROUND allow; cmd appops set com.example.app RUN_IN_BACKGROUND allow; am set-standby-bucket com.example.app active"
            assertTrue(who, a.decide(who, bg) is Decision.Allow)
            assertTrue(who, a.decide(who, "echo '@@x'\ncmd appops get com.example.app RUN_ANY_IN_BACKGROUND") is Decision.Allow)
            for (bad in listOf("$adopt; id", "id; $adopt", "$bg; id", "$bg; $adopt", "$bg\npm uninstall com.example.app", "pm grant com.diegonmarcos.superapp android.permission.WRITE_SECURE_SETTINGS; $bg",
                "$bg; pm install -S 5", "$bg; logcat -d", "$bg | id", "$bg && id", "$bg; \$(id)", "$bg; `id`"))
                assertTrue("$who accepted '$bad'", a.decide(who, bad) is Decision.Deny)
            // each lone command is still allowed
            assertTrue(who, a.decide(who, adopt) is Decision.Allow)
            assertTrue(who, a.decide(who, "id") is Decision.Allow)
        }
    }
}
