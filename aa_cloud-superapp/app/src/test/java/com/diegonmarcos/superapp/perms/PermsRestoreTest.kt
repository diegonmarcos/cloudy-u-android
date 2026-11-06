package com.diegonmarcos.superapp.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1546 regression guard: #1519 "organized" Configs > Perms by dropping rows and controls. Every declaration, kind,
 * section and permission group the catalogue had before #1519 (commit 65aedf9ed^) must still exist AND reach the page
 * under the default view (not-applicable rows included), and every grouping / filter axis the page had before #1519
 * (sections, Missing only) must still be offered next to the ones #1519 added (categories, System toggles).
 */
class PermsRestoreTest {
    private val cat = PermCatalogue.parse(File("../../ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json").readText())
    private val fleet = FleetPermsIndex.parse(File("../../ab_cloud-libs-shared/libs/perms/src/main/assets/fleet-perms.json").readText())

    private val pre1519Ids = ("notifications fine_location coarse_location background_location nearby_wifi bt_scan bt_connect bt_advertise " +
        "camera microphone contacts_read contacts_write calendar_read calendar_write phone_state phone_numbers sms_read media_images " +
        "media_video media_audio storage_read storage_write activity_recognition body_sensors health_connect all_files usb_host overlay " +
        "write_settings install_unknown exact_alarm usage_access restricted_settings notification_listener accessibility dnd_access " +
        "device_admin vpn battery_opt background_run standby_active samsung_unrestricted samsung_never_sleeping samsung_auto_blocker " +
        "read_logs write_secure_settings dump package_usage_stats_grant").split(" ")
    private val pre1519Kinds = setOf(PermKind.ADB_ONLY, PermKind.APPOPS, PermKind.RUNTIME, PermKind.SPECIAL)
    private val pre1519Sections = listOf(PermSection.RUNTIME, PermSection.SPECIAL, PermSection.BATTERY, PermSection.SAMSUNG, PermSection.ADB)
    private val pre1519Groups = ("ACCESSIBILITY ACTIVITY ADMIN ALARMS BATTERY CALENDAR CAMERA CONTACTS DEVELOPMENT DISPLAY LOCATION " +
        "LOCATION_BACKGROUND MICROPHONE NEARBY_DEVICES NOTIFICATIONS PACKAGES PHONE RESTRICTED SAMSUNG SENSORS SETTINGS SMS STORAGE USAGE VPN").split(" ")
    private val added1519 = listOf("location_services", "wifi_scan_throttle")

    private fun rows(state: (PermEntry) -> PermState) = PermsPage.rows(cat.entries, { PermStatus(state(it), "r") }, { true })

    @Test fun `every pre-1519 declaration and every 1519 addition is in the catalogue`() {
        val ids = cat.entries.map { it.id }.toSet()
        for (id in pre1519Ids + added1519) assertTrue("declaration $id was removed", id in ids)
        assertEquals("duplicate ids", cat.entries.size, ids.size)
        assertTrue(pre1519Kinds.all { k -> cat.entries.any { it.kind == k } })
        assertTrue(pre1519Sections.all { s -> cat.entries.any { it.section == s } })
        for (g in pre1519Groups) assertTrue("permission group $g was removed", cat.entries.any { it.group == g })
    }

    @Test fun `the default page shows every declaration, also when none applies on this phone`() {
        for (st in PermState.values()) {
            val shown = PermsPageQuery.query(rows { st }, PermsQuery()).flatMap { g -> g.rows.map { it.entry.id } }.toSet()
            assertEquals("state $st hid rows", cat.entries.map { it.id }.toSet(), shown)
        }
    }

    @Test fun `the pre-1519 groupings and filters are offered next to the 1519 ones`() {
        val all = rows { PermState.DENIED }
        val sections = PermsPageQuery.query(all, PermsQuery(groupBy = GroupBy.SECTION)).map { it.key }
        assertEquals(pre1519Sections.map { it.name } + "SYSTEM", sections)
        val cats = PermsPageQuery.query(all, PermsQuery(groupBy = GroupBy.CATEGORY)).map { it.key }
        assertTrue(cats.containsAll(listOf("LOCATION_WIFI", "NOTIFICATIONS", "STORAGE", "SHELL", "SPECIAL", "SAMSUNG", "SYSTEM")))
        // Missing only (pre-1519) and the status chips
        assertTrue(PermsPageQuery.query(rows { PermState.GRANTED }, PermsQuery(missingOnly = true)).isEmpty())
        assertEquals(StatusFilter.values().toSet(), PermsPageQuery.counts(all).keys)
        // every type / section / category / protection / group the catalogue has gets a chip
        assertEquals(pre1519Kinds, PermsPageQuery.kindsPresent(all).toSet())
        assertTrue(PermsPageQuery.sectionsPresent(all).containsAll(pre1519Sections))
        assertTrue(PermsPageQuery.groupsPresent(all).containsAll(pre1519Groups))
        assertEquals(Protection.values().toSet(), cat.entries.map { PermsPageQuery.protection(it) }.toSet())
        // the row actions: request (dialog), settings page, shell
        assertEquals(setOf(RowAction.GRANT, RowAction.SETTINGS, RowAction.SHELL), all.flatMap { it.actions }.toSet())
    }

    @Test fun `the fleet index names the apps that use a permission`() {
        assertTrue(fleet.apps.size >= 20)
        assertTrue("cloud-superapp" in fleet.appsUsing("fine_location"))
        val ids = cat.entries.map { it.id }.toSet()
        for (a in fleet.apps) for (n in a.needs) assertTrue("${a.app} needs unknown $n", n in ids)
        val byApp = PermsPageQuery.query(rows { PermState.DENIED }, PermsQuery(groupBy = GroupBy.APP), fleet::appsUsing)
        assertTrue(byApp.any { it.key == "cloud-superapp" })
    }
}
