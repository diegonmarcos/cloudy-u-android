package com.diegonmarcos.superapp.network

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * #1684 the strip's WebS and SSHd indicators: Cloud Webserver's HTTP server and the phone's SSH door(s), each green ONLY
 * from a real local round trip (never a proxy such as "the app is installed"):
 *   WebS = an HTTP HEAD on the webserver's health route over loopback, answered 2xx;
 *   SSHd = a TCP connect to a door's port over loopback and an `SSH-2.0-` banner read back.
 * The ports, the route and the binds are not written here: app/build.gradle resolves build.json::home_status_bar.local_doors
 * (which points into ac_cloud-c3-webserver/build.json and, through its ssh_doors, ac_cloud-nix-on-droid/build.json) into
 * home_status_bar.local_doors.resolved, baked inside BuildConfig.STATUS_BAR_B64 (no field of its own). A door the build could not resolve has port null and reads UNKNOWN with that reason.
 * Probes run when the strip becomes visible and on change events only (StatusStripModel.refreshDoors); nothing here
 * schedules itself. Every path returns a reading, never throws.
 */
object LocalDoors {
    enum class State { ON, OFF, UNKNOWN }

    data class Door(val id: String, val title: String, val port: Int?, val bind: String?, val route: String? = null)
    data class Decl(val webs: Door?, val sshd: List<Door>, val timeoutMs: Int = DEFAULT_TIMEOUT_MS)

    /** One round trip's outcome: [detail] is the status line / banner or the reason; [rttMs] null when nothing answered. */
    data class Probe(val door: Door, val state: State, val detail: String, val rttMs: Long?, val atMs: Long)

    const val DEFAULT_TIMEOUT_MS = 800
    const val LOOPBACK = "127.0.0.1"

