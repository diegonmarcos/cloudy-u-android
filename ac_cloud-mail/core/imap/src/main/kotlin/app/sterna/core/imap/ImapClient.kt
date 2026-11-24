package app.sterna.core.imap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

    /**
     * [responseCode] is the bracketed code (RFC 5530) of a tagged NO/BAD; [taggedStatus] is `"NO"`/
     * `"BAD"` only when the server was heard refusing. A spent read budget is NOT in this class (it
     * arrives as `SocketTimeoutException`), so [ImapSession.move] must exclude it by type first.
     */
class ImapException(
    message: String,
    val responseCode: String? = null,
    val taggedStatus: String? = null,
) : Exception(message)

/**
 * The selected mailbox has been renumbered: its UIDVALIDITY is no longer the one the caller's UIDs
 * were read under (RFC 3501 §2.3.1.1). Deliberately NOT an [ImapException]: reconnect-and-retry
 * paths must not absorb it, since retrying cannot help and swallowing it acts on a stale UID.
 */
class ImapUidValidityChanged(
    val mailbox: String,
    val expected: Long,
    val observed: Long,
) : Exception("UIDVALIDITY of $mailbox changed from $expected to $observed")

/**
 * Turn on RFC 2818 endpoint identification: a bare [SSLSocket] validates only the chain, so without
 * this any CA-valid certificate can MITM the connection. Must be called before the handshake.
 */
internal fun SSLSocket.verifyingHostname(): SSLSocket = apply {
    sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
}

/** Opens authenticated IMAP sessions. The session object carries the live connection. */
class ImapClient(
    /** What this build calls itself in the RFC 2971 `ID` command; `null` sends the name alone.
     *  A parameter, `core/imap` being plain Kotlin/JVM with no `BuildConfig` to read. */
    private val clientVersion: String? = null,
) {
    suspend fun connect(config: MailServerConfig, connectTimeoutMs: Int = 0): ImapSession =
        withContext(Dispatchers.IO) { openSession(config, connectTimeoutMs) }

    /**
     * Blocking connect + login. Use when already on a dedicated IO thread (e.g. IDLE).
     * [connectTimeoutMs] bounds the TCP connect AND the greeting/STARTTLS/login reads, after which
     * the socket returns to its blocking default so no later operation inherits it.
     */
    fun openSession(config: MailServerConfig, connectTimeoutMs: Int = 0): ImapSession {
        val timeout = connectTimeoutMs.coerceAtLeast(0)
        val plain = Socket()
        plain.connect(InetSocketAddress(config.host, config.port), timeout)
        val socket = when (config.security) {
            MailSecurity.TLS ->
                (tlsFactory.createSocket(plain, config.host, config.port, true) as SSLSocket).verifyingHostname()
            else -> plain
        }
        val session = ImapSession(socket)
        session.withReadTimeout(timeout) {
            session.readGreeting()
            if (config.security == MailSecurity.STARTTLS) {
                session.command("STARTTLS")
                session.upgradeTls(config.host, config.port)
            }
            if (config.accessToken != null) {
                session.authenticateXoauth2(config.username, config.accessToken)
            } else {
                session.login(config.username, config.password)
            }
            // Post-authentication, so the capability list is the real one (RFC 3501 §7.1), and
            // ahead of any SELECT — the window NetEase's servers judge (Codeberg #173).
            session.identify(clientVersion)
        }
        return session
    }

    /**
     * A connected socket (TLS wrapped for [MailSecurity.TLS]) with NO greeting read and NO login: the byte pipe the Rust core's IMAP
     * client speaks over (#1837 phase 3). STARTTLS needs the protocol first, so it is not offered here.
     */
    fun openRawSocket(config: MailServerConfig, connectTimeoutMs: Int = 0): Socket {
        require(config.security != MailSecurity.STARTTLS) { "STARTTLS is negotiated in-protocol" }
        val plain = Socket()
        plain.connect(InetSocketAddress(config.host, config.port), connectTimeoutMs.coerceAtLeast(0))
        return if (config.security == MailSecurity.TLS) {
            (tlsFactory.createSocket(plain, config.host, config.port, true) as SSLSocket).verifyingHostname()
        } else plain
    }

    private companion object {
        val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    }
}

/**
 * A connected, logged-in IMAP session. Stateful (SELECT then FETCH), so callers
 * keep it for a unit of work and [close] it after. Not thread-safe.
 */
class ImapSession(private var socket: Socket) : Closeable {
    private var input = ImapParser(BufferedInputStream(socket.inputStream))
    private var output: OutputStream = socket.outputStream
    private var tagN = 0

    /** What the server says it supports, learned once per session. See [capabilities]. */
    private var advertisedCapabilities: Set<String>? = null

    internal fun readGreeting() {
        // The greeting's capability list is deliberately not kept: RFC 3501 §7.1 lets it differ
        // from the post-authentication one, which is the only state this session acts in.
        input.readResponse()
    }

