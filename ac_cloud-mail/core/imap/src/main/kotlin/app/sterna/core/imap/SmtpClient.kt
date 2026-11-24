package app.sterna.core.imap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class SmtpException(message: String) : Exception(message)

/** The password-based SASL mechanisms this client can speak, in order of preference. */
internal enum class SmtpAuthMechanism { PLAIN, LOGIN }

        /**
         * The SASL mechanism names an EHLO response advertised, upper-cased, in the order listed;
         */
internal fun advertisedAuthMechanisms(ehloLines: List<String>): List<String> {
    val mechanisms = mutableListOf<String>()
    for (raw in ehloLines) {
        // Drop the reply code and its separator ("250-" / "250 ") when there is one.
        val hasCode = raw.length > 4 && raw.take(3).all { it.isDigit() } && (raw[3] == '-' || raw[3] == ' ')
        val capability = (if (hasCode) raw.substring(4) else raw).trim()
        val keyword = capability.substringBefore(' ')
        if (!keyword.equals("AUTH", ignoreCase = true) && !keyword.startsWith("AUTH=", ignoreCase = true)) continue
        // "AUTH=PLAIN" packs the first mechanism into the keyword itself.
        val listed = keyword.substringAfter('=', "") + " " + capability.substringAfter(' ', "")
        mechanisms += listed.split(' ', '\t').filter { it.isNotBlank() }.map { it.uppercase() }
    }
    return mechanisms
}

        /**
         * Which mechanism to authenticate with, given what the server [advertised]. `PLAIN` first
         */
internal fun chooseAuthMechanism(advertised: List<String>): SmtpAuthMechanism? = when {
    advertised.isEmpty() -> SmtpAuthMechanism.LOGIN
    advertised.any { it.equals("PLAIN", ignoreCase = true) } -> SmtpAuthMechanism.PLAIN
    advertised.any { it.equals("LOGIN", ignoreCase = true) } -> SmtpAuthMechanism.LOGIN
    else -> null
}

        /** The SASL PLAIN initial client response (base64), RFC 4616 — ONE blob, not the two AUTH
         *  LOGIN sends. `Base64.getEncoder()` on purpose: `getMimeEncoder()` wraps at 76 characters
         *  and would break the payload of a long password. */
internal fun saslPlainPayload(username: String, password: String): String {
    val nul = Char(0) // SASL PLAIN field separator (NUL)
    return Base64.getEncoder().encodeToString("$nul$username$nul$password".toByteArray(Charsets.UTF_8))
}

/** A file or inline attachment to include in an outgoing message. */
data class OutgoingAttachment(
    val name: String,
    val type: String,
    val bytes: ByteArray,
    /** Content-ID (no angle brackets) for an inline part referenced by `cid:` in the HTML body. */
    val cid: String? = null,
    /** When true the part is emitted inline (multipart/related) rather than as a file attachment. */
    val inline: Boolean = false,
)

        /** Whether a built message writes its blind copies as a `Bcc:` header. A type and not a
         *  `Boolean` — that IS the guard: a positional `true` puts the header into a message while
         *  the word "Bcc" appears nowhere on the line. */
enum class BlindCopies {
    /** No `Bcc:` header at all. The default, and what every byte that LEAVES the phone gets. */
    OMITTED,

    /** Write the blind recipients as a `Bcc:` header. Stored copies only — see [OutgoingMime.build]. */
    WRITTEN,
}

