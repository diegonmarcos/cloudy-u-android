package app.sterna.ui.compose

import app.sterna.util.isValidEmail

/*
 * #1803 Recipient deliverability, the Android half.
 *
 * Every recipient chip in To, Cc and Bcc carries a small mark saying whether that recipient can receive
 * the message, with the detail one tap away. The mark is computed when the address is committed and
 * again just before Send; a will-fail recipient makes Send ask first, and never blocks it.
 *
 * The verdicts come from the fleet mail API (POST …/v1/recipients/check). Everything in THIS file is
 * independent of that wire format: the model the UI draws, the local syntax check (always run, also
 * offline), the merge of local and remote answers, the cache, and the [RecipientChecker] seam the real
 * client is bound to. Until it is bound, [RecipientChecks.checker] answers "unknown" for every check the
 * phone cannot make itself, which is the honest answer (rule 16: a status reflects the real state).
 *
 * Plain Kotlin, no Android import: the unit tests execute all of it.
 */

/** The one-word answer a chip shows. Ordered from best to worst; [CHECKING] is "no answer yet". */
enum class RecipientVerdict { OK, RISKY, WILL_FAIL, UNKNOWN, CHECKING }

/** Each line of the detail sheet. The order of the entries is the order the sheet lists them. */
enum class RecipientCheckKind {
    /** The address is well formed (checked on the phone, offline too). */
    SYNTAX,
    /** The domain exists and accepts mail (MX, or the A fallback). */
    DOMAIN,
    /** The mailbox exists; [CheckOutcome.WARN] with [RecipientCheckItem.acceptsAll] when the server
     *  accepts every address, [CheckOutcome.UNKNOWN] when it would not tell. */
    MAILBOX,
    /** Mail to this address bounced before. */
    PAST_BOUNCE,
    /** A disposable-address domain, or a role mailbox (info@, noreply@ …). A hint, never a failure. */
    DISPOSABLE_ROLE,
    /** The message is bigger than the receiving server says it takes. */
    SIZE_LIMIT,
}

enum class CheckOutcome { PASS, WARN, FAIL, UNKNOWN }

/** Where an answer came from, shown in the sheet so a "pass" is never mistaken for a server's word. */
enum class CheckSource { PHONE, SERVER, NONE }

/**
 * One check's result. [detail] is the server's or the phone's own reason, verbatim, shown under the
 * line (null when there is none); [acceptsAll] marks the catch-all case of [RecipientCheckKind.MAILBOX].
 */
data class RecipientCheckItem(
    val kind: RecipientCheckKind,
    val outcome: CheckOutcome,
    val detail: String? = null,
    val source: CheckSource = CheckSource.SERVER,
    val acceptsAll: Boolean = false,
)

/** Everything known about one recipient. [checks] always holds one item per [RecipientCheckKind]. */
data class RecipientStatus(
    val address: String,
    val verdict: RecipientVerdict,
    val checks: List<RecipientCheckItem>,
    /** When the answer was taken (epoch millis); 0 for a placeholder. */
    val checkedAtMillis: Long = 0L,
    /** The server answered for this address (false: phone-only, or the server was not reached). */
    val fromServer: Boolean = false,
    /** Why the server was not reached, when it was not (shown in the sheet); null otherwise. */
    val serverNote: String? = null,
) {
    fun check(kind: RecipientCheckKind): RecipientCheckItem = checks.first { it.kind == kind }
}

/** What the composer hands the checker: the addresses, and the message's size so far (bytes, or null). */
data class RecipientCheckRequest(val addresses: List<String>, val messageSizeBytes: Long?)

/**
 * The seam the fleet client binds to. Answers what it can for [request]; an address it has no answer
 * for is simply absent from the map (the merge then marks it unknown). Throwing means "not reached".
 */
fun interface RecipientChecker {
    suspend fun check(request: RecipientCheckRequest): Map<String, RecipientStatus>
}

/** The checker that knows nothing: until the fleet client is bound, every remote check is unknown. */
object UnboundRecipientChecker : RecipientChecker {
    override suspend fun check(request: RecipientCheckRequest): Map<String, RecipientStatus> = emptyMap()
}

/** The process-wide binding, plus the process-wide cache (a reopened composer reuses its answers). */
object RecipientChecks {
    @Volatile var checker: RecipientChecker = UnboundRecipientChecker

