package app.sterna.fleet

import com.diegonmarcos.superapp.fleetendpoints.EndpointRouter
import com.diegonmarcos.superapp.fleetendpoints.FleetBearers
import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetendpoints.MeshProbe
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1802/#1803 The fleet mail API pair Cloud Account publishes is registered on the shared router (mesh first, the /v1
 * API only) and the granted bearer is set for its public host alone, never the mesh host.
 */
class MailFleetServicesTest {

    // Test-only hosts: the guard keeps fleet mesh literals out of the MAIN sources; these are example names.
    private val entry = FleetEndpointsContract.Entry(
        service = MailFleetServices.COMMUNICATION_MCP, kind = "mcp",
        mesh = "http://comm.example.test:3103", public = "https://hub.example.org/comm",
        policy = "mesh_first", health = "/health", only = listOf("/v1"),
    )

    @Test fun `the published pair is registered, routed on v1 only, with the bearer on the public host`() {
        val router = EndpointRouter(probe = MeshProbe { _, _, _ -> true })
        val bearers = FleetBearers()
        val base = MailFleetServices.register(FleetEndpointsContract.Published(listOf(entry), "tok"), MailFleetServices.COMMUNICATION_MCP, router, bearers)
        assertEquals("https://hub.example.org/comm", base)
        assertTrue(router.routes("$base/v1/sent/x/status".toHttpUrl()))
        assertTrue(router.routes("$base/v1/recipients/check".toHttpUrl()))
        assertFalse("the hub's other paths are not this service's", router.routes("$base/mcp".toHttpUrl()))
        assertTrue(router.isMeshHost("comm.example.test"))
        assertEquals("tok", bearers.forHost("hub.example.org"))
        assertNull(bearers.forHost("comm.example.test"))
        assertEquals(base, MailFleetServices.register(FleetEndpointsContract.Published(listOf(entry), "tok"), MailFleetServices.COMMUNICATION_MCP, router, bearers))
    }

    @Test fun `no bearer granted sets none, and a missing service is a failure`() {
        val bearers = FleetBearers()
        MailFleetServices.register(FleetEndpointsContract.Published(listOf(entry), ""), MailFleetServices.COMMUNICATION_MCP, EndpointRouter(), bearers)
        assertNull(bearers.forHost("hub.example.org"))
        val e = runCatching {
            MailFleetServices.register(FleetEndpointsContract.Published(emptyList(), "tok"), MailFleetServices.COMMUNICATION_MCP, EndpointRouter(), bearers)
        }.exceptionOrNull()
        assertTrue(e is IOException)
    }
}
