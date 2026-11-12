package com.diegonmarcos.superapp.shellbroker

import com.diegonmarcos.superapp.shellclient.ShellWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1682 The output door's per-caller cap and the audit grant are allowlist data, default off, capped. */
class OutDoorAllowlistTest {
    private val list = Allowlist.parse("""{"aliases":{"web.pkg":"mesh"},"callers":{
      "app.host":{"outMaxBytes":8388608,"audit":true,"allow":[{"id":"r","re":"^dumpsys [\\w.]+$"}]},
      "app.greedy":{"outMaxBytes":999999999,"allow":[]},
      "app.plain":{"allow":[{"id":"r","re":"^id$"}]},
      "mesh":{"allow":[]}}}""")

    @Test fun capIsTheCallersOwn() = assertEquals(8 * 1024 * 1024, list.outCap("app.host"))
    @Test fun capNeverExceedsTheWireCeiling() = assertEquals(ShellWire.OUT_MAX_BYTES, list.outCap("app.greedy"))
    @Test fun noDoorByDefault() { assertEquals(0, list.outCap("app.plain")); assertEquals(0, list.outCap("web.pkg")); assertEquals(0, list.outCap("nobody")) }
    @Test fun auditOnlyWhenGranted() {
        assertTrue(list.mayAudit("app.host"))
        assertFalse(list.mayAudit("app.plain")); assertFalse(list.mayAudit("web.pkg")); assertFalse(list.mayAudit("nobody"))
    }

    @Test fun shippedAllowlistGrantsOnlyTheOwnerHost() {
        val f = listOf("src/main/assets/shell-allowlist.json", "ab_cloud-libs-shared/libs/shell-broker/src/main/assets/shell-allowlist.json")
            .map { java.io.File(it) }.firstOrNull { it.isFile } ?: return
        val shipped = Allowlist.parse(f.readText())
        val audit = shipped.callers().filter { shipped.mayAudit(it) }
        val out = shipped.callers().filter { shipped.outCap(it) > 0 }
        assertEquals(listOf("com.diegonmarcos.superapp"), audit)
        assertEquals(listOf("com.diegonmarcos.superapp"), out)
    }
}
