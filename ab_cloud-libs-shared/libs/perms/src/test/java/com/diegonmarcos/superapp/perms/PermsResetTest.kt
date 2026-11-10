package com.diegonmarcos.superapp.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1601 "Reset all to default": the shell line that puts each kind of permission back to its default state, the inverse of the grant. */
class PermsResetTest {
    private val pkg = "com.example.app"

    private fun row(id: String, kind: PermKind, state: PermState = PermState.GRANTED, permission: String = "", appop: String = "",
                    item: PermItemKind = if (kind == PermKind.RUNTIME) PermItemKind.RUNTIME else PermItemKind.SPECIAL) =
        PermItem(id, id, "", "", item, state, permission = permission, appop = appop, permKind = kind)

    private fun reset(i: PermItem, components: Map<String, String> = emptyMap(), a11y: String = "") =
        (PermsActions.resetCommands(pkg, i, components, a11y) as PermsActions.Grant.Shell).commands

    @Test fun `runtime and development perms are revoked`() {
        assertEquals(listOf("pm revoke $pkg android.permission.CAMERA"), reset(row("camera", PermKind.RUNTIME, permission = "android.permission.CAMERA")))
        assertEquals(listOf("pm revoke $pkg android.permission.WRITE_SECURE_SETTINGS"),
            reset(row("write_secure_settings", PermKind.ADB_ONLY, permission = "android.permission.WRITE_SECURE_SETTINGS")))
    }

    @Test fun `app-ops and special access go back to default`() {
        assertEquals(listOf("appops set $pkg GET_USAGE_STATS default"),
            reset(row("usage_access", PermKind.APPOPS, permission = "android.permission.PACKAGE_USAGE_STATS", appop = "android:get_usage_stats")))
        assertEquals(listOf("appops set $pkg SYSTEM_ALERT_WINDOW default"), reset(row("overlay", PermKind.SPECIAL, permission = "android.permission.SYSTEM_ALERT_WINDOW")))
        assertEquals(listOf("appops set $pkg MANAGE_EXTERNAL_STORAGE default"), reset(row("all_files", PermKind.SPECIAL, permission = "android.permission.MANAGE_EXTERNAL_STORAGE")))
        assertEquals(listOf("appops set $pkg RUN_ANY_IN_BACKGROUND default"),
            reset(row("background_run", PermKind.APPOPS, appop = "android:run_any_in_background")))
    }

    @Test fun `battery, DND, listener, accessibility and device admin are undone`() {
        assertEquals(listOf("cmd deviceidle whitelist -$pkg"), reset(row("battery_opt", PermKind.SPECIAL)))
        assertEquals(listOf("cmd notification disallow_dnd $pkg"), reset(row("dnd", PermKind.SPECIAL)))
        assertEquals(listOf("cmd notification disallow_listener $pkg/.Listen"),
            reset(row("notification_listener", PermKind.SPECIAL), mapOf("notification_listener" to "$pkg/.Listen")))
        assertEquals(listOf("settings put secure enabled_accessibility_services other/.A"),
            reset(row("accessibility", PermKind.SPECIAL), mapOf("accessibility" to "$pkg/.Acc"), "other/.A:$pkg/.Acc"))
        assertEquals(listOf("dpm remove-active-admin --user 0 $pkg/.Admin"),
            reset(row("device_admin", PermKind.SPECIAL), mapOf("device_admin" to "$pkg/.Admin")))
    }

    @Test fun `what the shell cannot reset says why`() {
        for (i in listOf(row("health_connect", PermKind.SPECIAL), row("usb_host", PermKind.SPECIAL),
                         row("location_services", PermKind.SPECIAL), row("standby_active", PermKind.ADB_ONLY),
                         row("install_unknown", PermKind.SPECIAL, item = PermItemKind.INSTALL))) {
            val g = PermsActions.resetCommands(pkg, i)
            assertTrue("$i", g is PermsActions.Grant.Cannot && (g as PermsActions.Grant.Cannot).reason.isNotBlank())
        }
    }

    @Test fun `the reset-all plan covers every resettable row and skips install-time ones`() {
        val items = listOf(
            row("camera", PermKind.RUNTIME, permission = "android.permission.CAMERA"),
            row("overlay", PermKind.SPECIAL, permission = "android.permission.SYSTEM_ALERT_WINDOW"),
            row("usage_access", PermKind.APPOPS, appop = "android:get_usage_stats"),
            row("install_unknown", PermKind.SPECIAL, item = PermItemKind.INSTALL),
            row("health_connect", PermKind.SPECIAL),
        )
        val plan = PermsActions.resetPlan(pkg, items)
        assertEquals(listOf("camera", "overlay", "usage_access"), plan.steps.map { it.first.id })
        assertEquals(listOf("health_connect"), plan.cannot.map { it.first.id })   // install row skipped entirely
    }

    @Test fun `the reset plan runs through the channel and reports`() {
        val ran = ArrayList<String>()
        val ch = object : PermsGrant.GrantChannel {
            override fun up() = true
            override fun exec(command: String): String? { ran += command; return "" }
        }
        val plan = PermsActions.resetPlan(pkg, listOf(row("camera", PermKind.RUNTIME, permission = "android.permission.CAMERA")))
        val res = PermsActions.run(plan, ch)
        assertEquals(1, res.granted)
        assertTrue("pm revoke $pkg android.permission.CAMERA" in ran)
    }
}
