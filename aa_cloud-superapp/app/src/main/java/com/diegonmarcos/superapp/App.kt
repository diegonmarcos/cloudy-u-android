package com.diegonmarcos.superapp
import com.diegonmarcos.superapp.system.Trace
import com.diegonmarcos.superapp.system.CrashLogger
import com.diegonmarcos.superapp.system.AppProcessUptime
import com.diegonmarcos.superapp.battery.PowerStateReceiver
import com.diegonmarcos.superapp.battery.BatterySessionWorker

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Configuration as WorkManagerConfiguration
import com.diegonmarcos.superapp.devcontrol.DevControlServer
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.google.android.material.color.DynamicColors

/**
 * Application entry point — runs BEFORE any Activity. Wires:
 *  • Trace + CrashLogger (capture inflation/onCreate failures)
 *  • Material 3 DynamicColors — on Android 12+ the theme adapts to the
 *    system wallpaper palette. Older devices fall back to the brand
 *    palette declared in colors.xml + themes.xml.
 *  • Force night-mode — the app's whole visual identity is dark
 *    purple→black gradient, and the system long-press tooltip pill
 *    picks `tooltip_frame_dark` (black bg, white text) instead of
 *    `tooltip_frame_light` (white bg) when night mode is on. Same
 *    knob fixes the pill colour without us re-implementing it.
 */
class App : Application(), WorkManagerConfiguration.Provider {
    /**
     * On-demand WorkManager initialization. The vendored HeliBoard
     * (libs:keyboard) merge drops androidx.startup's auto-init
     * `WorkManagerInitializer`, so the default provider never initializes
     * WorkManager — every `WorkManager.getInstance(...)` then throws
     * "WorkManager is not initialized properly". That crashed the launcher at
     * MainActivity → Updater.start, and silently broke
     * BatterySessionWorker.schedule (swallowed by its runCatching).
     *
     * Implementing Configuration.Provider + removing the default initializer in
     * the manifest is the canonical on-demand setup: the FIRST getInstance()
     * call lazily initializes WorkManager with this config. Repairs both the
     * updater and the battery worker, deterministically, regardless of the
     * manifest-merge outcome.
     */
    override val workManagerConfiguration: WorkManagerConfiguration
        get() = WorkManagerConfiguration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        // Force night mode BEFORE super so AppCompatDelegate picks it up
        // on the very first inflation.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate()
        // #1728 the X-Ray App > Performance benchmarks of this app
        runCatching { com.diegonmarcos.superapp.xray.SuperAppBenchmarks.register() }
        // #1693 the Mattermost login and password are typed in Cloud Account (App credentials): the chat page reads them over the provider.
        com.diegonmarcos.superapp.chat.ChatCredentials.read = { c, id ->
            val x = com.diegonmarcos.superapp.fleetendpoints.android.AccountClient.credential(c, id)
            com.diegonmarcos.superapp.chat.ChatCredentials.Cred(x.value, x.line)
        }
        // #1693 the Dagu bearer token likewise.
        com.diegonmarcos.superapp.ops.OpsCredentials.read = { c, id ->
            val x = com.diegonmarcos.superapp.fleetendpoints.android.AccountClient.credential(c, id)
            com.diegonmarcos.superapp.ops.OpsCredentials.Cred(x.value, x.line)
        }
        com.diegonmarcos.superapp.ops.OpsCredentials.open = { c -> com.diegonmarcos.superapp.fleetendpoints.android.AccountClient.openCredentials(c) }
        com.diegonmarcos.superapp.chat.ChatCredentials.open = { c -> com.diegonmarcos.superapp.fleetendpoints.android.AccountClient.openCredentials(c) }
        // #1682 P3 step 5: every ADB OWNER hook (channel auto-arm, grant runner, keeper / plane hosts, readiness owner half, Connect chain,
        // in-frame opener) lives behind ONE facade; the client build (adb-owner.json names another app) installs none of them.
        // the in-frame ADB page no longer exists in any build: every "open the ADB Shell" goes to the owner (AdbShellLink / AdbReadiness)
        com.diegonmarcos.superapp.adbside.AdbSide.install(this) { _ -> false }
        // extapp: install progress — listening before any resumed install worker publishes.
        com.diegonmarcos.superapp.extinstall.ExtAppInstalls.init(this)
        // The island's haptics are the lib's; "Vibration on tap" (Configs -> Launcher -> Others) still gates them.
        com.diegonmarcos.superapp.bottomnav.FleetHaptics.enabled = { ctx ->
            runCatching { com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(ctx).toggle("haptics") }.getOrDefault(true)
        }
        // #841 the Store's Commits / CI-CD feeds read the fleet git-proxy with the
        // SAME Authelia bearer every other fleet call here sends (libs:ops
        // DaguPrefs, as OpsClient does). Read lazily per request; never logged.
        com.diegonmarcos.superapp.appstore.FeedViewer.fleetBearer = {
            runCatching { com.diegonmarcos.superapp.ops.dagu.DaguPrefs(this).bearerToken }.getOrDefault("")
        }
        com.diegonmarcos.superapp.adbside.AdbSide.installBoot(this)

