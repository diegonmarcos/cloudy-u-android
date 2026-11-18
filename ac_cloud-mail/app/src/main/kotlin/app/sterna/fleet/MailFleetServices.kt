package app.sterna.fleet

import android.content.Context
import com.diegonmarcos.superapp.fleetendpoints.EndpointPolicy
import com.diegonmarcos.superapp.fleetendpoints.EndpointRouter
import com.diegonmarcos.superapp.fleetendpoints.FleetBearers
import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetendpoints.FleetTransport
import com.diegonmarcos.superapp.fleetendpoints.android.FleetEndpointsClient
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * #1802/#1803 The fleet mail API (cloudy-apps-communication-mcp: delivery status, recipient checks) as Cloud Account
 * publishes it (fleet_endpoints id [COMMUNICATION_MCP], declared once in ac_cloud-account/build.json). Nothing about a
 * host is spelled in Mail's sources (MailEndpointSyncTest).
 *
 * [communicationMcpBase] pulls the pair (cached [TTL_MILLIS]) and REGISTERS it on the shared [FleetTransport.router],
 * with the granted fleet bearer on [FleetTransport.bearers] for the public host only; a caller then addresses the base
 * URL it returns through a client carrying the shared route + bearer interceptors, and the router takes the mesh first.
 */
object MailFleetServices {
    const val COMMUNICATION_MCP = "communication-mcp"
    const val TTL_MILLIS = 10 * 60_000L

    @Volatile private var context: Context? = null
    @Volatile private var cached: Pair<String, Long>? = null

    /** Called once at app start: the context the pull asks Cloud Account through. */
    fun bind(app: Context) { context = app.applicationContext }

    /** The base URL to address, after registering the pair. Throws (with the reason) when Cloud Account cannot say. */
    fun communicationMcpBase(clock: () -> Long = System::currentTimeMillis): String {
        val now = clock()
        cached?.takeIf { now - it.second < TTL_MILLIS }?.let { return it.first }
        val ctx = context ?: throw IOException("fleet services not bound")
        val published = when (val o = FleetEndpointsClient.pull(ctx)) {
            is FleetEndpointsClient.Outcome.Pulled -> o.published
            FleetEndpointsClient.Outcome.AccountMissing -> throw IOException("Cloud Account is not installed")
            is FleetEndpointsClient.Outcome.Refused -> throw IOException("Cloud Account: ${o.why} (fleet.endpoints)")
        }
        val base = register(published, COMMUNICATION_MCP, FleetTransport.router, FleetTransport.bearers)
        cached = base to now
        return base
    }

    /**
     * Registers [service] of [published] on [router] (idempotent: EndpointRouter.set refreshes the same pair) and sets the
     * granted bearer for its PUBLIC host alone. Answers the URL callers address: the public one, or the mesh one for a
     * mesh-only service.
     */
    internal fun register(
        published: FleetEndpointsContract.Published,
        service: String,
        router: EndpointRouter,
        bearers: FleetBearers,
    ): String {
        val e = published.entries.firstOrNull { it.service == service }
            ?: throw IOException("Cloud Account declares no $service endpoint")
        if (e.public.isBlank()) return e.mesh
        router.set(e.public, e.mesh, EndpointPolicy.parse(e.policy), e.only)
        val host = e.public.toHttpUrlOrNull()?.host
        if (host != null && e.auth == FleetEndpointsContract.AUTH_BEARER && published.bearer.isNotBlank()) {
            bearers.set(host, published.bearer)
        }
        return e.public
    }
}
