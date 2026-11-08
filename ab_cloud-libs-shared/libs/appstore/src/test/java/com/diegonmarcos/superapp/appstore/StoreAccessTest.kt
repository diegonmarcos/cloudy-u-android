package com.diegonmarcos.superapp.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Access view-model ([StoreAccess]): per-tab population, declared-vs-actual status, and the
 * empty / pending states. No device — StoreAccess holds no Android, so it is handed already-parsed
 * [AppsMcpApi] / [IpcMesh] rows and a plain identity snapshot.
 */
class StoreAccessTest {

    private fun ep(method: String, url: String, what: String = "") = AppsMcpApi.Endpoint(method, url, what)

    private fun row(
        mcp: List<AppsMcpApi.Endpoint> = emptyList(),
        app: List<AppsMcpApi.Endpoint> = emptyList(),
        debug: List<AppsMcpApi.Endpoint> = emptyList(),
        inherited: Set<String> = emptySet(),
    ) = AppsMcpApi.Row("x", "X", mapOf("mcp" to mcp, "app" to app, "debug" to debug), inherited)

    private fun ipcEp(id: String, kind: String, address: String, permission: String? = null) =
        IpcMesh.Endpoint(id, kind, id, address, permission, "none", emptyList(), emptyList(), "", null, emptyList(), emptyList())

    private fun rows(secs: List<StoreAccess.Section>) = secs.flatMap { it.rows }

    // ── MCP ─────────────────────────────────────────────────────────────────

    @Test fun mcpPopulatesEachToolAndMapsToApiRouteAsPending() {
        val r = rows(StoreAccess.mcp(row(mcp = listOf(ep("POST", "/tool/a", "does a")))))
        assertEquals(1, r.size)
        assertEquals("POST", r[0].lead)
        assertEquals("/tool/a", r[0].addr)
        // the MCP⇄API edge is not in the on-device projection yet: pending, never invented
        assertEquals(StoreAccess.PENDING, r[0].link)
        assertEquals(StoreAccess.Status.DECLARED, r[0].status)
    }

    @Test fun mcpEmptyWhenAppDeclaresNoTools() {
        assertTrue(rows(StoreAccess.mcp(row())).isEmpty())
        assertTrue(rows(StoreAccess.mcp(null)).isEmpty())
    }

    // ── API ─────────────────────────────────────────────────────────────────

    @Test fun apiCombinesAppAndDebugAndMarksInheritedDebugAsActual() {
        val r = rows(StoreAccess.api(row(
            app = listOf(ep("GET", "/api/self")),
            debug = listOf(ep("GET", "/shared")),
            inherited = setOf("debug"))))
        assertEquals(listOf("/api/self", "/shared"), r.map { it.addr })
        // own app route: declared; the inherited (shared-lib) debug route: actual
        assertEquals(StoreAccess.Status.DECLARED, r[0].status)
        assertEquals(StoreAccess.Status.ACTUAL, r[1].status)
        // every API route maps to an IPC target — pending until the projection carries that edge
        assertTrue(r.all { it.link == StoreAccess.PENDING })
    }

    // ── IPC ─────────────────────────────────────────────────────────────────

    @Test fun ipcStatusReflectsWhetherTheDeclaredEndpointResolvesOnTheDevice() {
        val eps = listOf(
            ipcEp("p1", "provider", "content://a.fleet", "android.permission.X"),
            ipcEp("p2", "provider", "content://b"),
            ipcEp("p3", "intent", "ACT"))
        val r = rows(StoreAccess.ipc(eps, mapOf("p1" to true, "p2" to false)))
        assertEquals(StoreAccess.Status.MATCH, r[0].status)      // resolves
        assertEquals(StoreAccess.Status.MISMATCH, r[1].status)   // declared but does not resolve
        assertEquals(StoreAccess.Status.DECLARED, r[2].status)   // not probed
        // the permission is surfaced as the row's description
        assertTrue(r[0].what.startsWith("perm:"))
    }

    @Test fun ipcEmptyWhenNoEndpointsDeclared() {
        assertTrue(rows(StoreAccess.ipc(emptyList())).isEmpty())
    }

    // ── ID ──────────────────────────────────────────────────────────────────

    private fun id(installed: Boolean, actualVer: String? = "1.0", code: Long? = 5L, sha: String? = "ab") =
        StoreAccess.Id("drive", "com.x.drive", "1.0", 5L, installed, if (installed) "com.x.drive" else null,
            if (installed) actualVer else null, if (installed) code else null, if (installed) 10001 else null,
            if (installed) sha else null)

    @Test fun idMatchesDeclaredAgainstActualWhenInstalled() {
        val r = rows(StoreAccess.id(id(installed = true)))
        val byField = r.associateBy { it.lead }
        assertEquals(StoreAccess.Status.DECLARED, byField.getValue("fleet id").status)
        assertEquals(StoreAccess.Status.MATCH, byField.getValue("package").status)
        assertEquals(StoreAccess.Status.MATCH, byField.getValue("version").status)
        assertEquals(StoreAccess.Status.ACTUAL, byField.getValue("uid").status)
        assertEquals(StoreAccess.Status.ACTUAL, byField.getValue("signing cert").status)
    }

    @Test fun idFlagsAVersionMismatch() {
        val r = rows(StoreAccess.id(id(installed = true, actualVer = "2.0")))
        assertEquals(StoreAccess.Status.MISMATCH, r.first { it.lead == "version" }.status)
    }

    @Test fun idActualSideIsNoneWhenNotInstalledAndSigningIsNotPendingThere() {
        val r = rows(StoreAccess.id(id(installed = false)))
        val byField = r.associateBy { it.lead }
        assertEquals(StoreAccess.Status.NONE, byField.getValue("package").status)
        assertEquals(StoreAccess.Status.NONE, byField.getValue("uid").status)
        // not installed ⇒ no cert to read, and that is NONE (nothing to check), not PENDING (a missing edge)
        assertEquals(StoreAccess.Status.NONE, byField.getValue("signing cert").status)
    }

    @Test fun idSigningIsPendingWhenInstalledButCertUnreadable() {
        val r = rows(StoreAccess.id(id(installed = true, sha = null)))
        assertEquals(StoreAccess.Status.PENDING, r.first { it.lead == "signing cert" }.status)
    }

    // ── summary ───────────────────────────────────────────────────────────────

    @Test fun summarySaysNoneDeclaredWhenEmptyElseCountsDeclaredAndChecked() {
        assertTrue(StoreAccess.summary(StoreAccess.Tab.MCP, StoreAccess.mcp(row())).endsWith("none declared"))
        val s = StoreAccess.summary(StoreAccess.Tab.MCP, StoreAccess.mcp(row(mcp = listOf(ep("POST", "/t")))))
        assertTrue(s.contains("1 declared"))
        assertTrue(s.contains("pending"))  // the MCP⇄API edge is pending
    }
}
