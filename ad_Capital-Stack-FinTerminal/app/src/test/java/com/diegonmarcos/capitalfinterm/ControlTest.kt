package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The container control: states from docker ps text, mesh-only enablement, confirmation for Stop and Restart, and the calls. */
class ControlTest {
    private val d = Control.parse(DataFiles.controlJson)!!

    private fun body(status: String?) = if (status == null) """[{"vm":"oci-apps","containers":[]}]"""
        else """[{"vm":"oci-apps","containers":[{"name":"other","status":"Up 1 hour"},{"name":"capital-stack-finterm-api","status":"$status"}]}]"""

    @Test fun statesFollowDockerPsText() {
        assertEquals(CState.RUNNING, Control.state(d, body("Up 3 hours")))
        assertEquals(CState.RUNNING, Control.state(d, body("Up 3 hours (healthy)")))
        assertEquals(CState.UNHEALTHY, Control.state(d, body("Up 3 hours (unhealthy)")))
        assertEquals(CState.UNHEALTHY, Control.state(d, body("Restarting (1) 5 seconds ago")))
        assertEquals(CState.STOPPED, Control.state(d, body("Exited (0) 2 minutes ago")))
        assertEquals(CState.UNKNOWN, Control.state(d, body(null)))
        assertEquals(CState.UNKNOWN, Control.state(d, "not json"))
        assertEquals(CState.UNKNOWN, Control.state(d, """[{"vm":"oci-apps","error":"ssh timeout"}]"""))
    }

    @Test fun offTheMeshNoActionIsEnabledInAnyState() {
        for (s in CState.values()) for (a in listOf("start", "stop", "restart")) assertFalse("$a/$s", Control.enabled(d, a, s, onMesh = false))
    }

    @Test fun onTheMeshEachActionIsAvailableOnlyInItsStates() {
        assertTrue(Control.enabled(d, "start", CState.STOPPED, true))
        assertFalse(Control.enabled(d, "start", CState.RUNNING, true))
        assertTrue(Control.enabled(d, "stop", CState.RUNNING, true))
        assertTrue(Control.enabled(d, "stop", CState.UNHEALTHY, true))
        assertFalse(Control.enabled(d, "stop", CState.STOPPED, true))
        assertTrue(Control.enabled(d, "restart", CState.UNHEALTHY, true))
        assertFalse(Control.enabled(d, "restart", CState.UNKNOWN, true))
        assertFalse(Control.enabled(d, "delete", CState.RUNNING, true))
    }

    @Test fun stopAndRestartAskFirstAndAnUnknownActionAlwaysAsks() {
        assertTrue(Control.needsConfirm(d, "stop"))
        assertTrue(Control.needsConfirm(d, "restart"))
        assertFalse(Control.needsConfirm(d, "start"))
        assertTrue(Control.needsConfirm(d, "delete"))
    }

    @Test fun addressesAreBuiltFromTheBakedMeshBaseAndTheDeclaredPaths() {
        assertEquals("http://c3.mesh.test:8081/health/deployed/oci-apps", Control.statusUrl(d, "http://c3.mesh.test:8081"))
        assertEquals("http://c3.mesh.test:8081/vms/oci-apps/containers/capital-stack-finterm-api/restart", Control.actionUrl(d, "http://c3.mesh.test:8081", "restart"))
        assertNotNull(d.actions.firstOrNull { it.id == "stop" })
        assertEquals("c3-infra-api", d.fleetEndpoint)
    }

    @Test fun runPostsThenReadsTheStateBack() {
        val posted = ArrayList<String>()
        val http = object : Http {
            override fun get(url: String) = body("Exited (0) 1 second ago")
            override fun post(url: String): String { posted += url; return "{}" }
        }
        val (ok, st) = Control.run(d, "http://c3.mesh.test:8081", http, "stop")
        assertTrue(ok)
        assertEquals(CState.STOPPED, st)
        assertEquals(listOf("http://c3.mesh.test:8081/vms/oci-apps/containers/capital-stack-finterm-api/stop"), posted)
        val failing = object : Http {
            override fun get(url: String): String = throw IOException("down")
            override fun post(url: String): String = throw IOException("down")
        }
        assertEquals(false to CState.UNKNOWN, Control.run(d, "x", failing, "stop"))
    }

    @Test fun reachabilityIsTheHealthAnswerAndNothingElse() {
        val up = object : Http { override fun get(url: String) = "ok"; override fun post(url: String) = "" }
        val down = object : Http { override fun get(url: String): String = throw IOException("no route"); override fun post(url: String) = "" }
        assertTrue(Mesh.reachable(up, "http://h.test:1", "/health"))
        assertFalse(Mesh.reachable(down, "http://h.test:1", "/health"))
        assertFalse(Mesh.reachable(up, "", "/health"))
        assertEquals(mapOf("fin-api" to "http://x"), Mesh.parse("""{"fin-api":"http://x"}"""))
        assertEquals("fin.mesh.test", Mesh.hostOf("http://fin.mesh.test:8340"))
    }
}