    /** True once a real client is bound; the sheet says "not connected" while it is false. */
    val bound: Boolean get() = checker !== UnboundRecipientChecker

    val cache = RecipientStatusCache()
}

/** Cache key: the address as the server compares it (trimmed; the domain is case-insensitive, and so,
 *  in practice, is the local part). */
fun recipientKey(address: String): String = address.trim().lowercase()

// -- the local syntax check -------------------------------------------------------------------------

/** Why an address fails the phone's own syntax check, or null when it passes. Stricter than
 *  [isValidEmail] (which still gates Send exactly as before) on the limits RFC 5321 sets. */
fun recipientSyntaxProblem(address: String): SyntaxProblem? {
    val a = address.trim()
    if (a.isEmpty()) return SyntaxProblem.EMPTY
    if (a.any { it.isWhitespace() }) return SyntaxProblem.SPACE
    val at = a.lastIndexOf('@')
    if (at <= 0 || a.count { it == '@' } != 1) return SyntaxProblem.NO_AT
    val local = a.substring(0, at)
    val domain = a.substring(at + 1)
    if (a.length > 254) return SyntaxProblem.TOO_LONG
    if (local.length > 64) return SyntaxProblem.LOCAL_TOO_LONG
    if (local.startsWith('.') || local.endsWith('.') || ".." in local) return SyntaxProblem.LOCAL_DOTS
    if (domain.isEmpty() || !domain.contains('.')) return SyntaxProblem.DOMAIN_NO_DOT
    val labels = domain.split('.')
    if (labels.any { it.isEmpty() }) return SyntaxProblem.DOMAIN_DOTS
    if (labels.any { it.length > 63 }) return SyntaxProblem.DOMAIN_LABEL_TOO_LONG
    if (labels.any { it.startsWith('-') || it.endsWith('-') }) return SyntaxProblem.DOMAIN_HYPHEN
    if (labels.any { l -> l.any { c -> !(c.isLetterOrDigit() || c == '-') } }) return SyntaxProblem.DOMAIN_CHARACTER
    if (labels.last().length < 2 || labels.last().all { it.isDigit() }) return SyntaxProblem.TLD
    if (!isValidEmail(a)) return SyntaxProblem.FORMAT
    return null
}

/** The phone's syntax verdicts, one string resource each (see [syntaxProblemText] in the sheet). */
enum class SyntaxProblem {
    EMPTY, SPACE, NO_AT, TOO_LONG, LOCAL_TOO_LONG, LOCAL_DOTS, DOMAIN_NO_DOT, DOMAIN_DOTS,
    DOMAIN_LABEL_TOO_LONG, DOMAIN_HYPHEN, DOMAIN_CHARACTER, TLD, FORMAT,
}

/** The SYNTAX line as the phone answers it. The problem's name is the detail; the sheet localises it. */
fun localSyntaxItem(address: String): RecipientCheckItem {
    val problem = recipientSyntaxProblem(address)
    return RecipientCheckItem(
        kind = RecipientCheckKind.SYNTAX,
        outcome = if (problem == null) CheckOutcome.PASS else CheckOutcome.FAIL,
        detail = problem?.name,
        source = CheckSource.PHONE,
    )
}

// -- verdict and merge ------------------------------------------------------------------------------

/** The verdict a list of checks adds up to: any failure fails, any warning is risky, any unknown is
 *  unknown, all passes are ok. A disposable/role hint is at most risky, never a failure. */
fun verdictOf(checks: List<RecipientCheckItem>): RecipientVerdict {
    val outcomes = checks.map {
        if (it.kind == RecipientCheckKind.DISPOSABLE_ROLE && it.outcome == CheckOutcome.FAIL) CheckOutcome.WARN else it.outcome
    }
    return when {
        CheckOutcome.FAIL in outcomes -> RecipientVerdict.WILL_FAIL
        CheckOutcome.WARN in outcomes -> RecipientVerdict.RISKY
        CheckOutcome.UNKNOWN in outcomes || outcomes.isEmpty() -> RecipientVerdict.UNKNOWN
        else -> RecipientVerdict.OK
    }
}

private fun unknownItem(kind: RecipientCheckKind) =
    RecipientCheckItem(kind, CheckOutcome.UNKNOWN, source = CheckSource.NONE)

/** The status the phone alone can give: its syntax line, every other line unknown. A syntax failure
 *  is already a will-fail; otherwise [pending] shows the chip as still checking. */
