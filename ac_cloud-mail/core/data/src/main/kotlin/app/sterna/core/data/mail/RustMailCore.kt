package app.sterna.core.data.mail

import android.content.Context
import app.sterna.core.data.db.EmailDao
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.JmapSession
import com.diegonmarcos.cloudlib.mailcore.MailCore
import com.diegonmarcos.cloudlib.mailcore.MailCoreTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * #1837 phase 1: the Rust mail core (libs:mail-core-rs, libmail_core.so) is the sync engine and the store of record of every
 * JMAP account. It mirrors the server whole (every mailbox, every message, newest first, paged; then Mailbox/changes +
 * Email/changes; gap detection: a mailbox's server total vs its local count, the missing ids re-queried; deletes and moves;
 * body prefetch) into its own SQLite store (filesDir/mail-core/mail.db).
 *
 * The Inbox list the UI draws comes from that store: [projectMailbox] reads the store's own feed (the Email objects written
 * after a cursor, in the server's objects verbatim, and the memberships that ended) and writes it, through the SAME
 * [toEntity] mapping every other path uses, into the app's list rows, which the existing repository interface pages
 * (sort orders, unread only, conversation view, snooze, Alerts split, Select all, widgets) unchanged. So the list is the
 * store's, complete and in server order, offline it opens from what is on the phone, and a refresh only ever upserts and
 * removes what the store removed: it never blanks the list.
 *
 * IMAP accounts keep the old path in phase 1 (ImapMailService + the Room cache, unchanged); phase 3 brings them into the core.
 * When the core is not loaded (no libmail_core.so for this ABI), or a pass of it fails, the JMAP account runs the previous
 * Kotlin sync for that pass and the reason is logged (tag MailCore): nothing the app did before is lost.
 */