    fun parse(json: String): Decl = runCatching {
        val o = JSONObject(json)
        fun door(x: JSONObject, fallbackId: String) = Door(
            id = x.optString("id", fallbackId), title = x.optString("title", fallbackId),
            port = if (x.has("port") && !x.isNull("port")) x.optInt("port", -1).takeIf { it in 1..65535 } else null,
            bind = x.optString("bind").takeIf { it.isNotBlank() }, route = x.optString("route").takeIf { it.isNotBlank() },
        )
        val webs = o.optJSONObject("webs")?.let { door(it, "webs") }
        val sshd = (o.optJSONArray("ssh") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { door(it, "ssh$i") } } }
        Decl(webs, sshd, o.optInt("timeout_ms", DEFAULT_TIMEOUT_MS).coerceIn(100, 5000))
    }.getOrDefault(Decl(null, emptyList()))

    /** The baked declaration; an empty one (no doors, every state UNKNOWN) if the build carried none. */
    val declared: Decl by lazy {
        runCatching {
            val bar = JSONObject(String(android.util.Base64.decode(com.diegonmarcos.superapp.BuildConfig.STATUS_BAR_B64, android.util.Base64.DEFAULT), Charsets.UTF_8))
            parse(bar.getJSONObject("local_doors").getJSONObject("resolved").toString())
        }.getOrDefault(Decl(null, emptyList()))
    }

    /** "HTTP/1.1 200 OK" -> 200; anything else -> null. */
    fun statusCode(line: String?): Int? =
        line?.trim()?.let { Regex("""^HTTP/\d(?:\.\d)?\s+(\d{3})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** WebS: a 2xx answer is ON; any other answer, or none, is OFF. */
    fun stateOfHttp(code: Int?): State = if (code != null && code in 200..299) State.ON else State.OFF

    /** SSHd: an RFC 4253 identification line is ON; anything else (or nothing) is OFF. */
    fun stateOfBanner(line: String?): State = if (line?.startsWith("SSH-") == true) State.ON else State.OFF

    /** The indicator for several doors: ON if any answers; OFF when all were checked and none did; else UNKNOWN. */
    fun combine(states: List<State>): State = when {
        states.any { it == State.ON } -> State.ON
        states.isNotEmpty() && states.all { it == State.OFF } -> State.OFF
        else -> State.UNKNOWN
    }

    /** HTTP HEAD [door].route on loopback. */
    fun probeHttp(door: Door?, timeoutMs: Int = DEFAULT_TIMEOUT_MS, host: String = LOOPBACK, now: () -> Long = System::currentTimeMillis): Probe {
        val d = door ?: Door("webs", "Cloud Webserver", null, null)
        val port = d.port ?: return Probe(d, State.UNKNOWN, "port not declared in this build", null, now())
        val route = d.route ?: "/"
        val t0 = System.nanoTime()
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs); s.soTimeout = timeoutMs
                s.getOutputStream().apply {
                    write("HEAD $route HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: superapp-strip\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
                }
                val line = readLine(s.getInputStream())
                val rtt = (System.nanoTime() - t0) / 1_000_000
                val code = statusCode(line)
                Probe(d, stateOfHttp(code), line?.trim()?.ifBlank { null } ?: "no HTTP answer", rtt, now())
            }
        }.getOrElse { e -> Probe(d, State.OFF, reason(e), null, now()) }
    }

    /** TCP connect + the SSH identification line on loopback. */
    fun probeSsh(door: Door, timeoutMs: Int = DEFAULT_TIMEOUT_MS, host: String = LOOPBACK, now: () -> Long = System::currentTimeMillis): Probe {
        val port = door.port ?: return Probe(door, State.UNKNOWN, "port not declared in this build", null, now())
        val t0 = System.nanoTime()
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs); s.soTimeout = timeoutMs
                // A server may send other lines before the identification (RFC 4253 4.2): read a few.
                var line: String? = null
                repeat(4) { if (line?.startsWith("SSH-") != true) line = readLine(s.getInputStream()) }
                val rtt = (System.nanoTime() - t0) / 1_000_000
                Probe(door, stateOfBanner(line), line?.trim()?.ifBlank { null } ?: "connected, no SSH banner", rtt, now())
            }
        }.getOrElse { e -> Probe(door, State.OFF, reason(e), null, now()) }
    }

    private fun reason(e: Throwable): String = when (e) {
        is java.net.ConnectException -> "refused (nothing listening)"
        is java.net.SocketTimeoutException -> "timed out"
        else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
    }

    /** One CRLF / LF-terminated line, at most 512 bytes; null on EOF before any byte. */
    internal fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 512) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** The dropdown's lines for one probe (port, bind, answer, round trip, checked at). */
    fun rows(p: Probe, clock: (Long) -> String = { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(it)) }): List<String> = listOf(
        "${p.door.title}: " + when (p.state) { State.ON -> "● up"; State.OFF -> "○ down"; State.UNKNOWN -> "? unknown" },
        "Port: " + (p.door.port?.toString() ?: "n/a (not declared in this build)"),
        "Bind: " + (p.door.bind ?: "n/a (not declared)"),
        "Answer: ${p.detail}",
        "Round trip: " + (p.rttMs?.let { "$it ms" } ?: "n/a") + " · checked " + clock(p.atMs),
    )

    /** "{...,"uptime_s":123,...}" -> "2m 03s" (the health body's uptime), or null. */
    fun uptime(body: String?): String? {
        val s = body?.let { Regex(""""uptime_s"\s*:\s*(\d+)""").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: return null
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "${h}h %02dm".format(m) else "${m}m %02ds".format(sec)
    }

    /** A GET of the health route (dropdown only, on open): the body carries pid / uptime. Null on any failure. */
    fun healthBody(door: Door?, timeoutMs: Int = DEFAULT_TIMEOUT_MS, host: String = LOOPBACK): String? {
        val port = door?.port ?: return null
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs); s.soTimeout = timeoutMs
                s.getOutputStream().apply { write("GET ${door.route ?: "/"} HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush() }
                run {
                    val buf = java.io.ByteArrayOutputStream(); val chunk = ByteArray(1024); val inp = s.getInputStream()
                    while (buf.size() < 4096) { val n = inp.read(chunk); if (n < 0) break; buf.write(chunk, 0, n) }
                    buf.toString("UTF-8")
                }.substringAfter("\r\n\r\n", "")
            }
        }.getOrNull()
    }
}