fun localRecipientStatus(address: String, pending: Boolean, nowMillis: Long, serverNote: String? = null): RecipientStatus {
    val syntax = localSyntaxItem(address)
    val checks = RecipientCheckKind.entries.map { if (it == RecipientCheckKind.SYNTAX) syntax else unknownItem(it) }
    val verdict = when {
        syntax.outcome == CheckOutcome.FAIL -> RecipientVerdict.WILL_FAIL
        pending -> RecipientVerdict.CHECKING
        else -> RecipientVerdict.UNKNOWN
    }
    return RecipientStatus(address, verdict, checks, nowMillis, fromServer = false, serverNote = serverNote)
}

/**
 * Local and remote, merged: every kind present exactly once, in [RecipientCheckKind] order. The phone's
 * syntax FAIL always wins (a malformed address cannot be delivered, whatever came back). Otherwise the
 * server's line is used when it gave one, and its verdict when it gave one; a missing line is unknown.
 */
fun mergeRecipientStatus(address: String, remote: RecipientStatus?, nowMillis: Long, serverNote: String? = null): RecipientStatus {
    val local = localSyntaxItem(address)
    if (remote == null) return localRecipientStatus(address, pending = false, nowMillis = nowMillis, serverNote = serverNote)
    val checks = RecipientCheckKind.entries.map { kind ->
        val fromServer = remote.checks.firstOrNull { it.kind == kind }
        when {
            kind == RecipientCheckKind.SYNTAX && local.outcome == CheckOutcome.FAIL -> local
            kind == RecipientCheckKind.SYNTAX -> fromServer ?: local
            else -> fromServer ?: unknownItem(kind)
        }
    }
    val computed = verdictOf(checks)
    val verdict = when {
        local.outcome == CheckOutcome.FAIL -> RecipientVerdict.WILL_FAIL
        remote.verdict == RecipientVerdict.CHECKING -> computed
        // The server's own verdict is trusted, but never better than what its own lines say fails.
        computed == RecipientVerdict.WILL_FAIL -> RecipientVerdict.WILL_FAIL
        else -> remote.verdict
    }
    return RecipientStatus(
        address = address,
        verdict = verdict,
        checks = checks,
        checkedAtMillis = if (remote.checkedAtMillis > 0) remote.checkedAtMillis else nowMillis,
        fromServer = true,
        serverNote = serverNote,
    )
}

// -- the cache --------------------------------------------------------------------------------------

/**
 * Answers by [recipientKey], each kept for a time that depends on how sure it is: a server's ok or
 * will-fail for [settledTtlMillis], a risky answer for [riskyTtlMillis], an unknown (server not reached,
 * or it could not tell) for [unknownTtlMillis] so it is asked again soon. At most [capacity] entries,
 * the oldest dropped first. A size-limit answer depends on the message, so [sizeBytes] is part of the
 * entry: a message that grows past the size an answer was taken for is asked again.
 */
