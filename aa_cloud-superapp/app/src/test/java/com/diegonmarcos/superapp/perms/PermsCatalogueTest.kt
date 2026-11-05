package com.diegonmarcos.superapp.perms

import com.diegonmarcos.superapp.configs.AdbResult
import com.diegonmarcos.superapp.perms.AdbScript
import com.diegonmarcos.superapp.perms.AutoRequestPlanner
import com.diegonmarcos.superapp.perms.PermCatalogue
import com.diegonmarcos.superapp.perms.PermEntry
import com.diegonmarcos.superapp.perms.PermKind
import com.diegonmarcos.superapp.perms.PermSection
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.PermStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1274 the permission catalogue (data asset), applicability, the open-time auto-request batching and the ADB block. */
class PermsCatalogueTest {
    private val asset = File("../../ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json")
    private val cat = PermCatalogue.parse(asset.readText())
    private fun e(id: String) = cat.byId(id) ?: error("no entry $id")

    // ---- catalogue ----

    @Test fun `the catalogue is data, every section is populated and every entry is actionable`() {
        assertTrue(cat.entries.size >= 30)
        for (s in PermSection.values()) assertTrue("section $s empty", cat.entries.any { it.section == s })
        for (x in cat.entries) {
            when (x.kind) {
                PermKind.RUNTIME -> {
                    assertTrue("${x.id}: runtime without permission", x.permission.startsWith("android.permission."))
                    assertTrue("${x.id}: group ${x.group} not in group_order", x.group in cat.groupOrder)
                    assertTrue("${x.id}: runtime without pm grant", x.adb.any { it.startsWith("pm grant {pkg} ${x.permission}") })
                }
                PermKind.SPECIAL, PermKind.APPOPS -> assertTrue("${x.id}: no way to grant", x.settings != null)
                PermKind.ADB_ONLY -> assertTrue("${x.id}: adb-only without a command", x.adb.isNotEmpty())
            }
            if (x.settings != null && x.kind != PermKind.RUNTIME) assertTrue("${x.id}: special without look_for", x.lookFor.isNotBlank() || x.instruction.isNotBlank())
            if (x.check == "appop") assertTrue("${x.id}: appop check without op", x.appop.startsWith("android:"))
            assertTrue("${x.id}: no feature needs it", x.features.isNotEmpty())
        }
    }

    @Test fun `package-scoped settings pages are the exact ones`() {
        val all = e("all_files").settings!!
        assertEquals("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", all.action); assertTrue(all.packageUri)
        assertEquals("android.settings.action.MANAGE_OVERLAY_PERMISSION", e("overlay").settings!!.action); assertTrue(e("overlay").settings!!.packageUri)
        assertEquals("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", e("battery_opt").settings!!.action)
        assertEquals("permission_settings", e("background_location").settings!!.highlightKey)
        assertEquals("{a11y}", e("accessibility").settings!!.highlightKey)
        assertTrue(e("samsung_never_sleeping").settings!!.samsungComponent.startsWith("com.samsung.android.lool/"))
    }

    @Test fun `duplicate ids are refused`() {
        val bad = """{"group_order":[],"entries":[{"id":"a"},{"id":"a"}]}"""
        assertTrue(runCatching { PermCatalogue.parse(bad) }.isFailure)
    }

    // ---- applicability ----

    @Test fun `sdk window and oem decide applicability`() {
        assertFalse(cat.applicable(e("notifications"), 32, "Google")); assertTrue(cat.applicable(e("notifications"), 33, "Google"))
        assertTrue(cat.applicable(e("storage_read"), 32, "Google")); assertFalse(cat.applicable(e("storage_read"), 33, "Google"))
        assertFalse(cat.applicable(e("all_files"), 29, "Google")); assertTrue(cat.applicable(e("all_files"), 30, "Google"))
        assertFalse(cat.applicable(e("samsung_unrestricted"), 34, "Google")); assertTrue(cat.applicable(e("samsung_unrestricted"), 34, "samsung"))
        assertEquals("needs Android API 33+ (this is 30)", cat.notApplicableReason(e("nearby_wifi"), 30, "x"))
        assertEquals("samsung devices only", cat.notApplicableReason(e("samsung_unrestricted"), 34, "Google"))
    }

    @Test fun `features declare their needs`() {
        assertEquals(setOf("fine_location", "coarse_location", "nearby_wifi", "location_services", "wifi_scan_throttle"), cat.forFeature("wifi").map { it.id }.toSet())
        assertEquals(setOf("bt_scan", "bt_connect"), cat.forFeature("bluetooth").map { it.id }.toSet())
        assertEquals(setOf("fine_location"), cat.forFeature("bluetooth-legacy").map { it.id }.toSet())
        assertTrue(cat.forFeature("mounts").any { it.id == "all_files" })
    }

    // ---- auto-request batching ----

