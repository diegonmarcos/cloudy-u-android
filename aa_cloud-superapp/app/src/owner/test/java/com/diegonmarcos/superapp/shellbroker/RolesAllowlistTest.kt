package com.diegonmarcos.superapp.shellbroker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1688 role reads are read-only rules; the release rule is the SuperApp's own package only; writes elsewhere stay refused. */
class RolesAllowlistTest {
    private val list = Allowlist.parse(File("../../ab_cloud-libs-shared/libs/shell-broker/src/main/assets/shell-allowlist.json").readText())
    private val self = ShellBroker.SELF
    private fun rule(c: String) = (list.decide(self, c) as? Allowlist.Decision.Allow)?.rule?.id

    @Test fun `reads are allowed by the read-only rules`() {
        assertEquals("roles-read-1", rule("cmd role get-role-holders android.app.role.HOME"))
        assertEquals("roles-read-1", rule("cmd role get-role-holders-as-user android.app.role.HOME 0"))
        assertEquals("roles-read-2", rule("dumpsys role"))
        assertEquals("roles-read-2", rule("dumpsys device_policy"))
    }

    @Test fun `the release rule is limited to this package`() {
        assertEquals("roles-release-self", rule("cmd role remove-role-holder android.app.role.HOME $self 0"))
        assertEquals("roles-release-self", rule("dpm remove-active-admin --user 0 $self/.Admin"))
        for (c in listOf("cmd role remove-role-holder android.app.role.HOME com.other.app 0", "cmd role remove-role-holder android.app.role.HOME com.diegonmarcos.superappx 0",
                "dpm remove-active-admin --user 0 com.other.app/.A"))
            assertTrue(c, list.decide(self, c) is Allowlist.Decision.Deny || rule(c) == null)
    }

    @Test fun `no read rule matches a write variant`() {
        val rx = Regex(org.json.JSONObject(File("../../ab_cloud-libs-shared/libs/shell-broker/src/main/assets/shell-allowlist.json").readText())
            .getJSONObject("callers").getJSONObject(self).getJSONArray("allow").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("id").startsWith("roles-read") }.joinToString("|") { "(" + it.getString("re") + ")" }
            })
        for (c in listOf("cmd role add-role-holder android.app.role.HOME com.x", "cmd role remove-role-holder android.app.role.HOME com.x 0",
                "cmd role add-role-holder-as-user android.app.role.HOME com.x 0", "cmd role clear-role-holders android.app.role.HOME"))
            assertTrue(c, !rx.matches(c))
        assertTrue(list.decide(self, "cmd role add-role-holder android.app.role.HOME com.x.y").let { it !is Allowlist.Decision.Allow || (it.rule.id != "selfdiag-1") })
    }
}
