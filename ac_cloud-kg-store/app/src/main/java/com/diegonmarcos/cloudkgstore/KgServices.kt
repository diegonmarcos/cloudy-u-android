package com.diegonmarcos.cloudkgstore

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.diegonmarcos.cloudsearch.core.Cache
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.SearchEngine
import com.diegonmarcos.cloudsearch.core.UrlHttp
import com.diegonmarcos.superapp.fleetdata.FleetData
import java.io.File

/**
 * The fleet bearer for the public fallback route, read from Cloud Account's `fleet.bearer` secret (behind the grant the owner
 * gives this package in Account > Secrets) on each use and never stored, logged or echoed. Empty = no sign-in: a public route is
 * then skipped with that reason, the mesh route is still tried.
 */
object KgBearer {
    private const val ACCOUNT_AUTHORITY = "com.diegonmarcos.cloudaccount.accountdata"

    /** Tests replace the provider read. */
    @Volatile var reader: (Context) -> String = { read(it) }

    fun token(ctx: Context): String? = reader(ctx).takeIf { it.isNotBlank() }

    private fun read(ctx: Context): String = runCatching {
        ctx.contentResolver.call(Uri.parse("content://$ACCOUNT_AUTHORITY"), "secret", "fleet.bearer", null)
    }.getOrNull()?.takeIf { it.getBoolean("ok") }?.getString("value").orEmpty().trim()
}

/**
 * The app's one set of collaborators: the page declaration (runtime dataset kg), Cloud Search's declaration run by its linked core
 * (the same engine and cache shape Cloud Search uses, here under filesDir/results), and the calls to the fleet services.
 */
class KgServices(ctx: Context, val http: Http? = null) {
    val app: Context = ctx.applicationContext
    /** #1547 the page declaration is the runtime dataset `kg` served by Cloud Store, never an APK asset; [KgConfig.EMPTY] until it is served. */
    @Volatile private var served: KgConfig? = null
    val kg: KgConfig get() = served ?: KgConfig.EMPTY

    /** Reads the dataset when not yet held (blocking, a few ms; call off the main thread when retrying). True once served; null is never cached. */
    fun refresh(): Boolean {
        if (served == null) served = runCatching { FleetData.get(app, KgConfig.DATASET, own = true) { KgConfig.parse(it) } }.getOrNull()
        return served != null
    }
    /** Reads the dataset again (blocking; off the main thread) and keeps the newer copy when it parses: a section added to kg.json reaches a running app. */
    fun reload(): KgConfig {
        runCatching { FleetData.get(app, KgConfig.DATASET, own = true) { KgConfig.parse(it) } }.getOrNull()?.let { served = it }
        return kg
    }
    val search: SearchConfig = SearchConfig.parse(text(BuildConfig.SEARCH_CONFIG_B64))
    private val net: Http = http ?: UrlHttp(search.userAgent)
    val engine = SearchEngine(search, net, Cache(File(app.filesDir, "results")), System::currentTimeMillis)
    val apps: List<KgApp> = KgApp.parse(text(BuildConfig.FLEET_APPS_B64))
    val ports: Map<String, Int> = KgApps.ports(text(BuildConfig.DEBUG_PORTS_B64))
    private val fleetServices = KgService.parse(text(BuildConfig.FLEET_SERVICES_B64))
    private var callsServed: KgCalls? = null
    val calls: KgCalls get() = callsServed ?: KgCalls(net, fleetServices, search.cgc, kg.timeoutMs, kg.mcpPath) { KgBearer.token(app) }.also { if (served != null) callsServed = it }

    init { refresh() }

    /** Opens [url] in the user's browser (the fleet's, else any). */
    fun open(url: String) {
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    companion object {
        @Volatile private var instance: KgServices? = null

        fun get(ctx: Context): KgServices = instance ?: synchronized(this) { instance ?: KgServices(ctx).also { instance = it } }

        private fun text(b64: String) = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
    }
}
