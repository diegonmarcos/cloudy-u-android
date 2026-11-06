package com.diegonmarcos.superapp.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1546 the organized page: filters, search, sort, group-by axes, per-group counts, bulk targets and detail. */
class PermsPageQueryTest {
    private fun e(id: String, kind: PermKind = PermKind.RUNTIME, group: String = "LOCATION", section: PermSection = PermSection.RUNTIME,
                  perm: String = "android.permission.$id", check: String = "", oem: String = "", features: List<String> = emptyList()) =
        PermEntry(id, id.replace('_', ' '), group, kind, section, permission = perm, check = check, oem = oem, why = "why $id", features = features)

    private val fine = e("fine_location", perm = PermsRequest.FINE, features = listOf("wifi", "maps"))
    private val camera = e("camera", group = "CAMERA", features = listOf("camera"))
    private val sms = e("sms_read", group = "SMS")
    private val wss = e("write_secure_settings", PermKind.ADB_ONLY, "DEVELOPMENT", PermSection.ADB, features = listOf("adb"))
    private val overlay = e("overlay", PermKind.SPECIAL, "DISPLAY", PermSection.SPECIAL, perm = "")
    private val usage = e("usage_access", PermKind.APPOPS, "USAGE", PermSection.SPECIAL, perm = "")
    private val batt = e("battery_opt", PermKind.SPECIAL, "BATTERY", PermSection.BATTERY, perm = "")
    private val sam = e("samsung_unrestricted", PermKind.SPECIAL, "SAMSUNG", PermSection.SAMSUNG, perm = "", check = "manual", oem = "samsung")
    private val loc = e("location_services", PermKind.SPECIAL, "SYSTEM", PermSection.SYSTEM, perm = "", check = "location_enabled", features = listOf("wifi"))
    private val all = listOf(fine, camera, sms, wss, overlay, usage, batt, sam, loc)

    private val states = mapOf("fine_location" to PermState.DENIED, "camera" to PermState.PERMANENTLY_DENIED, "sms_read" to PermState.NOT_APPLICABLE,
        "write_secure_settings" to PermState.DENIED, "usage_access" to PermState.UNKNOWN, "samsung_unrestricted" to PermState.DENIED)
    private val rows = PermsPage.rows(all, { PermStatus(states[it.id] ?: PermState.GRANTED, if (it.id == "sms_read") "not declared in this app's manifest" else "") }, { true })
    private val apps = mapOf("fine_location" to listOf("superapp", "nav"), "camera" to listOf("camera"))
    private val appsOf = { id: String -> apps[id].orEmpty() }

    private fun ids(q: PermsQuery) = PermsPageQuery.query(rows, q, appsOf).flatMap { g -> g.rows.map { it.entry.id } }.distinct().toSet()

    @Test fun `the default query shows every declaration, not-applicable ones included`() {
        assertEquals(all.map { it.id }.toSet(), ids(PermsQuery()))
    }

    @Test fun `status chips filter and count`() {
        val c = PermsPageQuery.counts(rows)
        assertEquals(9, c[StatusFilter.ALL]); assertEquals(3, c[StatusFilter.MISSING]); assertEquals(1, c[StatusFilter.DENIED_PERMANENTLY])
        assertEquals(1, c[StatusFilter.NEEDS_SHELL]); assertEquals(1, c[StatusFilter.NOT_APPLICABLE]); assertEquals(1, c[StatusFilter.UNKNOWN])
        assertEquals(1, c[StatusFilter.INFO])
        assertEquals(setOf("fine_location", "camera", "write_secure_settings"), ids(PermsQuery(status = StatusFilter.MISSING)))
        assertEquals(setOf("sms_read"), ids(PermsQuery(status = StatusFilter.NOT_APPLICABLE)))
        assertEquals(setOf("overlay", "battery_opt", "location_services"), ids(PermsQuery(status = StatusFilter.GRANTED)))
    }

    @Test fun `missing only keeps the pre-1519 rule, unknown rows stay visible`() {
        assertEquals(setOf("fine_location", "camera", "write_secure_settings", "usage_access", "samsung_unrestricted"), ids(PermsQuery(missingOnly = true)))
        // a feature asked for rows (highlight) + Missing only: just the highlighted missing ones, as before #1519
        assertEquals(setOf("fine_location"), ids(PermsQuery(missingOnly = true, highlight = setOf("fine_location"))))
    }

