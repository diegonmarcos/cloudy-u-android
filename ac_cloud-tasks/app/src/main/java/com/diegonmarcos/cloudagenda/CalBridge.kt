package com.diegonmarcos.cloudagenda

import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.webkit.JavascriptInterface
import com.diegonmarcos.cloudagenda.agents.AgentStore
import com.diegonmarcos.cloudagenda.agents.DriveScreenshotFeed
import com.diegonmarcos.cloudagenda.agents.ScreenshotFeed
import com.diegonmarcos.superapp.core.DataBackendClient
import androidx.security.crypto.EncryptedSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * JS bridge exposed to tasks.html as `window.CalBridge`.
 *
 * SECURITY: this bridge is only safe to attach because the WebView it
 * is registered on loads a LOCAL asset we control
 * (file:///android_asset/tasks.html) — see MainActivity. Never
 * attach a JavascriptInterface bridge to a WebView that can navigate
 * to remote/attacker-controlled content: any page loaded there would
 * gain the same Java-callable surface as our own UI.
 */
class CalBridge(private val ctx: Context) {

    private val executor = Executors.newSingleThreadExecutor()

    // ── Agents ───────────────────────────────────────────────────────────────
    // The screenshot agent reads Cloud Drive's screenshot records through
    // [ScreenshotFeed]; the page parses them (assets/agents.js) and the
    // decisions the user takes on the Invites tab land in [AgentStore].
    private val agents = AgentStore(ctx)
    private val screenshotFeed: ScreenshotFeed = DriveScreenshotFeed(ctx)

    private companion object {
        /** The engine CONTRACT meta-data key (libs/cal's manifest). */
        const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"
        /** The engine method that takes the one-time handover (CalBackendService). */
        const val SEED = "seed"
        /** The calendar id accepted screenshot events carry. */
        const val AGENTS_CALENDAR = "agents-screenshots"
        /** #1693 the App credentials id of the CalDAV password (Cloud Account holds it). */
        const val CALDAV_CREDENTIAL = "tasks.caldav_password"
    }

    @Volatile private var syncRunning = false
    @Volatile private var lastOk = 0
    @Volatile private var lastSkipped = 0
    @Volatile private var lastFailed = 0
    @Volatile private var lastMessages: List<String> = emptyList()

    @Volatile private var todoSyncRunning = false
    @Volatile private var todoLastOk = 0
    @Volatile private var todoLastFailed = 0
    @Volatile private var todoLastMessages: List<String> = emptyList()

    // ── the engine ───────────────────────────────────────────────────────────
    // Calendar data work lives in Cloud-Lib-Cal.apk; this class is the front
    // end, and libs:cal is in no module map of this app. Credentials are NOT
    // sent there to live - they stay in this app's EncryptedSharedPreferences
    // and ride along on the calls that need them, so nothing is orphaned by
    // the engine having its own storage.
    @Volatile private var client: DataBackendClient? = null

    /**
     * THE HANDSHAKE, BEFORE ANY BIND (engine-apk-split F2; the GhEngine shape): PackageManager
     * resolves the declared action in the declared package (build.json::engines.cal, resolved
     * from the fleet manifest at build time and queried in the manifest, so Android 11+ shows it)
     * and reads the CONTRACT it declares. Null when the engine is ready, else the sentence that
     * says what to do — "install" and "update" are different next steps.
     */
    private fun check(): String? {
        val pm = ctx.packageManager
        val pkg = BuildConfig.CAL_ENGINE_PACKAGE
        val needed = BuildConfig.CAL_ENGINE_MIN_CONTRACT
        val service = pm.resolveService(Intent(BuildConfig.CAL_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
            ?.serviceInfo
        if (service == null) {
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            return if (installed) "$pkg has no calendar engine service — update it from Store ▸ Cloud Constellation ▸ Libs"
                   else "$pkg is not installed — install it from Store ▸ Cloud Constellation ▸ Libs"
        }
        val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
        if (found < needed) return "$pkg serves contract $found, this build needs $needed — update it from Store ▸ Cloud Constellation ▸ Libs"
        if (client == null) synchronized(this) {
            if (client == null) client = DataBackendClient(ctx, service.packageName, service.name)
        }
        return null
    }

    /** Every engine call: the handshake, the one-time seed, then the call. A not-ready engine
     *  answers {"error": why} — the shape every page call already shows. */
    private fun ask(method: String, vararg args: String): String {
        val why = check()
        val c = client
        if (why != null || c == null) return JSONObject().put("error", why ?: "the calendar engine is not ready").toString()
        if (method != SEED) seedOnce()
        return c.call(method, *args)
    }

    private fun cfgJson(): String = CaldavPrefs.getJson(ctx) ?: ""

    /**
     * Hand the engine the tasks a re-sync cannot rebuild, exactly once.
     *
     * TodoStore moved into the engine's private storage with it. Almost all of
     * it re-syncs; a task created offline and never PUT (blank href) does not.
     * Guarded by a pref AND by the engine's own hasData, so a reinstall of
     * either side cannot double-seed or clobber newer data.
     */
    @Volatile private var seeded = false
    @Synchronized
    private fun seedOnce() {
        if (seeded) return
        seeded = true
        val prefs = ctx.getSharedPreferences("cal_bridge", Context.MODE_PRIVATE)
        if (prefs.getBoolean("seeded", false)) return
        val legacy = ctx.getSharedPreferences("cal_legacy_todos", Context.MODE_PRIVATE)
            .getString("todos", null)
        if (legacy.isNullOrBlank()) { prefs.edit().putBoolean("seeded", true).apply(); return }
        val ok = runCatching { JSONObject(ask(SEED, legacy)).optBoolean("ok") }
            .getOrDefault(false)
        // Only mark it done when it actually landed - a failed seed must be
        // retried, not silently skipped forever.
        if (ok) prefs.edit().putBoolean("seeded", true).apply()
    }

    @JavascriptInterface
    fun calendars(): String {
        val raw = ask("calendars")
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return raw
        arr.put(JSONObject()
            .put("id", AGENTS_CALENDAR).put("name", "Accepted from screenshots")
            .put("category", "agents").put("color", "#7E57C2").put("enabled", true)
            .put("lastSync", agents.lastScan))
        return arr.toString()
    }

    // fromUtcMillis/toUtcMillis are taken as String and parsed to Long here:
    // JS numbers passed over the WebView JS bridge are doubles, which lose
    // precision for 64-bit epoch-millis values, so callers must stringify
    // them before passing across the bridge.
    @JavascriptInterface
    fun events(fromUtcMillis: String, toUtcMillis: String): String {
        val raw = ask("events", fromUtcMillis, toUtcMillis)
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return raw
        val from = fromUtcMillis.toLongOrNull() ?: Long.MIN_VALUE
        val to = toUtcMillis.toLongOrNull() ?: Long.MAX_VALUE
        val accepted = agents.accepted()
        for (i in 0 until accepted.length()) {
            val e = accepted.optJSONObject(i) ?: continue
            val start = e.optLong("start"); val end = e.optLong("end", start)
            if (end >= from && start <= to) arr.put(e)
        }
        return arr.toString()
    }

    // ======================================================================
    // Agents (screenshot events)
    // ======================================================================

    /** {enabled, lastScan, feedReady, feedReason, dismissed:[key], accepted:[event]} */
    @JavascriptInterface
    fun agentsState(): String {
        val reason = screenshotFeed.unavailableReason()
        return JSONObject()
            .put("enabled", agents.enabled)
            .put("lastScan", agents.lastScan)
            .put("feedReady", reason == null)
            .put("feedReason", reason ?: "")
            .put("dismissed", JSONArray(agents.dismissed().toList()))
            .put("accepted", agents.accepted())
            .toString()
    }

    /** The raw screenshot records (see [ScreenshotFeed]); stamps the scan time. */
    @JavascriptInterface
    fun agentsRecords(): String {
        if (!agents.enabled) return JSONArray().toString()
        val records = screenshotFeed.records()
        agents.lastScan = System.currentTimeMillis()
        return records.toString()
    }

    /** enabled is "true"/"false" (bridge string convention). */
    @JavascriptInterface
    fun agentsSetEnabled(enabled: String): String {
        agents.enabled = enabled == "true"
        return okErr(true, "")
    }

    @JavascriptInterface
    fun agentsDismiss(key: String): String {
        agents.dismiss(key)
        return okErr(true, "")
    }

    /** Accepts one candidate as an event: {key,title,location,start,end,allDay,description}. */
    @JavascriptInterface
    fun agentsAccept(json: String): String {
        return try {
            val o = JSONObject(json)
            val key = o.optString("key")
            val start = o.optLong("start", 0L)
            if (key.isBlank() || start <= 0L) return okErr(false, "the event needs a date")
            val event = JSONObject()
                .put("id", "agent-" + key.hashCode().toUInt().toString(16))
                .put("key", key)
                .put("calendarId", AGENTS_CALENDAR)
                .put("title", o.optString("title").ifBlank { "Event" })
                .put("description", o.optString("description"))
                .put("location", o.optString("location"))
                .put("start", start)
                .put("end", o.optLong("end", start).coerceAtLeast(start))
                .put("allDay", o.optBoolean("allDay", false))
            agents.accept(event)
            okErr(true, "")
        } catch (e: Exception) {
            okErr(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Takes an accepted event back out of the calendar; it will not be offered again. */
    @JavascriptInterface
    fun agentsRemove(key: String): String {
        agents.remove(key)
        return okErr(true, "")
    }

    @JavascriptInterface
    fun sync(): String {
        // Kick the engine off our own executor and keep the UI-state flags
        // here: "is a sync running" is what the spinner reads, and the engine
        // deliberately returns a report rather than tracking it.
        if (syncRunning) return JSONObject().put("started", false).toString()
        syncRunning = true
        executor.execute {
            try {
                val r = JSONObject(ask("sync"))
                lastOk = r.optInt("ok"); lastSkipped = r.optInt("skipped")
                lastFailed = r.optInt("failed")
                lastMessages = (0 until (r.optJSONArray("messages")?.length() ?: 0))
                    .map { r.optJSONArray("messages")!!.optString(it) }
            } finally { syncRunning = false }
        }
        return JSONObject().put("started", true).toString()
    }

    @JavascriptInterface
    fun syncStatus(): String {
        val messages = JSONArray()
        for (m in lastMessages) messages.put(m)
        return JSONObject().apply {
            put("running", syncRunning)
            put("ok", lastOk)
            put("skipped", lastSkipped)
            put("failed", lastFailed)
            put("messages", messages)
        }.toString()
    }

    // ======================================================================
    // ToDo / Projects (CalDAV VTODO)
    // ======================================================================

    /** projects() = the discovered VTODO collections (CalendarStore.eventsFor's
     *  cache-only-read pattern: this never touches the network — it reads
     *  whatever [TodoStore] last cached, so the tab renders instantly
     *  offline. Call [syncTodos] to refresh from the server. */
    @JavascriptInterface
    fun projects(): String {
        return ask("projects")
    }

    /** projectId "" means all projects. Cache-only read, same reasoning
     *  as [projects]. */
    @JavascriptInterface
    fun todos(projectId: String): String {
        return ask("todos", projectId)
    }

    /** Create (id empty) or update (id set) one task. This performs the
     *  CalDAV PUT synchronously and returns the real ok/error outcome —
     *  unlike [sync]/[syncStatus]'s fire-and-poll shape, the contract
     *  here is a single call returning a definitive result, so there is
     *  no way to satisfy it without waiting for the network round trip.
     *  JavascriptInterface calls already run off Android's main/UI
     *  thread (the WebView core thread), so this blocks only that JS
     *  call in the page, not app rendering — but it is still a
     *  network wait on the bridge thread, worth flagging explicitly. */
    @JavascriptInterface
    fun saveTodo(json: String): String {
        return ask("saveTodo", json, cfgJson())
    }

    /** done is "true"/"false" (bridge string convention, see [events]). */
    @JavascriptInterface
    fun setTodoStatus(id: String, done: String): String {
        return ask("setTodoStatus", id, done, cfgJson())
    }

    @JavascriptInterface
    fun deleteTodo(id: String): String {
        return ask("deleteTodo", id, cfgJson())
    }

    @JavascriptInterface
    fun syncTodos(): String {
        if (todoSyncRunning) return JSONObject().put("started", false).toString()
        todoSyncRunning = true
        executor.execute {
            try {
                val r = JSONObject(ask("syncTodos", cfgJson()))
                todoLastOk = r.optInt("ok"); todoLastFailed = r.optInt("failed")
                todoLastMessages = (0 until (r.optJSONArray("messages")?.length() ?: 0))
                    .map { r.optJSONArray("messages")!!.optString(it) }
            } finally { todoSyncRunning = false }
        }
        return JSONObject().put("started", true).toString()
    }

    @JavascriptInterface
    fun todoSyncStatus(): String {
        val messages = JSONArray()
        for (m in todoLastMessages) messages.put(m)
        return JSONObject().apply {
            put("running", todoSyncRunning)
            put("ok", todoLastOk)
            put("failed", todoLastFailed)
            put("messages", messages)
        }.toString()
    }

    /** NEVER returns the password — only whether one is stored. */
    @JavascriptInterface
    fun caldavConfig(): String {
        val (url, username) = CaldavPrefs.urlAndUsername(ctx)
        return JSONObject().apply {
            put("url", url)
            put("username", username)
            put("hasPassword", CaldavPrefs.hasPassword(ctx))
        }.toString()
    }

    /** Writes CalDAV URL/username/password into EncryptedSharedPreferences.
     *  This is the ONLY place these secrets are ever persisted — never
     *  BuildConfig, never a log line. `password` may be omitted to leave
     *  the previously-stored password unchanged (so the Configs screen
     *  can update the URL/username without forcing password re-entry). */
    @JavascriptInterface
    fun setCaldavConfig(json: String): String {
        return try {
            val o = JSONObject(json)
            val url = o.optString("url", "").trim()
            val username = o.optString("username", "").trim()
            if (url.isNotEmpty() && !url.startsWith("https://", ignoreCase = true)) {
                return okErr(false, "CalDAV URL must use https://")
            }
            val password = if (o.has("password")) o.optString("password", "") else null
            CaldavPrefs.set(ctx, url, username, password)
            okErr(true, "")
        } catch (e: Exception) {
            okErr(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * #1693 The CalDAV password is typed in Cloud Account (App credentials), never here: this takes it over the provider's
     * per-credential grant and files it in the encrypted store above, next to the URL and username already saved. BLOCKS (a provider
     * call, like [testCaldav]); the answer names the fix and never carries the value.
     */
    @JavascriptInterface
    fun takeCaldavPassword(): String {
        return try {
            val c = com.diegonmarcos.superapp.fleetendpoints.android.AccountClient.credential(ctx, CALDAV_CREDENTIAL, "The CalDAV password")
            if (!c.present) return okErr(false, c.line)
            val (url, username) = CaldavPrefs.urlAndUsername(ctx)
            CaldavPrefs.set(ctx, url, username, c.value)
            okErr(true, "")
        } catch (e: Exception) {
            okErr(false, e.javaClass.simpleName)
        }
    }

    /** #1693 Opens Cloud Account at App credentials; ok=false carries the reason it could not. */
    @JavascriptInterface
    fun openCloudAccountCredentials(): String {
        val why = com.diegonmarcos.superapp.fleetendpoints.android.AccountClient.openCredentials(ctx)
        return okErr(why == null, why.orEmpty())
    }

    /** Round-trips discovery only (no writes) so the Configs screen can
     *  validate a URL/username/password before saving. Synchronous for
     *  the same reason [saveTodo] is — see its kdoc. */
    @JavascriptInterface
    fun testCaldav(): String {
        return ask("testCaldav", cfgJson())
    }

    // ---- small JSON helpers -----------------------------------------------

    private fun saveResult(ok: Boolean, id: String, error: String): String =
        JSONObject().apply { put("ok", ok); put("id", id); put("error", error) }.toString()

    private fun okErr(ok: Boolean, error: String): String =
        JSONObject().apply { put("ok", ok); put("error", error) }.toString()

    /**
     * CalDAV URL/username/password at rest, via EncryptedSharedPreferences
     * (androidx.security). This is the ONLY place these credentials are
     * read/written in the app — see [setCaldavConfig]/[caldavConfig].
     * Never logged, never returned in full ([caldavConfig] omits the
     * password entirely), never baked into BuildConfig.
     */
    private object CaldavPrefs {
        private const val FILE = "caldav_secure_prefs"
        private const val KEY_URL = "url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"

        @Volatile private var cached: SharedPreferences? = null

        private fun prefs(ctx: Context): SharedPreferences {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val appCtx = ctx.applicationContext
                val p = SafeEncryptedPrefs.prefs(appCtx, FILE)
                cached = p
                return p
            }
        }

        /** The CalDAV config as the wire JSON the engine expects, or null.
         *  Returns JSON rather than a library type because the app no longer
         *  links libs:cal - credentials stay HERE and travel per call. */
        fun getJson(ctx: Context): String? {
            val p = prefs(ctx)
            val url = p.getString(KEY_URL, "") ?: ""
            if (url.isBlank()) return null
            return JSONObject()
                .put("baseUrl", url)
                .put("username", p.getString(KEY_USERNAME, "") ?: "")
                .put("password", p.getString(KEY_PASSWORD, "") ?: "")
                .toString()
        }

        fun urlAndUsername(ctx: Context): Pair<String, String> {
            val p = prefs(ctx)
            return (p.getString(KEY_URL, "") ?: "") to (p.getString(KEY_USERNAME, "") ?: "")
        }

        fun hasPassword(ctx: Context): Boolean =
            !prefs(ctx).getString(KEY_PASSWORD, "").isNullOrEmpty()

        fun set(ctx: Context, url: String, username: String, password: String?) {
            val editor = prefs(ctx).edit()
                .putString(KEY_URL, url)
                .putString(KEY_USERNAME, username)
            if (password != null) editor.putString(KEY_PASSWORD, password)
            editor.apply()
        }
    }
}
