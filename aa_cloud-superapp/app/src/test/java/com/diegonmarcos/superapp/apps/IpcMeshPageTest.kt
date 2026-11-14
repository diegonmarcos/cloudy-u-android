package com.diegonmarcos.superapp.apps

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.appstore.IpcMesh
import com.diegonmarcos.superapp.appstore.IpcMeshFragment
import com.diegonmarcos.superapp.appstore.IpcMeshPage
import com.diegonmarcos.superapp.launcher.SectionPages
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.KitPageHarness
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #ipc the IPC Mesh page (libs:appstore IpcMeshPage, a0_docs/eng-specs/fleet-ipc.md):
 *
 *   P1 the baked registry is the committed data/ipc-mesh.json, every declared endpoint of
 *      every member present (its own and its shared libs', <pkg> resolved)
 *   P2 the page LISTS every declared endpoint: unfolded, a node per endpoint of every member
 *   P3 SuperApp: Configs > Network > Apps Mesh > IPC is the tab after Apps and builds the shared page's host
 *   P4 the filters partition what is drawn: All draws every member
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IpcMeshPageTest : KitPageHarness() {

    /** The committed registry, read independently of IpcMesh.parse. */
    private fun declared(): Map<String, Set<String>> {
        val o = JSONObject(File("../data/ipc-mesh.json").readText())
        val shared = o.getJSONObject("shared")
        val members = o.getJSONArray("members")
        return (0 until members.length()).associate { i ->
            val m = members.getJSONObject(i)
            val pkg = m.getString("pkg")
            val ids = HashSet<String>()
            fun take(arr: org.json.JSONArray) { for (j in 0 until arr.length()) ids += arr.getJSONObject(j).getString("id").replace("<pkg>", pkg) }
            take(m.getJSONArray("endpoints"))
            val libs = m.getJSONArray("libs")
            for (j in 0 until libs.length()) take(shared.getJSONArray(libs.getString(j)))
            m.getString("id") to ids
        }
    }

    @Test
    fun `P1 the baked registry carries every declared endpoint`() {
        val reg = IpcMesh.baked()
        val want = declared()
        assertEquals(want.keys, reg.members.map { it.id }.toSet())
        for (m in reg.members) assertEquals("${m.id}'s endpoints", want.getValue(m.id), m.endpoints.map { it.id }.toSet())
        assertTrue("the registry declares the fleet IPC door's ops", reg.members.any { m -> m.endpoints.any { it.id == "op:system/ping" } })
        assertEquals(want.values.sumOf { it.size }, reg.count)
    }

    @Test
    fun `P2 the page lists every declared endpoint`() {
        val reg = IpcMesh.baked()
        compose.setContent { IpcMeshPage(expandAll = true, registry = reg) }
        compose.waitForIdle()
        val rows = IpcMesh.rows(reg, null, "all", emptySet(), expandAll = true)
        assertEquals(reg.members.size + reg.count, rows.size)
        for (r in rows) compose.onNodeWithTag(r.tag, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(IpcMesh.TAG_SUMMARY, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `P3 IPC is Cloud Store Access only, Configs keeps one Apps Access tile and hosts the shared page`() {
        val pages = Sections.byId("config")!!.allPages   // #1737 Apps Access is declared hidden (OS Perms opens Store Access at Android Perms)
        val net = pages.filter { it.group == "Network" && it.id in setOf("ng-firewall", "wifi", "dns", "wg", "firewall", "os-perms", "webserver", "proxy", "bluetooth", "apps-access", "adb-shell") }.map { it.id }
        val tiles = pages.filter { it.group == "Network" && !it.hidden && !it.separator }
        assertTrue("Configs declares no ipc page: ${pages.map { it.id }}", pages.none { it.id == "ipc" || it.id == "apps-mesh" })
        assertTrue("Network holds the Apps Access tile: $net", "apps-access" in net)
        assertTrue("Network keeps its visible tile count (2..9, the owner's eight plus Bluetooth): ${tiles.map { it.id }}", tiles.size in 2..9)
        show(IpcMeshFragment())
        compose.onNodeWithTag(IpcMesh.TAG_SUMMARY, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `P4 All draws every member and the filters are declared`() {
        val reg = IpcMesh.baked()
        assertEquals(reg.members.size, IpcMesh.rows(reg, null, "all", emptySet()).size)
        assertEquals(listOf("all", "installed", "answers", "missing"), IpcMesh.FILTERS)
        val live = IpcMesh.Live(mapOf(reg.members.first().id to "1.0"), emptyMap())
        assertEquals(1, IpcMesh.rows(reg, live, "installed", emptySet()).size)
        assertEquals(reg.members.size - 1, IpcMesh.rows(reg, live, "missing", emptySet()).size)
    }
}
