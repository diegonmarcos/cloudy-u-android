package com.diegonmarcos.superapp.configs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1688 Roles held: parsed from real dumps, one exact release line each, run behind a confirm. */
class RolesHeldTest {
    private val pkg = "com.diegonmarcos.superapp"
    private val dump = """
        Roles (user 0):
          Role:
            name=android.app.role.HOME
            holders=com.sec.android.app.launcher
          Role:
            name=android.app.role.SYSTEM_UI_MODE
            holders=com.diegonmarcos.superapp,com.android.systemui
          android.app.role.BROWSER
            holders: com.diegonmarcos.superapp
    """.trimIndent()

    @Test fun `parses only the roles this package holds`() {
        assertEquals(listOf("android.app.role.SYSTEM_UI_MODE", "android.app.role.BROWSER"), RolesHeld.parseRoles(dump, pkg))
        assertEquals(emptyList<String>(), RolesHeld.parseRoles(null, pkg))
    }

    @Test fun `every held item has its release command`() {
        val h = RolesHeld.assemble(pkg, dump, listOf("android.app.role.BROWSER"), listOf("$pkg/.Admin", "com.x/.A"),
            listOf("$pkg/.L", "com.y/.N"), "com.z/.S:$pkg/.A11y", listOf("AA:BB"))
        val by = h.associateBy { it.id }
        assertEquals(listOf("cmd role remove-role-holder android.app.role.SYSTEM_UI_MODE $pkg 0"), by.getValue("role:android.app.role.SYSTEM_UI_MODE").release)
        assertEquals(1, h.count { it.name == "android.app.role.BROWSER" })
        assertEquals(listOf("dpm remove-active-admin --user 0 $pkg/.Admin"), by.getValue("device_admin:$pkg/.Admin").release)
        assertEquals(listOf("cmd notification disallow_listener $pkg/.L"), by.getValue("notification_listener:$pkg/.L").release)
        assertEquals(listOf("settings put secure enabled_accessibility_services com.z/.S"), by.getValue("accessibility:$pkg/.A11y").release)
        assertTrue(h.none { it.name.startsWith("com.x") || it.name.startsWith("com.y") })
        val comp = by.getValue("companion:AA:BB")
        assertTrue(comp.release.isEmpty() && !comp.toggle().runnable && comp.toggle().copyText().startsWith("#"))
        val t = by.getValue("device_admin:$pkg/.Admin").toggle()
        assertEquals(AdbToggle.Direction.DISABLE, t.direction); assertEquals("adb shell dpm remove-active-admin --user 0 $pkg/.Admin", t.copyText())
    }

    @Test fun `the page lists it with a confirm before release`() {
        val src = File("src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt").readText()
        assertTrue(src.contains("RolesHeldSection()") && src.contains("KitConfirmDialog(") && src.contains("confirmRelease = h"))
    }
}
