package com.diegonmarcos.superapp.headless

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The engine behind the dispatcher: WebView sessions on the phone ([WebViewEngine]), a fake in tests. Blocking; called off the main thread. */
interface HeadlessEngine {
    fun newSession(profile: String): Any
    fun closeSession(handle: Any)
    fun run(handle: Any, cmd: JSONObject): Any?
}

/**
 * #1498 the headless-api v1 session manager of the phone: validates commands, enforces max sessions, the per-call timeout and the
 * idle close, refuses work while the screen is dark, and writes the audit trail. Mirrors the container's api.js.
 */
class HeadlessDispatcher(
    private val engine: HeadlessEngine,
    var maxSessions: Int = HeadlessPolicy.MAX_SESSIONS_DEFAULT,
    var idleMs: Long = HeadlessPolicy.IDLE_STOP_MS_DEFAULT,
    var callTimeoutMs: Long = HeadlessPolicy.CALL_TIMEOUT_MS_DEFAULT,
    private val now: () -> Long = System::currentTimeMillis,
    private val screenOn: () -> Boolean = { true },
    private val audit: (HeadlessAudit.Entry) -> Unit = {},
) {
    private class S(val handle: Any, val agent: String, var last: Long, var url: String = "")
    private val sessions = LinkedHashMap<String, S>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "headless-call").apply { isDaemon = true } }
    private var seq = 0
    @Volatile var lastActivity: Long = now()

    @Synchronized fun open(): Int = sessions.size
    @Synchronized fun list(): JSONArray = JSONArray().also { a -> sessions.forEach { (id, s) -> a.put(JSONObject().put("id", id).put("agent", s.agent).put("idleMs", now() - s.last)) } }

    /** One command; the reply is always a JSON object (ok/result or ok:false/error), never an exception. */
    fun command(c: JSONObject): JSONObject = try {
        lastActivity = now()
        HeadlessApi.validate(c)
        if (!HeadlessPolicy.mayRun(screenOn())) throw HeadlessApi.Err("denied", "screen is off: no work while the screen is dark")
        val r = exec(c)
        auditIt(c, true)
        r
    } catch (e: Throwable) { auditIt(c, false); HeadlessApi.fail(e) }

    fun run(r: JSONObject): JSONObject {
        try { HeadlessApi.validateRun(r) } catch (e: Throwable) { return HeadlessApi.fail(e) }
        val out = JSONArray()
        var session: String? = r.optString("session").ifEmpty { null }
        var ok = true
        for (step in HeadlessApi.stepsOf(r)) {
            val c = JSONObject(step.toString())
            if (!c.has("session") && session != null) c.put("session", session)
            if (!c.has("timeoutMs") && r.has("stepTimeoutMs")) c.put("timeoutMs", r.getInt("stepTimeoutMs"))
            val x = command(c)
            out.put(x)
            if (x.optBoolean("ok")) session = x.optString("session").ifEmpty { session } else { ok = false; if (r.optBoolean("stopOnError", true)) break }
        }
        return JSONObject().put("ok", ok).apply { if (session != null) put("session", session) }.put("steps", out)
    }

    private fun exec(c: JSONObject): JSONObject {
        val cmd = c.getString("cmd")
        val timeout = if (c.has("timeoutMs")) c.getLong("timeoutMs") else callTimeoutMs
        if (cmd == "open") {
            val id = c.optString("session").ifEmpty { "s${++seq}-${now().toString(36)}" }
            val s = synchronized(this) {
                sessions[id] ?: run {
                    if (!HeadlessPolicy.mayOpen(sessions.size, maxSessions)) throw HeadlessApi.Err("limit", "max sessions $maxSessions")
                    S(engine.newSession(c.optString("profile").ifEmpty { id }), c.optString("agent"), now()).also { sessions[id] = it }
                }
            }
            s.last = now()
            val res = timed(timeout, "open") { engine.run(s.handle, c) }
            s.url = c.optString("url")
            return HeadlessApi.ok(id, res)
        }
        val id = c.optString("session").ifEmpty { throw HeadlessApi.Err("bad_request", "session required") }
        if (cmd == "close") return HeadlessApi.ok(id, JSONObject().put("closed", close(id)))
        val s = synchronized(this) { sessions[id] } ?: throw HeadlessApi.Err("not_found", "no such session")
        s.last = now()
        val res = timed(timeout, cmd) { engine.run(s.handle, c) }
        s.last = now()
        return HeadlessApi.ok(id, res)
    }

    fun close(id: String): Boolean {
        val s = synchronized(this) { sessions.remove(id) } ?: return false
        runCatching { engine.closeSession(s.handle) }
        return true
    }

    /** Close the sessions idle for [idleMs]; returns their ids. */
    fun sweep(): List<String> {
        val dead = synchronized(this) { sessions.filter { HeadlessPolicy.sessionExpired(now(), it.value.last, idleMs) }.keys.toList() }
        dead.forEach { close(it) }
        return dead
    }

    fun closeAll() { synchronized(this) { sessions.keys.toList() }.forEach { close(it) } }

    private fun <T> timed(ms: Long, label: String, f: () -> T): T {
        val fut = pool.submit(Callable { f() })
        try { return fut.get(ms, TimeUnit.MILLISECONDS) }
        catch (e: TimeoutException) { fut.cancel(true); throw HeadlessApi.Err("timeout", "$label timed out after ${ms}ms") }
        catch (e: ExecutionException) { throw e.cause ?: e }
    }

    private fun auditIt(c: JSONObject, ok: Boolean) {
        val id = c.optString("session")
        val agent = c.optString("agent").ifEmpty { synchronized(this) { sessions[id]?.agent }.orEmpty() }
        val url = c.optString("url").ifEmpty { synchronized(this) { sessions[id]?.url }.orEmpty() }
        runCatching { audit(HeadlessAudit.Entry(now(), agent, c.optString("cmd"), url, ok)) }
    }
}
