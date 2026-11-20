package app.sterna.fleet

import app.sterna.ui.compose.CheckOutcome
import app.sterna.ui.compose.CheckSource
import app.sterna.ui.compose.RecipientCheckItem
import app.sterna.ui.compose.RecipientCheckKind
import app.sterna.ui.compose.RecipientStatus
import app.sterna.ui.compose.RecipientVerdict
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/*
 * #1827 The mailbox probe's fallback when the delivery server cannot reach port 25.
 *
 * Every fleet host has outbound TCP/25 blocked by its cloud provider, and a receiving MX takes connections on port 25
 * only (587/465 are authenticated submission ports of the sender's OWN provider and answer nothing about a foreign
 * mailbox), so no other port can stand in. The fallback is a different vantage point: this phone's own network. The
 * server says so with check_details.mailbox.probe_blocked and hands over what the probe needs (client_probe: MX hosts in
 * priority order, its HELO name and reverse-path); the phone then runs the SAME read-only dialogue the server would:
 *
 *   EHLO -> MAIL FROM -> RCPT TO <address> -> RCPT TO <random@domain> (catch-all) -> RSET -> QUIT
 *
 * There is no code path that writes DATA (PhoneMailboxProbeTest asserts the transcript). The classification is the
 * server's (cloudy-u-containers infra-api_cloudy-apps-communication-mcp smtp-probe.ts), line for line.
 *
 * Mobile networks and many home providers block port 25 too; then the lines stay unknown and say that the phone's network
 * could not reach port 25 either. One failed connect closes a phone-side gate for [GATE_TTL_MILLIS] so later addresses
 * answer at once instead of each spending a connect timeout. Answers are kept for [RESULT_TTL_MILLIS].
 */

/** What the server handed over for a blocked probe. */
data class ClientProbeSpec(val hosts: List<String>, val helo: String, val mailFrom: String, val port: Int = 25)

data class PhoneProbeResult(
    val host: String,
    val connected: Boolean,
    /** EHLO SIZE in bytes; 0 = advertised without a limit; null = not advertised / not reached. */
    val sizeLimit: Long?,
    /** exists | does_not_exist | accepts_all | unknown (the server's states). */
    val mailbox: String,
    val code: Int?,
    val reason: String,
    val transcript: List<String> = emptyList(),
    /** True when no host could be reached from this phone (its network blocks port 25, or the hosts are down). */
    val networkBlocked: Boolean = false,
)

/** One line-oriented SMTP session; replies are multi-line joined. Every call must honour the deadline it gets. */
interface SmtpSession : Closeable {
    fun reply(deadlineMillis: Long): Pair<Int, String>
    fun send(line: String)
}

fun interface SmtpDialer {
    /** Opens host:port within [connectTimeoutMillis] or throws. */
    fun open(host: String, port: Int, connectTimeoutMillis: Int): SmtpSession
}

/** The real transport: a plain TCP socket (SMTP on port 25 is plaintext until STARTTLS, which a probe never needs). */
object SocketSmtpDialer : SmtpDialer {
    override fun open(host: String, port: Int, connectTimeoutMillis: Int): SmtpSession {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val out: OutputStream = socket.getOutputStream()
        return object : SmtpSession {
            override fun reply(deadlineMillis: Long): Pair<Int, String> {
                val lines = ArrayList<String>()
                while (true) {
                    val left = deadlineMillis - System.currentTimeMillis()
                    if (left <= 0) throw SocketTimeoutException("SMTP dialogue budget exhausted")
                    socket.soTimeout = left.toInt().coerceAtLeast(1)
                    val line = reader.readLine() ?: throw java.io.EOFException("connection closed")
                    lines += line
                    // "250-..." continues a multi-line reply, "250 ..." (or a bare code) ends it.
                    if (line.length < 4 || line[3] != '-') break
                }
                val code = lines.last().take(3).toIntOrNull() ?: 0
                return code to lines.joinToString("\n")
            }

            override fun send(line: String) {
                out.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
                out.flush()
            }

            override fun close() {
                runCatching { socket.close() }
            }
        }
    }
}

object PhoneMailboxProbe {
    const val CONNECT_TIMEOUT_MILLIS = 3_000
    const val DIALOGUE_BUDGET_MILLIS = 6_000L
    const val GATE_TTL_MILLIS = 10 * 60_000L
    const val RESULT_TTL_MILLIS = 15 * 60_000L

    private val rng = SecureRandom()
    private fun randomLocal(): String = "probe-" + ByteArray(8).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    private class Gate(val blocked: Boolean, val reason: String, val at: Long)
    @Volatile private var gate: Gate? = null
    private val results = LinkedHashMap<String, Pair<PhoneProbeResult, Long>>()

    /** For tests: forget the gate and every kept answer. */
    fun reset() {
        gate = null
        synchronized(results) { results.clear() }
    }