    private fun planner(states: Map<String, PermState>, asked: Set<String> = emptySet()) =
        AutoRequestPlanner.batches(cat, { PermStatus(states[it.id] ?: PermState.NOT_APPLICABLE) }, asked)

    @Test fun `one batch per permission group, in group order`() {
        val b = planner(mapOf("fine_location" to PermState.DENIED, "coarse_location" to PermState.DENIED, "notifications" to PermState.DENIED,
            "bt_scan" to PermState.DENIED, "nearby_wifi" to PermState.DENIED, "camera" to PermState.GRANTED))
        assertEquals(listOf(listOf("notifications"), listOf("fine_location", "coarse_location"), listOf("nearby_wifi", "bt_scan")),
            b.map { batch -> batch.map { it.id } })
    }

    @Test fun `permanently denied, granted, not applicable and settings-only are never auto-asked`() {
        val b = planner(mapOf("fine_location" to PermState.PERMANENTLY_DENIED, "coarse_location" to PermState.GRANTED,
            "camera" to PermState.NOT_APPLICABLE, "background_location" to PermState.DENIED))
        assertTrue(b.isEmpty())
    }

    @Test fun `already asked this session is skipped and the chain terminates`() {
        val states = cat.entries.filter { it.kind == PermKind.RUNTIME }.associate { it.id to PermState.DENIED }.toMutableMap()
        val asked = HashSet<String>()
        var rounds = 0
        while (true) {
            val next = planner(states, asked).firstOrNull() ?: break
            asked += next.map { it.permission }      // the user denies every dialog
            rounds++
            assertTrue("auto-request loops", rounds <= cat.groupOrder.size)
        }
        assertTrue(rounds > 0)
        assertTrue(planner(states, asked).isEmpty())
    }

    @Test fun `requires holds a dependent back until its prerequisite is granted`() {
        val dep = PermEntry("bg", "bg", "LOCATION", PermKind.RUNTIME, PermSection.RUNTIME, permission = "p.BG", requires = listOf("fg"))
        val fg = PermEntry("fg", "fg", "LOCATION", PermKind.RUNTIME, PermSection.RUNTIME, permission = "p.FG")
        val c = PermCatalogue(listOf(fg, dep), listOf("LOCATION"))
        assertEquals(listOf("fg"), AutoRequestPlanner.batches(c, { PermStatus(PermState.DENIED) }, emptySet()).flatten().map { it.id })
        assertEquals(listOf("bg"), AutoRequestPlanner.batches(c, { PermStatus(if (it.id == "fg") PermState.GRANTED else PermState.DENIED) }, emptySet()).flatten().map { it.id })
    }

    // ---- ADB block ----

    private val vars = mapOf("pkg" to "com.x", "listener" to "com.x/.L", "a11y" to "com.x/.A", "admin" to "")

    @Test fun `the block grants every runtime perm and sets app-ops, deviceidle and accessibility`() {
        val lines = AdbScript.lines(cat.entries, vars).map { it.command }
        for (x in cat.entries.filter { it.kind == PermKind.RUNTIME }) assertTrue(x.id, "pm grant com.x ${x.permission}" in lines)
        assertTrue("appops set com.x MANAGE_EXTERNAL_STORAGE allow" in lines)
        assertTrue("appops set com.x SYSTEM_ALERT_WINDOW allow" in lines)
        assertTrue("cmd deviceidle whitelist +com.x" in lines)
        assertTrue("cmd notification allow_listener com.x/.L" in lines)
        assertTrue(lines.any { it.contains("settings put secure enabled_accessibility_services") && it.contains("com.x/.A") })
        assertFalse("unfilled placeholder", lines.any { Regex("\\{[a-z0-9_]+\\}").containsMatchIn(it) })
        assertFalse("device admin line without an admin component", lines.any { it.startsWith("dpm set-active-admin") })
        assertEquals(lines.size, lines.toSet().size)
    }

    @Test fun `computer form prefixes adb shell and quotes shell syntax`() {
        val l = AdbScript.lines(listOf(e("accessibility"), e("camera")), vars)
        val block = AdbScript.block(l, "com.x").lines()
        assertTrue(block[0].startsWith("# Grant all for com.x (2 commands)"))
        assertEquals("adb shell pm grant com.x android.permission.CAMERA", block.first { it.contains("CAMERA") })
        val a = block.first { it.contains("accessibility") }
        assertTrue(a, a.startsWith("adb shell 'v=\$(settings get secure enabled_accessibility_services)") && a.endsWith("'"))
    }

    @Test fun `shell answers are classified per line`() {
        assertTrue(AdbResult.ok("")); assertTrue(AdbResult.ok("Success"))
        assertFalse(AdbResult.ok(null)); assertFalse(AdbResult.ok("java.lang.SecurityException: grant"))
        assertFalse(AdbResult.ok("Exception occurred while executing 'grant'"))
        assertFalse(AdbResult.ok("Error: Unknown option"))
    }
}
