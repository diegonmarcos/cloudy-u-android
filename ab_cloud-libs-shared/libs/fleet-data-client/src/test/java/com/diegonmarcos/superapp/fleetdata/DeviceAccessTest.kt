package com.diegonmarcos.superapp.fleetdata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1718 the ONE device access model. The served dataset (data/fleet/device-access.json) is parsed by [DeviceAccess] and compared,
 * field by field, with the declarations it is generated from: nothing the old pages listed (the Apps MCP-API endpoints, the unified
 * IPC holder's routes, scopes and peers, the per-app IPC templates, the webserver's own routes) may be missing from it, and no app
 * that exposes the fleet door may count zero API routes.
 */
class DeviceAccessTest {
    private val root = File("../../..")
    private fun json(rel: String) = Json.parseToJsonElement(File(root, rel).readText()).jsonObject
    private val text = File(root, "data/fleet/device-access.json").readText()
    private val doc = DeviceAccess.parse(text)
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content.orEmpty()
    private fun JsonObject.a(k: String) = (this[k] as? JsonArray)?.map { it.jsonObject }.orEmpty()

    @Test fun theServedDatasetParsesAndIsListedInTheManifest() {
        assertNull(doc.problem)
        assertTrue(SchemaCheck.check(text, DeviceAccess.SUPPORTED) is SchemaCheck.Result.Ok)
        val shared = json("data/fleet/manifest.json")["shared"]!!.jsonObject
        val row = shared[DeviceAccess.DATASET]?.jsonObject
        assertNotNull("data/fleet/manifest.json does not serve ${DeviceAccess.DATASET}", row)
        assertEquals("device-access is tier 3 (app defaults), never personal", "3", row!!.s("tier"))
        assertTrue(doc.apps.size > 30 && doc.mcp.tools.isNotEmpty() && doc.gateways.size == 2)
    }

    @Test fun noAppThatExposesTheDoorCountsZeroApiRoutes() {
        assertEquals("apps with a door and zero API routes", emptyList<String>(), doc.zeroApis().map { it.id })
        // the count is the declared map's, not a proxy: every op the IPC registry lists for the member is one of its API routes
        val mesh = json("aa_cloud-superapp/data/ipc-mesh.json")
        val shared = mesh["shared"]!!.jsonObject
        for (m in mesh.a("members")) {
            val eps = m.a("endpoints") + (m["libs"] as? JsonArray).orEmpty().flatMap { shared[it.jsonPrimitive.content]?.jsonArray?.map { e -> e.jsonObject }.orEmpty() }
            val ops = eps.filter { it.s("kind") == "op" }.map { it.s("name") }.toSet()
            val app = doc.app(m.s("pkg"))
            assertNotNull("${m.s("id")} is in the IPC registry but not in the model", app)
            assertEquals("${m.s("id")}: ops", ops, app!!.ops.toSet())
            if (ops.isNotEmpty()) assertTrue("${m.s("id")} declares ${ops.size} ops but counts ${app.apiCount}", app.apiCount >= ops.size)
        }
    }

    @Test fun everyEndpointOfTheOldAppsMcpApiPageIsInTheModel() {
        val old = json("ab_cloud-libs-shared/libs/appstore/src/main/assets/apps-mcp-api.json")
        assertEquals(old.a("kinds").map { Triple(it.s("id"), it.s("label"), it.s("what")) }, doc.kinds.map { Triple(it.id, it.label, it.what) })
        val sd = old["shared_debug"]!!.jsonObject
        assertEquals(sd.s("lib"), doc.sharedDebug.lib); assertEquals(sd.s("service"), doc.sharedDebug.service)
        assertEquals(sd.a("endpoints").map { it.s("method") + " " + sd.s("base") + it.s("path") + " " + it.s("what") }, doc.sharedDebug.endpoints.map { it.method + " " + it.url + " " + it.what })
        val apps = old["apps"]!!.jsonObject
        for ((id, kinds) in apps) for ((kind, eps) in kinds.jsonObject) {
            val app = doc.app(id); assertNotNull("$id declared endpoints but is not in the model", app)
            assertEquals("$id $kind", eps.jsonArray.map { it.jsonObject.s("method") + " " + it.jsonObject.s("path") + " " + it.jsonObject.s("what") },
                app!!.declared[kind].orEmpty().map { it.method + " " + it.path + " " + it.what })
        }
        // an app with no debug list inherits the shared default, as the old page drew it
        val plain = doc.members().first { it.declared["debug"].isNullOrEmpty() }
        assertTrue(doc.inherits(plain, "debug")); assertEquals(doc.sharedDebug.endpoints, doc.declared(plain, "debug"))
    }

