package com.diegonmarcos.clouddrive.manage

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 16 for the removal of the Fleet-apps sub page (#1715): the page itself is gone on the owner's instruction, nothing it
 * carried is lost. Each field and action of a Fleet-apps tile is listed with where it lives now; a field that only a catalogue
 * entry has (an app that is NOT installed) is available in Cloud Store, and Local Apps says so on the page.
 */
class FleetAppsParityTest {
    private val root = File(System.getProperty("user.dir") ?: ".").let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val src = File(root, "app/src/main/java/com/diegonmarcos/clouddrive")
    private fun text(rel: String) = File(src, rel).readText()
    private val manage by lazy { text("manage/ManageScreen.kt") }
    private val snapshot by lazy { text("manage/AppSnapshot.kt") }

    /** Fleet-apps field/action -> (where it lives now, evidence that must be in the source). */
    private val parity: Map<String, Pair<String, () -> Boolean>> by lazy { linkedMapOf(
        "label" to ("Local Apps > Name column (installed label)" to { manage.contains("r.label") }),
        "icon (glyph)" to ("Local Apps > Name column, glyph prefix for a declared fleet app (AppRow.fleetIcon)" to { manage.contains("r.fleetIcon") && snapshot.contains("fleetIcon = fleet[pkg]?.first") }),
        "packageName / installed status (the not-installed badge)" to ("Local Apps > one row per installed app, Running column; declared fleet apps not on the phone are named on the page, installable from Cloud Store" to { manage.contains("APPS_FLEET_MISSING") && manage.contains("R.string.manage_apps_fleet_missing") }),
        "version / size (new on installed apps)" to ("Local Apps > Version, Total, App size, Data, Cache, RAM columns" to { manage.contains("AppColumn.VERSION") && manage.contains("AppColumn.TOTAL") }),
        "tap = launch the app (packageName + fallbackUrl)" to ("Local Apps > Actions column, Open (launchApp(pkg, fleetUrl))" to { manage.contains("onLaunch") && manage.contains("r.fleetUrl") && snapshot.contains("fleetUrl = fleet[pkg]?.second") }),
        "route tiles (GitSync, RSync): stay in the app" to ("Local Apps > page bar shortcut pills (onRoute(routeTab, routePage))" to { manage.contains("onRoute(a.routeTab, a.routePage)") }),
        "catalogue entry of an app not on the phone" to ("Cloud Store (the fleet catalogue); Local Apps names the missing ones" to { manage.contains("manage_apps_fleet_missing") }),
    ) }

    @Test fun everyFleetAppsFieldIsMappedAndItsEvidenceIsInTheSource() {
        assertEquals(7, parity.size)
        parity.forEach { (field, where) -> assertTrue("$field -> ${where.first}", where.second()) }
    }

    @Test fun theFleetAppsPageIsGoneButNothingElseIs() {
        assertTrue("no Fleet apps pill / view any more", !manage.contains("manage_apps_view_fleet") && !manage.contains("AppsGrid("))
        assertTrue("the Installed table keeps its sortable columns", AppColumn.entries.map { it.name }.containsAll(listOf("NAME", "TOTAL", "APP", "DATA", "CACHE", "RUNNING", "RAM")))
    }

    @Test fun everyDeclaredFleetTileHasAHome() {
        val tiles = JSONObject(File(root, "data/drive-apps.json").readText()).getJSONArray("apps")
        assertTrue(tiles.length() > 0)
        for (i in 0 until tiles.length()) {
            val t = tiles.getJSONObject(i)
            val route = t.optString("route_tab").ifBlank { t.optJSONObject("route")?.optString("tab").orEmpty() }
            val pkg = t.optString("package").ifBlank { t.optString("package_name") }.ifBlank { t.optString("fleet") }
            // route tile -> page-bar shortcut; package tile -> installed row (when installed) or the missing-apps line (Cloud Store); otherwise a URL launcher kept as the fallback of its row
            assertTrue("${t.optString("label")} has a route, a package/fleet id or a URL", route.isNotBlank() || pkg.isNotBlank() || t.optString("fallback_url").isNotBlank() || t.optString("url").isNotBlank())
        }
    }
}
