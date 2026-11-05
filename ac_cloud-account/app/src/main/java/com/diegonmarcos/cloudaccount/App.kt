package com.diegonmarcos.cloudaccount

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.util.Log
import com.diegonmarcos.superapp.appstore.StoreImport
import com.diegonmarcos.superapp.profile.AccountDebugApi
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.profile.AccountMigrate
import com.diegonmarcos.superapp.profile.FleetEndpointsRuntime
import com.diegonmarcos.superapp.settings.AccountVault

/**
 * #867 Cloud Account: hosts libs:account as its own app.
 *
 * The Account code lives once, in ab_cloud-libs-shared/libs/account, and cloud-superapp hosts the
 * same code. This class supplies only what a standalone host must: where the page's links go.
 * There is no launcher here, so no launcher palette, tab chrome, icon table or haptics (the
 * library's defaults stand) and no mesh tunnel (the Fleet tab reports it as not available).
 * This app OWNS the data, so the read-through to another host stays off.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AccountHost.apply {
            route = { activity, route -> open(activity, route) }
            readThrough = false
            // Redesign task 3: the debug API's `tabs` op answers this app's five islands and their pages.
            nav = { navJson() }
            autoBackupChanged = { ctx -> kotlin.concurrent.thread(name = "account-backup") { AccountForeground.backupIfDue(ctx) } }   // #1502 foreground only, no job
            // #1093 the fleet endpoint declaration (build.json::fleet_endpoints): this app's own mesh-first transport and
            // Connect > Status, and what Account publishes to the other apps (Cloud Mail reads its kind=mail services, #1090).
            fleetEndpointsDecl = { String(android.util.Base64.decode(BuildConfig.FLEET_ENDPOINTS_B64, android.util.Base64.DEFAULT)) }
        }
        // #874 the vault's Secrets section is enforced by this app's setup provider (`<package>.fleetsetup`) and by
        // AccountData.Provider: install the grant-aware authorizer and seed the defaults the fleet relied on, once.
        runCatching { AccountVault.install(this) }.onFailure { Log.w(TAG, "vault grants not installed: ${it.javaClass.simpleName}") }
        // #1093 register every declared pair on the shared transport (mesh first, then public + bearer) and re-probe on a network change.
        runCatching { FleetEndpointsRuntime.start(this) }.onFailure { Log.w(TAG, "fleet endpoints not registered: ${it.javaClass.simpleName}") }
        // #874 take in what SuperApp kept (Dagu bearer, DNS preset, WireGuard tunnel, mail login), only where the vault is empty.
        kotlin.concurrent.thread(name = "account-migrate") {
            runCatching { AccountMigrate.run(this) }.onFailure { Log.w(TAG, "migration from SuperApp failed: ${it.javaClass.simpleName}") }
        }
        // A legacy device id renamed by a declared alias (galaxy -> galaxy-s21) is migrated now, before any page draws it.
        runCatching { com.diegonmarcos.superapp.profile.AccountDevice.migrateAtStart(this) }
            .onFailure { Log.w(TAG, "device alias migration failed: ${it.javaClass.simpleName}") }
        // #1512 No shell channel of its own: the runbook's shell and store steps run through the SuperApp's shell broker
        // (libs:shell-client). Nothing to arm, keep alive or re-arm at boot here.
        // /api/account/... on the fleet debug server, as in SuperApp.
        runCatching { AccountDebugApi.register(this) }
            .onFailure { Log.w(TAG, "account debug API not registered", it) }
        // Settings ▸ Debug API off keeps the loopback server stopped (it is started by DebugInitProvider).
        runCatching { com.diegonmarcos.superapp.profile.DebugApiSwitch.apply(this) }
        // #1502 the Autocomplete pull and the auto-backup run from MainActivity (foreground) only; the two periodic jobs an older
        // build persisted are cancelled here, so a process start by one of them is the last.
        AccountForeground.retireLegacyJobs(this)
    }

    companion object {
        private const val TAG = "CloudAccount"

        /** build.json::ui.bottom_nav + ui.sections as baked: [{id, label, pages:[{id, label}]}]. */
        fun navJson(): org.json.JSONArray = org.json.JSONArray().also { a ->
            MainActivity.NAV.bottomSections().forEach { s ->
                a.put(org.json.JSONObject().put("id", s.id).put("label", s.label)
                    .put("pages", org.json.JSONArray(s.pages.map { p -> org.json.JSONObject().put("id", p.id).put("label", p.label) })))
            }
        }
        private const val SUPERAPP = "com.diegonmarcos.superapp"
        private const val STORE = "com.diegonmarcos.cloudstore"
        private val PEERS = mapOf(
            "cloud-drive" to "com.diegonmarcos.clouddrive",
            "cloud-c3" to "com.diegonmarcos.cloudc3",
            "cloud-wallet" to "com.diegonmarcos.cloudwallet",
        )
        /** SuperApp's Store page (a launcher route, not one of this app's ui.sections). */
        private const val STORE_PAGE = "config/store"

        /**
         * Account links to launcher routes (`page:…`, `section:…`, `extapp:…`). With no launcher
         * here: the Store routes go to Cloud Store when it is installed; any other route opens
         * SuperApp, which owns the pages. False when neither is installed, and Account then says so.
         */
        fun open(activity: Activity, route: String): Boolean = runCatching {
            val pm = activity.packageManager
            // #1081 Backups opens Cloud Drive, Configs ▸ C3 opens Cloud C3, Wallet opens Cloud Wallet: each its own app.
            PEERS.entries.firstOrNull { route.startsWith("extapp:${it.key}") }?.let { peer ->
                pm.getLaunchIntentForPackage(peer.value)?.let { activity.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@runCatching true }
                return@runCatching false
            }
            val store = route.startsWith("extapp:cloud-store") || route.removePrefix("page:").startsWith(STORE_PAGE)
            val intent = if (store && pm.getLaunchIntentForPackage(STORE) != null)
                Intent("$STORE.OPEN").setPackage(STORE).putExtra("tab", if (route.endsWith("store-phone")) "phone" else "cloud")
                    // #570 Fleet ▸ Apps ▸ Apply list to Store: the declared inventory crosses to Cloud Store's Phone page.
                    .putExtra(StoreImport.EXTRA_IMPORT, StoreImport.takePending())
            else pm.getLaunchIntentForPackage(SUPERAPP)
            if (intent == null) false else { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
        }.getOrDefault(false)
    }
}
