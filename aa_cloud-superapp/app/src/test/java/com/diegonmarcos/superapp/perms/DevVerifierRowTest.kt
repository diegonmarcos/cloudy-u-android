package com.diegonmarcos.superapp.perms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1822 the "Apps from unverified developers" row: present with label, group and icon, every previous Perms row still there in
 * the same order (rule 16 parity), the shell action registered in the auto-ask walk, the strings in English and Spanish, and the
 * background hooks wired.
 */
class DevVerifierRowTest {
    private val asset = File("../../fleet-private-data/perms-catalogue.json")
    private val cat = PermCatalogue.parse(asset.readText())
    private val e = cat.byId("developer_verifier") ?: error("no developer_verifier row")
    private fun src(p: String) = File("src/main/$p").readText()

    /** The Perms list before #1822, in order (54 rows). */
    private val before = listOf(
        "notifications", "fine_location", "coarse_location", "background_location", "nearby_wifi", "bt_scan", "bt_connect", "bt_advertise",
        "camera", "microphone", "contacts_read", "contacts_write", "calendar_read", "calendar_write", "phone_state", "phone_numbers",
        "sms_read", "media_images", "media_video", "media_audio", "storage_read", "storage_write", "activity_recognition", "body_sensors",
        "health_connect", "all_files", "usb_host", "overlay", "write_settings", "install_unknown", "exact_alarm", "usage_access",
        "restricted_settings", "notification_listener", "accessibility", "dnd_access", "device_admin", "vpn", "battery_opt",
        "background_run", "standby_active", "samsung_unrestricted", "samsung_never_sleeping", "samsung_auto_blocker", "location_services",
        "wifi_scan_throttle", "read_logs", "write_secure_settings", "dump", "package_usage_stats_grant", "shizuku", "termux_run_command",
        "send_sms", "keep_unused")

    @Test fun `every previous Perms row is still there in order and the new row is the only addition`() {
        val now = cat.entries.map { it.id }
        assertEquals(before, now.filter { it != "developer_verifier" })
        assertEquals(before.size + 1, now.size)
        assertEquals(1, now.count { it == "developer_verifier" })
    }

    @Test fun `the row has label, group, icon and a page to open`() {
        assertEquals("Apps from unverified developers", e.label)
        assertEquals("SYSTEM", e.group); assertEquals(PermSection.SYSTEM, e.section); assertEquals(PermKind.SPECIAL, e.kind)
        assertEquals("security", e.icon)
        val icons = JSONObject(asset.readText()).getJSONObject("icons")
        assertTrue("icon not drawable", icons.has(e.icon))
        assertEquals(PermCategory.SYSTEM, PermsPage.category(e))
        assertEquals("android.settings.APPLICATION_DEVELOPMENT_SETTINGS", e.settings!!.action)
        assertTrue(e.why.contains("adb installs are not checked"))
        assertTrue(e.features.isNotEmpty())
    }

    @Test fun `the checker is the row's own, never a proxy`() {
        assertEquals("developer_verifier", e.check)
        assertTrue(PermsPage.isInfo(e).not())
        val reader = File("../../ab_cloud-libs-shared/libs/perms/src/main/java/com/diegonmarcos/superapp/perms/Perms.kt").readText()
        assertTrue(reader.contains("\"developer_verifier\" -> DevVerifier.status(ctx)"))
    }

    @Test fun `the open action is registered in the auto-ask walk`() {
        val cmds = PermsShellGrant.commands("com.x", e)
        assertEquals(listOf(DevVerifier.OPEN_CMD), cmds)
        assertEquals(DevVerifier.OPEN_CMD, e.adb.single())
        val missing = PermsPage.rows(listOf(e), { PermStatus(PermState.DENIED, "r") }, { true })
        val step = PermsPage.nextStep(missing, emptySet(), channelUp = true, shellable = { true })
        assertTrue(step is PermsPage.Step.Shell && step.row.entry.id == "developer_verifier")
        // waiting / restart / unknown rows are never auto-walked (nothing for the shell to do)
        val unknown = PermsPage.rows(listOf(e), { PermStatus(PermState.UNKNOWN, "r") }, { true })
        assertEquals(null, PermsPage.nextStep(unknown, emptySet(), channelUp = true, shellable = { true }))
    }

    @Test fun `strings exist in English and Spanish`() {
        for (f in listOf("res/values/strings.xml", "res/values-es/strings.xml")) {
            val x = src(f)
            for (k in listOf("devver_channel", "devver_restart_title", "devver_restart_body", "devver_window_title", "devver_window_body", "devver_reset_title", "devver_reset_body"))
                assertTrue("$f lacks $k", x.contains("name=\"$k\""))
        }
    }

    @Test fun `the plane, the periodic re-check, the boot receiver and the deep link are wired`() {
        val j = "java/com/diegonmarcos/superapp"
        assertTrue(src("$j/system/PrivilegedPlaneRun.kt").contains("DevVerifierNotifier.run(ctx, exec)"))
        assertTrue(src("$j/App.kt").contains("DevVerifierNotifier.enqueuePeriodic(this)"))
        val n = src("$j/system/DevVerifierNotifier.kt")
        assertTrue(n.contains("PeriodicWorkRequestBuilder<DevVerifierCheckWorker>(6, TimeUnit.HOURS)"))
        assertTrue(n.contains("setAndAllowWhileIdle") && n.contains("DevVerifier.measure(ctx, exec)"))
        assertTrue(n.contains("DevVerifierFlow.immediate(r)"))
        val m = src("AndroidManifest.xml")
        assertTrue(m.contains(".system.DevVerifierReceiver") && m.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(src("$j/ShellActivity.kt").contains("getStringExtra(\"perms_row\")"))
        assertTrue(n.contains("EXTRA_ROW = \"perms_row\""))
    }
}