    @Test fun `type, section, category, protection, group, feature and app filters`() {
        assertEquals(setOf("write_secure_settings"), ids(PermsQuery(kinds = setOf(PermKind.ADB_ONLY))))
        assertEquals(setOf("usage_access"), ids(PermsQuery(kinds = setOf(PermKind.APPOPS))))
        assertEquals(setOf("battery_opt"), ids(PermsQuery(sections = setOf(PermSection.BATTERY))))
        assertEquals(setOf("fine_location", "location_services"), ids(PermsQuery(categories = setOf(PermCategory.LOCATION_WIFI, PermCategory.SYSTEM))))
        assertEquals(setOf("samsung_unrestricted"), ids(PermsQuery(protections = setOf(Protection.OEM))))
        assertEquals(setOf("camera"), ids(PermsQuery(groups = setOf("CAMERA"))))
        assertEquals(setOf("fine_location", "location_services"), ids(PermsQuery(features = setOf("wifi"))))
        assertEquals(setOf("fine_location"), ids(PermsQuery(apps = setOf("nav"))))
        // filters combine (AND)
        assertEquals(setOf("fine_location"), ids(PermsQuery(features = setOf("wifi"), kinds = setOf(PermKind.RUNTIME))))
    }

    @Test fun `search matches label, permission name, group, reason and app`() {
        assertEquals(setOf("fine_location"), ids(PermsQuery(text = "ACCESS_FINE")))
        assertEquals(setOf("camera"), ids(PermsQuery(text = "camera")))
        assertEquals(setOf("sms_read"), ids(PermsQuery(text = "not declared")))
        assertEquals(setOf("fine_location"), ids(PermsQuery(text = "nav location")))
        assertTrue(ids(PermsQuery(text = "zzz-nothing")).isEmpty())
    }

    @Test fun `every group-by axis files every shown row with counts`() {
        for (by in GroupBy.values()) {
            val gs = PermsPageQuery.query(rows, PermsQuery(groupBy = by), appsOf)
            assertEquals("$by lost rows", all.map { it.id }.toSet(), gs.flatMap { g -> g.rows.map { it.entry.id } }.toSet())
            assertTrue(gs.all { it.rows.isNotEmpty() })
        }
        val sections = PermsPageQuery.query(rows, PermsQuery(groupBy = GroupBy.SECTION))
        assertEquals(listOf("RUNTIME", "SPECIAL", "BATTERY", "SAMSUNG", "ADB", "SYSTEM"), sections.map { it.key })
        val rt = sections.first()
        assertEquals("0/2", rt.count); assertEquals(2, rt.missing)   // fine + camera missing, sms not applicable (not counted)
        val types = PermsPageQuery.query(rows, PermsQuery(groupBy = GroupBy.TYPE)).map { it.title }
        assertEquals(listOf("Runtime", "Special access", "App-ops", "ADB-only"), types)
    }

    @Test fun `sort orders missing first, by name, by type and by protection`() {
        val m = PermsPageQuery.sort(rows, SortBy.MISSING_FIRST).map { it.entry.id }
        assertEquals("camera", m.first()); assertEquals("sms_read", m.last())
        assertEquals(rows.map { it.entry.label }.sorted(), PermsPageQuery.sort(rows, SortBy.NAME).map { it.entry.label })
        assertEquals(PermKind.RUNTIME, PermsPageQuery.sort(rows, SortBy.TYPE).first().entry.kind)
        assertEquals(Protection.SYSTEM_TOGGLE, PermsPageQuery.protection(PermsPageQuery.sort(rows, SortBy.PROTECTION).last().entry))
        assertEquals(rows, PermsPageQuery.sort(rows, SortBy.CATALOGUE))
    }

    @Test fun `bulk targets and the detail lines`() {
        assertEquals(listOf("fine_location"), PermsPageQuery.dialogTargets(rows).map { it.id })
        assertTrue("write_secure_settings" in PermsPageQuery.shellTargets(rows).map { it.id })
        val d = PermsPageQuery.detail(rows.first(), listOf("superapp", "nav"), "dangerous").toMap()
        assertEquals(PermsRequest.FINE, d["Permission"])
        assertTrue(d.getValue("Protection").startsWith("Dangerous") && d.getValue("Protection").endsWith("dangerous"))
        assertEquals("superapp, nav", d["Used by fleet apps"]); assertEquals("wifi, maps", d["Used by features"])
        assertFalse(d.getValue("Actions") == "-")
    }

    @Test fun `protection words from the platform level`() {
        assertEquals("dangerous", FleetPerms.protectionWords(1, 0))
        assertEquals("signature|privileged|development", FleetPerms.protectionWords(2, 0x10 or 0x20))
        assertEquals("signature|appop", FleetPerms.protectionWords(2, 0x40))
    }
}
