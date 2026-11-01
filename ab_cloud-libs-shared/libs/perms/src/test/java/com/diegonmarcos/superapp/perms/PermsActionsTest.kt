package com.diegonmarcos.superapp.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1403 the Perms page's filter chips, the adb command for every kind, and grant-all over the tap-ones. */
class PermsActionsTest {
    private val pkg = "com.example.app"

    private fun row(id: String, kind: PermKind, state: PermState, permission: String = "", appop: String = "",
                    item: PermItemKind = if (kind == PermKind.RUNTIME) PermItemKind.RUNTIME else PermItemKind.SPECIAL) =
        PermItem(id, id, "", "", item, state, permission = permission, appop = appop, permKind = kind)

    private val camera = row("camera", PermKind.RUNTIME, PermState.DENIED, "android.permission.CAMERA")
    private val mic = row("microphone", PermKind.RUNTIME, PermState.PERMANENTLY_DENIED, "android.permission.RECORD_AUDIO")
    private val notif = row("notifications", PermKind.RUNTIME, PermState.GRANTED, "android.permission.POST_NOTIFICATIONS")
    private val overlay = row("overlay", PermKind.SPECIAL, PermState.DENIED, "android.permission.SYSTEM_ALERT_WINDOW")
    private val allFiles = row("all_files", PermKind.SPECIAL, PermState.PERMANENTLY_DENIED, "android.permission.MANAGE_EXTERNAL_STORAGE")
    private val usage = row("usage_access", PermKind.APPOPS, PermState.DENIED, "android.permission.PACKAGE_USAGE_STATS", "android:get_usage_stats")
    private val wss = row("write_secure_settings", PermKind.ADB_ONLY, PermState.DENIED, "android.permission.WRITE_SECURE_SETTINGS")
    private val all = listOf(camera, mic, notif, overlay, allFiles, usage, wss)

    @Test fun `state chips - missing is denied or never answered, denied is refused for good, allowed is granted`() {
        assertEquals(setOf(StateFilter.MISSING), PermsActions.states(camera))
        assertEquals(setOf(StateFilter.MISSING, StateFilter.DENIED), PermsActions.states(mic))
        assertEquals(setOf(StateFilter.ALLOWED), PermsActions.states(notif))
        assertEquals(listOf(camera, mic, overlay, allFiles, usage, wss), PermsActions.filter(all, setOf(StateFilter.MISSING), emptySet()))
        assertEquals(listOf(mic, allFiles), PermsActions.filter(all, setOf(StateFilter.DENIED), emptySet()))
        assertEquals(listOf(notif), PermsActions.filter(all, setOf(StateFilter.ALLOWED), emptySet()))
        assertEquals(all, PermsActions.filter(all, emptySet(), emptySet()))
    }

    @Test fun `kind chips - grant is runtime, tap is special access, adb is adb-only and app-ops`() {
        assertEquals(KindFilter.GRANT, PermsActions.kind(camera))
        assertEquals(KindFilter.TAP, PermsActions.kind(overlay))
        assertEquals(KindFilter.ADB, PermsActions.kind(usage))
        assertEquals(KindFilter.ADB, PermsActions.kind(wss))
        assertEquals(KindFilter.HELPER, PermsActions.kind(camera.copy(id = "cld.termux:camera", kind = PermItemKind.HELPER)))
        assertEquals(listOf(overlay), PermsActions.filter(all, setOf(StateFilter.MISSING), setOf(KindFilter.TAP)).filter { it.state == PermState.DENIED })
        assertEquals(listOf(KindFilter.GRANT, KindFilter.TAP, KindFilter.ADB), PermsActions.kindsPresent(all))
    }

    private fun shell(i: PermItem, components: Map<String, String> = emptyMap(), a11y: String = "") =
        (PermsActions.commands(pkg, i, components, a11y) as PermsActions.Grant.Shell).commands