    /**
     * The probe for one address, trying [ClientProbeSpec.hosts] in order until one answers (a reachable MX is never
     * shopped around for a friendlier one). Blocking: call it off the main thread.
     */
    fun probe(
        spec: ClientProbeSpec,
        address: String,
        dialer: SmtpDialer = SocketSmtpDialer,
        now: () -> Long = System::currentTimeMillis,
        random: () -> String = ::randomLocal,
    ): PhoneProbeResult {
        val key = address.trim().lowercase()
        synchronized(results) { results[key]?.let { (r, at) -> if (now() - at < RESULT_TTL_MILLIS) return r } }
        gate?.let { g ->
            if (g.blocked && now() - g.at < GATE_TTL_MILLIS) {
                return PhoneProbeResult(spec.hosts.firstOrNull().orEmpty(), false, null, "unknown", null, g.reason, networkBlocked = true)
            }
        }
        if (spec.hosts.isEmpty()) return PhoneProbeResult("", false, null, "unknown", null, "no mail host to probe")
        var last: PhoneProbeResult? = null
        val errors = ArrayList<String>()
        for (host in spec.hosts) {
            val r = probeHost(dialer, host, address, spec, random)
            last = r
            if (r.connected) break
            errors += r.reason
        }
        var result = last!!
        if (!result.connected) {
            val reason = "this phone's network cannot reach port ${spec.port} either (${errors.joinToString("; ")}); " +
                "mobile networks and many home providers block it, so the mailbox could not be asked"
            result = result.copy(reason = reason, networkBlocked = true)
            gate = Gate(true, reason, now())
        } else {
            gate = Gate(false, "", now())
        }
        // Only a reached server's answer is kept; an unreachable network is the gate's to remember.
        if (result.connected) synchronized(results) {
            results[key] = result to now()
            while (results.size > 256) results.remove(results.keys.first())
        }
        return result
    }

