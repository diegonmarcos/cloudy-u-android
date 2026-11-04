package com.diegonmarcos.cloudsearch.data

import android.content.Context
import com.diegonmarcos.cloudsearch.BuildConfig
import com.diegonmarcos.cloudsearch.Decl
import com.diegonmarcos.cloudsearch.core.Cache
import com.diegonmarcos.cloudsearch.core.FleetEndpoint
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.SearchEngine
import com.diegonmarcos.cloudsearch.core.UrlHttp
import com.diegonmarcos.superapp.ai.AiFileSessions
import com.diegonmarcos.superapp.ai.AiHost
import java.io.File

/**
 * The app's one set of collaborators, shared by the screens and the debug API so a check on the
 * phone runs the same engine, cache and stores the user sees. Results are cached under filesDir
 * (not cacheDir, which the system may empty) so the last answers stay readable offline.
 */
class Services(ctx: Context, val http: Http) {
    /** The application context: the chat's token read and opening a result need one (#823 SearchAi). */
    val app: Context = ctx.applicationContext
    val cfg: SearchConfig = Decl.config
    val engine = SearchEngine(cfg, http, Cache(File(ctx.filesDir, "results")), System::currentTimeMillis)
    /** #1184 Cloud-CGC / Me-CGC: the fleet services behind them, addressed from the baked fleet declaration. */
    val fleet: FleetCalls by lazy {
        FleetCalls(http, FleetEndpoint.parse(String(android.util.Base64.decode(BuildConfig.FLEET_ENDPOINTS_B64, android.util.Base64.DEFAULT), Charsets.UTF_8))) { FleetBearer.token(app) }
    }
    val saved = SavedStore(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    val prefs = Prefs(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE), cfg)
    /** libs:ai's file store: the same chat-sessions.json and JSON shape the app always wrote. */
    val sessions = AiFileSessions(File(ctx.filesDir, "chat-sessions.json"))
    /** #823 the chat, hosted on libs:ai (SearchAi). */
    val ai: AiHost by lazy { SearchAi.host(this) }
    val models = ModelCatalog(cfg, http, Cache(File(ctx.filesDir, "catalog")), System::currentTimeMillis)
    /** #913 the Agents tab (draft-only agents, runs, reports); null when the app declares no agents. */
    private var agentService: AgentService? = null
    val agents: AgentService? get() = agentService ?: AgentService.from(this)?.also { agentService = it }

    /** Tests hand in an AgentService built on fakes. */
    fun installAgents(a: AgentService?) { agentService = a }

    companion object {
        const val PREFS = "cloud_search"

        @Volatile private var instance: Services? = null

        fun get(ctx: Context): Services = instance ?: synchronized(this) {
            instance ?: Services(ctx.applicationContext, UrlHttp(Decl.config.userAgent)).also { instance = it }
        }

        /** Tests hand in a fake network; the next [get] builds on it. */
        fun install(s: Services?) { instance = s }
    }
}