    @Test fun `every kind has its command`() {
        assertEquals(listOf("pm grant $pkg android.permission.CAMERA"), shell(camera))
        assertEquals(listOf("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"), shell(wss))
        assertEquals(listOf("appops set $pkg GET_USAGE_STATS allow"), shell(usage))
        assertEquals(listOf("appops set $pkg SYSTEM_ALERT_WINDOW allow"), shell(overlay))
        assertEquals(listOf("appops set $pkg MANAGE_EXTERNAL_STORAGE allow"), shell(allFiles))
        assertEquals(listOf("appops set $pkg REQUEST_INSTALL_PACKAGES allow"), shell(row("install_unknown", PermKind.SPECIAL, PermState.DENIED)))
        assertEquals(listOf("cmd deviceidle whitelist +$pkg"), shell(row("battery_opt", PermKind.SPECIAL, PermState.DENIED)))
        assertEquals(listOf("cmd notification allow_dnd $pkg"), shell(row("dnd", PermKind.SPECIAL, PermState.DENIED)))
        assertEquals(listOf("cmd notification allow_listener $pkg/.Listen"),
            shell(row("notification_listener", PermKind.SPECIAL, PermState.DENIED), mapOf("notification_listener" to "$pkg/.Listen")))
        assertEquals(listOf("settings put secure enabled_accessibility_services other/.A:$pkg/.Acc", "settings put secure accessibility_enabled 1"),
            shell(row("accessibility", PermKind.SPECIAL, PermState.DENIED), mapOf("accessibility" to "$pkg/.Acc"), "other/.A"))
        assertEquals(listOf("appops set $pkg RUN_ANY_IN_BACKGROUND allow"),
            shell(row("background_run", PermKind.APPOPS, PermState.DENIED, appop = "android:run_any_in_background")))
        assertEquals(listOf("pm grant cld.termux android.permission.CAMERA"), shell(camera.copy(id = "cld.termux:camera", kind = PermItemKind.HELPER)))
    }

    @Test fun `what adb cannot set says why`() {
        for (i in listOf(row("notification_listener", PermKind.SPECIAL, PermState.DENIED), row("health_connect", PermKind.SPECIAL, PermState.DENIED),
                         row("samsung_unrestricted", PermKind.SPECIAL, PermState.DENIED), row("usb_host", PermKind.SPECIAL, PermState.DENIED))) {
            val g = PermsActions.commands(pkg, i)
            assertTrue("$i", g is PermsActions.Grant.Cannot && g.reason.isNotBlank())
        }
    }

    @Test fun `grant-all covers the tap-ones, and denied also adds the refused ones`() {
        val missing = PermsActions.plan(pkg, all, PermsActions.Mode.ADB_MISSING)
        assertEquals(listOf("camera", "overlay", "usage_access", "write_secure_settings"), missing.steps.map { it.first.id })
        val denied = PermsActions.plan(pkg, all, PermsActions.Mode.DENIED_ALSO)
        assertEquals(listOf("camera", "microphone", "overlay", "all_files", "usage_access", "write_secure_settings"), denied.steps.map { it.first.id })
        assertTrue(denied.steps.none { it.first.state == PermState.GRANTED })
        val text = PermsActions.copyText(denied)
        assertTrue(text, "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow" in text.lines())
        assertTrue(text, "adb shell pm grant $pkg android.permission.RECORD_AUDIO" in text.lines())
    }

    @Test fun `run grants every step and reports failures and what adb cannot set`() {
        val ran = ArrayList<String>()
        val ch = object : PermsGrant.GrantChannel {
            override fun up() = true
            override fun exec(command: String): String? { ran += command; return if ("RECORD_AUDIO" in command) "Exception: not allowed" else "" }
        }
        val plan = PermsActions.plan(pkg, all + row("health_connect", PermKind.SPECIAL, PermState.DENIED), PermsActions.Mode.DENIED_ALSO)
        val r = PermsActions.run(plan, ch)
        assertEquals(5, r.granted)
        assertEquals(listOf("microphone"), r.failed.map { it.first })
        assertEquals(listOf("health_connect"), r.cannot.map { it.first })
        assertTrue("appops set $pkg SYSTEM_ALERT_WINDOW allow" in ran)
        val down = object : PermsGrant.GrantChannel { override fun up() = false; override fun exec(command: String): String? = error("ran") }
        assertEquals(0, PermsActions.run(plan, down).granted)
    }
}
