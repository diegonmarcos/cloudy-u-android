package com.diegonmarcos.superapp.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1597 the SuperApp holds every Android permission its scope implies: each newly declared catalogue row (the ~11
 * runtime/special perms plus the owner-authorised SEND_SMS) offers a shell grant through libs:perms, and #1600-B's
 * "keep permissions when unused" row turns Android's auto-revoke off (and resets it) through the same shell owner.
 */
class Perms1597Test {
    private val cat = PermCatalogue.parse(File("src/main/assets/perms-catalogue.json").readText())
    private val pkg = "com.diegonmarcos.superapp"

    private fun entry(id: String) = cat.byId(id) ?: error("no catalogue entry $id")
    private fun grant(id: String) = PermsShellGrant.commands(pkg, entry(id))

    /** The #1597 runtime perms, each granted by `pm grant <pkg> <permission>`. */
    private val runtime1597 = listOf(
        "background_location", "bt_advertise", "contacts_write", "calendar_read", "calendar_write",
        "phone_numbers", "storage_read", "storage_write", "activity_recognition", "body_sensors", "send_sms",
    )

    @Test fun `send_sms is a real runtime SMS permission the owner authorised`() {
        val e = entry("send_sms")
        assertEquals("android.permission.SEND_SMS", e.permission)
        assertEquals(PermKind.RUNTIME, e.kind)
    }

    @Test fun `every 1597 runtime perm offers a pm-grant shell command`() {
        for (id in runtime1597) {
            val e = entry(id)
            val g = grant(id)
            assertTrue("$id has no shell grant", g != null && g.contains("pm grant $pkg ${e.permission}"))
        }
    }

    @Test fun `exact_alarm grants through its app-op, not a runtime dialog`() {
        val g = grant("exact_alarm")
        assertTrue("exact_alarm shell grant", g != null && g.any { it == "appops set $pkg SCHEDULE_EXACT_ALARM allow" })
    }

    @Test fun `keep_unused turns auto-revoke off and resets it to default through the shell`() {
        val e = entry("keep_unused")
        assertEquals("android:auto_revoke_permissions_if_unused", e.appop)
        val item = PermsView.item(e, PermStatus(PermState.DENIED))
        val g = PermsActions.commands(pkg, item)
        assertTrue(g is PermsActions.Grant.Shell &&
            g.commands == listOf("appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore"))
        val r = PermsActions.resetCommands(pkg, item)
        assertTrue(r is PermsActions.Grant.Shell &&
            r.commands == listOf("appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED default"))
    }

    @Test fun `the new rows are additions - the pre-existing catalogue is intact`() {
        val ids = cat.entries.map { it.id }.toSet()
        assertTrue("send_sms" in ids && "keep_unused" in ids)
        assertTrue(runtime1597.all { it in ids })
        assertTrue("notifications" in ids && "sms_read" in ids && "all_files" in ids)
    }
}