class RecipientStatusCache(
    private val settledTtlMillis: Long = 30 * 60_000L,
    private val riskyTtlMillis: Long = 10 * 60_000L,
    private val unknownTtlMillis: Long = 2 * 60_000L,
    private val capacity: Int = 512,
) {
    private class Entry(val status: RecipientStatus, val atMillis: Long, val sizeBytes: Long?)

    private val entries = LinkedHashMap<String, Entry>(64, 0.75f, true)

    private fun ttlOf(status: RecipientStatus): Long = when {
        !status.fromServer -> unknownTtlMillis
        status.verdict == RecipientVerdict.OK || status.verdict == RecipientVerdict.WILL_FAIL -> settledTtlMillis
        status.verdict == RecipientVerdict.RISKY -> riskyTtlMillis
        else -> unknownTtlMillis
    }

    @Synchronized
    fun put(status: RecipientStatus, nowMillis: Long, sizeBytes: Long?) {
        entries[recipientKey(status.address)] = Entry(status, nowMillis, sizeBytes)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    /** The cached answer for [address] if still fresh for a message of [sizeBytes]; else null. With
     *  [maxAgeMillis] an answer older than that is not returned either (it stays cached). */
    @Synchronized
    fun get(address: String, nowMillis: Long, sizeBytes: Long?, maxAgeMillis: Long? = null): RecipientStatus? {
        val key = recipientKey(address)
        val e = entries[key] ?: return null
        val expired = nowMillis - e.atMillis >= ttlOf(e.status) || nowMillis < e.atMillis
        if (!expired && maxAgeMillis != null && nowMillis - e.atMillis > maxAgeMillis) return null
        val grew = sizeBytes != null && (e.sizeBytes == null || sizeBytes > e.sizeBytes)
        if (expired) {
            entries.remove(key)
            return null
        }
        if (grew) return null
        return e.status.copy(address = address)
    }

    @Synchronized
    fun invalidate(address: String) {
        entries.remove(recipientKey(address))
    }

    @Synchronized
    fun size(): Int = entries.size
}

/** The addresses of [addresses] that need asking: distinct by [recipientKey], syntax-valid (a malformed
 *  address is answered on the phone), and not fresh in [cache]. */
fun recipientsToAsk(
    addresses: List<String>,
    cache: RecipientStatusCache,
    nowMillis: Long,
    sizeBytes: Long?,
    maxAgeMillis: Long? = null,
): List<String> =
    addresses.map { it.trim() }.filter { it.isNotEmpty() }
        .distinctBy(::recipientKey)
        .filter { recipientSyntaxProblem(it) == null }
        .filter { cache.get(it, nowMillis, sizeBytes, maxAgeMillis) == null }

/** The worst verdict among [statuses] (a missing one counts as still checking), for a folded field's
 *  one-line summary; null when there are none. Worst first: will-fail, risky, unknown, checking, ok. */
fun worstVerdict(statuses: List<RecipientStatus?>): RecipientVerdict? {
    if (statuses.isEmpty()) return null
    val order = listOf(
        RecipientVerdict.WILL_FAIL, RecipientVerdict.RISKY, RecipientVerdict.UNKNOWN,
        RecipientVerdict.CHECKING, RecipientVerdict.OK,
    )
    val verdicts = statuses.map { it?.verdict ?: RecipientVerdict.CHECKING }
    return order.first { it in verdicts }
}

/** Before Send, an answer older than this is asked again ("checked again before Send"). */
const val SEND_RECHECK_MAX_AGE_MILLIS: Long = 60_000L

/** How long Send waits for the recheck before going on with what is known (it never blocks). */
const val SEND_RECHECK_TIMEOUT_MILLIS: Long = 5_000L

/** The recipients Send has to ask about: those whose verdict is [RecipientVerdict.WILL_FAIL]. */
fun willFailRecipients(addresses: List<String>, statuses: Map<String, RecipientStatus>): List<String> =
    addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(::recipientKey)
        .filter { a -> statuses[recipientKey(a)]?.verdict == RecipientVerdict.WILL_FAIL }

/**
 * One round of checking, the whole decision in one place so a test can drive it: answer [addresses]
 * from [cache] where fresh, ask [checker] for the rest, merge, store, and return every address's status
 * keyed by [recipientKey]. [maxAgeMillis] is the Send-time recheck: an answer older than that is asked
 * again even though still cached. A checker that throws marks the asked addresses unknown with its message as
 * the note (and caches that only for the short unknown time).
 */
suspend fun runRecipientCheck(
    addresses: List<String>,
    sizeBytes: Long?,
    checker: RecipientChecker,
    cache: RecipientStatusCache,
    now: () -> Long,
    maxAgeMillis: Long? = null,
): Map<String, RecipientStatus> {
    val distinct = addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(::recipientKey)
    val ask = recipientsToAsk(distinct, cache, now(), sizeBytes, maxAgeMillis)
    var note: String? = null
    val answers: Map<String, RecipientStatus> = if (ask.isEmpty()) {
        emptyMap()
    } else {
        try {
            checker.check(RecipientCheckRequest(ask, sizeBytes)).mapKeys { recipientKey(it.key) }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            note = t.message ?: t.javaClass.simpleName
            emptyMap()
        }
    }
    val at = now()
    val askedKeys = ask.map(::recipientKey).toSet()
    return distinct.associate { a ->
        val key = recipientKey(a)
        val status = if (key in askedKeys) {
            mergeRecipientStatus(a, answers[key], at, serverNote = note).also { cache.put(it, at, sizeBytes) }
        } else {
            cache.get(a, at, sizeBytes) ?: localRecipientStatus(a, pending = false, nowMillis = at)
        }
        key to status
    }
}
