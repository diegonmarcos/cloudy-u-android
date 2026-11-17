package app.sterna.net

import android.content.Context
import com.diegonmarcos.superapp.fleetendpoints.android.FleetHttpInstall
import com.diegonmarcos.superapp.fleetendpoints.android.NetworkChangeWatch
import com.diegonmarcos.superapp.fleetendpoints.FleetTransport

/**
 * #1090 Tells the endpoint router the network changed, so a fleet account re-probes its mesh address instead of
 * trusting a verdict taken on another network. The signal itself is the fleet's one [NetworkChangeWatch]
 * (shared with Cloud Account); this only says what to do with it.
 */
object EndpointNetworkWatch {
    fun start(ctx: Context) {
        FleetHttpInstall.install() // libs below the transport (core's Telemetry) ride it through FleetHttpSeam
        NetworkChangeWatch.start(ctx) {
            FleetTransport.router.onNetworkChanged()
            // #1768: connectivity regained (or a new network): the full sync tops the window up; WorkManager waits for a link.
            runCatching { app.sterna.mail.OfflineSyncWorker.runNow(ctx) }
        }
    }
}