    private fun probeHost(dialer: SmtpDialer, host: String, address: String, spec: ClientProbeSpec, random: () -> String): PhoneProbeResult {
        val transcript = ArrayList<String>()
        val session = try {
            dialer.open(host, spec.port, CONNECT_TIMEOUT_MILLIS)
        } catch (t: Throwable) {
            return PhoneProbeResult(host, false, null, "unknown", null, "cannot reach $host:${spec.port} (${t.message ?: t.javaClass.simpleName})", transcript)
        }
        val deadline = System.currentTimeMillis() + DIALOGUE_BUDGET_MILLIS
        fun say(l: String) { transcript += "C: $l"; session.send(l) }
        fun hear(): Pair<Int, String> = session.reply(deadline).also { transcript += "S: ${it.second}" }
        var size: Long? = null
        fun done(mailbox: String, code: Int?, reason: String) = PhoneProbeResult(host, true, size, mailbox, code, reason, transcript)
        try {
            val greet = hear()
            if (greet.first != 220) return done("unknown", null, "greeting ${greet.second}")
            say("EHLO ${spec.helo}")
            val ehlo = hear()
            if (ehlo.first != 250) return done("unknown", null, "EHLO refused: ${ehlo.second}")
            size = parseSize(ehlo.second)
            say("MAIL FROM:<${spec.mailFrom}>")
            val mf = hear()
            if (mf.first != 250) return done("unknown", null, "MAIL FROM refused (${mf.second}); mailbox not tested")
            say("RCPT TO:<$address>")
            val rc = hear()
            val code = rc.first
            return when {
                code == 250 || code == 251 -> {
                    val domain = address.substring(address.lastIndexOf('@') + 1)
                    say("RCPT TO:<${random()}@$domain>")
                    val rr = hear()
                    when {
                        rr.first == 250 || rr.first == 251 ->
                            done("accepts_all", code, "$host accepts any address at $domain (catch-all), so $code proves nothing")
                        rr.first >= 500 -> done("exists", code, "$host: ${rc.second}")
                        else -> done("unknown", code, "$host accepted the address but deferred the control address (${rr.second}); cannot rule out catch-all")
                    }
                }
                code in 400..499 -> done("unknown", code, "$host deferred RCPT (${rc.second}) — greylisting or rate limit, not a verdict")
                code in setOf(550, 551, 553) && !Regex("""\b5\.7\.\d+\b""").containsMatchIn(rc.second) ->
                    done("does_not_exist", code, "$host: ${rc.second}")
                else -> done("unknown", code, "$host refused RCPT for a policy reason (${rc.second}), not a mailbox verdict")
            }
        } catch (t: Throwable) {
            return done("unknown", null, "probe to $host aborted: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { say("RSET") }
            runCatching { say("QUIT") }
            session.close()
        }
    }

    /** EHLO's SIZE extension: "250-SIZE 35882577" -> bytes; "SIZE" alone -> 0 (no limit); absent -> null. */
    internal fun parseSize(ehlo: String): Long? {
        for (line in ehlo.lines()) {
            val m = Regex("""^\d{3}[- ]SIZE(?:\s+(\d+))?\s*$""", RegexOption.IGNORE_CASE).find(line.trim()) ?: continue
            return m.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
        }
        return null
    }
}

/**
 * The server's status with the phone's probe applied: the mailbox and size-limit lines become the phone's (labelled
 * "Checked on this phone"), and the verdict is decided by the server's own rules (recipients.ts): does-not-exist fails,
 * a catch-all is risky, a confirmed mailbox is ok unless a past bounce or a disposable/role hint makes it risky, and a
 * message over the advertised SIZE fails. When the phone could not tell either, the server's verdict stands.
 */
internal fun applyPhoneProbe(status: RecipientStatus, probe: PhoneProbeResult, messageSizeBytes: Long?): RecipientStatus {
    val mailboxOutcome = when (probe.mailbox) {
        "exists" -> CheckOutcome.PASS
        "does_not_exist" -> CheckOutcome.FAIL
        "accepts_all" -> CheckOutcome.WARN
        else -> CheckOutcome.UNKNOWN
    }
    val hostCode = listOfNotNull(probe.host.takeIf { probe.connected && it.isNotEmpty() }, probe.code?.toString())
        .joinToString(" ").takeIf { it.isNotBlank() }
    val mailbox = RecipientCheckItem(
        RecipientCheckKind.MAILBOX, mailboxOutcome,
        listOfNotNull(probe.reason, hostCode).joinToString("\n"),
        source = CheckSource.PHONE, acceptsAll = probe.mailbox == "accepts_all",
    )
    val lim = probe.sizeLimit
    val size = when {
        lim != null && lim > 0 && messageSizeBytes != null && messageSizeBytes > lim -> RecipientCheckItem(
            RecipientCheckKind.SIZE_LIMIT, CheckOutcome.FAIL,
            "message $messageSizeBytes bytes exceeds the server limit of $lim bytes (EHLO SIZE at ${probe.host})", source = CheckSource.PHONE,
        )
        lim != null && lim > 0 -> RecipientCheckItem(
            RecipientCheckKind.SIZE_LIMIT, CheckOutcome.PASS, "server limit $lim bytes (EHLO SIZE at ${probe.host})", source = CheckSource.PHONE,
        )
        lim == 0L -> RecipientCheckItem(RecipientCheckKind.SIZE_LIMIT, CheckOutcome.PASS, "server advertises SIZE without a limit", source = CheckSource.PHONE)
        else -> RecipientCheckItem(
            RecipientCheckKind.SIZE_LIMIT, CheckOutcome.UNKNOWN,
            if (probe.connected) "SIZE not advertised" else "server not reached from this phone", source = CheckSource.PHONE,
        )
    }
    val checks = status.checks.map {
        when (it.kind) {
            RecipientCheckKind.MAILBOX -> mailbox
            RecipientCheckKind.SIZE_LIMIT -> size
            else -> it
        }
    }.let { list ->
        list + listOfNotNull(mailbox.takeIf { list.none { c -> c.kind == RecipientCheckKind.MAILBOX } },
            size.takeIf { list.none { c -> c.kind == RecipientCheckKind.SIZE_LIMIT } })
    }
    val hinted = checks.any {
        (it.kind == RecipientCheckKind.PAST_BOUNCE || it.kind == RecipientCheckKind.DISPOSABLE_ROLE) &&
            (it.outcome == CheckOutcome.WARN || it.outcome == CheckOutcome.FAIL)
    }
    val syntaxOrDomainFail = checks.any {
        (it.kind == RecipientCheckKind.SYNTAX || it.kind == RecipientCheckKind.DOMAIN) && it.outcome == CheckOutcome.FAIL
    }
    var verdict = when {
        syntaxOrDomainFail -> RecipientVerdict.WILL_FAIL
        probe.mailbox == "does_not_exist" -> RecipientVerdict.WILL_FAIL
        probe.mailbox == "accepts_all" -> RecipientVerdict.RISKY
        probe.mailbox == "exists" -> if (hinted) RecipientVerdict.RISKY else RecipientVerdict.OK
        else -> status.verdict
    }
    if (size.outcome == CheckOutcome.FAIL) verdict = RecipientVerdict.WILL_FAIL
    val summary = if (probe.networkBlocked) {
        "The mail server cannot reach port 25 (its cloud provider blocks it), and neither can this phone's network: the mailbox could not be asked"
    } else {
        "Mailbox checked on this phone, because the mail server cannot reach port 25: ${probe.reason}"
    }
    return status.copy(checks = checks, verdict = verdict, serverReason = summary)
}