    internal fun upgradeTls(host: String, port: Int) {
        val tls = ((SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(socket, host, port, true) as SSLSocket).verifyingHostname()
        tls.startHandshake()
        socket = tls
        input = ImapParser(BufferedInputStream(tls.inputStream))
        output = tls.outputStream
    }

    internal fun login(username: String, password: String) {
        rememberCapabilities(command("LOGIN ${quote(username)} ${quote(password)}").tagged)
    }

        /**
         * Upper-cased capability names (RFC 3501 §6.1.1), asked at most once per session — most servers
         * volunteer the list in their authentication completion. [identify] puts a `CAPABILITY` of
         * its own without filling this cache. No list at all is taken at its word: an empty set.
         */
    fun capabilities(): Set<String> =
        advertisedCapabilities ?: parseCapabilities(command("CAPABILITY").untagged)
            .also { advertisedCapabilities = it }

    /** Whether the server advertises [name], e.g. `UIDPLUS`. Case-insensitive. */
    fun hasCapability(name: String): Boolean = name.uppercase() in capabilities()

    /** Take the `[CAPABILITY …]` code of an authentication completion as the session's list: free,
     *  and unlike the greeting's it is the post-authentication one. */
    private fun rememberCapabilities(taggedLine: List<Any?>) {
        val advertised = capabilitiesInResponseCode(taggedLine) ?: return
        if (advertised.isNotEmpty()) advertisedCapabilities = advertised
    }

    /**
     * Authenticate with SASL XOAUTH2. On a bad token the server sends a "+" base64 error challenge
     * and waits for an empty client response before the tagged NO, so we acknowledge it or hang.
     */
    internal fun authenticateXoauth2(username: String, accessToken: String) {
        val tag = "a${++tagN}"
        output.write("$tag AUTHENTICATE XOAUTH2 ${xoauth2Payload(username, accessToken)}\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        while (true) {
            val resp = input.readResponse()
            if (resp.isEmpty()) {
                if (socket.isClosed) throw ImapException("Connection closed")
                continue
            }
            when (resp[0]) {
                tag -> {
                    val status = resp.getOrNull(1) as? String ?: "BAD"
                    if (status != "OK") {
                        throw ImapException(
                            "AUTHENTICATE … failed: ${resp.drop(1).joinToString(" ")}",
                            responseCodeIn(resp),
                            status,
                        )
                    }
                    rememberCapabilities(resp)
                    return
                }
                "+" -> {
                    output.write("\r\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                // else: an untagged "*" status line — ignore and read on for the tag.
            }
        }
    }

        /**
         * `ID ("name" "Sterna Mail" "version" "…")`, RFC 2971. NetEase (163/126/188/yeah.net) refuses
         * every SELECT with `NO SELECT Unsafe Login` unless the client NAMES itself (#173); RFC 2971 §3
         * forbids sending ID unadvertised, so a server that published no list at login pays a
         * `CAPABILITY` whose answer is thrown away. Best effort inside one `runCatching`:
         * [ImapClient.openSession] has no `try/finally`, so a throw leaks the socket.
         */
    internal fun identify(clientVersion: String?) {
        runCatching {
            val published = advertisedCapabilities
            val advertised = published ?: parseCapabilities(command("CAPABILITY").untagged)
            if (CAP_ID !in advertised) return@runCatching
            val fields = buildList {
                add(quote("name"))
                add(quote(CLIENT_NAME))
                if (clientVersion != null) {
                    add(quote("version"))
                    add(quote(clientVersion))
                }
            }
            command("ID (${fields.joinToString(" ")})")
        }
    }

    /** Strip credential-bearing arguments before a command appears in an error/log. */
    private fun redactCommand(line: String): String {
        val verb = line.substringBefore(' ').uppercase()
        return if (verb == "LOGIN" || verb == "AUTHENTICATE") "$verb …" else line
    }

    /**
     * Bound every socket read inside [block] to [millis], then restore the previous setting, so one
     * operation gets a deadline without any other inheriting it. A per-READ bound, not a total.
     */
    fun <T> withReadTimeout(millis: Int, block: () -> T): T {
        val previous = armReadTimeout(millis)
        try {
            return block()
        } finally {
            disarmReadTimeout(previous)
        }
    }

    /**
     * [withReadTimeout] for a block that SUSPENDS; a plain function cannot take a suspending lambda
     * and making the other one suspend would drag `connect` with it. What they share is
     * [armReadTimeout]/[disarmReadTimeout], and `ImapBudgetSharedTest` refuses to let them differ.
     */
    suspend fun <T> withReadTimeoutSuspending(millis: Int, block: suspend () -> T): T {
        val previous = armReadTimeout(millis)
        try {
            return block()
        } finally {
            disarmReadTimeout(previous)
        }
    }

    /**
     * Bound this socket's reads to [millis] and answer with the previous bound — the load-bearing
     * half of both wrappers, in ONE place: written out twice, dropping the assignment from the copy
     * no test reaches leaves every budget test green while the bound stops applying.
     */
    private fun armReadTimeout(millis: Int): Int {
        val previous = socket.soTimeout
        socket.soTimeout = millis.coerceAtLeast(0)
        return previous
    }

    /** Put back what [armReadTimeout] answered. The field can have been swapped by a STARTTLS
     *  upgrade inside the block, so this restores on whatever socket is current — and never throws
     *  on the way out of a block that is already failing. */
    private fun disarmReadTimeout(previous: Int) {
        runCatching { socket.soTimeout = previous }
    }

        /**
         * [maxTokensKept] caps what the WHOLE response retains, not each line: a server free to split
         * its answer over K lines would otherwise be allowed K times the cap. Plus [STATUS_LINE_TOKENS]
         * per line once it is spent, so a status line stays readable.
         */
    internal fun command(line: String, maxTokensKept: Int = Int.MAX_VALUE): ImapResult {
        val tag = "a${++tagN}"
        output.write("$tag $line\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        val untagged = mutableListOf<List<Any?>>()
        var budget = maxTokensKept
        while (true) {
            // A floor per line whatever is left of the budget: the tagged status line must stay
            // recognisable, or this loop would not recognise its own answer and would read forever.
            val resp = input.readResponse(budget.coerceAtLeast(STATUS_LINE_TOKENS))
            if (resp.isEmpty()) {
                if (socket.isClosed) throw ImapException("Connection closed")
                continue
            }
            when (resp[0]) {
                tag -> {
                    val status = resp.getOrNull(1) as? String ?: "BAD"
                    // Redact the echoed command: LOGIN/AUTHENTICATE carry the password,
                    // and this message surfaces to the UI and logs.
                    if (status != "OK") {
                        throw ImapException(
                            "${redactCommand(line)} failed: ${resp.drop(1).joinToString(" ")}",
                            responseCodeIn(resp),
                            status,
                        )
                    }
                    return ImapResult(status, untagged, resp)
                }
                else -> {
                    untagged.add(resp) // "*" untagged or "+" continuation
                    budget -= resp.size
                }
            }
        }
    }

    /**
     * Run one IMAP IDLE cycle on the selected mailbox: true on new mail (untagged EXISTS or RECENT),
     * false on the keep-alive timeout. Always ends IDLE and consumes the tagged result.
     */
    fun idle(timeoutMs: Int): Boolean {
        val tag = "a${++tagN}"
        output.write("$tag IDLE\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        val previousTimeout = socket.soTimeout
        socket.soTimeout = timeoutMs
        var changed = false
        try {
            while (true) {
                val resp = try {
                    input.readResponse()
                } catch (_: java.net.SocketTimeoutException) {
                    break // keep-alive window elapsed — refresh IDLE
                }
                if (resp.isEmpty()) {
                    if (socket.isClosed) throw ImapException("Connection closed during IDLE")
                    continue
                }
                // Untagged "* <n> EXISTS" / "* <n> RECENT" announce new mail.
                val kind = resp.getOrNull(2)
                if (kind == "EXISTS" || kind == "RECENT") {
                    changed = true
                    break
                }
                // "+ idling" continuation and other status updates: keep waiting.
            }
        } finally {
            socket.soTimeout = previousTimeout
            // End IDLE and drain to its tagged completion so the stream is clean.
            runCatching {
                output.write("DONE\r\n".toByteArray(Charsets.UTF_8))
                output.flush()
                while (true) {
                    val resp = input.readResponse()
                    if (resp.isEmpty()) {
                        if (socket.isClosed) break else continue
                    }
                    if (resp[0] == tag) break
                }
            }
        }
        return changed
    }

        /**
         * Unicode in, modified UTF-7 out, then quoted. EVERY command naming a mailbox goes through
         * this, so adding one cannot quietly skip it (Codeberg #101). Encode BEFORE quote: the encoded
         * form is ASCII, so [quote]'s CR/LF refusal still holds.
         */
    private fun mailboxArg(path: String): String = quote(encodeModifiedUtf7(path))

        /**
         * Also SUBSCRIBEs; succeeds quietly if the mailbox exists. `CREATE` does not touch the
         * subscription list, so with "subscribed folders only" on the new folder would vanish from the
         * drawer the instant it appeared (#174). The only subscription Sterna ever writes. The
         * SUBSCRIBE goes out even when the CREATE failed (the swallowed failure is "already exists"),
         * a refused SUBSCRIBE never fails the creation, and both carry the SAME [mailboxArg] value.
         */
    fun createFolder(path: String) {
        val mailbox = mailboxArg(path)
        runCatching { command("CREATE $mailbox") }
        runCatching { command("SUBSCRIBE $mailbox") }
    }

    /** Rename a mailbox from [oldPath] to [newPath]. */
    fun renameFolder(oldPath: String, newPath: String) {
        command("RENAME ${mailboxArg(oldPath)} ${mailboxArg(newPath)}")
    }

    /** Delete a mailbox. */
    fun deleteFolder(path: String) {
        command("DELETE ${mailboxArg(path)}")
    }

    /** Move a message to another mailbox; the answer carries its new UID in the destination and
     *  that destination's numbering, which an Undo of THIS message will have to oppose. */
    fun move(uid: Long, destination: String): ImapMoved = move(listOf(uid), destination)

        /**
         * One `UID MOVE <set> <dest>` per chunk (Codeberg #29), falling back to `UID COPY` + `\Deleted`
         * + a purge; the caller must have SELECTed the source. That fallback is reserved for a server
         * that ANSWERED with a tagged NO/BAD and does not advertise MOVE: every other failure travels
         * up untouched, a read timeout and a lost connection included. Chunks disagreeing on the
         * destination's numbering leave [ImapMoved.destinationUidValidity] null, its refusing value.
         */
    fun move(uids: List<Long>, destination: String): ImapMoved {
        val mapping = LinkedHashMap<Long, Long>()
        // A set, because the only question asked of it is whether every chunk said the same thing.
        val statedNumbering = LinkedHashSet<Long>()
        // The no-UIDPLUS purge is folder-wide, so it is decided once for the whole move, over the
        // union of the chunks that took the copy fallback — a chunk sent as UID MOVE flagged nothing.
        val flaggedByFallback = mutableSetOf<Long>()
        // The source-side verdict, chunk by chunk: see [ImapMoved.confirmedGone].
        val gone = LinkedHashSet<Long>()
        for (chunk in uids.distinct().chunked(UID_SET_CHUNK)) {
            val set = compressUidSet(chunk)
            if (set.isEmpty()) continue
            // BEFORE the move touches anything: which of this chunk's UIDs the source still answers
            // for. Only the DIFFERENCE between the two observations is a departure this session
            // caused; a failed probe proves no presence. Not gated on a capability — Stalwart
            // advertises neither MOVE nor UIDPLUS and returns COPYUID anyway.
            val presentBefore = runCatching { uidsStillInSource(set) }.getOrElse { emptySet() }
            // Whether THIS chunk left as a real `UID MOVE`, which decides whether it may be probed
            // at all: the copy fallback below leaves its originals in the source folder.
            var byMove = true
            val result = runCatching { command("UID MOVE $set ${mailboxArg(destination)}") }.getOrElse {
                // FIRST statement of the block: from here on the chunk is a fallback chunk whatever
                // happens to it, and a probe sent on one is a false negative by construction.
                byMove = false
                    // Destructive — copy, flag, purge the SOURCE — so it takes two conditions: the
                    // server was HEARD refusing (a dead socket says nothing, and a server gone quiet
                    // may have PERFORMED the move), and its capability list does not carry MOVE.
                    // Condition 1 is on the TYPE of the failure, and deliberately not a precondition
                    // on the UID MOVE: servers implement it without advertising it.
                if (it !is ImapException || it.taggedStatus == null) throw it
                // The probe stays wrapped, and an unanswerable one still means "rethrow": a
                // `CAPABILITY` that cannot be answered right behind the refusal says the socket left
                // in between, and not copying is the right side of that error.
                val announcesMove = runCatching { hasCapability(CAP_MOVE) }.getOrDefault(true)
                if (announcesMove) throw it
                val byUid = hasCapability(CAP_UIDPLUS)
                val copy = command("UID COPY $set ${mailboxArg(destination)}")
                command("UID STORE $set +FLAGS (\\Deleted)")
                // Best effort, unlike in [delete]: the copy has already landed, so failing here
                // would have the caller retry a move that would copy a second time.
                if (byUid) {
                    runCatching { command("UID EXPUNGE $set") }
                } else {
                    flaggedByFallback += chunk
                }
                copy
            }
            val landed = parseCopyUidReport(result)
            mapping.putAll(landed.uids)
            landed.destinationUidValidity?.let { statedNumbering += it }
            gone += confirmedGoneInChunk(chunk, set, landed.uids.keys, byMove, presentBefore)
        }
        finishPurgeWithoutUidPlus(flaggedByFallback)
        return ImapMoved(mapping, statedNumbering.singleOrNull(), gone)
    }

        /**
         * For [ImapMoved.confirmedGone]. A covering `COPYUID` is authoritative; otherwise, and only for
         * a `UID MOVE` chunk, a UID is confirmed gone when it was in [presentBefore] and is absent from
         * a second probe; a failed probe confirms nothing. The probe AFTER, alone, is no verdict, and
         * upstairs a wrong verdict drops the row AND its FTS entry. The copy fallback is never probed —
         * `COPYUID` says the server copied those messages OUT, not that they have left.
         */
    private fun confirmedGoneInChunk(
        chunk: List<Long>,
        set: String,
        namedByCopyUid: Set<Long>,
        byMove: Boolean,
        presentBefore: Set<Long>,
    ): Set<Long> {
        val handedIn = chunk.toSet()
        val stated = namedByCopyUid intersect handedIn
        if (stated.containsAll(handedIn)) return handedIn
        if (!byMove) return stated
        val stillThere = runCatching { uidsStillInSource(set) }.getOrElse { return emptySet() }
        // Present before, absent after — nothing else. `handedIn - stillThere` alone would credit
        // the move with a UID that had already left the folder before the command went out.
        return stated + ((presentBefore intersect handedIn) - stillThere)
    }

        /**
         * The identity probe of [confirmedGoneInChunk]. NO token cap, unlike [allUids]: the answer is
         * already bounded by the question, and truncation falls the WRONG WAY — a dropped UID is
         * reported as PROVED GONE, destroying the row of a message still in the folder. Every untagged
         * `SEARCH` line is read, a server being free to split its result.
         */
    private fun uidsStillInSource(set: String): Set<Long> =
        command("UID SEARCH UID $set").untagged
            .filter { it.getOrNull(1) == "SEARCH" }
            .flatMap { line -> line.drop(2).mapNotNull { (it as? String)?.toLongOrNull() } }
            .toSet()

    /** Fetch a single message by UID (envelope + flags), or null if not found — or hidden. */
    fun fetchByUid(uid: Long): ImapMessage? {
        val result = command("UID FETCH $uid (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])")
        return result.messages().firstOrNull()
    }

        /**
         * The ONE place a message flagged `\Deleted` is dropped, so it cannot appear in a folder list,
         * a search result, a notification or a restored batch (Codeberg #99) — every fetch here funnels
         * through it, so a purge that legitimately stops short of its EXPUNGE does not walk its messages
         * back into the list. HIDING IS NOT DELETING: the snapshot and the purge use `UID SEARCH`.
         */
    private fun ImapResult.messages(): List<ImapMessage> =
        untagged.mapNotNull { parseFetch(it) }.filterNot { it.deleted }

    private fun parseCopyUid(result: ImapResult): Long? {
        val text = (result.untagged + listOf(result.tagged)).joinToString(" ") { resp ->
            resp.joinToString(" ") { flatten(it) }
        }
        // [COPYUID <uidvalidity> <sourceUid> <destUid>]
        val m = Regex("COPYUID\\s+\\d+\\s+[\\d,:]+\\s+(\\d+)").find(text) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

        /**
         * RFC 4315: the ordered source→destination mapping and the destination folder's stated
         * numbering, the two sets corresponding positionally. An empty mapping if they do not line up —
         * the numbering still stands, being a property of the FOLDER; `<uidvalidity>` at or below 0
         * states nothing.
         */
    private fun parseCopyUidReport(result: ImapResult): ImapMoved {
        val text = (result.untagged + listOf(result.tagged)).joinToString(" ") { resp ->
            resp.joinToString(" ") { flatten(it) }
        }
        val m = Regex("COPYUID\\s+(\\d+)\\s+([\\d,:]+)\\s+([\\d,:]+)").find(text) ?: return ImapMoved.NONE
        return ImapMoved(
            uids = copyUidMapping(m.groupValues[2], m.groupValues[3]),
            destinationUidValidity = m.groupValues[1].toLongOrNull()?.takeIf { it > 0L },
            // Empty here on purpose: this parses ONE response and knows nothing about which command
            // carried it. Whether a COPYUID amounts to "it left the source" is [move]'s to say.
            confirmedGone = emptySet(),
        )
    }

        /**
         * `RETURN (SPECIAL-USE)` (RFC 6154 §5) is asked only of a server that advertised it: one that
         * did not answers BAD and the drawer comes back empty. [onlySubscribed] adds a `LSUB "" "*"`
         * (core IMAP4rev1, so no guard) and only MARKS each folder. A failed LSUB means "unknown",
         * never "subscribed to nothing": `null` leaves every folder subscribed.
         */
    fun listFolders(onlySubscribed: Boolean = false): List<ImapFolder> {
        val list = if (hasCapability("SPECIAL-USE")) "LIST \"\" \"*\" RETURN (SPECIAL-USE)" else "LIST \"\" \"*\""
        val folders = parseListFolders(command(list).untagged)
        if (!onlySubscribed) return folders
        val subscribed = runCatching { parseLsubPaths(command("LSUB \"\" \"*\"").untagged) }.getOrNull()
        return withSubscriptions(folders, subscribed)
    }

        /**
         * [expectedUidValidity] is the UIDVALIDITY this caller's UIDs were read under; a different one
         * means every UID was reassigned (RFC 3501 §2.3.1.1), so the call is refused with
         * [ImapUidValidityChanged] before any command naming a UID goes out (Codeberg #99). A server
         * stating no UIDVALIDITY (0) is not refused. THE ONE RETRY: a name that is not a valid encoding
         * of itself (a bare `&`) cannot be told from the decoding of `R&-D`, so the verbatim form is
         * tried after the standard one is refused — SELECT alone, being read-only and idempotent.
         */
    fun select(path: String, expectedUidValidity: Long? = null): ImapMailboxStatus {
        val encoded = mailboxArg(path)
        val result = try {
            command("SELECT $encoded")
        } catch (refused: ImapException) {
            val verbatim = quote(path)
            // Only where the ambiguity can exist: a pure-ASCII path the encoder would nonetheless
            // have changed. A non-ASCII path has exactly one wire form, so a refusal there means
            // what it says.
            if (verbatim == encoded || path.any { it.code !in 0x20..0x7E }) throw refused
            command("SELECT $verbatim")
        }
        var exists = 0
        // Zero is also what this holds when the server said NOTHING about the folder's size, and
        // the two must not read alike: a walk built on an unstated zero brings back no UID, and the
        // reconcile takes that for an empty folder and DELETES the cache.
        var existsObserved = false
        var uidValidity = 0L
        var uidNext = 0L
        for (resp in result.untagged) {
            // * <n> EXISTS  |  * OK [UIDVALIDITY n] ...  |  * OK [UIDNEXT n] ...
            if (resp.getOrNull(2) == "EXISTS") {
                // Same strictness as [folderMoved]'s: an n that will not parse is not a count.
                (resp.getOrNull(1) as? String)?.toIntOrNull()?.let { exists = it; existsObserved = true }
            }
            val flat = resp.joinToString(" ") { it?.toString() ?: "NIL" }
            Regex("UIDVALIDITY (\\d+)").find(flat)?.let { uidValidity = it.groupValues[1].toLong() }
            Regex("UIDNEXT (\\d+)").find(flat)?.let { uidNext = it.groupValues[1].toLong() }
        }
        if (expectedUidValidity != null && expectedUidValidity > 0L &&
            uidValidity > 0L && uidValidity != expectedUidValidity
        ) {
            throw ImapUidValidityChanged(path, expectedUidValidity, uidValidity)
        }
        return ImapMailboxStatus(exists, uidValidity, uidNext, existsObserved)
    }

    /** Fetch a page of messages by sequence number, newest first: [offset] skips the newest N, up
     *  to [limit] come back, mapped onto high sequence numbers via [exists] from a prior SELECT. */
    fun fetchPage(exists: Int, offset: Int, limit: Int): List<ImapMessage> {
        val highest = exists - offset
        if (highest < 1) return emptyList()
        val lowest = (highest - limit + 1).coerceAtLeast(1)
        val result = command("FETCH $lowest:$highest (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])")
        return result.messages().sortedByDescending { it.uid }
    }

        /**
         * Each page goes to [onPage] AS IT LANDS. [onPage] runs inside the caller's session, so the
         * account's ONE connection is held for the whole walk. [status] whole, not just its count:
         * [ImapFolderWalk.folderStatedEmpty] separates "the server said it holds nothing" from "it never
         * stated a size", and the caller DELETES on that difference. Movement is detected by a `NOOP`
         * before every request but the first (RFC 3501 §7.4.1) and by the untagged lines of every
         * `FETCH`, the only detector a one-page walk has.
         */
    suspend fun walkFolder(
        status: ImapMailboxStatus,
        limit: Int,
        pageSize: Int,
        onPage: suspend (List<ImapMessage>) -> Unit,
    ): ImapFolderWalk {
        val exists = status.exists
        val lowest = folderWindowLowest(exists, limit)
        // De-duplicated BY UID: the walk's own pages can overlap after a renumbering, and a
        // sequence number is not a name. Insertion-ordered so the result stays newest-first.
        val seen = LinkedHashSet<Long>()
        var moved = false
        var previous: IntRange? = null
        while (true) {
            val page = nextFolderPage(lowest, exists, previous, pageSize) ?: break
            // Every request but the first: give the server its chance to say the folder moved,
            // before the numbering of the range below is used. The NOOP goes out whatever the
            // verdict so far, hence `folderMoved(...) || moved` and not the other way round.
            if (previous != null) moved = folderMoved(command("NOOP").untagged, exists) || moved
            val result = command("FETCH ${page.first}:${page.last} (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])")
            // On a one-page walk no NOOP is ever sent, so this line is the only thing that can
            // notice the folder moving.
            moved = folderMoved(result.untagged, exists) || moved
            // Through messages(), like every other fetch here: the one place a `\Deleted` message
            // is dropped (Codeberg #99).
            val fresh = result.messages().sortedByDescending { it.uid }.filter { seen.add(it.uid) }
            if (fresh.isNotEmpty()) onPage(fresh)
            previous = page
        }
        // "Nothing came back" is not "there is nothing": an empty [seen] is equally a folder of
        // zero messages, a SELECT that never stated a size, and a page whose FETCH was unreadable.
        // Only the first may clear a cache, and only the SELECT tells them apart.
        return ImapFolderWalk(
            uids = seen.toList(),
            moved = moved,
            folderStatedEmpty = status.existsObserved && exists == 0,
        )
    }

    /** Unseen messages in the selected mailbox, `\Deleted` ones excluded: this count is the account
     *  badge while the list it labels drops those same messages ([messages]). */
    fun unseenCount(): Int {
        val result = command("SEARCH UNSEEN UNDELETED")
        val line = result.untagged.firstOrNull { it.getOrNull(1) == "SEARCH" } ?: return 0
        return line.drop(2).count { it is String }
    }

    /**
     * UIDs in the SELECTed mailbox matching a built [ImapSearchCommand]. `CHARSET UTF-8` is declared
     * only when a value needs it; RFC 3501 lets a server support US-ASCII alone and answer
     * `NO [BADCHARSET]`, so that case retries once without the declaration.
     */
    fun searchUids(search: ImapSearchCommand): List<Long> {
        val result = try {
            command("UID SEARCH ${search.arguments()}")
        } catch (e: ImapException) {
            if (!search.needsUtf8) throw e
            command("UID SEARCH ${search.arguments(declareUtf8 = false)}")
        }
        val line = result.untagged.firstOrNull { it.getOrNull(1) == "SEARCH" } ?: return emptyList()
        return line.drop(2).mapNotNull { (it as? String)?.toLongOrNull() }
    }

        /**
         * The whole folder, not the synced window, and without fetching an envelope (used to freeze an
         * "Empty trash", Codeberg #99). [cap] is enforced DURING the parse: without ESEARCH the server
         * cannot be asked for fewer, so a huge folder is read to the end while only [cap] ids are held
         * — the ones listed FIRST, i.e. the OLDEST, where the JMAP snapshot caps at the other end.
         * Every untagged `SEARCH` line is read, unlike [searchUids]: a dropped tail shortens the list.
         */
    fun allUids(cap: Int): List<Long> = uidSearchCapped("ALL", cap)

        /**
         * The other end of the folder from [allUids], for the draft de-duplication of #95 whose copy is
         * the LAST thing `APPEND`ed to Drafts. `ALL` because `HEADER "Message-ID"` is not universal:
         * Stalwart answers an empty `* SEARCH` to every shape of it, indistinguishable from "your draft
         * is not there". No cap during the parse, the ids wanted being at the END. A refused search
         * still THROWS — "I could not look" must read as "append it".
         */
    fun newestUids(cap: Int): List<Long> {
        if (cap <= 0) return emptyList()
        return command("UID SEARCH ALL").untagged
            .filter { it.getOrNull(1) == "SEARCH" }
            .flatMap { line -> line.drop(2).mapNotNull { (it as? String)?.toLongOrNull() } }
            .sorted()
            .takeLast(cap)
    }

        /**
         * `UID SEARCH HEADER` (RFC 3501 §6.4.4), quoted through [quote]. Not a search the user runs —
         * `ImapSearch.kt` keeps its `HEADER` ban because Stalwart answers this key empty. Here that
         * empty answer is READ AS "not found" on purpose, by a caller whose safe reading of it is a
         * duplicate rather than a loss (#189).
         */
    fun uidSearchHeader(name: String, value: String, cap: Int = 2): List<Long> =
        uidSearchCapped("HEADER ${quote(name)} ${quote(value)}", cap)

    /**
     * At most [cap] UIDs matching one plain search [key] (`ALL`, `DELETED`, …), the cap enforced
     * during the parse as [allUids] describes. Every untagged `SEARCH` line is read: a dropped tail
     * would shorten a list that is about to decide what gets destroyed.
     */
    private fun uidSearchCapped(key: String, cap: Int): List<Long> {
        if (cap <= 0) return emptyList()
        // +2 leaves room for the "*" and "SEARCH" that open the first line.
        val keep = if (cap > Int.MAX_VALUE - 2) Int.MAX_VALUE else cap + 2
        return command("UID SEARCH $key", maxTokensKept = keep).untagged
            .filter { it.getOrNull(1) == "SEARCH" }
            .flatMap { line -> line.drop(2).mapNotNull { (it as? String)?.toLongOrNull() } }
            .take(cap)
    }

    /** Fetch several messages by UID (envelope + flags); `\Deleted` ones are not returned. */
    fun fetchUids(uids: List<Long>): List<ImapMessage> {
        if (uids.isEmpty()) return emptyList()
        val result = command("UID FETCH ${uids.joinToString(",")} (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])")
        return result.messages()
    }

    /** Raw content of one MIME section (e.g. an attachment), still transfer-encoded. */
    fun fetchSection(uid: Long, section: String): String =
        command("UID FETCH $uid (BODY.PEEK[$section])").bodyItem()

        /**
         * A partial fetch (RFC 3501 §6.4.5). `BODY.PEEK`: the caller is a NOTIFICATION, so a bare
         * `BODY[…]` would set `\Seen` the moment the mail is announced, on every device, irreversibly.
         * And `<0.[maxBytes]>`, without which the server sends the WHOLE part. The `take` is what
         * makes "[maxBytes] at most" TRUE rather than requested: a literal is read up to 32 MiB.
         */
    fun fetchSectionPartial(uid: Long, section: String, maxBytes: Int): String =
        command("UID FETCH $uid (BODY.PEEK[$section]<0.$maxBytes>)").bodyItem().take(maxBytes)

        /**
         * [fetchSectionPartial] for a caller holding a list, keyed BY UID — it exists because the
         * single-message form is one round trip each over the account's single connection.
         * `BODY.PEEK` and `<0.[maxBytes]>` and the `take`, as [fetchSectionPartial] states: a bare
         * fetch here would mark the mailbox read. [uids] is de-duplicated and SORTED before it is cut
         * into pages, or the ranges no longer line up with them.
         */
    fun fetchSectionPartials(uids: List<Long>, section: String, maxBytes: Int): Map<Long, String> {
        // Not "the loop below does nothing anyway": an empty list must put NO command on the
        // wire at all, and `compressUidSet(emptyList())` would otherwise send `UID FETCH  (…)`.
        if (uids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Long, String>()
        for (page in uids.distinct().sorted().chunked(IMAP_PREVIEW_FETCH_CHUNK)) {
            val set = compressUidSet(page)
            if (set.isEmpty()) continue
            val result = command("UID FETCH $set (BODY.PEEK[$section]<0.$maxBytes>)")
            out += result.bodyItemsByUid(maxBytes)
        }
        return out
    }

    /** Raw RFC822 source of a message, for parsing the body/attachments. */
    fun fetchSource(uid: Long): String =
        command("UID FETCH $uid (BODY.PEEK[])").bodyItem()

        /**
         * `INTERNALDATE` (RFC 3501 §2.3.3) in epoch millis, or `null` when the server answers nothing
         * readable. Never confused with [ImapMessage.dateMillis], the `Date:` HEADER written by
         * whoever sent the message: this one is stamped on delivery and cannot be forged, which is why
         * it dates an Autocrypt key — a key dated in 2100 could never be beaten by an authentic one.
         */
    fun fetchInternalDate(uid: Long): Long? {
        val fetch = command("UID FETCH $uid (INTERNALDATE)").untagged
            .firstOrNull { it.getOrNull(2) == "FETCH" } ?: return null
        @Suppress("UNCHECKED_CAST")
        val items = fetch.getOrNull(3) as? List<Any?> ?: return null
        val raw = pairUp(items)["INTERNALDATE"] as? String ?: return null
        return imapDateTimeMillis(raw)
    }

        /**
         * The one reading shared by the three body fetches above. The empty string when the answer
         * carries NO body item: the item list opens with `UID <n>`, so an `indexOfFirst` answering -1
         * and used as-is hands back the atom "UID" as though it were the message. Matched by PREFIX
         * (`BODY[1]<0>`), but never the `BODY[HEADER.FIELDS (REFERENCES)]` item it would take.
         */
    private fun ImapResult.bodyItem(): String {
        val fetch = untagged.firstOrNull { it.getOrNull(2) == "FETCH" } ?: return ""
        @Suppress("UNCHECKED_CAST")
        val items = fetch.getOrNull(3) as? List<Any?> ?: return ""
        val idx = items.indexOfFirst { it is String && it.startsWith("BODY", true) && !isReferencesItem(it) }
        if (idx < 0) return ""
        return (items.getOrNull(idx + 1) as? String).orEmpty()
    }

        /**
         * The reader of [fetchSectionPartials]. Keyed by the `UID` item of EACH line, never by
         * position: a grouped `UID FETCH` is answered one line per message, in any order, numbered by
         * SEQUENCE. No entry rather than an empty one, for the reason [bodyItem] returns "".
         */
    private fun ImapResult.bodyItemsByUid(maxBytes: Int): Map<Long, String> {
        val out = LinkedHashMap<Long, String>()
        for (line in untagged) {
            if (line.getOrNull(2) != "FETCH") continue
            @Suppress("UNCHECKED_CAST")
            val items = line.getOrNull(3) as? List<Any?> ?: continue
            val uidIdx = items.indexOfFirst { it is String && it.equals("UID", true) }
            if (uidIdx < 0) continue
            val uid = (items.getOrNull(uidIdx + 1) as? String)?.toLongOrNull() ?: continue
            val bodyIdx = items.indexOfFirst { it is String && it.startsWith("BODY", true) && !isReferencesItem(it) }
            if (bodyIdx < 0) continue
            val body = (items.getOrNull(bodyIdx + 1) as? String).orEmpty()
            if (body.isEmpty()) continue
            out[uid] = body.take(maxBytes)
        }
        return out
    }

    fun setFlag(uid: Long, flag: String, set: Boolean) {
        val op = if (set) "+FLAGS" else "-FLAGS"
        command("UID STORE $uid $op ($flag)")
    }

    fun delete(uid: Long) = delete(listOf(uid))

        /**
         * One `UID STORE <set> +FLAGS (\Deleted)` per chunk (Codeberg #29), erased with
         * `UID EXPUNGE <set>` when the server implements UIDPLUS (RFC 4315). Whether it does is asked
         * ONCE, before the first chunk: the alternative ([purgeWithoutUidPlus]) is folder-wide, so
         * asking per chunk would apply a folder-wide command fifty times over an "Empty trash" (#99).
         * A refused `UID EXPUNGE` on a UIDPLUS server throws, and the destroy worker retries.
         */
    fun delete(uids: List<Long>) {
        val flagged = uids.distinct()
        val sets = flagged.chunked(UID_SET_CHUNK).map { compressUidSet(it) }.filter { it.isNotEmpty() }
        if (sets.isEmpty()) return
        val byUid = hasCapability(CAP_UIDPLUS)
        for (set in sets) {
            command("UID STORE $set +FLAGS (\\Deleted)")
            if (byUid) command("UID EXPUNGE $set")
        }
        // The union of every chunk: the folder-wide question is asked once, about the whole
        // operation, after the last message has been flagged.
        if (!byUid) finishPurgeWithoutUidPlus(flagged.toSet())
    }

    /** Close a purge on a server without UIDPLUS, per [purgeWithoutUidPlus]. Called at most once per
     *  operation; [flagged] is the union of every chunk it flagged in the selected mailbox. */
    private fun finishPurgeWithoutUidPlus(flagged: Set<Long>) {
        if (flagged.isEmpty()) return
        when (purgeWithoutUidPlus) {
            PurgeWithoutUidPlus.LEAVE_FLAGGED -> Unit
            PurgeWithoutUidPlus.EXPUNGE_WHEN_ONLY_OURS -> if (nothingElseIsFlagged(flagged)) command("EXPUNGE")
            PurgeWithoutUidPlus.EXPUNGE_WHOLE_FOLDER -> command("EXPUNGE")
        }
    }

        /**
         * Whether a bare `EXPUNGE` would erase [flagged] and nothing besides. A STRICT SUBSET is a yes;
         * an empty answer is a no; the list is capped at one more than [flagged], so a truncated answer
         * is always a no. THE RACE: another client can flag a message between this `SEARCH` and the
         * `EXPUNGE`, and that message would be destroyed — narrowed to one round trip, and IMAP without
         * UIDPLUS cannot close it.
         */
    private fun nothingElseIsFlagged(flagged: Set<Long>): Boolean {
        val onServer = uidSearchCapped("DELETED", cap = flagged.size + 1)
        return onServer.isNotEmpty() && onServer.size <= flagged.size && flagged.containsAll(onServer)
    }

    /**
     * APPEND a message the app COMPOSED into [mailbox], with [flags]. Text, so UTF-8 on the wire.
     * Not for a message FETCHED from a server: a raw source is one char per octet here
     * (ISO-8859-1) and UTF-8-encoding it would double every 8-bit byte — that path takes [ByteArray].
     */
    fun append(mailbox: String, message: String, flags: String) {
        append(mailbox, message.toByteArray(Charsets.UTF_8), flags)
    }

        /**
         * The literal is exactly these octets, under [internalDate] when the caller has one: a message
         * moved from another account keeps the date it was received under (#189). Answers the UID from
         * `[APPENDUID …]` (RFC 4315), or null on a server that names nothing, which is not a failure.
         */
    fun append(mailbox: String, message: ByteArray, flags: String, internalDate: Long? = null): Long? {
        val tag = "a${++tagN}"
        val flagPart = if (flags.isNotBlank()) "($flags) " else ""
        val datePart = if (internalDate != null) "${imapDateTime(internalDate)} " else ""
        output.write(
            "$tag APPEND ${mailboxArg(mailbox)} $flagPart$datePart{${message.size}}\r\n".toByteArray(Charsets.UTF_8),
        )
        output.flush()
        val cont = input.readResponse()
        // Not a "+": the APPEND did not get its go-ahead. This line is not necessarily the tagged
        // completion — RFC 3501 §7 lets an untagged `* 12 EXISTS` / `* BYE …` land here — so the
        // status word counts as a refusal the server PRONOUNCED only when the tag is on it.
        if (cont.getOrNull(0) != "+") {
            throw ImapException(
                "APPEND not accepted: $cont",
                responseCodeIn(cont),
                if (cont.getOrNull(0) == tag) cont.getOrNull(1) as? String else null,
            )
        }
        output.write(message)
        output.write("\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        while (true) {
            val resp = input.readResponse()
            if (resp.getOrNull(0) == tag) {
                val status = resp.getOrNull(1) as? String ?: "BAD"
                if (status != "OK") {
                    throw ImapException(
                        "APPEND failed: ${resp.drop(1).joinToString(" ")}",
                        responseCodeIn(resp),
                        status,
                    )
                }
                return appendUidIn(resp)
            }
        }
    }

        /**
         * The READ is bounded by [LOGOUT_READ_BUDGET_MS]. The LOGOUT is still sent and its answer
         * still read: dropping either leaves the session open until the server's idle timeout, and an
         * account with a small connection limit then refuses the NEXT upload. On a mute peer the bound
         * replaces an unbounded wait on the `runWithRetry` path (#95) and the 28-minute IDLE window.
         */
    override fun close() {
        runCatching { withReadTimeout(LOGOUT_READ_BUDGET_MS) { command("LOGOUT") } }
        runCatching { socket.close() }
    }

    // ---- FETCH parsing ----

    private fun parseFetch(resp: List<Any?>): ImapMessage? {
        if (resp.getOrNull(2) != "FETCH") return null
        @Suppress("UNCHECKED_CAST")
        val items = resp.getOrNull(3) as? List<Any?> ?: return null
        val map = pairUp(items)

        val uid = (map["UID"] as? String)?.toLongOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        val flags = (map["FLAGS"] as? List<Any?>)?.mapNotNull { it as? String } ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val envelope = map["ENVELOPE"] as? List<Any?>

        val subject = decodeWords(envelope?.getOrNull(1) as? String)
        val dateMillis = parseDate(envelope?.getOrNull(0) as? String)
        @Suppress("UNCHECKED_CAST")
        val fromAddr = (envelope?.getOrNull(2) as? List<Any?>)?.firstOrNull() as? List<Any?>
        val fromName = decodeWords(fromAddr?.getOrNull(0) as? String)
        // The halves of an address need [decodeHeaderBytes] as much as the display name: an address
        // is PERSISTED, indexed and prefilled as a reply recipient. RFC 3501 forbids 8-bit bytes in
        // a quoted-string, so an EAI address (RFC 6531) arrives as a literal.
        val mailbox = (fromAddr?.getOrNull(2) as? String)?.let(::decodeHeaderBytes)
        val hostPart = (fromAddr?.getOrNull(3) as? String)?.let(::decodeHeaderBytes)
        val fromEmail = if (mailbox != null && hostPart != null) "$mailbox@$hostPart" else null
        // Envelope "to" (index 5): every parseable address — Sent-folder rows show the recipients,
        // not the sender (Codeberg #59). Group-syntax delimiters (no host) are skipped.
        @Suppress("UNCHECKED_CAST")
        val toAddrs = envelopeAddresses(envelope?.getOrNull(5) as? List<Any?>)
        // Envelope "reply-to" (index 4), where the SENDER said to answer. Index 4, between sender
        // (3) and to (5): its neighbour would answer the copied parties instead.
        @Suppress("UNCHECKED_CAST")
        val replyToAddrs = envelopeAddresses(envelope?.getOrNull(4) as? List<Any?>)
        // Envelope "cc" (index 6): prefilled as a recipient of a reply-all and re-sent, so a mangled
        // host sends the copy to a domain that does not exist while the message looks sent.
        @Suppress("UNCHECKED_CAST")
        val ccAddrs = envelopeAddresses(envelope?.getOrNull(6) as? List<Any?>)
        // Envelope "bcc" (index 7): the BLIND copies. Not index 6 — filling `cc` from this slot
        // would publish addresses the sender chose to hide, and a sent reply cannot be walked back.
        @Suppress("UNCHECKED_CAST")
        val bccAddrs = envelopeAddresses(envelope?.getOrNull(7) as? List<Any?>)
        val messageId = envelope?.getOrNull(9) as? String
        val inReplyTo = envelope?.getOrNull(8) as? String
        // The one item the ENVELOPE does not carry, answered as a header block under a key the
        // server spells as it likes (RFC 3501 §7.4.2). Hence a match by shape, not by string.
        val references = map.entries.firstOrNull { isReferencesItem(it.key) }
            ?.let { referencesHeader(it.value as? String) }

        return ImapMessage(
            uid = uid,
            subject = subject,
            fromName = fromName,
            fromEmail = fromEmail,
            to = toAddrs,
            replyTo = replyToAddrs,
            cc = ccAddrs,
            bcc = bccAddrs,
            dateMillis = dateMillis,
            seen = flags.any { it.equals("\\Seen", true) },
            flagged = flags.any { it.equals("\\Flagged", true) },
            answered = flags.any { it.equals("\\Answered", true) },
            deleted = flags.any { it.equals("\\Deleted", true) },
            hasAttachment = hasAttachment(map["BODYSTRUCTURE"]),
            messageId = messageId,
            inReplyTo = inReplyTo,
            references = references,
            // The same structure the line above reduces to a boolean, read a second way: which
            // section holds the text and how to decode it. Costs no command.
            textPart = firstTextPart(map["BODYSTRUCTURE"]),
            // And a third read of that same structure: WHICH files, not just whether there are any.
            // The boolean above and this list must never disagree, so they are computed from one
            // value in one place rather than from two fetches at two times.
            attachments = attachmentParts(map["BODYSTRUCTURE"]),
        )
    }

    /** Turn a flat FETCH item list [k1, v1, k2, v2, ...] into a name→value map. */
    private fun pairUp(items: List<Any?>): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        var i = 0
        while (i < items.size) {
            val key = (items[i] as? String)?.uppercase()
            if (key == null) {
                i++
                continue
            }
            map[key] = items.getOrNull(i + 1)
            i += 2
        }
        return map
    }

    private fun hasAttachment(bodystructure: Any?): Boolean {
        fun walk(node: Any?): Boolean = when (node) {
            is List<*> -> node.any { child ->
                (child is String && child.equals("attachment", true)) || walk(child)
            }
            else -> false
        }
        return walk(bodystructure)
    }

    private companion object {
        fun parseDate(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            val cleaned = raw.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
            return runCatching { ZonedDateTime.parse(cleaned, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(cleaned).toInstant().toEpochMilli() }
                .getOrDefault(0L)
        }

    }
}

/** The FETCH item every list fetch asks for beside the envelope: the `References` header alone. */
internal const val REFERENCES_ITEM = "BODY[HEADER.FIELDS (REFERENCES)]"

/** Whether a FETCH item key is the `References` header item, however the server spells it: case,
 *  quotes around the field name and the width of the blank all vary. */
internal fun isReferencesItem(key: String): Boolean =
    key.replace("\"", "").replace(Regex("\\s+"), " ").uppercase() == REFERENCES_ITEM

/**
 * The value of the `References` header out of a `HEADER.FIELDS` block, unfolded onto one line
 * (RFC 5322 §2.2.3). Null when the block is NIL, empty, or names no `References`. The ids are kept
 * raw and whole; nothing is split here.
 */
internal fun referencesHeader(block: String?): String? {
    if (block.isNullOrEmpty()) return null
    val unfolded = block.replace(Regex("\r?\n[ \t]+"), " ")
    for (line in unfolded.split("\r\n", "\n")) {
        val colon = line.indexOf(':')
        if (colon < 0) continue
        if (!line.substring(0, colon).trim().equals("References", ignoreCase = true)) continue
        val value = line.substring(colon + 1).trim().replace(Regex("\\s+"), " ")
        return value.ifEmpty { null }
    }
    return null
}

/**
 * IMAP quoted-string. Per RFC 3501 it may not contain CR or LF: a raw newline would terminate the
 * command line and let an attacker-controlled value (folder name, search text) inject a second
 * authenticated command. File-level so ImapSearch.kt's builder uses the exact same guard.
 */
internal fun quote(s: String): String {
    if (s.any { it == '\r' || it == '\n' }) throw ImapException("Illegal newline in IMAP argument")
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** Render one parsed response token back to text, so a response code can be matched in it. */
internal fun flatten(v: Any?): String = when (v) {
    is List<*> -> v.joinToString(" ") { flatten(it) }
    null -> "NIL"
    else -> v.toString()
}

/** The ID extension (RFC 2971), looked for in the post-authentication list — [ImapSession.identify]. */
internal const val CAP_ID = "ID"

/** What Sterna calls itself in the RFC 2971 `ID` command. A protocol field, never translated. */
internal const val CLIENT_NAME = "Sterna Mail"

/** The UIDPLUS extension (RFC 4315): the one that provides `UID EXPUNGE <set>`. */
internal const val CAP_UIDPLUS = "UIDPLUS"

/** The MOVE extension (RFC 6851), consulted only to tell "this server has no MOVE" from "this MOVE
 *  was refused for another reason" ([ImapSession.move]), never to decide whether to try it. */
internal const val CAP_MOVE = "MOVE"

/** Capability names from the untagged `* CAPABILITY …` line, upper-cased. Empty when the server
 *  answered without one — read as "no optional extension", the conservative reading. */
internal fun parseCapabilities(untagged: List<List<Any?>>): Set<String> =
    untagged.firstOrNull { (it.getOrNull(1) as? String)?.equals("CAPABILITY", ignoreCase = true) == true }
        ?.drop(2)
        ?.mapNotNull { (it as? String)?.uppercase() }
        ?.toSet()
        .orEmpty()

/**
 * Capability names from a `[CAPABILITY …]` response code on [line], or `null` when it carries none —
 * not the same as an empty one, hence the nullable return. Matched over the flattened line, `[` and
 * `]` not being IMAP token delimiters.
 */
internal fun capabilitiesInResponseCode(line: List<Any?>): Set<String>? {
    val flat = line.joinToString(" ") { flatten(it) }
    val code = Regex("\\[CAPABILITY([^\\]]*)\\]", RegexOption.IGNORE_CASE).find(flat) ?: return null
    return code.groupValues[1].split(' ').filter { it.isNotBlank() }.map { it.uppercase() }.toSet()
}

/**
 * The uid named by an `[APPENDUID uidvalidity uid]` response code (RFC 4315 §3), or null. Matched
 * over the flattened line. Only the single-uid shape: a `uid-set` (MULTIAPPEND) answers null.
 */
internal fun appendUidIn(line: List<Any?>): Long? {
    val flat = line.joinToString(" ") { flatten(it) }
    return Regex("\\[APPENDUID\\s+\\d+\\s+(\\d+)\\]", RegexOption.IGNORE_CASE)
        .find(flat)?.groupValues?.get(1)?.toLongOrNull()
}

/**
 * [epochMs] as the RFC 3501 §9 `date-time` an APPEND takes for INTERNALDATE, in UTC. Month names are
 * the RFC's own English ones and not the locale's: a device whose CLDR short months differ must not
 * change the wire.
 */
internal fun imapDateTime(epochMs: Long): String {
    val t = java.time.Instant.ofEpochMilli(epochMs).atOffset(java.time.ZoneOffset.UTC)
    val day = t.dayOfMonth.toString().padStart(2, ' ')
    val time = "%02d:%02d:%02d".format(java.util.Locale.ROOT, t.hour, t.minute, t.second)
    return "\"$day-${IMAP_MONTHS[t.monthValue - 1]}-${t.year} $time +0000\""
}

private val IMAP_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /**
     * `null` when [raw] is not an RFC 3501 §9 `date-time`. [raw] is the value AS THE PARSER HANDS IT
     * OVER, quotes already removed; the leading space of a single-digit day is part of the grammar and
     * the unpadded form is accepted too. `null` on everything else — never `0L`, above all never
     * "now": this dates a correspondent's key, and a made-up date lets a key win in the provider.
     */
internal fun imapDateTimeMillis(raw: String): Long? {
    val m = IMAP_DATE_TIME.matchEntire(raw) ?: return null
    val g = m.groupValues
    val month = IMAP_MONTHS.indexOfFirst {
        it.lowercase(java.util.Locale.ENGLISH) == g[2].lowercase(java.util.Locale.ENGLISH)
    }
    if (month < 0) return null
    val sign = if (g[7] == "-") -1 else 1
    return runCatching {
        java.time.OffsetDateTime.of(
            g[3].toInt(), month + 1, g[1].toInt(),
            g[4].toInt(), g[5].toInt(), g[6].toInt(), 0,
            java.time.ZoneOffset.ofHoursMinutes(sign * g[8].toInt(), sign * g[9].toInt()),
        ).toInstant().toEpochMilli()
    }.getOrNull()
}

/** `date-day-fixed "-" date-month "-" date-year SP time SP zone`, unquoted. The optional leading
 *  space is written `\x20` because a space at the start of a pattern is invisible to a reformat. */
private val IMAP_DATE_TIME =
    Regex("""\x20?(\d{1,2})-([A-Za-z]{3})-(\d{4}) (\d{2}):(\d{2}):(\d{2}) ([+-])(\d{2})(\d{2})""")

/**
 * The response code of [line] (RFC 5530): the first bracketed WORD, upper-cased, or `null`. On an OK
 * completion this answers `"CAPABILITY"`, which is harmless — the throw sites only ask on a tagged
 * NO/BAD, where the bracketed word is the failure's name.
 */
internal fun responseCodeIn(line: List<Any?>): String? {
    val flat = line.joinToString(" ") { flatten(it) }
    return Regex("\\[([^\\]\\s]+)").find(flat)?.groupValues?.get(1)?.uppercase()
}

    /**
     * On a server without UIDPLUS (RFC 4315) the only way to erase is a bare `EXPUNGE`, which erases
     * EVERY message flagged `\Deleted` in the folder, ours and another client's alike. A product
     * decision, hence a single named value rather than a condition spread over the purge paths.
     */
internal enum class PurgeWithoutUidPlus {
    /** Flag `\Deleted` and stop, always. Nothing is erased that the user did not designate; in
     *  exchange the folder is never actually emptied on such a server. */
    LEAVE_FLAGGED,

    /**
     * Ask the server what is flagged, and send one bare `EXPUNGE` only if everything flagged is
     * something this operation flagged ([ImapSession.nothingElseIsFlagged], which states the
     * residual race). Otherwise behave as [LEAVE_FLAGGED].
     */
    EXPUNGE_WHEN_ONLY_OURS,

    /** One bare `EXPUNGE` for the whole operation, asking nothing — including any message flagged
     *  `\Deleted` by somebody else, destroyed with no way back. */
    EXPUNGE_WHOLE_FOLDER,
}

    /**
     * THE policy line: this single assignment switches every purge path in this file and nothing else.
     * It ships as [PurgeWithoutUidPlus.EXPUNGE_WHEN_ONLY_OURS] — the Trash is genuinely emptied when
     * that can be shown to harm nothing, and destroying less than ordered stays the fallback (#99).
     */
internal val purgeWithoutUidPlus = PurgeWithoutUidPlus.EXPUNGE_WHEN_ONLY_OURS

/** Cap on how many UIDs go into one `UID MOVE`/`UID STORE` sequence-set, so an enormous selection is
 *  split across a few commands instead of one over-long line. */
private const val UID_SET_CHUNK = 200

    /**
     * Deliberately NOT [UID_SET_CHUNK], which is bounded by a command LINE; this one is bounded by
     * the ANSWER, all of it parsed and held inside a foreground service. The rule for changing it:
     * `chunk × IMAP_PREVIEW_FETCH_BYTES` stays in the hundreds of kilobytes.
     */
internal const val IMAP_PREVIEW_FETCH_CHUNK = 20

/**
 * How long [ImapSession.close] may wait for the answer to its `LOGOUT`. A teardown courtesy: a live
 * link answers in one round trip and a dead one never will. Paid under the account mutex, and
 * TWICE at worst — `runWithRetry` can close the session on each of two attempts.
 */
private const val LOGOUT_READ_BUDGET_MS = 5_000

/** Tokens always readable on a line, however spent a caller's token budget is: enough for a
 *  tagged status line (`a12 NO [BADCHARSET] …`) to stay recognisable. */
private const val STATUS_LINE_TOKENS = 8

/** Untagged responses collected for one tagged command. */
internal class ImapResult(
    val status: String,
    val untagged: List<List<Any?>>,
    val tagged: List<Any?> = emptyList(),
)

    /**
     * Non-selectable container mailboxes are dropped (Gmail nests its special mailboxes under one named
     * "[Gmail]"); their selectable children keep their full path and re-parent to top level, and INBOX
     * is never dropped. THE ONE PLACE a mailbox name is decoded (Codeberg #101): [ImapFolder.path] is
     * the FAITHFUL decoding, an identifier that must reproduce the server's exact bytes, so the
     * anti-spoofing filter applies to [ImapFolder.name] alone.
     */
internal fun parseListFolders(untagged: List<List<Any?>>): List<ImapFolder> {
    val listed = untagged.mapNotNull { resp ->
        // * LIST (attrs) "delim" "name"
        if (resp.getOrNull(1) != "LIST") return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val attrs = (resp.getOrNull(2) as? List<Any?>)?.mapNotNull { it as? String } ?: emptyList()
        val delim = resp.getOrNull(3) as? String ?: "/"
        val wire = resp.getOrNull(4) as? String ?: return@mapNotNull null
        val path = decodeMailboxPath(wire)
        val leaf = path.substringAfterLast(delim)
        // A conforming name is modified UTF-7, pure ASCII on the wire. One that arrived with 8-bit
        // bytes is not conforming (Sterna itself sent raw UTF-8 up to 1.4.3), so reading them is the
        // difference between "Помеченные" and truncated mojibake. Testing the WIRE form keeps this
        // free of false positives; [path] is deliberately NOT touched, being the identifier.
        val name = stripBidiAndControls(if (wire.any { it.code >= 0x80 }) decodeHeaderBytes(leaf) else leaf)
        val nonSelectable = attrs.any {
            it.equals("\\Noselect", ignoreCase = true) || it.equals("\\NonExistent", ignoreCase = true)
        }
        if (nonSelectable && !path.equals("INBOX", ignoreCase = true)) return@mapNotNull null
        ListedFolder(name = name, path = path, delimiter = delim, attrs = attrs)
    }
    val roles = assignRoles(listed)
    return listed.mapIndexed { i, folder ->
        ImapFolder(
            name = folder.name,
            path = folder.path,
            role = roles[i].elected,
            delimiter = folder.delimiter,
            unelectedRole = roles[i].unelected,
        )
    }
}

    /**
     * Decoded EXACTLY as [parseListFolders] decodes a LIST name, the two sets being intersected by
     * path: decode them differently by one character and every non-ASCII folder falls out and vanishes
     * from the drawer (#101, #174). A path the LIST did not carry is kept — a subscription may outlive
     * its mailbox (RFC 3501 §6.3.9).
     */
internal fun parseLsubPaths(untagged: List<List<Any?>>): Set<String> =
    untagged.mapNotNullTo(mutableSetOf()) { resp ->
        // * LSUB (attrs) "delim" "name"
        if (resp.getOrNull(1) != "LSUB") return@mapNotNullTo null
        (resp.getOrNull(4) as? String)?.let { decodeMailboxPath(it) }
    }

    /**
     * Adds no folder and removes none. [subscribed] `null` — not asked, or the answer never arrived —
     * leaves every folder subscribed. And an EMPTY set is treated as that same unknown: the two are
     * indistinguishable on the wire, and reading an accident as "subscribed to nothing" empties the
     * whole drawer, inbox included.
     */
internal fun withSubscriptions(folders: List<ImapFolder>, subscribed: Set<String>?): List<ImapFolder> {
    if (subscribed.isNullOrEmpty()) return folders
    return folders.map { it.copy(isSubscribed = it.path in subscribed) }
}

/** One selectable mailbox as the LIST described it, before its role is settled. Roles cannot be
 *  settled folder by folder ([assignRoles]), so parsing and role assignment are two passes. */
internal data class ListedFolder(
    val name: String,
    val path: String,
    val delimiter: String,
    val attrs: List<String>,
)

    /**
     * The whole list, because both rules are properties of it: a role the SERVER STATED beats a role
     * guessed from a name wherever either sits, and a folder that stated a recognised attribute never
     * falls back on the name table even when the role it stated was taken; and a role is carried ONCE,
     * INBOX included (RFC 3501 §5.1). A folder that lost its claim stays ORDINARY; what crosses to
     * `:core:data` is the bare fact ([ImapFolder.unelectedRole]), keeping it out of search.
     */
internal fun assignRoles(listed: List<ListedFolder>): List<FolderRole> {
    val stated = listed.map { roleFromAttributes(it.attrs) }
    val roles = MutableList<String?>(listed.size) { null }
    val unelected = MutableList<String?>(listed.size) { null }
    val taken = mutableSetOf<String>()
    stated.forEachIndexed { i, role ->
        if (role != null && taken.add(role)) roles[i] = role
    }
    listed.forEachIndexed { i, folder ->
        if (stated[i] != null) {
            // It stated a recognised role and was not elected to it: the claim is what it stated,
            // and the name table stays out of it (rule 1).
            if (roles[i] == null) unelected[i] = stated[i]
            return@forEachIndexed
        }
        val guessed = roleFromName(folder.name) ?: return@forEachIndexed
        if (taken.add(guessed)) roles[i] = guessed else unelected[i] = guessed
    }
    return List(listed.size) { FolderRole(elected = roles[it], unelected = unelected[it]) }
}

/** What [assignRoles] settled for one folder: the role it was ELECTED to, and the role it CLAIMED
 *  and lost to another folder of the same listing. At most one of the two is non-null. */
internal data class FolderRole(val elected: String?, val unelected: String?)

/** The role a server STATED with an RFC 6154 SPECIAL-USE attribute — the only one it did not
 *  have to be guessed. */
internal fun roleFromAttributes(attrs: List<String>): String? =
    attrs.firstNotNullOfOrNull { attr ->
        when (attr.lowercase()) {
            "\\sent" -> "sent"
            "\\drafts" -> "drafts"
            "\\trash" -> "trash"
            "\\junk" -> "junk"
            "\\archive" -> "archive"
            "\\all" -> "all"
            else -> null
        }
    }

    /**
     * The names below are the ones SERVERS carry in each language — what Dovecot, Roundcube and the
     * common webmails create — never the app's own labels. With no trash recognised,
     * `MailRepository.deleteWouldDestroy` calls every delete a destruction and the swipe erases mail.
     * ACCEPTED: a USER folder named exactly "Papierkorb" takes the role and drops out of search.
     * [lowercase] with no argument is locale-independent: a Turkish phone maps 'I' to 'ı'.
     */
internal fun roleFromName(name: String): String? = when (name.lowercase()) {
    "inbox" -> "inbox"

    // Trash and Junk first: the role whose absence destroys, then the one whose absence pollutes
    // every search with spam.
    "trash", "deleted", "deleted items", "deleted messages", // en
    "papierkorb", "gelöschte objekte", "gelöschte elemente", // de
    "papelera", "elementos eliminados", // es
    "corbeille", "éléments supprimés", // fr
    "cestino", "posta eliminata", "elementi eliminati", // it
    "prullenbak", "prullenmand", "verwijderde items", // nl
    "kosz", "elementy usunięte", // pl
    "lixeira", "lixo", "itens excluídos", "itens eliminados", // pt
    "корзина", "удалённые", "удаленные", // ru
    -> "trash"

    "junk", "spam", "junk e-mail", "junk email", // en
    "junk-e-mail", // de
    "correo no deseado", "correo basura", // es
    "courrier indésirable", "indésirables", // fr
    "posta indesiderata", "indesiderata", // it
    "ongewenste e-mail", "ongewenst", // nl
    "wiadomości-śmieci", // pl
    "lixo eletrônico", "lixo eletrónico", // pt
    "спам", "нежелательная почта", // ru
    -> "junk"

    "sent", "sent mail", "sent items", "sent messages", // en
    "gesendet", "gesendete objekte", "gesendete elemente", // de
    "enviados", "elementos enviados", "correo enviado", // es + pt ("enviados")
    "envoyés", "éléments envoyés", "messages envoyés", // fr
    "inviata", "posta inviata", "elementi inviati", // it
    "verzonden", "verzonden items", // nl
    "wysłane", "elementy wysłane", // pl
    "itens enviados", // pt
    "отправленные", // ru
    -> "sent"

    "drafts", // en
    "entwürfe", // de
    "borradores", // es
    "brouillons", // fr
    "bozze", // it
    "concepten", // nl
    "kopie robocze", "wersje robocze", "robocze", // pl
    "rascunhos", // pt
    "черновики", // ru
    -> "drafts"

    "archive", "archives", // en + fr
    "archiv", // de
    "archivo", // es
    "archivio", // it
    "archief", // nl
    "archiwum", // pl
    "arquivo", // pt
    "архив", // ru
    -> "archive"

    else -> null
}