class RustMailEngine(
    private val dbPath: String,
    private val cursors: ProjectionCursors = ProjectionCursors.InMemory(),
) {
    private val mutex = Mutex()
    @Volatile private var loggedUnavailable = false

    /** Null when the core can run in this process, otherwise why not (logged once). */
    fun unavailableReason(): String? {
        val why = MailCore.unavailableReason() ?: return null
        if (!loggedUnavailable) {
            loggedUnavailable = true
            log("core not loaded, JMAP accounts keep the Kotlin sync: $why")
        }
        return why
    }

    /** One sync pass of a JMAP account. [budgetMs] bounds the walks (the rest continues on the next pass). Answers the report. */
    suspend fun sync(
        client: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: JmapAuth,
        localAccountId: String,
        budgetMs: Long?,
    ): CoreSyncReport = mutex.withLock {
        withContext(Dispatchers.IO) {
            val transport = transportFor(client, session, jmapAccountId, auth)
            val batch = session.getBatchSize()
            val config = buildJsonObject {
                put("account", localAccountId)
                put("jmapAccountId", jmapAccountId)
                putJsonArray("properties") { client.emailListProperties().forEach { add(it) } }
                put("queryPage", batch.coerceIn(256, 1000))
                put("getChunk", batch.coerceIn(1, 200))
                put("maxChanges", 500)
                put("prefetchBodies", PREFETCH_BODIES)
                put("prefetchRaw", PREFETCH_BODIES)
                if (budgetMs != null) put("budgetMs", budgetMs)
            }
            val text = MailCore.sync(dbPath, config.toString(), transport).getOrThrow()
            CoreSyncReport.parse(text).also { log("sync $localAccountId: ${it.summary}") }
        }
    }

    /** The core's view of the app's HTTP client: the JMAP POST and the blob GET, both through [client]. */
    private fun transportFor(client: JmapClient, session: JmapSession, jmapAccountId: String, auth: JmapAuth) = object : MailCoreTransport {
        override fun post(body: String): String = try {
            client.postRawBlocking(session.apiUrl, auth, body)
        } catch (c: CancellationException) {
            MailCoreTransport.error("cancelled")
        } catch (t: Throwable) {
            MailCoreTransport.error(t.message ?: t.javaClass.simpleName)
        }

        override fun download(blobId: String): ByteArray = client.downloadBlobBlocking(session, jmapAccountId, blobId, auth)
    }

    /**
     * #1837 phase 2: the reader document of a message FROM THE STORE, no network: the raw message was downloaded and parsed by the
     * prefetch (or by [fetchRaw]), so an open is one indexed read (budget 100 ms, asserted in the core's tests) and works offline.
     * Null = not in the store yet, or the core is not loaded: the caller keeps the old reader path.
     */
    suspend fun reader(localAccountId: String, emailId: String): CoreReaderDoc? = withContext(Dispatchers.IO) {
        if (MailCore.unavailableReason() != null) return@withContext null
        val started = System.nanoTime()
        val answer = MailCore.query(
            dbPath,
            buildJsonObject { put("op", "reader"); put("account", localAccountId); put("id", emailId) }.toString(),
        ).getOrElse {
            log("reader $localAccountId/$emailId: failed, old reader path: ${it.message}")
            return@withContext null
        }
        val o = JSON.parseToJsonElement(answer).jsonObject
        if (o["available"]?.jsonPrimitive?.boolean != true) {
            log("reader $localAccountId/$emailId: not in the store, old reader path")
            return@withContext null
        }
        CoreReaderDoc(
            emailId = emailId,
            kind = o["kind"]!!.jsonPrimitive.content,
            fragment = o["fragment"]!!.jsonPrimitive.content,
            text = o["text"]?.jsonPrimitive?.content.orEmpty(),
            remoteImages = o["remoteImages"]?.jsonPrimitive?.boolean ?: false,
            encodingProblem = o["encodingProblem"]?.jsonPrimitive?.boolean ?: false,
        ).also {
            log("reader $localAccountId/$emailId: ${it.kind} from the store in ${(System.nanoTime() - started) / 1_000_000} ms" +
                (if (it.encodingProblem) " (encoding problem noted)" else ""))
        }
    }

    /** Download, parse and store the raw message of [emailIds] (a message opened before the prefetch reached it). Answers how many landed. */
    suspend fun fetchRaw(
        client: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: JmapAuth,
        localAccountId: String,
        emailIds: List<String>,
    ): Int = withContext(Dispatchers.IO) {
        val args = buildJsonObject {
            put("account", localAccountId)
            put("jmapAccountId", jmapAccountId)
            putJsonArray("ids") { emailIds.forEach { add(it) } }
        }
        val text = MailCore.fetchRaw(dbPath, args.toString(), transportFor(client, session, jmapAccountId, auth)).getOrElse {
            log("fetchRaw $localAccountId: failed: ${it.message}")
            return@withContext 0
        }
        val o = JSON.parseToJsonElement(text).jsonObject
        val fetched = o["fetched"]?.jsonPrimitive?.int ?: 0
        log("fetchRaw $localAccountId: fetched=$fetched failed=${o["failed"]?.jsonPrimitive?.int ?: 0} ${o["error"]?.jsonPrimitive?.content.orEmpty()}")
        fetched
    }

    /** The account ids the store holds rows for. */
    suspend fun storedAccounts(): List<String> = withContext(Dispatchers.IO) {
        val o = JSON.parseToJsonElement(MailCore.query(dbPath, """{"op":"accounts"}""").getOrThrow()).jsonObject
        o["ids"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    }

    /** Every row of an account that left the app. */
    suspend fun dropAccount(localAccountId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            MailCore.query(dbPath, buildJsonObject { put("op", "dropAccount"); put("account", localAccountId) }.toString()).getOrThrow()
            cursors.dropAccount(localAccountId)
        }
    }

    /** The store's per-mailbox counts of an account (local, unread, the server's totals, complete, gaps found). */
    suspend fun counts(localAccountId: String): Map<String, CoreMailboxCount> = withContext(Dispatchers.IO) {
        val o = JSON.parseToJsonElement(
            MailCore.query(dbPath, buildJsonObject { put("op", "counts"); put("account", localAccountId) }.toString()).getOrThrow(),
        ).jsonObject
        o.mapValues { (_, v) ->
            val m = v.jsonObject
            CoreMailboxCount(
                local = m["local"]!!.jsonPrimitive.int,
                unread = m["unread"]!!.jsonPrimitive.int,
                serverTotal = m["serverTotal"]!!.jsonPrimitive.int,
                complete = m["complete"]!!.jsonPrimitive.boolean,
                gapsFound = m["gapsFound"]!!.jsonPrimitive.int,
            )
        }
    }

    /** A list query of the store, answered as the core's JSON (flat / thread / sender / domain, server order). */
    suspend fun list(localAccountId: String, mailboxId: String, group: String, offset: Int, limit: Int, unreadOnly: Boolean): String =
        withContext(Dispatchers.IO) {
            MailCore.query(
                dbPath,
                buildJsonObject {
                    put("op", "list"); put("account", localAccountId); put("mailbox", mailboxId); put("group", group)
                    put("offset", offset); put("limit", limit); put("unreadOnly", unreadOnly)
                }.toString(),
            ).getOrThrow()
        }

    /**
     * Bring the app's list rows of ([localAccountId], [mailboxId]) to the store's: every Email object the store wrote after this
     * mailbox's cursor is upserted (mapped by [toEntity], as every fetch path maps it), every membership that ended is removed
     * where the app still files it in this mailbox, then the cursor moves. [isProtected] spares a row the user changed a moment
     * ago (its server echo comes with the next pass). When the counts still differ afterwards (the app's rows were cleared,
     * or another path filed a row elsewhere) the mailbox is re-fed from the start; rows the store does not hold are removed
     * only once the store's walk of this mailbox is complete, so a first sync in progress never shortens the list.
     */
    suspend fun projectMailbox(
        localAccountId: String,
        mailboxId: String,
        emailDao: EmailDao,
        isProtected: (String) -> Boolean,
        beforeWrite: suspend () -> Unit,
    ): Projection = withContext(Dispatchers.IO) {
        val upserted = ArrayList<String>()
        val removed = ArrayList<String>()
        var rounds = 0
        var (seq, id) = cursors.load(localAccountId, mailboxId) ?: (0L to "")
        while (true) {
            rounds++
            val start = seq
            while (true) {
                val page = feed(localAccountId, mailboxId, seq, id)
                val inRoom = emailDao.idsForMailbox(localAccountId, mailboxId).toHashSet()
                val rows = page.emails.filterNot { isProtected(it.id) }
                val gone = page.removed.filter { it in inRoom && !isProtected(it) }
                if (rows.isNotEmpty() || gone.isNotEmpty()) {
                    beforeWrite()
                    emailDao.applyDelta(localAccountId, gone, rows.map { it.toEntity(localAccountId, mailboxId) })
                    rows.forEach { if (it.id !in inRoom) upserted += it.id }
                    removed += gone
                }
                seq = page.nextSeq
                id = page.nextId
                if (!page.more) break
            }
            cursors.save(localAccountId, mailboxId, seq, id)
            val count = counts(localAccountId)[mailboxId]
            val roomIds = emailDao.idsForMailbox(localAccountId, mailboxId).toHashSet()
            if (count == null || count.local == roomIds.size) break
            val storeIds = members(localAccountId, mailboxId)
            if (count.complete) {
                val extra = roomIds.filter { it !in storeIds && !isProtected(it) }
                if (extra.isNotEmpty()) {
                    beforeWrite()
                    emailDao.applyDelta(localAccountId, extra, emptyList())
                    removed += extra
                }
            }
            val missing = storeIds.any { it !in roomIds }
            // Re-feed from the start once when the app lacks rows the store has; never loop on a row another path re-files.
            if (!missing || rounds > 1 || start == 0L) break
            seq = 0L
            id = ""
        }
        Projection(upserted, removed).also {
            if (it.upserted.isNotEmpty() || it.removed.isNotEmpty()) {
                log("projected $localAccountId/$mailboxId: +${it.upserted.size} -${it.removed.size}")
            }
        }
    }

    private suspend fun members(localAccountId: String, mailboxId: String): Set<String> {
        val o = JSON.parseToJsonElement(
            MailCore.query(dbPath, buildJsonObject { put("op", "members"); put("account", localAccountId); put("mailbox", mailboxId) }.toString())
                .getOrThrow(),
        ).jsonObject
        return o["ids"]?.jsonArray?.map { it.jsonPrimitive.content }?.toHashSet().orEmpty()
    }

    private fun feed(localAccountId: String, mailboxId: String, seq: Long, id: String): FeedPage {
        val q = buildJsonObject {
            put("op", "changes"); put("account", localAccountId); put("mailbox", mailboxId)
            put("sinceSeq", seq); put("sinceId", id); put("limit", FEED_PAGE)
        }
        val o = JSON.parseToJsonElement(MailCore.query(dbPath, q.toString()).getOrThrow()).jsonObject
        return FeedPage(
            emails = o["emails"]!!.jsonArray.map { JSON.decodeFromJsonElement<Email>(it) },
            removed = o["removed"]!!.jsonArray.map { it.jsonPrimitive.content },
            nextSeq = o["nextSeq"]!!.jsonPrimitive.long,
            nextId = o["nextId"]!!.jsonPrimitive.content,
            more = o["more"]!!.jsonPrimitive.boolean,
        )
    }

    private class FeedPage(val emails: List<Email>, val removed: List<String>, val nextSeq: Long, val nextId: String, val more: Boolean)

    class Projection(val upserted: List<String>, val removed: List<String>)

    /** Where each mailbox's projection cursor (store seq, last id) is kept. */
    interface ProjectionCursors {
        fun load(account: String, mailbox: String): Pair<Long, String>?
        fun save(account: String, mailbox: String, seq: Long, id: String)
        fun dropAccount(account: String)

        class InMemory : ProjectionCursors {
            private val m = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
            override fun load(account: String, mailbox: String) = m["$account\u0000$mailbox"]
            override fun save(account: String, mailbox: String, seq: Long, id: String) {
                m["$account\u0000$mailbox"] = seq to id
            }
            override fun dropAccount(account: String) {
                m.keys.removeIf { it.startsWith("$account\u0000") }
            }
        }

        /** Persisted, so a process death does not re-feed every row. */
        class Prefs(context: Context) : ProjectionCursors {
            private val prefs = context.getSharedPreferences("mail_core_projection", Context.MODE_PRIVATE)
            override fun load(account: String, mailbox: String): Pair<Long, String>? {
                val raw = prefs.getString("$account\u0000$mailbox", null) ?: return null
                val split = raw.split('\n', limit = 2)
                return split[0].toLongOrNull()?.let { it to split.getOrElse(1) { "" } }
            }
            override fun save(account: String, mailbox: String, seq: Long, id: String) {
                prefs.edit().putString("$account\u0000$mailbox", "$seq\n$id").apply()
            }
            override fun dropAccount(account: String) {
                val e = prefs.edit()
                prefs.all.keys.filter { it.startsWith("$account\u0000") }.forEach { e.remove(it) }
                e.apply()
            }
        }
    }

    companion object {
        /** Bodies the core prefetches per mailbox (the newest), for the reader to open offline. */
        const val PREFETCH_BODIES = 30
        /** Rows one projection feed page carries. */
        const val FEED_PAGE = 500
        /** How long an interactive refresh lets the core walk before it shows what it has (the worker continues). */
        const val INTERACTIVE_BUDGET_MS = 8_000L
        /** How long one background pass of the worker may walk. */
        const val WORKER_BUDGET_MS = 8 * 60_000L

        private val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        /**
         * The Gmail-like reading document around a reader [fragment] (the core owns the stylesheet: Roboto 14 px, Gmail's colours,
         * images and tables fit the width, `pre` keeps its alignment). Pure: it reads no store. Null when the core is not loaded.
         */
        fun wrap(kind: String, fragment: String, theme: CoreReaderTheme): String? {
            if (MailCore.unavailableReason() != null) return null
            val q = buildJsonObject {
                put("op", "wrap"); put("kind", kind); put("fragment", fragment)
                put("dark", theme.dark); put("bg", theme.background); put("fg", theme.text); put("link", theme.link)
                put("images", true); put("top", theme.topSpacerCss); put("bottom", theme.bottomSpacerCss)
                put("quoteLabel", theme.quoteLabel)
            }
            val answer = MailCore.query(":memory:", q.toString()).getOrElse {
                log("wrap: failed, old reader path: ${it.message}")
                return null
            }
            return runCatching { JSON.parseToJsonElement(answer).jsonObject["doc"]?.jsonPrimitive?.content }.getOrNull()
        }

        fun log(message: String) {
            runCatching { android.util.Log.i("MailCore", message) }
        }

        fun create(context: Context): RustMailEngine =
            RustMailEngine(java.io.File(context.filesDir, "mail-core/mail.db").path, ProjectionCursors.Prefs(context))
    }
}

