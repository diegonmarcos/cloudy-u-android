package com.diegonmarcos.superapp.fleetendpoints.errors

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * #1925 The mail error log: a bounded ring of log lines plus the recent [ErrorReport]s, process-wide. [capture] is the
 * OkHttp hook (every failed exchange of the JMAP client lands here with its route profile); [record] merges a surfaced
 * [Throwable] (operation, account, exception chain) with the exchange that caused it. [text] / [markdown] are THE content
 * of "See log", "Export to Markdown" and "Copy all": the same string.
 */
class ErrorLog(
    private val lines: Int = 300,
    private val reports: Int = 20,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The VPN state of the device, read at failure time (the app supplies it). */
    @Volatile var vpnState: () -> String = { "unknown" },
    /** Which account a URL host belongs to (the app supplies it). */
    @Volatile var accountOf: (String) -> String = { "" },
    @Volatile var profiler: (host: String, port: Int, https: Boolean, vpn: String) -> RouteProfile? =
        { h, p, s, v -> RouteProfiler.profile(h, p, s, v) },
) {
    private val ring = ArrayDeque<String>()
    private val recent = ArrayDeque<ErrorReport>()

    fun log(tag: String, message: String) = synchronized(this) {
        if (ring.size >= lines) ring.removeFirst()
        ring.addLast("${stamp(clock())} $tag ${ErrorRedactor.text(message)}")
    }

    fun ringLines(): List<String> = synchronized(this) { ring.toList() }
    fun latest(): ErrorReport? = synchronized(this) { recent.peekLast() }
    fun all(): List<ErrorReport> = synchronized(this) { recent.toList() }

    private fun push(r: ErrorReport) = synchronized(this) {
        if (recent.size >= reports) recent.removeFirst()
        recent.addLast(r)
        if (ring.size >= lines) ring.removeFirst()
        ring.addLast("${stamp(r.atMs)} ERROR ${r.operation} ${r.method} ${r.url} -> ${r.status ?: "no response"} ${r.reason}")
    }

    /** Builds, stores and returns the report of one failed exchange. [response] null = the transport failed ([t]). */
    fun capture(
        operation: String, account: String, urlString: String, method: String, response: Response?, t: Throwable?,
    ): ErrorReport {
        val u = urlString.toHttpUrlSafe()
        val host = u?.host.orEmpty()
        val route = if (host.isEmpty()) null else runCatching {
            profiler(host, u!!.port, u.isHttps, vpnState())
        }.getOrNull()
        val headers = response?.headers?.let { h -> (0 until h.size).map { h.name(it) to ErrorRedactor.header(h.name(it), h.value(it)) } }.orEmpty()
        val body = runCatching { response?.peekBody(ErrorReport.BODY_LIMIT.toLong())?.string().orEmpty() }.getOrDefault("")
        val r = ErrorReport(
            operation = operation, account = account.ifBlank { accountOf(host) },
            url = ErrorRedactor.url(urlString), path = u?.encodedPath.orEmpty(), method = method,
            status = response?.code, reason = response?.message ?: (t?.message.orEmpty()),
            responseHeaders = headers, bodyHead = ErrorRedactor.text(body.take(ErrorReport.BODY_LIMIT)),
            chain = chainOf(t), atMs = clock(), route = route,
        )
        push(r)
        return r
    }

    /**
     * The report for a failure the app surfaces: the newest captured exchange (within [windowMs]) when the throwable is a
     * transport or HTTP failure, with [operation], [account] and the real exception chain put on it; otherwise a report with
     * no exchange (a parse error, a cancelled job) that still carries the chain and the time.
     */
    fun record(operation: String, account: String, t: Throwable, windowMs: Long = 60_000): ErrorReport {
        val base = latest()?.takeIf { clock() - it.atMs <= windowMs }
        val r = (base ?: ErrorReport(
            operation, account, "", "", "", null, t.message.orEmpty(), emptyList(), "", emptyList(), clock(), null,
        )).copy(
            operation = operation, account = account.ifBlank { base?.account.orEmpty() },
            chain = chainOf(t).ifEmpty { base?.chain.orEmpty() }, atMs = clock(),
        )
        synchronized(this) { recent.addLast(r); if (recent.size > reports) recent.removeFirst() }
        return r
    }

    /** The interceptor that feeds [capture]: any non-2xx answer (3xx apart) and any transport failure. */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val req = chain.request()
        val op = operationOf(req.url.encodedPath, req.method)
        val resp = try { chain.proceed(req) } catch (e: IOException) {
            runCatching { capture(op, "", req.url.toString(), req.method, null, e) }
            throw e
        }
        if (resp.code >= 400) runCatching { capture(op, "", req.url.toString(), req.method, resp, null) }
        resp
    }

    companion object {
        /** The process-wide instance the app and the JMAP client share. */
        val shared = ErrorLog()

        fun operationOf(path: String, method: String): String = when {
            path.contains("/.well-known/jmap") || path.endsWith("/session") -> "JMAP session"
            path.contains("upload") -> "attachment upload"
            path.contains("download") || path.contains("/blob") -> "attachment download"
            path.contains("eventsource") || path.contains("push") -> "push"
            path.contains("oauth") || path.contains("token") || path.contains("auth") -> "account"
            method == "POST" -> "JMAP request (sync/send)"
            else -> "request"
        }

        fun chainOf(t: Throwable?): List<String> {
            val out = ArrayList<String>(); var c = t; var n = 0
            while (c != null && n++ < 10) { out += "${c.javaClass.name}: ${ErrorRedactor.text(c.message.orEmpty())}"; c = c.cause }
            return out
        }

        fun stamp(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        private fun String.toHttpUrlSafe() = toHttpUrlOrNull()
    }

    /** One report as plain text with `== ` sections (the input of [ReportMarkdown.fromPlain]). */
    fun reportText(r: ErrorReport): String = buildString {
        fun line(s: String = "") { append(s).append('\n') }
        fun kv(k: String, v: String) = line("${k.padEnd(12)} ${v.ifBlank { "-" }}")
        line("Cloud Mail error ${stamp(r.atMs)}")
        line("== Error")
        kv("operation", r.operation); kv("account", r.account); kv("time", stamp(r.atMs) + " (epoch ms ${r.atMs})")
        line("== Request")
        kv("url", r.url); kv("path", r.path); kv("method", r.method)
        kv("status", r.status?.toString().orEmpty()); kv("reason", r.reason)
        line("== Response headers")
        if (r.responseHeaders.isEmpty()) line("(none)") else r.responseHeaders.forEach { line("${it.first}: ${it.second}") }
        line("== Response body (first ${ErrorReport.BODY_LIMIT} bytes)")
        line(r.bodyHead.ifBlank { "(empty)" })
        line("== Exception chain")
        if (r.chain.isEmpty()) line("(none)") else r.chain.forEachIndexed { i, c -> line("${"  ".repeat(i)}${if (i > 0) "caused by " else ""}$c") }
        line("== Route profile")
        val p = r.route
        if (p == null) line("(not profiled)") else {
            kv("host", p.host); kv("path", p.path + (if (p.path == "mesh") " (private .app namespace)" else " (public internet)"))
            kv("dns", p.dnsAnswers.joinToString(", ").ifBlank { "no answer" } + " via ${p.resolver}" + (p.dnsMs?.let { " in $it ms" } ?: ""))
            kv("tcp connect", p.tcpMs?.let { "$it ms" } ?: ("failed " + p.tcpError))
            kv("tls", if (p.tlsMs != null) "${p.tlsMs} ms, certificate ${p.certName.ifBlank { "-" }}" else p.tlsError.ifBlank { "not attempted" })
            kv("total", "${p.totalMs} ms"); kv("vpn", p.vpn)
        }
    }.trimEnd()

    /** THE content of See log / Copy all / Export: the report (null = none yet) and the recent log ring. */
    fun text(r: ErrorReport? = latest()): String = buildString {
        if (r != null) append(reportText(r)) else append("Cloud Mail error log\n== Error\n(no error captured yet)")
        append("\n== Mail log (").append(ringLines().size).append(")\n")
        append(ringLines().joinToString("\n").ifBlank { "(empty)" })
    }

    fun markdown(r: ErrorReport? = latest()): String = ReportMarkdown.fromPlain(text(r))
}