/** A message to submit over SMTP. Addresses are bare "name <addr>" or "addr" strings. */
data class OutgoingMessage(
    val from: String,
    val to: List<String>,
    val cc: List<String> = emptyList(),
        /** Blind-copy recipients: in the SMTP envelope (RCPT TO) and, on the wire, nowhere else. A
         *  `Bcc:` header exists in exactly one place — the copy APPENDed to Drafts, read back by
         *  nobody else. */
    val bcc: List<String> = emptyList(),
    val subject: String,
    val body: String,
    /** Optional HTML body; when set the message leaves as multipart/alternative (text/plain + text/html). */
    val html: String? = null,
    val inReplyTo: String? = null,
    val references: String? = null,
    val messageId: String,
    val dateMillis: Long,
    val attachments: List<OutgoingAttachment> = emptyList(),
            /** A complete pre-built MIME entity that REPLACES the normal body/attachments. NOT a
             *  PGP field: the seam through which any payload this builder cannot express reaches the
             * wire verbatim. Its octets come from OUTSIDE, so [OutgoingMime.build] canonicalises
             *  the line endings to CRLF, bare CR preserved. */
    val prebuiltEntity: String? = null,
            /** Ask the recipient's client for a read receipt: [OutgoingMime.build] then writes
             *  `Disposition-Notification-To:` (RFC 8098) naming THIS message's [from]. A flag and not
             *  an address, so no second address exists to drift from the visible mailbox. */
    val requestReceipt: Boolean = false,
            /** The value of the `Autocrypt:` header announcing this sender's key (Autocrypt Level 1
             *  §2.1), or null. Sanitised but NOT folded: [OutgoingMime.build] folds it, and JMAP wants
             * it flat. A ready value and not an account — the rule lives in core/data's
             *  `autocryptHeaderValue`, where a JVM test can execute it. */
    val autocryptHeader: String? = null,
)

    /** How long to wait for the TCP connection, and for the answer to one command. The values JMAP
     *  submission already uses; one convention for the whole app, and no user-facing setting. */
internal const val SMTP_CONNECT_TIMEOUT_MS = 20_000
internal const val SMTP_COMMAND_TIMEOUT_MS = 30_000

        /** `0` is the socket's own default, "block with no deadline of ours" — kept by the ONE read
         *  that is legitimately long: the 250 answering the dot, behind which the server queues, signs
         *  and scans. Cutting a send the server accepted would have `OutboxWorker` send it TWICE. */
internal const val SMTP_BLOCKING_READ = 0