/** One mailbox's line of the store's counts. */
data class CoreMailboxCount(val local: Int, val unread: Int, val serverTotal: Int, val complete: Boolean, val gapsFound: Int)

/** What one core sync pass did (the core's JSON report, the fields the app logs and shows). */
data class CoreSyncReport(
    val mode: String,
    val fetched: Int,
    val removed: Int,
    val walks: Int,
    val gaps: Int,
    val bodies: Int,
    val requests: Int,
    val ms: Long,
    val complete: Boolean,
    val raw: JsonObject,
) {
    val summary: String
        get() = "mode=$mode fetched=$fetched removed=$removed walks=$walks gaps=$gaps bodies=$bodies requests=$requests " +
            "${ms}ms complete=$complete"

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun parse(text: String): CoreSyncReport {
            val o = JSON.parseToJsonElement(text).jsonObject
            fun i(k: String) = o[k]?.jsonPrimitive?.int ?: 0
            val walks = o["walks"]?.jsonArray.orEmpty()
            return CoreSyncReport(
                mode = o["mode"]?.jsonPrimitive?.content.orEmpty(),
                fetched = i("fetched"),
                removed = i("removed"),
                walks = walks.size,
                gaps = walks.sumOf { it.jsonObject["missing"]?.jsonPrimitive?.int ?: 0 },
                bodies = i("bodies"),
                requests = i("requests"),
                ms = o["ms"]?.jsonPrimitive?.long ?: 0L,
                complete = o["complete"]?.jsonPrimitive?.boolean ?: false,
                raw = o,
            )
        }
    }
}

/** A message's reader document as the core stored it: [fragment] is sanitised HTML (inline images embedded), [kind] "html" or "text". */
data class CoreReaderDoc(
    val emailId: String,
    val kind: String,
    val fragment: String,
    val text: String,
    val remoteImages: Boolean,
    val encodingProblem: Boolean,
)

/** What the screen tells the core about the page: theme colours (CSS hex), the two spacers (CSS lengths) and the quote button's words. */
data class CoreReaderTheme(
    val dark: Boolean,
    val background: String,
    val text: String,
    val link: String,
    val topSpacerCss: String,
    val bottomSpacerCss: String,
    val quoteLabel: String,
)
