package com.diegonmarcos.superapp.settings

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1720 Every command a Presets mode sends (On, re-apply, Off; Samsung and plain Android) is one the shell broker carries for
 * the SuperApp. The SuperApp is a broker CLIENT: its lines pass the per-caller allowlist of libs:shell-broker
 * (assets/shell-allowlist.json, default deny, each `;` segment matched on its own, a shell metacharacter refused unless the
 * rule declares it). This reads that file and applies the same rule, so a step that the broker would refuse cannot ship again:
 * the `svc` lines and the piped reads Super Saving declared before were refused, so NFC, Bluetooth and mobile data never ran.
 */
class ModeCommandsAllowlistTest {
    private class Rule(val id: String, val re: Regex, val meta: String)

    private val entry = JSONObject(File("../../ab_cloud-libs-shared/libs/shell-broker/src/main/assets/shell-allowlist.json").readText())
        .getJSONObject("callers").getJSONObject("com.diegonmarcos.superapp")
    private val rules = entry.getJSONArray("allow").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Rule(it.optString("id"), Regex(it.getString("re")), it.optString("meta")) } }
    private val exact = entry.optJSONArray("exact")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
    private val meta = ";&|`\$<>(){}\\#"
    private val merge = " 2>&1"

    /** libs:shell-broker Allowlist.one: a full-match rule whose declared meta covers every metacharacter of the command. */
    private fun one(cmd: String): Boolean {
        val c = if (cmd.endsWith(merge)) cmd.removeSuffix(merge).trimEnd() else cmd
        return rules.any { r -> r.re.matches(c) && c.none { it in meta && it !in r.meta } }
    }

    /** libs:shell-broker Allowlist.decide for a caller with `segments`. */
    private fun allowed(cmd: String): Boolean {
        if (cmd.isBlank() || cmd.length > 2048 || cmd.any { it == '\u0000' || it == '\r' }) return false
        val c = cmd.trim()
        if (c in exact) return true
        val parts = c.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.all { one(it) }
    }

    @Test fun `the rule is the broker's - known lines pass and a chained or piped line does not`() {
        assertTrue("the SuperApp entry chains segments", entry.optBoolean("segments"))
        assertTrue(rules.size >= 10)
        assertTrue(allowed("settings get global low_power"))
        assertTrue(allowed("settings put global low_power 1; echo '@@global:low_power'; settings get global low_power"))
        assertFalse("a pipe is not carried", allowed("dumpsys nfc | grep -m1 -o 'mState=[a-z_]*'"))
        assertFalse("svc has no rule", allowed("svc nfc disable"))
        assertFalse(allowed("svc bluetooth disable")); assertFalse(allowed("svc data disable"))
        assertFalse("a redirect is not carried", allowed("ip route get 1.1.1.1 2>/dev/null | grep -o 'dev wlan'"))
        assertFalse(allowed("settings put global low_power 1; reboot"))
    }

    @Test fun `every command of every mode passes the broker allowlist, on, re-applied and off`() {
        var total = 0
        for (rig in listOf(ModeRig(), ModeRig().samsung())) for (id in ModeRig.ids) {
            val s = rig.switch()
            s.select(id); s.select(id); s.off()
            assertTrue("$id sent nothing through the shell", rig.dev.log.isNotEmpty())
            for (cmd in rig.dev.log) { total++; assertTrue("$id sends a line the broker refuses: $cmd", allowed(cmd)) }
            rig.dev.log.clear()
        }
        assertTrue("the run exercised the modes: $total commands", total > 60)
    }

    @Test fun `every command a step declares is a single allowlisted line`() {
        for (id in ModeRig.ids) for (s in ModeRig.stepsFor(id)!!) {
            val lines = ArrayList<String>()
            for (k in listOf("read", "apply", "require_cmd", "skip_if_cmd")) s.p.optString(k).takeIf { it.isNotEmpty() }?.let { lines += it }
            s.p.optJSONObject("revert_by_old")?.let { o -> for (k in o.keys()) lines += o.getString(k) }
            for (l in lines) assertTrue("$id/${s.id} declares a line the broker refuses: $l", allowed(l))
        }
    }
}