        // #1120 The mesh is connected by default and reconnects until the owner taps Disconnect.
        runCatching { com.diegonmarcos.superapp.network.MeshSupervisorRunner.start(this) }

        // #1693 the profile and its server sync are Cloud Account's (libs:account is its own now); Cloud Account
        // retries a profile edit that never reached the server, so this app queues and flushes nothing.

        // #751 The DNS choice holds without the mesh: the WireGuard engine carries
        // it in the VPN slot, but nothing starts the engine after a reboot or a
        // killed process, so the launcher's own start hands it back. Only for an
        // explicit choice, and never over a running firewall (it keeps the slot).
        // #794 ...and then CHECKS it took: a choice Android does not resolve with
        // is never silent. Missing VPN consent (never given, or dropped by an
        // engine reinstall) raises the alert that opens the DNS page's one-tap
        // consent; an engine update or reinstall re-runs the same check, since
        // it kills the process that held the DNS-only tunnel.
        val dnsCheck = Runnable {
            runCatching {
                com.diegonmarcos.superapp.network.FleetDns.syncAndCheck(this,
                    raiseNow = !com.diegonmarcos.superapp.firewall.FirewallController.isEnabled(this))
            }.onFailure { android.util.Log.w("App", "fleet DNS not handed to the engine or not checked", it) }
        }
        if (com.diegonmarcos.superapp.network.FleetDns.Prefs(this).chosen) Thread(dnsCheck).start()
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    if (i.data?.schemeSpecificPart == com.diegonmarcos.superapp.net.AidlBackend.ENGINE_PKG &&
                        com.diegonmarcos.superapp.network.FleetDns.Prefs(c).chosen) Thread(dnsCheck).start()
                }
            }, android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
                addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
                addDataScheme("package")
            }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        // #1506 Background policy: PACKAGE_REPLACED re-applies the chosen policy to that package once (no service, no retry).
        com.diegonmarcos.superapp.bgpolicy.BgReplacedReceiver.register(this)
        // #1506 Super Saving: when the mode is on, the screen-off doze hook and the Off notification come back with the process.
        com.diegonmarcos.superapp.bgpolicy.SuperSavingRuntime.onAppStart(this)

        // Tell libs:appstore what it cannot know: this app's entry Activity,
        // its notification icon, how this launcher routes a tap, and the
        // host-side toggle that gates the periodic check. The store moved out
        // of the app, so these are the host's to supply.
        com.diegonmarcos.superapp.appstore.AppStoreHost.apply {
            launchActivity = MainActivity::class.java
            notificationIcon = R.drawable.ic_stat_notify
            launchExtras = mapOf("shortcut_action" to "page:config/store-cloud")
            // #831 a DNS failure on a Store row opens the same page the DNS alert does.
            dnsPageExtras = mapOf("shortcut_action" to "page:config/dns")
            // #563: both Store pages group by the launcher's own taxonomy.
            classify = com.diegonmarcos.superapp.apps.StoreShelves::of
            periodicCheckAllowed = { ctx ->
                com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(ctx).toggle("fleet_check")
            }
            // #894 Cloud Store runs the unattended fleet pass and updates SuperApp with the rest of
            // the fleet. SuperApp never does, installed or not (StoreNotifyGate holds the same line
            // where a notification would be posted).
            runsFleetPass = { false }
        }
        // #1693 libs:account (the Account pages, their host hooks and the one-shot copy of Cloud Account's stores) is
        // Cloud Account's alone: this app links none of it and reads what it needs over Cloud Account's provider (AccountClient).
        // #874 ONE resolver for this process: the fleet DNS bridge (libs:sysdns),
        // whose routes are the DNS page's preset read by FleetDns. The Store's
        // downloads and check (libs:appstore StoreDns, shared with Cloud Store),
        // the DNS page's Test and /api/net/dns all go through it, so a failure
        // names the bridge and every route it tried.
        val dnsCtx = applicationContext
        com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge.apply {
            routes = { name -> com.diegonmarcos.superapp.network.FleetDns.bridgeRoutes(dnsCtx, name) }
            timeoutMs = com.diegonmarcos.superapp.network.FleetDns.decl.timeoutMs
            start(dnsCtx, com.diegonmarcos.cloudlib.sysdns.BuildConfig.BRIDGE_PORT)
        }
        com.diegonmarcos.superapp.appstore.StoreDns.apply {
            resolve = com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge::resolve
            resolverLabel = { com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge.label }
            tried = { com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge.lastFailure }
            forget = com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge::forget
            meshResolve = { host -> com.diegonmarcos.superapp.network.FleetDns.meshResolve(dnsCtx, host) }
            networkSummary = com.diegonmarcos.superapp.network.FleetDns::resolverSummary
        }
        com.diegonmarcos.superapp.appstore.StoreDns.start(dnsCtx)
        com.diegonmarcos.superapp.devtools.AppDebugServer.dnsVia = { com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge.label }
        // The mesh leg's "down" is the tunnel's state, which only this app reads.
        com.diegonmarcos.superapp.updater.source.MeshMirror.meshUp = { c -> com.diegonmarcos.superapp.network.FleetDns.meshUp(c) }
        // Capture process-start time before anything else so About →
        // Battery & Usage can report the real uptime.
        AppProcessUptime.initOnce()
        // #1649 X-Ray App engine profiler: frame durations, page / activity open times and the foreground flag, from the platform.
        runCatching { com.diegonmarcos.superapp.xray.XrayLifecycleProbe.install(this) }
        // Fleet token is DECLARATIVE: BuildConfig.FLEET_TOKEN is baked from the
        // sops secret (build.sh exports SUPERAPP_FLEET_TOKEN from the vault, the
        // same value the cloud-superapp-mcp env is rendered with). Seed it into
        // the slot DevControlPrefs/FleetToken read, BEFORE DevControlServer.start
        // captures the token, so there is no random per-install token and no
        // "regenerate". Empty (unsigned dev build) falls back to the adopt/mint
        // path unchanged.
        runCatching {
            val declared = BuildConfig.FLEET_TOKEN
            if (declared.isNotBlank())
                com.diegonmarcos.superapp.devtools.DevControlPrefs(this).adopt(declared)
        }
        // DevControlServer FIRST so even if anything downstream
        // crashes I can still curl /logcat / /trace / /crashes from
        // this device's shell to debug.
        runCatching { DevControlServer.start(this) }
        runCatching { Trace.install(this) }
        runCatching { CrashLogger.install(this) }
        runCatching { DynamicColors.applyToActivitiesIfAvailable(this) }
        // #515: `detectVersionBump()` used to run here and push an
        // "Updated to vc:N" line into core.NotificationStore. It was the
        // SECOND entry for an event that already had a producer —
        // PackageInstallerReceiver.surface() records every install result
        // into the same store, under the same source "Updater", from the
        // install callback that actually knows the outcome. See the
        // deleted function's rationale in
        // a0_docs/eng-specs/superapp-notification-centre-duplicate.md.
        // #515: this used to be `KdeStatusService.start(this)` — ONE service,
        // named by hand. That hand-written line is why the KDE badge was the
        // only one of Diego's three still standing after an update: it was the
        // only badge with any restart path at all. Quickmarks, Media and Alerts
        // are all owned by FloatingNavService, which nothing here started.
        //
        // Now the declaration decides. ensureAll re-ensures every producer
        // build.json::ui.notification_center marks persistent, which is the
        // same set BadgeRestartReceiver ensures on MY_PACKAGE_REPLACED /
        // BOOT_COMPLETED — one list, one mechanism, cold start and update
        // converging on the same services running.
        runCatching { BadgeServices.ensureAll(this) }
        // #775: /api/overlays — what is drawn vs. what each switch says.
        runCatching { com.diegonmarcos.superapp.floatingnav.OverlaysDebugApi.register(this) }
        // Diagnostics (Configs ▸ Observability ▸ Diagnostics) + its remote read /api/diagnostics/{get,screenshot,open,inject};
        // the lifecycle callback also re-ensures the edge strips on ANY activity resume (an update kills the service).
        runCatching { com.diegonmarcos.superapp.configs.Diagnostics.install(this) }
        runCatching { com.diegonmarcos.superapp.configs.Diagnostics.register(this) }
        // #777: /api/notify/{groups,alerts} — the four shade groups and the fleet alerts.
        runCatching { com.diegonmarcos.superapp.notificationcenter.NotifyDebugApi.register(this) }
        // #777: the Alerts group is drawn from the store, so it comes back
        // (silently) after a reboot or an update cleared the shade.
        runCatching { com.diegonmarcos.superapp.notificationcenter.AlertsNotifier.refresh(this) }
        // #894 The Store badge (#812) is gone with every other Store / update notification:
        // Cloud Store is the only app that posts them. What an older build left (queued update
        // work, its notifications and channels, collected Store alerts) is retired here.
        runCatching { com.diegonmarcos.superapp.notificationcenter.StoreRetirement.run(this) }
        // #1693 /api/account/* is served by Cloud Account's own debug API (libs:account), not here.
        // #794: /api/net/dns/overview — the DNS page as JSON: preset in effect or not, every app's path, every server.
        runCatching { com.diegonmarcos.superapp.network.DnsOverview.register(this) }
        // #ipc the SuperApp bridge (adb/exec) over the fleet IPC layer: terminals and Drive call it.
        com.diegonmarcos.superapp.adbside.AdbSide.registerBridge(this)
        // Schedule the battery SoT's low-frequency tick (15 min cadence):
        // one history sample even when the SuperApp is backgrounded or
        // killed and no battery surface is open (BatteryRepository.record).
        // Idempotent — the KEEP policy makes re-scheduling a no-op.
        runCatching { BatterySessionWorker.schedule(this) }
        // The plug edges (charge-cycle boundaries, #1381): Android 8+ does not deliver them to the manifest entry.
        runCatching { PowerStateReceiver.register(this) }
        // Constellation AppStore — periodic fleet check across every
        // constellation APK (Configs → Constellation). Notifies when siblings
        // have GHCR updates; install stays user-initiated. Idempotent (KEEP).
        runCatching { com.diegonmarcos.superapp.appstore.ConstellationWorker.start(this) }
        // #774 /api/store/{cache,stage,download,install,clear,auto} on the fleet
        // debug server — the Store's stages, verifiable with the screen locked.
        runCatching { com.diegonmarcos.superapp.appstore.StoreDebugApi.register(this) }
        // #1093 the fleet endpoints: pull the pairs Cloud Account publishes (grants fleet.endpoints + fleet.bearer) and put the
        // shared transport behind the seam, so the libraries below it (Telemetry, the updater/news/mail calls) go mesh first.
        runCatching { com.diegonmarcos.superapp.fleetendpoints.android.FleetBoot.start(this) }
        // HeliBoard (libs:keyboard) is vendored WITHOUT its own Application —
        // our .App wins the manifest merge (tools:replace android:name), so the
        // keyboard's app-level init never ran. That left Settings /
        // SubtypeSettings.prefs null → Configs→Keyboard (SettingsActivity) AND
        // LatinIME crashed with "parameter prefs is null". Replicate HeliBoard
        // App.onCreate's synchronous init here so both work.
        Trace.i("App", "Application.onCreate done — pid=${android.os.Process.myPid()}")
    }


}
