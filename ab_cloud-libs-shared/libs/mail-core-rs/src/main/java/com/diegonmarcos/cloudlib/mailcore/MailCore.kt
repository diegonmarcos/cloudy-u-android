package com.diegonmarcos.cloudlib.mailcore

/**
 * Cloud Mail's native core (#1837, phase 1): the local store, the JMAP sync engine and the list queries of
 * libmail_core.so, as Kotlin calls answering JSON text.
 *
 * - [sync] one sync pass of one JMAP account into the store at [db] (initial: every mailbox and every message, newest first,
 *   paged; then Mailbox/changes + Email/changes; gap detection; deletes and moves; body prefetch). Answers the report.
 * - [query] a read: `list` (flat / thread / sender / domain, server date order, paged, unread only), `counts`, `changes`
 *   (the projection feed by (seq, id) cursor), `members`, `search` (FTS5), `body`, `stats`, and `dropAccount`; phase 2 adds
 *   `reader` (a message's parsed reader document from the store) and `wrap` (a fragment in the Gmail-like document).
 * - [fetchRaw] the raw messages of given ids downloaded, parsed and stored (phase 2).
 *
 * Every call answers a [Result]: a library that did not load, an error, or a panic inside the core (caught at the JNI
 * boundary) is a failure with its reason, never a crash; the caller keeps its previous engine for that pass.
 */
object MailCore {
    /**
     * The feature switch (#1837): false makes every caller take its Kotlin engine (the core reports itself unavailable), without
     * touching the library. On by default; the Kotlin engine stays until the parity fixtures (fixtures/parity) are green in both.
     */
    @Volatile var enabled: Boolean = true

    /**
     * OUTGOING mail and the network protocol stay on the Kotlin engine until the on-phone check: [composeEnabled] lets the core
     * build outgoing messages and [imapEnabled] lets it fetch over IMAP. Both default FALSE; [enabled] keeps governing read, parse
     * and search as before. A call site engages the core only when its own switch AND [enabled] are on.
     */
    @Volatile var composeEnabled: Boolean = false
    @Volatile var imapEnabled: Boolean = false

    /** Null when the core is usable in this process, otherwise why not. */
    fun unavailableReason(): String? = if (!enabled) "the Rust mail core is switched off" else NativeBridge.unavailableReason()

    fun version(): String? = if (unavailableReason() == null) runCatching { NativeBridge.nativeVersion() }.getOrNull() else null

    fun sync(db: String, configJson: String, transport: MailCoreTransport): Result<String> =
        call { NativeBridge.nativeSync(db, configJson, transport) }

    fun query(db: String, queryJson: String): Result<String> = call { NativeBridge.nativeQuery(db, queryJson) }

    /**
     * #1837 phase 2: download, parse and store the raw messages named in [argsJson] (`{"account","jmapAccountId","ids":[..]}`), the
     * on-demand fetch of a message opened before the prefetch reached it. Answers `{"fetched","failed","error","ms"}`.
     */
    fun fetchRaw(db: String, argsJson: String, transport: MailCoreTransport): Result<String> =
        call { NativeBridge.nativeFetchRaw(db, argsJson, transport) }

    /**
     * #1837 phase 3: IMAP fetch through the core over the caller's own socket [io] (TLS stays in Kotlin): LOGIN, SELECT, UID SEARCH,
     * UID FETCH of [argsJson] (`{"user","password","mailbox","uids":[..]|"newest":n,"timeoutMs"}`). Answers the report JSON
     * (`exists`, `uidValidity`, `uidNext`, `messages[{uid,flags,internalDate,raw(base64),kind,text}]`).
     */
    fun imapFetch(argsJson: String, io: MailCoreImapIo): Result<String> = call { NativeBridge.nativeImapFetch(argsJson, io) }

    /** Proves the boundary: answers a failure whose reason starts with "panic:". */
    fun panicProbe(): Result<String> = call { NativeBridge.nativePanicProbe() }

    private inline fun call(native: () -> String?): Result<String> {
        unavailableReason()?.let { return Result.failure(MailCoreException(it)) }
        val answer = try {
            native()
        } catch (t: Throwable) {
            return Result.failure(MailCoreException("JNI call failed: ${t.javaClass.simpleName}: ${t.message}"))
        }
        return decode(answer)
    }

    /** `OK\n<json>` -> the json; `ERR\t<reason>` -> a failure with the reason; null -> a failure. */
    fun decode(answer: String?): Result<String> = when {
        answer == null -> Result.failure(MailCoreException("the core answered null"))
        answer.startsWith("OK\n") -> Result.success(answer.substring(3))
        answer.startsWith("ERR\t") -> Result.failure(MailCoreException(answer.substring(4)))
        else -> Result.failure(MailCoreException("unreadable answer: ${answer.take(80)}"))
    }
}

class MailCoreException(message: String) : RuntimeException(message)