    @Test fun everyRouteScopeAndPeerOfTheUnifiedHolderIsInTheModel() {
        val map = json("ac_cloud-c3-webserver/data/api-ipc-map.json")
        val scopes = map["acl_scopes"]!!.jsonObject.filterKeys { !it.startsWith("_") }.mapValues { it.value.jsonPrimitive.content }
        assertEquals(scopes, doc.scopes)
        val peers = map["peers"]!!.jsonObject.filterKeys { !it.startsWith("_") }
        assertEquals(peers.keys.toList(), doc.peers.map { it.id })
        for (p in doc.peers) assertEquals(peers[p.id]!!.jsonObject["scopes"]!!.jsonArray.map { it.jsonPrimitive.content }, p.scopes)
        val reg = map["ipc_registry"]!!.jsonObject
        val prefix = reg.s("route_prefix")
        for (e in reg.a("entries")) {
            val cls = e.s("app_class")
            val owners = if (cls == "fleet") doc.apps else listOfNotNull(doc.app(cls.removePrefix("package:")))
            assertTrue("registry route ${e.s("route")} of $cls has no app in the model", owners.isNotEmpty())
            for (a in owners) {
                val r = a.ipc.firstOrNull { it.route == e.s("route") && it.appClass == cls }
                assertNotNull("${a.id} lost its IPC route ${e.s("route")}", r)
                assertEquals("$prefix/${a.id}/${e.s("route")}", r!!.path)
                assertEquals(e.s("acl"), r.acl); assertEquals(e.s("status"), r.status); assertEquals(e.s("what"), r.what)
                assertEquals(e.s("mcp_tool").ifEmpty { null }, r.mcpTool); assertEquals(e.s("legacy_route").ifEmpty { null }, r.legacyRoute)
                assertEquals(e["ipc"]!!.jsonObject.s("authority").replace(DeviceAccess.PKG, a.pkg), r.authority)
            }
            for (also in (e["also_apps"] as? JsonArray).orEmpty()) assertTrue(doc.app(also.jsonPrimitive.content)!!.ipc.any { it.route == e.s("route") && it.appClass == cls })
        }
        // the webserver's own listener: every mapped route, on the holder app alone
        val holder = doc.app(doc.gateway("mesh")!!.pkg)!!
        assertEquals(map.a("routes").map { it.s("surface") + " " + it.s("path") }, holder.routes.map { it.surface + " " + it.path })
        assertTrue(doc.apps.filter { it !== holder }.all { it.routes.isEmpty() })
    }

    @Test fun thePerAppIpcTemplatesAndTheDeviceMcpToolsAreInTheModel() {
        val b = json("ac_cloud-c3-webserver/build.json")
        val tpl = b["api"]!!.jsonObject.a("ipc")
        val app = doc.members().first()
        val eps = doc.ipcEndpoints(app)
        for (t in tpl) assertTrue("template ${t.s("id")}", eps.any { it.id == t.s("id") && it.authority == t.s("authority").replace(DeviceAccess.PKG, app.pkg) && it.guard == t.s("guard") && it.methods == t.s("methods") && it.what == t.s("what") })
        val binder = doc.apps.first { it.engines.isNotEmpty() }
        assertTrue(doc.ipcEndpoints(binder).any { it.id == "engine" && it.authority == b["api"]!!.jsonObject.s("engine_action").replace(DeviceAccess.PKG, binder.pkg) })
        val cat = json("ac_cloud-c3-webserver/data/mcp-catalogue.json")
        val device = cat.a("servers").flatMap { it.a("tools") }.filter { it.s("node") == doc.mcp.node }.map { it.s("tool") }
        assertEquals(device, doc.mcp.tools.map { it.tool })
        assertTrue("a tool the map routes comes in through the mesh gateway", doc.tools("mesh").all { it.routes.isNotEmpty() } && doc.tools("mesh").isNotEmpty())
        assertEquals(doc.mcp.tools.size, doc.tools("mesh").size + doc.tools("debug").size)
        val gw = json("1_cicd/src/data/debug-ports.json")["gateway"]!!.jsonObject
        assertEquals(gw.s("pkg"), doc.gateway("debug")!!.pkg); assertEquals(gw.s("port").toInt(), doc.gateway("debug")!!.port)
        assertEquals(json("1_cicd/src/data/adb-owner.json").s("package"), doc.adbOwner.pkg)
    }

    @Test fun aBrokenDocumentIsAReasonNeverAThrow() {
        for (bad in listOf("", "[]", "{", """{"schemaVersion":"x"}""", """{"schemaVersion":99}""", """{"schemaVersion":1,"apps":"no","gateways":[1],"mcp":{"tools":[3]}}""")) {
            val d = DeviceAccess.parse(bad)
            assertTrue("'$bad' must parse to an empty model", d.apps.isEmpty())
            if (!bad.startsWith("""{"schemaVersion":1""")) assertNotNull("'$bad' must carry a reason", d.problem)
            assertFalse(DeviceAccess.report(d).isEmpty())
        }
        val partial = DeviceAccess.parse("""{"schemaVersion":1,"apps":[{"id":"x","pkg":"p.x","door":true,"ops":["system/ping"],"port":null},{"id":"","pkg":""}]}""")
        assertEquals(1, partial.apps.size); assertEquals(1, partial.apps[0].apiCount); assertNull(partial.apps[0].port)
        assertEquals("system/ping", partial.opUrl(partial.apps[0], "system/ping"))
    }

    @Test fun theReportPrintsEveryRowOnce() {
        val r = DeviceAccess.report(doc)
        for (a in doc.apps) { assertTrue(a.pkg, r.contains(a.pkg)); for (i in a.ipc) assertTrue(i.path, r.contains(i.path)) }
        for (t in doc.mcp.tools) assertTrue(t.tool, r.contains(t.tool))
        for (g in doc.gateways) assertTrue(g.id, r.contains("- ${g.id}: "))
    }
}