/** Minimal SMTP submission client (EHLO, optional STARTTLS, AUTH, MAIL/RCPT/DATA). */
class SmtpClient {
            /**
             * Submit [message] through [config]'s server. [connectTimeoutMs] bounds the TCP connect
             */
    suspend fun send(
        config: MailServerConfig,
        message: OutgoingMessage,
        connectTimeoutMs: Int = SMTP_CONNECT_TIMEOUT_MS,
        commandTimeoutMs: Int = SMTP_COMMAND_TIMEOUT_MS,
    ) = withContext(Dispatchers.IO) {
        val connectTimeout = connectTimeoutMs.coerceAtLeast(0)
        val commandTimeout = commandTimeoutMs.coerceAtLeast(0)
        val plain = Socket()
            // What the finally closes, and STARTTLS reassigns it. Every way out of the dialogue has
            // to close the connection: a rejected recipient used to leak its socket.
        var socket: Socket = plain
        try {
            plain.connect(InetSocketAddress(config.host, config.port), connectTimeout)
                // Armed BEFORE the handshake: startHandshake() READS from this socket, so a bound
                // set afterwards would never cover a server that accepts and then says nothing.
            plain.soTimeout = commandTimeout
            if (config.security == MailSecurity.TLS) {
                val secure = (tlsFactory.createSocket(plain, config.host, config.port, true) as SSLSocket)
                    .verifyingHostname()
                secure.soTimeout = commandTimeout
                secure.startHandshake()
                socket = secure
            }
            var reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            var out: OutputStream = socket.outputStream

                /** The lines of the response [read] last returned, continuations included — the EHLO
                 *  capabilities, which [read] throws away. Local to this send: a field would mix two
                 *  accounts sharing one client. */
            val lastResponse = mutableListOf<String>()

            fun read(): String {
                lastResponse.clear()
                var line = reader.readLine() ?: throw SmtpException("Connection closed")
                lastResponse += line
                    // Skip continuation lines ("250-…"). The RETURN VALUE is the final line and
                    // nothing else: `expect` below compares a prefix on it. The accumulator observes.
                while (line.length >= 4 && line[3] == '-') {
                        // A response that stops between two of its lines is NOT a response. Handing
                        // the last continuation back would pass `expect`'s prefix test — a cut EHLO
                        // would read as a success, its capabilities taken from a partial list.
                    line = reader.readLine() ?: throw SmtpException("Connection closed mid-response")
                    lastResponse += line
                }
                return line
            }

                    /**
                     * The acknowledgement of the message BODY, and that read alone: [read]'s reader
                     */
            fun readBodyAck(): String {
                var line = reader.readLine() ?: throw SmtpException("Connection closed")
                while (line.length >= 4 && line[3] == '-') {
                        // The tolerant exit, and the only one in this client. Its own `val`: `line =
                        // reader.readLine() ?: return line` hands back the OLD value while reading as
                        // if it handed back the new one.
                    val next = reader.readLine() ?: return line
                    line = next
                }
                return line
            }

            fun expect(prefix: String, what: String) {
                val resp = read()
                if (!resp.startsWith(prefix)) throw SmtpException("$what: $resp")
            }

            fun write(line: String) {
                out.write((line + "\r\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }

            expect("220", "greeting")
            write("EHLO ${localHost()}")
            expect("250", "EHLO")
            var advertisedAuth = advertisedAuthMechanisms(lastResponse)

            if (config.security == MailSecurity.STARTTLS) {
                write("STARTTLS")
                expect("220", "STARTTLS")
                val secure = (tlsFactory.createSocket(socket, config.host, config.port, true) as SSLSocket)
                    .verifyingHostname()
                // The upgrade replaces the socket, and a socket option does not travel: this one is
                // armed on the new one, before its handshake, like the implicit-TLS one above.
                secure.soTimeout = commandTimeout
                secure.startHandshake()
                socket = secure
                reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                out = socket.outputStream
                write("EHLO ${localHost()}")
                expect("250", "EHLO (TLS)")
                // RFC 3207: what was advertised in the clear is void. Only this EHLO counts, and it is
                // exactly where a server that offered no AUTH before encryption finally lists one.
                advertisedAuth = advertisedAuthMechanisms(lastResponse)
            }

            // AUTH: XOAUTH2 (OAuth bearer) when a token is present, else the best password mechanism
            // the server actually offers (Codeberg #145 — inventati.org refuses LOGIN outright).
            if (config.accessToken != null) {
                write("AUTH XOAUTH2 ${xoauth2Payload(config.username, config.accessToken)}")
                val resp = read()
                if (!resp.startsWith("235")) {
                    // A rejected token comes back as "334 <base64 error>"; reply with an empty
                    // line so the server can finish (with its 535), then report the failure.
                    if (resp.startsWith("334")) { write(""); read() }
                    throw SmtpException("AUTH XOAUTH2: $resp")
                }
            } else {
                when (chooseAuthMechanism(advertisedAuth)) {
                    SmtpAuthMechanism.PLAIN -> {
                        // One command, initial response included; the server answers 235 or refuses.
                        write("AUTH PLAIN ${saslPlainPayload(config.username, config.password)}")
                        expect("235", "AUTH PLAIN")
                    }
                    SmtpAuthMechanism.LOGIN -> {
                        write("AUTH LOGIN")
                        expect("334", "AUTH")
                        write(base64(config.username))
                        expect("334", "AUTH user")
                        write(base64(config.password))
                        expect("235", "AUTH password")
                    }
                    // The offered list, never the command: an exception is stored in the outbox and
                    // shown on screen, so a base64 credential in it would leak twice over.
                    null -> throw SmtpException(
                        "AUTH: no supported mechanism, server offers ${advertisedAuth.joinToString(" ")}",
                    )
                }
            }

            write("MAIL FROM:<${OutgoingMime.envelopeAddress(message.from)}>")
            expect("250", "MAIL FROM")
            (message.to + message.cc + message.bcc).forEach { recipient ->
                write("RCPT TO:<${OutgoingMime.envelopeAddress(recipient)}>")
                expect("250", "RCPT TO")
            }

            write("DATA")
            expect("354", "DATA")
            val mime = buildMime(message)
                // INVARIANT: `mime` is CRLF-only, so splitting on "\r\n" yields the actual lines.
                // `build`'s seam for `prebuiltEntity` — octets from elsewhere — has to keep it true: an
                // armor with bare LF endings collapses into ONE oversized line (RFC 5321 §4.5.3.1
                // allows 1000 octets), with bare LFs inside and dot-stuffing tested on its first char.
            mime.split("\r\n").forEach { line ->
                // Dot-stuffing: a line starting with '.' must be doubled.
                out.write(((if (line.startsWith(".")) ".$line" else line) + "\r\n").toByteArray(Charsets.UTF_8))
            }
            out.write(".\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
                    // The regime changes here, and here only: what answers the body is the server's
                    // queue, its DKIM signing and its scan, where minutes are normal and a short bound
                    // would have OutboxWorker send an accepted message twice. BOTH sockets: what was
                    // armed twice is disarmed twice, `SSLSocket.setSoTimeout` reaching only itself.
            plain.soTimeout = SMTP_BLOCKING_READ
            socket.soTimeout = SMTP_BLOCKING_READ
            // Not `expect`: a reply cut after a `250-` is an ACCEPTED message here, and only
            // here — see [readBodyAck]. The prefix test stays, so the code still decides.
            val ack = readBodyAck()
            if (!ack.startsWith("250")) throw SmtpException("message body: $ack")

            runCatching { write("QUIT") }
            Unit
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun buildMime(m: OutgoingMessage): String = OutgoingMime.build(m)

    private fun localHost(): String = "[127.0.0.1]"

    private fun base64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private companion object {
        val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    }
}

/** Builds the RFC 5322 message for both SMTP submission and IMAP APPEND. */
object OutgoingMime {
    /**
     * #1837 phase 3: the Rust core's builder, installed by core/data behind MailCore.enabled. Null (the default, and on any
     * failure or an inexpressible message) leaves this Kotlin builder to answer, byte for byte as before.
     */
    @Volatile var coreBuilder: ((OutgoingMessage, BlindCopies) -> String?)? = null

            /**
             * [blindCopies] says whether the blind-copy recipients are written as a `Bcc:` header.
             */
    fun build(m: OutgoingMessage, blindCopies: BlindCopies = BlindCopies.OMITTED): String {
        coreBuilder?.let { core -> runCatching { core(m, blindCopies) }.getOrNull()?.let { return it } }
        return buildInKotlin(m, blindCopies)
    }

    /** The Kotlin engine, kept whole: the fallback of [build] and the reference the parity tests compare the core with. */
    fun buildInKotlin(m: OutgoingMessage, blindCopies: BlindCopies = BlindCopies.OMITTED): String {
        val date = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(m.dateMillis),
            java.time.ZoneOffset.UTC,
        ).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        return buildString {
            append("From: ${headerSafe(m.from)}\r\n")
            // A draft may have no recipient yet (#69); an empty "To:" header is malformed, so omit
            // it entirely. A real send always has recipients, so this only affects saved drafts.
            if (m.to.isNotEmpty()) append("To: ${m.to.joinToString(", ") { headerSafe(it) }}\r\n")
            if (m.cc.isNotEmpty()) append("Cc: ${m.cc.joinToString(", ") { headerSafe(it) }}\r\n")
            // Stored copies only — see [blindCopies]. Without that test this line is a disclosure,
            // not a header: the same bytes are what a submission writes into DATA.
            if (blindCopies == BlindCopies.WRITTEN && m.bcc.isNotEmpty()) append("Bcc: ${m.bcc.joinToString(", ") { headerSafe(it) }}\r\n")
            append("Subject: ${encodeHeader(m.subject)}\r\n")
            append("Date: $date\r\n")
            append("Message-ID: <${headerSafe(m.messageId.trim('<', '>'))}>\r\n")
            m.inReplyTo?.let { append("In-Reply-To: <${headerSafe(it.trim('<', '>'))}>\r\n") }
            m.references?.let { append("References: ${headerSafe(it)}\r\n") }
                // Where a read receipt should be sent (RFC 8098), the address being the From character
                // for character. Written HERE, above the body, so the PGP/MIME branch below carries
                // it too: that entity replaces the body, and a receipt inside it would be lost.
            if (m.requestReceipt) append("Disposition-Notification-To: ${headerSafe(m.from)}\r\n")
                    // This sender's own OpenPGP key (Autocrypt Level 1 §2.1) — the rule is
                    // `autocryptHeaderValue`, in core/data. HERE, above the body: a pre-built entity
                    // replaces the body below, and a header inside it would change the signed octets.
            m.autocryptHeader?.let { append("${AutocryptHeader.NAME}: ${AutocryptHeader.fold(headerSafe(it))}\r\n") }
            append("MIME-Version: 1.0\r\n")
                    // A pre-built entity (PGP/MIME, or a multipart/report receipt) replaces the whole
                    // body. THE SEAM WHERE OCTETS FROM ELSEWHERE ENTER, and the only place that also
            if (m.prebuiltEntity != null) {
                append(PgpMime.canonicalize(m.prebuiltEntity))
            } else {
                append(buildBodyEntity(m))
            }
        }
    }

            /**
             * The message's MIME entity WITHOUT the top-level message headers, starting at its own
             */
    fun buildBodyEntity(m: OutgoingMessage, protectedSubject: String? = null): String {
        val inlineParts = m.attachments.filter { it.inline }
        val fileParts = m.attachments.filter { !it.inline }
            // Both empty when there is no protected subject. [encodeHeader] is the same door the
            // envelope subject goes through, so no header line of this block can be split in two.
        val subjectField = protectedSubject?.let { "Subject: ${encodeHeader(it)}\r\n" }.orEmpty()
        val protectedParam = if (protectedSubject != null) "; protected-headers=\"v1\"" else ""
        val root = bodyTree(m, inlineParts, fileParts)
        return subjectField + "Content-Type: ${root.contentType}$protectedParam\r\n" + root.headers + "\r\n" + root.body
    }

    /**
     * The body as a MIME tree. An HTML message ALWAYS leaves as multipart/alternative — a text/plain
     * rendering first (the composer's own plain text, or one derived from the HTML), the text/html last —
     * so every client gets a body it can show and an HTML-capable one shows the formatting:
     *
     *   mixed( alternative( text/plain, related( text/html, cid images ) ), files )
     *
     * Each level appears only when needed: no files, no mixed; no inline image, no related. A message
     * with no HTML keeps the single text/plain part (or related/mixed around it) it always had.
     */
    private fun bodyTree(m: OutgoingMessage, inlineParts: List<OutgoingAttachment>, fileParts: List<OutgoingAttachment>): MimeNode {
        val html = m.html
        val text = if (html != null) m.body.ifBlank { htmlToPlainText(html) } else m.body
        val textLeaf = leaf("text/plain; charset=utf-8", text)
        val core = if (html == null) {
            if (inlineParts.isEmpty()) textLeaf
            else multipart(m, "related", listOf(textLeaf) + inlineParts.map(::inlineLeaf), "type=\"text/plain\"; ")
        } else {
            val htmlLeaf = leaf("text/html; charset=utf-8", html)
            val htmlSide = if (inlineParts.isEmpty()) htmlLeaf
            else multipart(m, "related", listOf(htmlLeaf) + inlineParts.map(::inlineLeaf), "type=\"text/html\"; ")
            multipart(m, "alternative", listOf(textLeaf, htmlSide))
        }
        return if (fileParts.isEmpty()) core else multipart(m, "mixed", listOf(core) + fileParts.map(::attachmentLeaf))
    }

    /** One MIME entity: its Content-Type value, its other header lines (each CRLF-ended), its body. */
    private class MimeNode(val contentType: String, val headers: String, val body: String, val isLeaf: Boolean)

    private fun leaf(contentType: String, content: String) =
        MimeNode(contentType, "Content-Transfer-Encoding: base64\r\n", base64(content.toByteArray(Charsets.UTF_8)), true)

    private fun multipart(m: OutgoingMessage, subtype: String, parts: List<MimeNode>, typeParam: String = ""): MimeNode {
        val boundary = boundary(m, subtype)
        val body = buildString {
            for (p in parts) {
                append("--$boundary\r\n")
                append("Content-Type: ${p.contentType}\r\n").append(p.headers).append("\r\n").append(p.body)
                if (p.isLeaf) append("\r\n")
            }
            append("--$boundary--\r\n")
        }
        return MimeNode("multipart/$subtype; ${typeParam}boundary=\"$boundary\"", "", body, false)
    }

    /** A file attachment part (Content-Disposition: attachment). */
    private fun attachmentLeaf(att: OutgoingAttachment): MimeNode {
        val safeName = headerSafe(att.name).replace("\"", "")
        val safeType = headerSafe(att.type).replace("\"", "")
        return MimeNode(
            "$safeType; name=\"$safeName\"",
            "Content-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=\"$safeName\"\r\n",
            base64(att.bytes), true,
        )
    }

    /** An inline image of a related entity: Content-ID (what the HTML's `cid:` names) + inline disposition. */
    private fun inlineLeaf(att: OutgoingAttachment): MimeNode {
        val safeName = headerSafe(att.name).replace("\"", "")
        val safeType = headerSafe(att.type).replace("\"", "")
        val safeCid = headerSafe(att.cid ?: "").trim().trim('<', '>').replace("\"", "")
        return MimeNode(
            "$safeType; name=\"$safeName\"",
            "Content-Transfer-Encoding: base64\r\nContent-ID: <$safeCid>\r\nContent-Disposition: inline; filename=\"$safeName\"\r\n",
            base64(att.bytes), true,
        )
    }

    /** Unique-per-message boundary, tagged so nested mixed/alternative/related never collide. */
    private fun boundary(m: OutgoingMessage, tag: String): String =
        "----sterna_${tag}_${m.messageId.filter { it.isLetterOrDigit() }.take(24)}"

            /**
             * MIME base64: 76-column lines separated by CRLF, which `Base64.getMimeEncoder()` already
             */
    private fun base64(bytes: ByteArray): String =
        Base64.getMimeEncoder().encodeToString(bytes)

            /**
             * Strip CR/LF and other control chars from a structured header value, so an
             */
    fun headerSafe(value: String): String =
        value.filterNot { it == '\r' || it == '\n' || it.code < 32 }

            /**
             * The addr-spec for a `MAIL FROM:`/`RCPT TO:`. The LAST '<', so a display name containing
             */
    internal fun envelopeAddress(address: String): String {
        val lt = address.lastIndexOf('<')
        val addr = if (lt >= 0) address.substring(lt + 1).substringBefore('>') else address
        return headerSafe(addr).trim()
    }

    /** RFC 2047-encode a header value if it contains non-ASCII OR control chars (CR/LF). */
    private fun encodeHeader(value: String): String =
        if (value.all { it.code in 32..126 }) {
            value
        } else {
            "=?utf-8?B?${Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))}?="
        }

    /** RFC 5322 "specials": their presence in a display name forces a quoted-string. */
    private val ADDRESS_SPECIALS = "()<>[]:;@\\,.\"".toSet()

            /** A header-correct RFC 5322 mailbox from a display name + address: bare [address] with no
             *  name, else `phrase <address>` — atom when plain ASCII, quoted-string on ASCII specials
             *  or edge whitespace, RFC 2047 encoded-word when non-ASCII. Already quoted/encoded, so
             *  callers must NOT do it again. */
    fun formatAddress(name: String?, address: String): String {
        if (name.isNullOrBlank()) return address
        return "${encodePhrase(name)} <$address>"
    }

    /** Encode a display-name phrase as atom / quoted-string / RFC 2047 encoded-word. */
    private fun encodePhrase(phrase: String): String = when {
        // Non-ASCII (or control chars) -> RFC 2047 encoded-word, itself a valid atom.
        phrase.any { it.code !in 32..126 } -> encodeHeader(phrase)
        // ASCII specials or leading/trailing space -> quoted-string (escape \ then ").
        phrase.any { it in ADDRESS_SPECIALS } || phrase != phrase.trim() ->
            "\"" + phrase.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        // Plain atom(s) -> unchanged.
        else -> phrase
    }
}
