package com.diegonmarcos.superapp.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1519 the real catalogue on the Perms page: every row has a category, the new Wi-Fi rows exist, the summary stays truthful. */
class PermsPageCatalogueTest {
    private val cat = PermCatalogue.parse(File("../../ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json").readText())
    private fun e(id: String) = cat.byId(id) ?: error("no entry $id")

    @Test fun `the Wi-Fi scan has a row for each of its needs`() {
        assertEquals(setOf("fine_location", "coarse_location", "nearby_wifi", "location_services", "wifi_scan_throttle"), cat.forFeature("wifi").map { it.id }.toSet())
        assertEquals("android.settings.LOCATION_SOURCE_SETTINGS", e("location_services").settings!!.action)
        assertEquals("location_enabled", e("location_services").check)
        assertTrue(PermsPage.isInfo(e("wifi_scan_throttle")))
    }

    @Test fun `every catalogue row lands in a category and the named groups are all populated`() {
        val cats = cat.entries.map { PermsPage.category(it) }.toSet()
        for (c in PermCategory.values()) assertTrue("category ${c.title} has no row", c in cats)
        assertEquals(PermCategory.LOCATION_WIFI, PermsPage.category(e("fine_location")))
        assertEquals(PermCategory.LOCATION_WIFI, PermsPage.category(e("nearby_wifi")))
        assertEquals(PermCategory.SYSTEM, PermsPage.category(e("location_services")))
        assertEquals(PermCategory.SHELL, PermsPage.category(e("write_secure_settings")))
        assertEquals(PermCategory.SAMSUNG, PermsPage.category(e("samsung_never_sleeping")))
        assertEquals(PermCategory.STORAGE, PermsPage.category(e("all_files")))
        assertEquals(PermCategory.SPECIAL, PermsPage.category(e("overlay")))
    }

    @Test fun `with fine location missing the page never says all granted`() {
        val rows = PermsPage.rows(cat.entries, { if (it.id == "fine_location") PermStatus(PermState.DENIED) else PermStatus(PermState.GRANTED) }, { true })
        val s = PermsPage.summary(rows)
        assertFalse(s.allGranted)
        assertTrue(s.text, s.text.endsWith("1 missing"))
        val fine = rows.single { it.entry.id == "fine_location" }
        assertEquals(listOf(RowAction.GRANT, RowAction.SHELL), fine.actions)
        assertEquals("fine_location", PermsPage.groups(rows).first().rows.first().entry.id)   // missing first in its group
    }

    @Test fun `every runtime row can be granted by shell with the exact pm grant line`() {
        for (x in cat.entries.filter { it.kind == PermKind.RUNTIME }) {
            assertEquals(x.id, listOf("pm grant com.diegonmarcos.superapp ${x.permission}"), PermsShellGrant.commands("com.diegonmarcos.superapp", x))
        }
    }

    @Test fun `the manifest declares fine and coarse location together`() {
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android.permission.ACCESS_FINE_LOCATION") && m.contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertTrue(m.contains("android.permission.NEARBY_WIFI_DEVICES"))
    }
}
