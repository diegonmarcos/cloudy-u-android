package com.diegonmarcos.superapp.headless

import android.content.Context
import android.os.Process
import android.os.PowerManager
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1498 the process-wide wiring of the "This App" engine: Configs values (the `browser_settings` prefs the Headless Browser section writes),
 * the dispatcher over the WebView engine, the per-run bearer token, the audit ring, the loopback listener and the CDP mode.
 * Everything starts on demand and [shutdown] stops it all (the service calls it when idle or when the screen goes dark).
 */
class HeadlessRuntime private constructor(private val app: Context) {
    private val auditPrefs = app.getSharedPreferences("headless_audit", Context.MODE_PRIVATE)
    private val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
    val engine = WebViewEngine(app)
    val gate = HeadlessTokenGate.generate()
    val dispatcher = HeadlessDispatcher(engine, screenOn = { pm.isInteractive }, audit = ::record)
    private var loopback: HeadlessLoopback? = null
    private var cdp: CdpRelay? = null
    @Volatile private var cdpArmedAt = 0L

    /** The Configs value of [key] (set by the host app through [settingsSource]; null = the declared default applies). */
    fun setting(key: String): String? = settingsSource?.invoke(key)
    private fun int(key: String): Int? = setting(key)?.toIntOrNull()
    private fun store() = HeadlessStore(app)

    private fun applyConfig() {
        dispatcher.maxSessions = HeadlessPolicy.clampSessions(int("headless_max_sessions"))
        dispatcher.idleMs = HeadlessPolicy.clampIdleMs(int("headless_idle_stop_s")?.times(1000L))
        dispatcher.callTimeoutMs = HeadlessPolicy.clampCallMs(int("headless_call_timeout_s")?.times(1000L))
    }

    fun route(): HeadlessRoute = HeadlessSelector.resolve({ setting(it) }, { k -> store().get(k) })

    /** A single command / run, routed to the selected backend. [path] is /command or /run. */
    fun execute(path: String, body: JSONObject): JSONObject {
        applyConfig()
        if (path == "/cdp") return if (body.optBoolean("on")) armCdp(true) else { disarmCdp(); JSONObject().put("ok", true) } // the loopback gate already checked the token
        val r = route()
        return when {
            !r.local -> HeadlessRemote.forward(r, path, body)
            path == "/run" -> dispatcher.run(body)
            else -> dispatcher.command(body)
        }
    }

    fun status(): JSONObject = JSONObject().put("ok", true).put("backend", route().backend.id).put("sessions", dispatcher.list())
        .put("max", dispatcher.maxSessions).put("loopbackPort", loopback?.port ?: JSONObject.NULL).put("cdp", cdp?.port ?: JSONObject.NULL)

    /** Start the loopback listener (127.0.0.1 only); the token is the per-run bearer. */
    @Synchronized fun startLoopback(): JSONObject {
        val l = loopback ?: HeadlessLoopback(gate, ::execute, { dispatcher.list() }, { dispatcher.open() }).also { it.start(int("headless_http_port") ?: 8765); loopback = it }
        return JSONObject().put("ok", true).put("host", "127.0.0.1").put("port", l.port).put("token", gate.token)
    }

    /** Opt-in DevTools/CDP: refused unless the Configs switch is on AND the caller holds the token. Self-expires after the configured minutes. */
    @Synchronized fun armCdp(tokenOk: Boolean): JSONObject {
        val on = setting(CdpPolicy.KEY_ON) == "true"
        if (!CdpPolicy.mayArm(tokenOk, on)) return HeadlessApi.fail("denied", if (!on) "DevTools/CDP is off in Configs" else "token required")
        val port = CdpPolicy.clampPort(int(CdpPolicy.KEY_PORT))
        com.diegonmarcos.superapp.headless.CdpRelay.enableDebugging()
        val r = cdp ?: CdpRelay(gate, CdpPolicy.socketName(Process.myPid())).also { it.start(port); cdp = it }
        cdpArmedAt = System.currentTimeMillis()
        return JSONObject().put("ok", true).put("host", "127.0.0.1").put("port", r.port).put("socket", CdpPolicy.socketName(Process.myPid()))
            .put("minutes", CdpPolicy.clampMinutes(int(CdpPolicy.KEY_MINUTES))).put("hint", "playwright.chromium.connectOverCDP('http://127.0.0.1:${r.port}', {headers:{Authorization:'Bearer <token>'}})")
    }

    @Synchronized fun disarmCdp() { cdp?.stop(); cdp = null; cdpArmedAt = 0; runCatching { WebView.setWebContentsDebuggingEnabled(false) } }

    /** Called by the service's sweep: closes idle sessions, auto-offs CDP. True when the service has nothing left to do. */
    fun sweep(): Boolean {
        applyConfig()
        dispatcher.sweep()
        if (cdp != null && CdpPolicy.expired(System.currentTimeMillis(), cdpArmedAt, CdpPolicy.clampMinutes(int(CdpPolicy.KEY_MINUTES)))) disarmCdp()
        return cdp == null && HeadlessPolicy.serviceShouldStop(System.currentTimeMillis(), dispatcher.lastActivity, dispatcher.open(), dispatcher.idleMs)
    }

    @Synchronized fun shutdown() {
        dispatcher.closeAll()
        disarmCdp()
        loopback?.stop(); loopback = null
        engine.shutdown()
    }

    fun storeSet(key: String, value: String) { store().set(key, value) }

    private fun record(e: HeadlessAudit.Entry) = synchronized(auditPrefs) {
        auditPrefs.edit().putString(KEY, HeadlessAudit.toJson(HeadlessAudit.add(HeadlessAudit.fromJson(auditPrefs.getString(KEY, null)), e))).apply()
    }

    companion object {
        const val KEY = "audit_json"
        /** Installed by the host app (Cloud-Browser: BrowserSettings over the declared catalogue) before any call; the lib never opens the settings prefs itself. */
        @Volatile var settingsSource: ((String) -> String?)? = null
        @Volatile private var inst: HeadlessRuntime? = null
        fun get(ctx: Context): HeadlessRuntime = inst ?: synchronized(this) { inst ?: HeadlessRuntime(ctx.applicationContext).also { inst = it } }
        /** The audit ring, for Domains > History (tagged "agent"). */
        fun audit(ctx: Context): List<HeadlessAudit.Entry> = HeadlessAudit.fromJson(ctx.applicationContext.getSharedPreferences("headless_audit", Context.MODE_PRIVATE).getString(KEY, null))
    }
}
