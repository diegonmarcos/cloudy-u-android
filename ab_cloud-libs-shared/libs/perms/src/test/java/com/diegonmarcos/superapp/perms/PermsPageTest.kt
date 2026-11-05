package com.diegonmarcos.superapp.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1519 the Perms page as data: categories, chips, actions, missing first, the truthful summary and the Ask-all walk. */
class PermsPageTest {
    private fun e(id: String, kind: PermKind = PermKind.RUNTIME, group: String = "LOCATION", section: PermSection = PermSection.RUNTIME,
                  perm: String = "android.permission.$id", check: String = "", oem: String = "", auto: Boolean = true) =
        PermEntry(id, id, group, kind, section, permission = perm, check = check, oem = oem, autoRequest = auto, why = "why $id")

    private val fine = e("fine_location", perm = PermsRequest.FINE)
    private val nearby = e("nearby_wifi", group = "NEARBY_DEVICES")
    private val notif = e("notifications", group = "NOTIFICATIONS")
    private val media = e("media_images", group = "STORAGE")
    private val wss = e("write_secure_settings", PermKind.ADB_ONLY, "DEVELOPMENT", PermSection.ADB)
    private val overlay = e("overlay", PermKind.SPECIAL, "DISPLAY", PermSection.SPECIAL, perm = "")
    private val ram = e("samsung_unrestricted", PermKind.SPECIAL, "SAMSUNG", PermSection.SAMSUNG, perm = "", check = "manual", oem = "samsung")
    private val loc = e("location_services", PermKind.SPECIAL, "SYSTEM", PermSection.SYSTEM, perm = "", check = "location_enabled")
    private val throttle = e("wifi_scan_throttle", PermKind.SPECIAL, "SYSTEM", PermSection.SYSTEM, perm = "", check = "wifi_scan_throttle")
    private val all = listOf(fine, nearby, notif, media, wss, overlay, ram, loc, throttle)

    private fun page(states: Map<String, PermState>, shellable: (PermEntry) -> Boolean = { true }) =
        PermsPage.rows(all, { PermStatus(states[it.id] ?: PermState.GRANTED) }, shellable)

    @Test fun `every row lands in the category the page names`() {
        assertEquals(listOf(PermCategory.LOCATION_WIFI, PermCategory.LOCATION_WIFI, PermCategory.NOTIFICATIONS, PermCategory.STORAGE,
            PermCategory.SHELL, PermCategory.SPECIAL, PermCategory.SAMSUNG, PermCategory.SYSTEM, PermCategory.SYSTEM), all.map { PermsPage.category(it) })
        assertEquals(listOf("Location & Wi-Fi", "Notifications", "Storage & Media", "Other runtime", "Shell/ADB-only", "Special access", "Samsung", "System toggles"),
            PermCategory.values().map { it.title })
    }

    @Test fun `the summary never says all granted while a row is missing`() {
        val ok = PermsPage.summary(page(emptyMap()))
        assertTrue(ok.allGranted); assertEquals("7 of 7 granted, 0 missing", ok.text)   // OEM guide and throttle info stay out
        for (id in listOf("fine_location", "nearby_wifi", "write_secure_settings", "overlay", "location_services")) {
            val s = PermsPage.summary(page(mapOf(id to PermState.DENIED)))
            assertFalse("$id missing but all granted", s.allGranted)
            assertEquals("6 of 7 granted, 1 missing", s.text)
        }
        val two = PermsPage.summary(page(mapOf("fine_location" to PermState.PERMANENTLY_DENIED, "overlay" to PermState.DENIED)))
        assertEquals("5 of 7 granted, 2 missing", two.text)
        assertFalse(PermsPage.Summary(0, 0).allGranted)
    }

    @Test fun `a row the phone cannot grant by dialog still counts when it is the only one missing`() {
        val s = PermsPage.summary(page(mapOf("write_secure_settings" to PermState.DENIED), shellable = { false }))
        assertEquals(1, s.missing); assertFalse(s.allGranted)
    }

