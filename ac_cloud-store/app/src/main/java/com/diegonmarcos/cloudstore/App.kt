package com.diegonmarcos.cloudstore

import android.app.Application
import android.util.Log
import com.diegonmarcos.superapp.appstore.AppStoreHost
import com.diegonmarcos.superapp.appstore.ConstellationWorker
import com.diegonmarcos.superapp.appstore.FeedViewer
import com.diegonmarcos.superapp.appstore.FleetBearer
import com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge
import com.diegonmarcos.superapp.appstore.StoreDns
import com.diegonmarcos.superapp.apps.StoreShelves
import com.diegonmarcos.superapp.appstore.StoreDebugApi
import com.diegonmarcos.superapp.core.SilentChannels

/**
 * #865 Cloud Store: hosts libs:appstore as its own app.
 *
 * The store code lives once, in ab_cloud-libs-shared/libs/appstore, and
 * cloud-superapp hosts the same code. This class supplies only what a host
 * must: where a notification tap lands, which icon it uses, and the claim on
 * the unattended fleet pass. Cloud Store always runs that pass; SuperApp
 * stands down once this app is installed (see [AppStoreHost.runsFleetPass]).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Cloud Store's notifications are silent (libs:core SilentChannels): it posts on new "_silent_v2"
        // channels, and the old ones, whose sound Android will not let an app change, are deleted here,
        // before any service or worker of this process can post.
        runCatching { SilentChannels.retireLegacy(this) }.onFailure { Log.w(TAG, "old channels not retired", it) }
        AppStoreHost.apply {
            launchActivity = MainActivity::class.java
            notificationIcon = R.drawable.ic_stat_notify
            launchExtras = mapOf(MainActivity.EXTRA_TAB to MainActivity.TAB_CLOUD)
            // No DNS page of its own: a row that fails on name resolution
            // offers SuperApp's DNS page (the same extras SuperApp's App.kt
            // puts on its launcher), when SuperApp is installed.
            dnsPagePackage = "com.diegonmarcos.superapp"
            dnsPageExtras = mapOf("shortcut_action" to BuildConfig.DNS_HANDOFF_ACTION)
            runsFleetPass = { true }
            com.diegonmarcos.superapp.updater.UpdaterHost.apply {
                // #894 the pass summary and result alerts open Cloud Store itself, on the fleet tab.
                alertLink = "intent:#Intent;action=com.diegonmarcos.cloudstore.OPEN;package=com.diegonmarcos.cloudstore;S.tab=cloud;end"
                storeName = "Cloud Store"
            }
            // #866 the same shelves as SuperApp's Store pages (libs:appstore StoreShelves,
            // over the one taxonomy SuperApp's build.json declares).
            classify = StoreShelves::of
        }
        // #860/#866/#874 downloads resolve through the same StoreDns path as SuperApp:
        // this process's fleet DNS bridge. Cloud Store has no DNS page, so the bridge's
        // default plan applies: Mirror — Android's resolver on each underlying network,
        // then the uid's default.
        FleetDnsBridge.start(this, com.diegonmarcos.cloudlib.sysdns.BuildConfig.BRIDGE_PORT)
        StoreDns.apply {
            resolve = FleetDnsBridge::resolve
            resolverLabel = { FleetDnsBridge.label }
            tried = { FleetDnsBridge.lastFailure }
            forget = FleetDnsBridge::forget
        }
        StoreDns.start(this)
        com.diegonmarcos.superapp.devtools.AppDebugServer.dnsVia = { FleetDnsBridge.label }
        // #866 the fleet bearer for the Commits / CI-CD feeds: SuperApp's token over its
        // CONSTELLATION_DATA provider when SuperApp is installed, else the token typed
        // into this app's Settings tab. Read per request; never logged.
        FeedViewer.fleetBearer = { runCatching { FleetBearer.resolve(this) }.getOrDefault("") }
        // #1512 No shell channel of its own: Fleet.commit and the self-update install through the SuperApp's shell broker
        // (libs:shell-client, the stdin door), no prompt. Nothing to pair, re-arm or keep alive here, so Store has zero
        // background wakes for it.
        // Periodic fleet check + the Wi-Fi trigger + the auto chain, which
        // updates every constellation app (SuperApp included) and this one last.
        runCatching { ConstellationWorker.start(this) }
            .onFailure { Log.w(TAG, "fleet pass not scheduled", it) }
        // /api/store/... on the fleet debug server, as in SuperApp (#774).
        runCatching { StoreDebugApi.register(this) }
            .onFailure { Log.w(TAG, "store debug API not registered", it) }
        // An import a self-update (or any process death) cut short picks up where it stopped.
        runCatching { StoreDebugApi.resumePendingImport(this) }
            .onFailure { Log.w(TAG, "pending import not resumed", it) }
        // Search > Torrent hands its results to the torrent engine (libs:torrent) through this host; the engine itself
        // starts off the main thread, re-adds the remembered list, and keeps applying the seeding policy while no screen is open.
        // A device where libtorrent cannot load leaves the engine Unavailable with a reason: nothing here can crash the app.
        com.diegonmarcos.superapp.appstore.StoreTorrentHost.host = TorrentHost(this)
        Thread({
            runCatching {
                val engine = com.diegonmarcos.superapp.torrent.TorrentEngines.get(this)
                engine.start()
                engine.startPolling()
            }.onFailure { Log.w(TAG, "torrent engine not started", it) }
        }, "torrent-start").start()
    }

    private companion object { const val TAG = "CloudStore" }
}