    @Test fun `chips tell granted, missing, denied permanently and needs shell`() {
        val r = page(mapOf("fine_location" to PermState.PERMANENTLY_DENIED, "nearby_wifi" to PermState.DENIED,
            "write_secure_settings" to PermState.DENIED, "wifi_scan_throttle" to PermState.UNKNOWN)).associateBy { it.entry.id }
        assertEquals(RowChip.DENIED_PERMANENTLY, r.getValue("fine_location").chip)
        assertEquals(RowChip.MISSING, r.getValue("nearby_wifi").chip)
        assertEquals(RowChip.NEEDS_SHELL, r.getValue("write_secure_settings").chip)
        assertEquals(RowChip.INFO, r.getValue("wifi_scan_throttle").chip)
        assertEquals(RowChip.GRANTED, r.getValue("notifications").chip)
        assertEquals("Denied permanently", RowChip.DENIED_PERMANENTLY.word)
    }

    @Test fun `actions - dialog for a plain denial, settings and shell after a permanent one, shell only for adb-only`() {
        val r = page(mapOf("fine_location" to PermState.DENIED, "nearby_wifi" to PermState.PERMANENTLY_DENIED,
            "write_secure_settings" to PermState.DENIED, "location_services" to PermState.DENIED)).associateBy { it.entry.id }
        assertEquals(listOf(RowAction.GRANT, RowAction.SHELL), r.getValue("fine_location").actions)
        assertEquals(listOf(RowAction.SETTINGS, RowAction.SHELL), r.getValue("nearby_wifi").actions)
        assertEquals(listOf(RowAction.SHELL), r.getValue("write_secure_settings").actions)
        assertEquals(listOf(RowAction.SETTINGS, RowAction.SHELL), r.getValue("location_services").actions)
        assertEquals(emptyList<RowAction>(), r.getValue("notifications").actions)
    }

    @Test fun `groups follow the category order and put missing rows first`() {
        val g = PermsPage.groups(page(mapOf("nearby_wifi" to PermState.DENIED, "samsung_unrestricted" to PermState.UNKNOWN)))
        assertEquals(listOf(PermCategory.LOCATION_WIFI, PermCategory.NOTIFICATIONS, PermCategory.STORAGE, PermCategory.SHELL,
            PermCategory.SPECIAL, PermCategory.SAMSUNG, PermCategory.SYSTEM), g.map { it.category })
        assertEquals(listOf("nearby_wifi", "fine_location"), g.first().rows.map { it.entry.id })
        assertEquals("1 / 2", g.first().count)
        // the system group lists the info row after the granted one only when nothing is missing there
        assertEquals(listOf("location_services", "wifi_scan_throttle"), g.last().rows.map { it.entry.id })
    }

    @Test fun `Ask all walks one combined dialog first, then the shell or the page per row, and ends`() {
        val states = mutableMapOf("fine_location" to PermState.DENIED, "nearby_wifi" to PermState.DENIED,
            "write_secure_settings" to PermState.DENIED, "overlay" to PermState.DENIED)
        val rows = { page(states) }
        val shellable = { x: PermEntry -> x.id != "overlay" }
        val walked = HashSet<String>(); val asked = HashSet<String>()
        val first = PermsPage.nextStep(rows(), walked, true, shellable, asked) as PermsPage.Step.Dialog
        assertEquals(listOf("fine_location", "nearby_wifi"), first.entries.map { it.id })
        asked += first.entries.map { it.id }
        // the dialog answered: nearby granted, fine refused for good
        states["nearby_wifi"] = PermState.GRANTED; states["fine_location"] = PermState.PERMANENTLY_DENIED
        val second = PermsPage.nextStep(rows(), walked, true, shellable, asked) as PermsPage.Step.Shell
        assertEquals("fine_location", second.row.entry.id); walked += "fine_location"
        val third = PermsPage.nextStep(rows(), walked, true, shellable, asked) as PermsPage.Step.Shell
        assertEquals("write_secure_settings", third.row.entry.id); walked += "write_secure_settings"
        val fourth = PermsPage.nextStep(rows(), walked, true, shellable, asked) as PermsPage.Step.Settings
        assertEquals("overlay", fourth.row.entry.id); walked += "overlay"
        assertEquals(null, PermsPage.nextStep(rows(), walked, true, shellable, asked))
    }

    @Test fun `without a shell channel a permanent denial falls back to its settings page`() {
        val rows = page(mapOf("fine_location" to PermState.PERMANENTLY_DENIED))
        val step = PermsPage.nextStep(rows, emptySet(), channelUp = false, shellable = { true }, asked = emptySet()) as PermsPage.Step.Settings
        assertEquals("fine_location", step.row.entry.id)
    }
}
