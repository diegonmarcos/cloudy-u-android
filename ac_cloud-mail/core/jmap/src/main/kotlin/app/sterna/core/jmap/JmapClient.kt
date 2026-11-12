package app.sterna.core.jmap

import app.sterna.core.jmap.model.CrawlPage
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailChangesResult
import app.sterna.core.jmap.model.EmailIdPage
import app.sterna.core.jmap.model.EmailPage
import app.sterna.core.jmap.model.EmailQueryChangesResult
import app.sterna.core.jmap.model.EmailSetResult
import app.sterna.core.jmap.model.Identity
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.jmap.model.PushSubscription
import app.sterna.core.jmap.model.StateChange
import app.sterna.core.jmap.model.Quota
import app.sterna.core.jmap.model.SearchPage
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.core.jmap.model.SieveScript
import app.sterna.core.jmap.model.SubmissionEnvelope
import app.sterna.core.jmap.model.UploadedBlob
import app.sterna.core.jmap.model.VacationResponse
import com.diegonmarcos.superapp.fleetendpoints.EndpointRouteInterceptor
import com.diegonmarcos.superapp.fleetendpoints.FleetBearerInterceptor
import com.diegonmarcos.superapp.fleetendpoints.FleetTransport
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.Closeable
import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Minimal JMAP client: fetch the Session resource and run method calls.
 * Pure JVM (no Android), so it is unit-testable with MockWebServer.
 */
class JmapClient internal constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
        /**
         * Where the few push-connection lines go. Silent by default so this module keeps no logging
         */
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    /**
     * #1674: the Inbox guard of each account whose mailboxes this client fetched, by accountId.
     * Every membership patch consults it ([inboxSafeRemove]): no move / archive / label / rule
     * action may take a message out of the Inbox; only a delete (Trash) or spam report (Junk) may.
     */
    private val inboxGuards = java.util.concurrent.ConcurrentHashMap<String, InboxGuard>()

    /** The Inbox guard recorded for [accountId], or null before its mailboxes were fetched. */
    fun inboxGuardOf(accountId: String): InboxGuard? = inboxGuards[accountId]

    /** Records [accountId]'s Inbox guard from its [mailboxes] (also what [getMailboxes] does). */
    fun rememberInboxGuard(accountId: String, mailboxes: List<Mailbox>) {
        InboxGuard.of(mailboxes)?.let { inboxGuards[accountId] = it }
    }

    /** Public constructor for app code — uses a default OkHttp client. */
    constructor(log: (String, Throwable?) -> Unit = { _, _ -> }) : this(defaultHttpClient(), DefaultJson, log)

    /** GET the Session resource and parse it (RFC 8620 §2). */
    suspend fun fetchSession(sessionUrl: String, auth: JmapAuth): JmapSession =
        withContext(Dispatchers.IO) {
            var url = sessionUrl
            var retried = false
            while (true) {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", auth.authorizationHeader())
                    .header("Accept", "application/json")
                    .get()
                    .build()
                val session = httpClient.newCall(request).execute().use { response ->
                            // OkHttp drops the Authorization header when a redirect changes origin, so an
                            // autodiscovery redirect (RFC 8620 §2.2) lands unauthenticated; retry it once,
                            // re-authenticated. The trigger is the MECHANICAL fact that dropped the
                            // header, never the answer: Stalwart answers 200 with an empty session (#137).
                    val landedAt = response.request.url
                    if (!retried && response.priorResponse != null &&
                        redirectDroppedAuthorization(request.url, landedAt)
                    ) {
                        retried = true
                        url = landedAt.toString()
                        return@use null
                    }
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw JmapException(
                            "Session request failed: HTTP ${response.code} ${response.message}",
                            httpCode = response.code,
                            edge = (response.code == 401 || response.code == 403) &&
                                RejectionLayer.of(response.headers("WWW-Authenticate"), response.header("Content-Type")) == RejectionLayer.GATE,
                        )
                    }
                    runCatching { json.decodeFromString<JmapSession>(body) }
                        .getOrElse { throw JmapException("Could not parse JMAP session", it) }
                }
                if (session != null) return@withContext upgradeSessionUrls(session, url)
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("unreachable")
        }

    /** Fetch all mailboxes for an account via a single Mailbox/get call (RFC 8621 §2.1). */
    suspend fun getMailboxes(session: JmapSession, accountId: String, auth: JmapAuth): List<Mailbox> =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.MAIL_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Mailbox/get")
                        addJsonObject {
                            put("accountId", accountId)
                            put("ids", JsonNull) // null = all mailboxes
                        }
                        add("c0")
                    }
                }
            }
            val request = Request.Builder()
                .url(session.apiUrl)
                .header("Authorization", auth.authorizationHeader())
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw JmapException("Mailbox/get failed: HTTP ${response.code} ${response.message}")
                }
                decodeList(body, "Mailbox/get", Mailbox.serializer())
                    .also { rememberInboxGuard(accountId, it) }
            }
        }

        /** Fetch the most recent emails in a mailbox: a batched Email/query + Email/get, where
         *  Email/get back-references the query result (RFC 8620 §3.7). */
    suspend fun queryEmailsPage(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        limit: Int,
        auth: JmapAuth,
        position: Int = 0,
        calculateTotal: Boolean = false,
        // Stable paging: anchor on a known email id and start [anchorOffset] after
        // it, instead of an absolute [position] that shifts when new mail arrives.
        anchorId: String? = null,
        anchorOffset: Int = 0,
        // Only unread messages (notKeyword $seen) — lets "Mark all read" resolve its
        // targets server-side instead of from the cached window.
        unseenOnly: Boolean = false,
    ): EmailPage = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/query")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("filter") {
                            put("inMailbox", mailboxId)
                            if (unseenOnly) put("notKeyword", "\$seen")
                        }
                        putJsonArray("sort") {
                            addJsonObject {
                                put("property", "receivedAt")
                                put("isAscending", false)
                            }
                        }
                            // Always uncollapsed — every message, not one representative per thread:
                            // the local cache is WYSIWYG, and a collapsed query once made "empty
                            // Trash" destroy only thread representatives.
                        put("collapseThreads", false)
                        if (anchorId != null) {
                            put("anchor", anchorId)
                            put("anchorOffset", anchorOffset)
                        } else {
                            put("position", position)
                        }
                        put("limit", limit)
                        if (calculateTotal) put("calculateTotal", true)
                    }
                    add("q0")
                }
                addJsonArray {
                    add("Email/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("#ids") {
                            put("resultOf", "q0")
                            put("name", "Email/query")
                            put("path", "/ids")
                        }
                        putJsonArray("properties") { EMAIL_LIST_PROPERTIES.forEach { add(it) } }
                    }
                    add("g0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Email/query failed: HTTP ${response.code} ${response.message}")
            }
            EmailPage(
                emails = decodeList(body, "Email/get", Email.serializer()),
                queryState = methodResponseArgs(body, "Email/query")["queryState"]?.jsonPrimitive?.contentOrNull,
                emailState = methodResponseArgs(body, "Email/get")["state"]?.jsonPrimitive?.contentOrNull,
                total = methodResponseArgs(body, "Email/query")["total"]?.jsonPrimitive?.intOrNull,
                queryCount = (methodResponseArgs(body, "Email/query")["ids"] as? JsonArray)?.size ?: 0,
            )
        }
    }

            /**
             * Handed to [onPage] ONE REQUEST AT A TIME, so the peak is one server page and not the window:
             */
    suspend fun queryEmailsWindow(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        target: Int,
        pageSize: Int,
        auth: JmapAuth,
        onPage: suspend (List<Email>) -> Unit,
    ): WindowWalk = withContext(Dispatchers.IO) {
            // Ids in walk order, plus the set that de-duplicates them: pages can overlap after a
            // recovery, and a duplicate would be written and counted twice against the window.
        val ids = ArrayList<String>()
        val seen = HashSet<String>()
            // The first response's two cursor STRINGS, not the first response: keeping the page alive
            // to read two strings off it at the end pinned a whole decoded page for the walk.
            // `sawFirst` and not a null check — the first response is allowed to carry no cursors.
        var sawFirst = false
        var firstQueryState: String? = null
        var firstEmailState: String? = null
        var seenIds = 0
            // Whether this walk ever paged by an absolute POSITION. A property of the WHOLE walk: once
            // a recovery under-shoots, the ids it missed are gone from `ids` and the caller must know.
        var resumedByPosition = false
        var limit = nextWindowPageLimit(fetched = 0, target = target, pageSize = pageSize, last = null)
        while (limit != null) {
            // The oldest id accumulated so far, i.e. where the previous request stopped.
            val anchor = ids.lastOrNull()
            val page = try {
                queryEmailsPage(
                    session, accountId, mailboxId, limit, auth,
                    anchorId = anchor,
                    anchorOffset = if (anchor != null) 1 else 0,
                )
            } catch (e: JmapException) {
                        // Recover ONCE on an absolute position; any other failure propagates. ONE
                        // BEHIND the count accumulated: `anchorNotFound` says the list lost a row, so
                if (anchor == null || e.errorType != "anchorNotFound") throw e
                resumedByPosition = true
                queryEmailsPage(
                    session, accountId, mailboxId, limit, auth,
                    position = (ids.size - 1).coerceAtLeast(0),
                )
            }
            if (!sawFirst) {
                sawFirst = true
                firstQueryState = page.queryState
                firstEmailState = page.emailState
            }
            // `seen.add` is the de-duplication the accumulation used to get from `putIfAbsent`,
            // and it has to happen HERE, before the hand-off: a message the recovery page repeats
            // must not be written twice, and must not count twice against the window.
            val fresh = page.emails.filter { seen.add(it.id) }
            fresh.forEach { ids += it.id }
            // Handed off NOW, while the next request has not been sent: this is the line that
            // turns a window into a stream. Its failure propagates (see the KDoc).
            if (fresh.isNotEmpty()) onPage(fresh)
            seenIds += page.queryCount
            limit = nextWindowPageLimit(
                fetched = ids.size,
                target = target,
                pageSize = pageSize,
                last = WalkedPage(
                    requested = limit,
                    queryCount = page.queryCount,
                    added = fresh.size,
                ),
            )
        }
        WindowWalk(
            ids = ids,
            queryState = firstQueryState,
            emailState = firstEmailState,
            // Every id the walk's queries listed, so a caller can still tell a short GET from an
            // exhausted folder. Not a single query's count — this walk is not a single query.
            queryCount = seenIds,
            // The walk's own verdict on whether it may be reconciled against. See the catch.
            resumedByPosition = resumedByPosition,
        )
    }

        /** Ids-only page of a mailbox query: a lone Email/query, no chained Email/get — for resolving
         *  bulk-action targets ("Mark all read"), where fetching thousands of headers is pure waste. */
    suspend fun queryEmailIds(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        limit: Int,
        auth: JmapAuth,
        position: Int = 0,
        calculateTotal: Boolean = false,
        unseenOnly: Boolean = false,
    ): EmailIdPage = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/query")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("filter") {
                            put("inMailbox", mailboxId)
                            if (unseenOnly) put("notKeyword", "\$seen")
                        }
                        putJsonArray("sort") {
                            addJsonObject {
                                put("property", "receivedAt")
                                put("isAscending", false)
                            }
                        }
                        // Always uncollapsed — see [queryEmailsPage]: bulk targets must
                        // cover every message, never just thread representatives.
                        put("collapseThreads", false)
                        put("position", position)
                        put("limit", limit)
                        if (calculateTotal) put("calculateTotal", true)
                    }
                    add("q0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Email/query failed: HTTP ${response.code} ${response.message}")
            }
            val args = methodResponseArgs(body, "Email/query")
            EmailIdPage(
                ids = (args["ids"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                total = args["total"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    suspend fun queryEmails(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        limit: Int,
        auth: JmapAuth,
    ): List<Email> = queryEmailsPage(session, accountId, mailboxId, limit, auth).emails

        /** Email/queryChanges for the folder sync query. Its arguments (filter, sort, collapseThreads)
         *  MUST mirror [queryEmailsPage]'s exactly: a queryState is only comparable against the same
         *  query (RFC 8620 §5.6). */
    suspend fun emailQueryChanges(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        sinceQueryState: String,
        maxChanges: Int,
        auth: JmapAuth,
    ): EmailQueryChangesResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/queryChanges")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("filter") { put("inMailbox", mailboxId) }
                        putJsonArray("sort") {
                            addJsonObject {
                                put("property", "receivedAt")
                                put("isAscending", false)
                            }
                        }
                        put("collapseThreads", false)
                        put("sinceQueryState", sinceQueryState)
                        put("maxChanges", maxChanges)
                    }
                    add("qc0")
                }
            }
        }
        val body = postJmap(session, auth, payload)
        val args = methodResponseArgsOrNull(body, "Email/queryChanges")
            ?: return@withContext EmailQueryChangesResult(null, emptyList(), emptyList(), calculated = false)
        EmailQueryChangesResult(
            newQueryState = args["newQueryState"]?.jsonPrimitive?.contentOrNull,
            removed = args["removed"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            added = args["added"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull } ?: emptyList(),
            calculated = true,
        )
    }

    /** Email/changes for property-level deltas (created/updated/destroyed). */
    suspend fun emailChanges(
        session: JmapSession,
        accountId: String,
        sinceState: String,
        maxChanges: Int,
        auth: JmapAuth,
    ): EmailChangesResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/changes")
                    addJsonObject {
                        put("accountId", accountId)
                        put("sinceState", sinceState)
                        put("maxChanges", maxChanges)
                    }
                    add("c0")
                }
            }
        }
        val body = postJmap(session, auth, payload)
        val args = methodResponseArgsOrNull(body, "Email/changes")
            ?: return@withContext EmailChangesResult(null, emptyList(), emptyList(), emptyList(), hasMoreChanges = false, calculated = false)
        fun ids(key: String) = args[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        EmailChangesResult(
            newState = args["newState"]?.jsonPrimitive?.contentOrNull,
            created = ids("created"),
            updated = ids("updated"),
            destroyed = ids("destroyed"),
            hasMoreChanges = args["hasMoreChanges"]?.jsonPrimitive?.booleanOrNull ?: false,
            calculated = true,
        )
    }

    /** Email/get a specific set of ids with list (no-body) properties, split across as many
     *  requests as [JmapSession.getBatchSize] requires ([getInBatches]) and concatenated. */
    suspend fun getEmailsByIds(
        session: JmapSession,
        accountId: String,
        ids: List<String>,
        auth: JmapAuth,
    ): List<Email> = withContext(Dispatchers.IO) {
        getInBatches(session, ids) { batch ->
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.MAIL_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Email/get")
                        addJsonObject {
                            put("accountId", accountId)
                            putJsonArray("ids") { batch.forEach { add(it) } }
                            putJsonArray("properties") { EMAIL_LIST_PROPERTIES.forEach { add(it) } }
                        }
                        add("g0")
                    }
                }
            }
            decodeList(postJmap(session, auth, payload), "Email/get", Email.serializer())
        }
    }

            /**
             * An authoritative existence check for the sync ghost sweep: ONLY ids listed in the response's
             */
    suspend fun missingEmailIds(
        session: JmapSession,
        accountId: String,
        ids: List<String>,
        auth: JmapAuth,
    ): Set<String> = withContext(Dispatchers.IO) {
        getInBatches(session, ids) { batch ->
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.MAIL_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Email/get")
                        addJsonObject {
                            put("accountId", accountId)
                            putJsonArray("ids") { batch.forEach { add(it) } }
                            putJsonArray("properties") { add("id") }
                        }
                        add("g0")
                    }
                }
            }
            val args = methodResponseArgs(postJmap(session, auth, payload), "Email/get")
            args["notFound"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        }.toSet()
    }

            /**
             * Each id the server returns mapped to its `mailboxIds` set; ids it does not return are absent.
             */
    suspend fun mailboxIdsOf(
        session: JmapSession,
        accountId: String,
        ids: List<String>,
        auth: JmapAuth,
    ): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        getInBatches(session, ids) { batch ->
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.MAIL_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Email/get")
                        addJsonObject {
                            put("accountId", accountId)
                            putJsonArray("ids") { batch.forEach { add(it) } }
                            putJsonArray("properties") {
                                add("id")
                                add("mailboxIds")
                            }
                        }
                        add("g0")
                    }
                }
            }
            val args = methodResponseArgs(postJmap(session, auth, payload), "Email/get")
            args["list"]?.jsonArray?.mapNotNull { entry ->
                val row = entry.jsonObject
                val id = row["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                // `{mailboxId: true}` per RFC 8621 §4.1.1; a false value would mean "not in it".
                val folders = row["mailboxIds"]?.jsonObject.orEmpty()
                    .filterValues { it.jsonPrimitive.booleanOrNull == true }.keys.toSet()
                id to folders
            }.orEmpty()
        }.toMap()
    }

        /**
         * Full-text search across the account (Email/query `text` filter + Email/get). Returns both
         */
    suspend fun searchEmails(
        session: JmapSession,
        accountId: String,
        query: SearchQuery,
        limit: Int,
        auth: JmapAuth,
        excludeMailboxIds: List<String> = emptyList(),
    ): SearchPage = withContext(Dispatchers.IO) {
        val filter = searchFilter(query, excludeMailboxIds)
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/query")
                    addJsonObject {
                        put("accountId", accountId)
                        put("filter", filter)
                        putJsonArray("sort") {
                            addJsonObject {
                                put("property", "receivedAt")
                                put("isAscending", false)
                            }
                        }
                        put("limit", limit)
                    }
                    add("q0")
                }
                addJsonArray {
                    add("Email/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("#ids") {
                            put("resultOf", "q0")
                            put("name", "Email/query")
                            put("path", "/ids")
                        }
                        putJsonArray("properties") { EMAIL_INDEX_PROPERTIES.forEach { add(it) } }
                    }
                    add("g0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Search failed: HTTP ${response.code} ${response.message}")
            }
            // mailboxId is left null: the caller (core:data) resolves each hit's folder
            // deterministically from the returned mailboxIds map — picking the map's
            // arbitrary first key here could route a multi-mailbox hit to Trash.
            SearchPage(
                emails = decodeList(body, "Email/get", Email.serializer()),
                // What the QUERY matched, kept apart from what the GET returned — same reason the
                // crawl keeps both (see [SearchPage]). Absent `ids` stays null rather than 0.
                matchedIds = methodResponseArgs(body, "Email/query")["ids"]?.jsonArray?.size,
            )
        }
    }

        /** Crawl message headers (no filter) for the local search index: `Email/query` the whole
         *  account newest-first from [position], then `Email/get` the lightweight header fields.
         *  Bodies are not fetched. Up to [limit] emails, fewer at the end of the mailbox. */
    suspend fun crawlHeaders(
        session: JmapSession,
        accountId: String,
        position: Int,
        limit: Int,
        auth: JmapAuth,
        excludeMailboxIds: List<String> = emptyList(),
    ): CrawlPage = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/query")
                    addJsonObject {
                        put("accountId", accountId)
                        // Don't index Trash/Junk into the local search index (same exclusion the
                        // server search and the IMAP walk apply), so a deleted message never
                        // surfaces in as-you-type results. A message still filed elsewhere is kept.
                        if (excludeMailboxIds.isNotEmpty()) putJsonObject("filter") {
                            putJsonArray("inMailboxOtherThan") { excludeMailboxIds.forEach { add(it) } }
                        }
                        putJsonArray("sort") {
                            addJsonObject {
                                put("property", "receivedAt")
                                put("isAscending", false)
                            }
                        }
                        put("position", position)
                        put("limit", limit)
                    }
                    add("q0")
                }
                addJsonArray {
                    add("Email/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("#ids") {
                            put("resultOf", "q0")
                            put("name", "Email/query")
                            put("path", "/ids")
                        }
                        putJsonArray("properties") {
                            // Headers only — responses stay tiny so the crawl reaches even years-old
                            // mail fast. Body search is served by the server's own full-text index.
                            EMAIL_INDEX_PROPERTIES.forEach { add(it) }
                        }
                    }
                    add("g0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Index crawl failed: HTTP ${response.code} ${response.message}")
            }
            val queryCount = methodResponseArgs(body, "Email/query")["ids"]?.jsonArray?.size ?: 0
            CrawlPage(
                emails = decodeList(body, "Email/get", Email.serializer()),
                queryCount = queryCount,
            )
        }
    }

            /**
             * Its own request, deliberately NOT [getEmailsWithBody] with one id: this is the reader opening
             */
    suspend fun getEmail(
        session: JmapSession,
        accountId: String,
        emailId: String,
        auth: JmapAuth,
    ): Email = withContext(Dispatchers.IO) {
        // Two guards, receipt OUTSIDE and unsubscribe INSIDE — the order is load-bearing and its
        // reasons are in [withReceiptHeaderFallback].
        withReceiptHeaderFallback(session) { withReceiptHeader ->
            withUnsubscribeHeaderFallback(session) { withUnsubscribeHeaders ->
                val payload = buildJsonObject {
                    putJsonArray("using") {
                        add(Jmap.CORE_CAPABILITY)
                        add(Jmap.MAIL_CAPABILITY)
                    }
                    putJsonArray("methodCalls") {
                        addJsonArray {
                            add("Email/get")
                            addJsonObject {
                                put("accountId", accountId)
                                putJsonArray("ids") { add(emailId) }
                                putJsonArray("properties") {
                                    EMAIL_BODY_PROPERTIES.forEach { add(it) }
                                    if (withUnsubscribeHeaders) UNSUBSCRIBE_PROPERTIES.forEach { add(it) }
                                    if (withReceiptHeader) add(RECEIPT_HEADER_PROPERTY)
                                }
                                put("fetchHTMLBodyValues", true)
                                put("fetchTextBodyValues", true)
                            }
                            add("g0")
                        }
                    }
                }
                val request = Request.Builder()
                    .url(session.apiUrl)
                    .header("Authorization", auth.authorizationHeader())
                    .header("Accept", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // The message is unchanged word for word — it is shown to the reader.
                        // Only the structured code is new, and only so the fallbacks above can
                        // tell a rejected property (400) from a refusal that must stand.
                        throw JmapException(
                            "Email/get failed: HTTP ${response.code} ${response.message}",
                            httpCode = response.code,
                        )
                    }
                    decodeList(body, "Email/get", Email.serializer()).firstOrNull()
                        ?: throw JmapException("Email not found: $emailId")
                }
            }
        }
    }

        /** Fetch just the raw header fields of a message, in original order with duplicates kept
         *  (RFC 8621 §4.1.3 `headers`). No blob download, no body values — the reader's "view headers"
         *  action (issue #60). Empty list if the id is not found. */
    suspend fun getEmailHeaders(
        session: JmapSession,
        accountId: String,
        emailId: String,
        auth: JmapAuth,
    ): List<app.sterna.core.jmap.model.EmailHeader> =
        getEmailsHeaders(session, accountId, listOf(emailId), auth)[emailId] ?: emptyList()

            /**
             * The same read for MANY messages, keyed by id — what the body prefetch needs to name a
             */
    suspend fun getEmailsHeaders(
        session: JmapSession,
        accountId: String,
        ids: List<String>,
        auth: JmapAuth,
    ): Map<String, List<app.sterna.core.jmap.model.EmailHeader>> = withContext(Dispatchers.IO) {
        getInBatches(session, ids) { batch ->
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.MAIL_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Email/get")
                        addJsonObject {
                            put("accountId", accountId)
                            putJsonArray("ids") { batch.forEach { add(it) } }
                            putJsonArray("properties") { HEADER_FIELDS_PROPERTIES.forEach { add(it) } }
                        }
                        add("g0")
                    }
                }
            }
            decodeList(postJmap(session, auth, payload), "Email/get", Email.serializer())
        }.associate { it.id to it.headers }
    }

            /**
             * Split across as many Email/get requests as [JmapSession.getBatchSize] requires. Same
             */
    suspend fun getEmailsWithBody(
        session: JmapSession,
        accountId: String,
        ids: List<String>,
        auth: JmapAuth,
    ): List<Email> = withContext(Dispatchers.IO) {
        getInBatches(session, ids) { batch ->
            withReceiptHeaderFallback(session) { withReceiptHeader ->
                withUnsubscribeHeaderFallback(session) { withUnsubscribeHeaders ->
                    val payload = buildJsonObject {
                        putJsonArray("using") {
                            add(Jmap.CORE_CAPABILITY)
                            add(Jmap.MAIL_CAPABILITY)
                        }
                        putJsonArray("methodCalls") {
                            addJsonArray {
                                add("Email/get")
                                addJsonObject {
                                    put("accountId", accountId)
                                    putJsonArray("ids") { batch.forEach { add(it) } }
                                    putJsonArray("properties") {
                                        EMAIL_BODY_PROPERTIES.forEach { add(it) }
                                        if (withUnsubscribeHeaders) UNSUBSCRIBE_PROPERTIES.forEach { add(it) }
                                        if (withReceiptHeader) add(RECEIPT_HEADER_PROPERTY)
                                    }
                                    put("fetchHTMLBodyValues", true)
                                    put("fetchTextBodyValues", true)
                                }
                                add("g0")
                            }
                        }
                    }
                    decodeList(postJmap(session, auth, payload), "Email/get", Email.serializer())
                }
            }
        }
    }

            /**
             * Asks for the two `header:List-Unsubscribe*` properties and, if the server rejects them, runs
             */
    private suspend fun <T> withUnsubscribeHeaderFallback(
        session: JmapSession,
        call: suspend (withUnsubscribeHeaders: Boolean) -> T,
    ): T {
        val ask = session.apiUrl !in serversRefusingUnsubscribeHeaders
        return try {
            call(ask)
        } catch (e: JmapException) {
            if (!ask || !isPropertyRejection(e)) throw e
                // The verdict is recorded only if dropping THESE properties is what made the call
                // work. They are no longer the only optional thing in the payload: a server refusing
                // the receipt property instead would otherwise be written down as refusing these, and
                // lose the reader's banner for a header it was happy with.
            val answered = call(false)
            serversRefusingUnsubscribeHeaders.add(session.apiUrl)
            answered
        }
    }

            /**
             * The twin for [RECEIPT_HEADER_PROPERTY], with its OWN per-URL memo: one guard over both
             */
    private suspend fun <T> withReceiptHeaderFallback(
        session: JmapSession,
        call: suspend (withReceiptHeader: Boolean) -> T,
    ): T {
        val ask = session.apiUrl !in serversRefusingReceiptHeader
        return try {
            call(ask)
        } catch (e: JmapException) {
            if (!ask || isRateLimit(e)) throw e
            val answered = try {
                call(false)
            } catch (_: JmapException) {
                // The property was not the problem. Report what the reader actually met.
                throw e
            }
            serversRefusingReceiptHeader.add(session.apiUrl)
            answered
        }
    }

            /**
             * Whether [session]'s server was measured to reject [RECEIPT_HEADER_PROPERTY], i.e. whether a
             */
    fun refusesReceiptHeader(session: JmapSession): Boolean =
        session.apiUrl in serversRefusingReceiptHeader

            /**
             * The THIRD per-URL memo, for [AUTOCRYPT_HEADER_PROPERTY] on [sendEmail]. At stake is not a
             */
    private suspend fun <T> withAutocryptHeaderFallback(
        session: JmapSession,
        carriesHeader: Boolean,
        call: suspend (withAutocryptHeader: Boolean, point: SendPointOfNoReturn) -> T,
    ): T {
        val ask = carriesHeader && session.apiUrl !in serversRefusingAutocryptHeader
        val point = SendPointOfNoReturn()
        return try {
            call(ask, point)
        } catch (e: JmapException) {
            if (!mayReplayWithoutAutocryptHeader(ask, point.passed, e.message)) throw e
            val answered = try {
                call(false, SendPointOfNoReturn())
            } catch (_: JmapException) {
                // The property was not the problem after all. Report what the sender actually met.
                throw e
            }
            serversRefusingAutocryptHeader.add(session.apiUrl)
            answered
        }
    }

            /** Whether [session]'s server was measured to reject [AUTOCRYPT_HEADER_PROPERTY], i.e. whether
             * its sends go out without the sender's key. `internal`, unlike [refusesReceiptHeader],
             *  which `MailRepository` reads across the module: this one answers nobody outside
             *  [withAutocryptHeaderFallback] and this module's tests. */
    internal fun refusesAutocryptHeader(session: JmapSession): Boolean =
        session.apiUrl in serversRefusingAutocryptHeader

            /**
             * `Thread/get`, then `Email/get` on the ids it named. TWO requests, not one chained pair: a
             */
    suspend fun getThreadEmails(
        session: JmapSession,
        accountId: String,
        threadId: String,
        auth: JmapAuth,
    ): List<Email> = withContext(Dispatchers.IO) {
        val ids = threadEmailIds(session, accountId, threadId, auth)
        getInBatches(session, ids) { batch ->
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.MAIL_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Email/get")
                        addJsonObject {
                            put("accountId", accountId)
                            putJsonArray("ids") { batch.forEach { add(it) } }
                            putJsonArray("properties") {
                                // Thread members are cached too ([MailRepository.fetchThreadMembers]),
                                // so this is the list set — plus `mailboxIds`, which is how the caller
                                // decides which folder to file each member under.
                                (EMAIL_LIST_PROPERTIES + "mailboxIds").forEach { add(it) }
                            }
                        }
                        add("g0")
                    }
                }
            }
            decodeList(postJmap(session, auth, payload), "Email/get", Email.serializer())
        }
    }

        /**
         * The email ids a thread is made of, in the server's order (RFC 8621 §3) — the first half of
         */
    private suspend fun threadEmailIds(
        session: JmapSession,
        accountId: String,
        threadId: String,
        auth: JmapAuth,
    ): List<String> {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Thread/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonArray("ids") { add(threadId) }
                    }
                    add("t0")
                }
            }
        }
        val list = methodResponseArgs(postJmap(session, auth, payload), "Thread/get")["list"]
            ?.jsonArray ?: return emptyList()
        return list.flatMap { thread ->
            thread.jsonObject["emailIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()
        }
    }

    /** Set or clear a keyword (e.g. "${'$'}seen", "${'$'}flagged") on an email. */
    /** Returns the new `Email/set` state so the caller can advance its sync cursor. */
    suspend fun setKeyword(
        session: JmapSession,
        accountId: String,
        emailId: String,
        keyword: String,
        value: Boolean,
        auth: JmapAuth,
    ): String? {
        val args = emailSet(session, auth) {
            put("accountId", accountId)
            putJsonObject("update") {
                putJsonObject(emailId) {
                    if (value) put("keywords/$keyword", true) else put("keywords/$keyword", JsonNull)
                }
            }
        }
        val result = emailSetResult(args)
        // errorType carries the per-id SetError type (RFC 8620 §5.3) so the repository can
        // tell an authoritative `notFound` (the id no longer exists — prune the cached row)
        // from other rejections, without parsing the human-readable message.
        result.failed[emailId]?.let { throw JmapException("Server rejected the keyword change ($it)", errorType = it) }
        return result.newState
    }

    /** Convenience for the \$seen keyword. Returns the new `Email/set` state. */
    suspend fun setSeen(session: JmapSession, accountId: String, emailId: String, seen: Boolean, auth: JmapAuth): String? =
        setKeyword(session, accountId, emailId, "\$seen", seen, auth)

            /**
             * Split into requests of at most [JmapSession.setBatchSize] ids. THROWS on a transport
             */
    suspend fun setSeenAll(
        session: JmapSession,
        accountId: String,
        emailIds: List<String>,
        seen: Boolean,
        auth: JmapAuth,
    ): EmailSetResult = setInBatches(session, emailIds, rethrowTransportFailure = true) { batch ->
        emailSet(session, auth) {
            put("accountId", accountId)
            putJsonObject("update") {
                batch.forEach { id ->
                    putJsonObject(id) {
                        if (seen) put("keywords/\$seen", true) else put("keywords/\$seen", JsonNull)
                    }
                }
            }
        }
    }

    /**
     * Move an email out of [sourceMailboxId] and into [targetMailboxId]. Returns the new state.
     * Throws when the server rejects the update (per-id `notUpdated`).
     *
     * [sourceMailboxId] is REQUIRED and has no default. A null default would read as "the caller
     * does not know", and the patch this builds would then only ADD the target — turning every
     * move into a copy that leaves the message in the folder it was supposed to leave. Callers
     * all have the source in hand; passing null is a deliberate statement that there is nothing
     * to leave (an add-only file, which [addToMailbox] says better).
     */
    suspend fun move(
        session: JmapSession,
        accountId: String,
        emailId: String,
        targetMailboxId: String,
        auth: JmapAuth,
        sourceMailboxId: String?,
    ): String? {
        val args = emailSet(session, auth) {
            put("accountId", accountId)
            putJsonObject("update") {
                putJsonObject(emailId) {
                    putMembershipPatch(add = targetMailboxId, remove = inboxSafeRemove(inboxGuards[accountId], targetMailboxId, sourceMailboxId))
                }
            }
        }
        val result = emailSetResult(args)
        // errorType = the per-id SetError type, so callers can react to `notFound` (see setKeyword).
        result.failed[emailId]?.let { throw JmapException("Server rejected the move ($it)", errorType = it) }
        return result.newState
    }

    /**
     * Put [emailId] IN [mailboxId], leaving every other mailbox it belongs to alone.
     *
     * This is one of the two honest operations on a JMAP message's folder membership — see
     * [putMembershipPatch]. A message belongs to a SET of mailboxes at once, so "add" and
     * "remove" are the primitives and "move" is the pair of them; there is no single-folder
     * slot to overwrite, and code that writes one destroys labels.
     */
    suspend fun addToMailbox(
        session: JmapSession,
        accountId: String,
        emailId: String,
        mailboxId: String,
        auth: JmapAuth,
    ): String? = patchMembership(session, accountId, emailId, auth, add = mailboxId, remove = null)

    /**
     * Take [emailId] OUT of [mailboxId], leaving every other mailbox it belongs to alone.
     *
     * The server refuses a patch that would empty the set (RFC 8621 §4.1: a message must be in at
     * least one mailbox), and that refusal arrives as a per-id `notUpdated` this throws on — which
     * is the right outcome. Removing the last mailbox is not "hide it", it is "lose it", and the
     * caller has to say that out loud with a destroy rather than reach it by subtraction.
     */
    suspend fun removeFromMailbox(
        session: JmapSession,
        accountId: String,
        emailId: String,
        mailboxId: String,
        auth: JmapAuth,
    ): String? = patchMembership(session, accountId, emailId, auth, add = null, remove = mailboxId)

    private suspend fun patchMembership(
        session: JmapSession,
        accountId: String,
        emailId: String,
        auth: JmapAuth,
        add: String?,
        remove: String?,
    ): String? {
        val args = emailSet(session, auth) {
            put("accountId", accountId)
            putJsonObject("update") {
                putJsonObject(emailId) { putMembershipPatch(add = add, remove = inboxSafeRemove(inboxGuards[accountId], add, remove)) }
            }
        }
        val result = emailSetResult(args)
        result.failed[emailId]?.let {
            throw JmapException("Server rejected the mailbox change ($it)", errorType = it)
        }
        return result.newState
    }

            /**
             * Codeberg #29, over [postWithRetry] so each request still backs off on the rate limit, split
             */
    /**
     * [sourceMailboxIds] maps each id to the mailbox it is LEAVING. A bulk move drains several
     * folders at once (the unified inbox does exactly that), so the source is per id and not one
     * value for the batch: patching every message with the first row's source would remove a
     * membership the other messages never had, and leave theirs behind.
     *
     * An id missing from the map, or mapped to null, is only ADDED to [targetMailboxId]. See
     * [move]'s note on why that is a deliberate statement rather than a default.
     */
    suspend fun move(
        session: JmapSession,
        accountId: String,
        emailIds: List<String>,
        targetMailboxId: String,
        auth: JmapAuth,
        sourceMailboxIds: Map<String, String?>,
    ): EmailSetResult = setInBatches(session, emailIds, rethrowTransportFailure = false) { batch ->
        emailSet(session, auth) {
            put("accountId", accountId)
            putJsonObject("update") {
                batch.forEach { id ->
                    putJsonObject(id) {
                        putMembershipPatch(
                            add = targetMailboxId,
                            remove = inboxSafeRemove(inboxGuards[accountId], targetMailboxId, sourceMailboxIds[id]),
                        )
                    }
                }
            }
        }
    }

            /**
             * Codeberg #29, split into requests of at most [JmapSession.setBatchSize] ids. Unlike [move]
             */
    suspend fun destroy(
        session: JmapSession,
        accountId: String,
        emailIds: List<String>,
        auth: JmapAuth,
    ): EmailSetResult = setInBatches(session, emailIds, rethrowTransportFailure = true) { batch ->
        emailSet(session, auth) {
            put("accountId", accountId)
            putJsonArray("destroy") { batch.forEach { add(it) } }
        }
    }

    /** Create a mailbox (e.g. an Archive folder) and return its new id (RFC 8621 §2.5). */
    suspend fun createMailbox(
        session: JmapSession,
        accountId: String,
        name: String,
        role: String?,
        auth: JmapAuth,
        parentId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Mailbox/set")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("create") {
                            putJsonObject("new") {
                                put("name", name)
                                    // ALWAYS, never conditionally: `isSubscribed` is writable at
                                    // create time (RFC 8621 §2) and a create that omits it reads back
                                    // `false`, so with "subscribed folders only" on the new folder
                                    // would vanish from the drawer at once (#174).
                                put("isSubscribed", true)
                                if (role != null) put("role", role)
                                if (parentId != null) put("parentId", parentId)
                            }
                        }
                    }
                    add("m0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "Mailbox/set")
        val created = args["created"]?.jsonObject?.get("new")?.jsonObject
        if (created != null) {
            return@withContext created["id"]?.jsonPrimitive?.content
                ?: throw JmapException("Mailbox create returned no id")
        }
        val type = args["notCreated"]?.jsonObject?.get("new")?.jsonObject
            ?.get("type")?.jsonPrimitive?.content
        throw JmapException("Couldn't create the '$name' folder" + (type?.let { " ($it)" } ?: ""))
    }

            /**
             * `Identity/set`, RFC 8621 §6.4. Sterna only ever read identities, so an alias added in
             */
    suspend fun createIdentity(
        session: JmapSession,
        accountId: String,
        name: String,
        email: String,
        auth: JmapAuth,
    ): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.SUBMISSION_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Identity/set")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("create") {
                            putJsonObject("new") {
                                put("email", email)
                                put("name", name)
                            }
                        }
                    }
                    add("i0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "Identity/set")
        val created = args["created"]?.jsonObject?.get("new")?.jsonObject
        if (created != null) {
            return@withContext created["id"]?.jsonPrimitive?.content
                ?: throw JmapException("Identity create returned no id")
        }
        val refusal = args["notCreated"]?.jsonObject?.get("new")?.jsonObject
        val type = refusal?.get("type")?.jsonPrimitive?.content
        val description = refusal?.get("description")?.jsonPrimitive?.content
            // NO address in this message. The screen puts the refused address into its own
            // translated sentence and shows this text next to it; carrying it here printed it twice,
            // the second time inside an English fragment sitting in eight translations.
        val detail = listOfNotNull(type, description).joinToString(": ")
        throw JmapException(
            detail.ifEmpty { "The server refused the identity" },
            errorType = type,
        )
    }

    /** Rename a mailbox (Mailbox/set update of `name`). */
    suspend fun renameMailbox(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        name: String,
        auth: JmapAuth,
    ) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") { add(Jmap.CORE_CAPABILITY); add(Jmap.MAIL_CAPABILITY) }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Mailbox/set")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("update") { putJsonObject(mailboxId) { put("name", name) } }
                    }
                    add("m0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "Mailbox/set")
        if (args["updated"]?.jsonObject?.containsKey(mailboxId) != true) {
            throw JmapException("Couldn't rename the folder")
        }
    }

    /** Delete a mailbox (Mailbox/set destroy). */
    suspend fun deleteMailbox(session: JmapSession, accountId: String, mailboxId: String, auth: JmapAuth) =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                putJsonArray("using") { add(Jmap.CORE_CAPABILITY); add(Jmap.MAIL_CAPABILITY) }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Mailbox/set")
                        addJsonObject {
                            put("accountId", accountId)
                            putJsonArray("destroy") { add(mailboxId) }
                            // Allow removing a folder that still has messages in it.
                            put("onDestroyRemoveEmails", true)
                        }
                        add("m0")
                    }
                }
            }
            val args = methodResponseArgs(postJmap(session, auth, payload), "Mailbox/set")
            val destroyed = args["destroyed"]?.jsonArray?.any { it.jsonPrimitive.content == mailboxId } == true
            if (!destroyed) throw JmapException("Couldn't delete the folder")
        }

    /** Fetch the identities (from-addresses) the user may send as (RFC 8621 §6). */
    suspend fun getIdentities(session: JmapSession, accountId: String, auth: JmapAuth): List<Identity> =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.SUBMISSION_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Identity/get")
                        addJsonObject {
                            put("accountId", accountId)
                            put("ids", JsonNull)
                        }
                        add("i0")
                    }
                }
            }
            val request = Request.Builder()
                .url(session.apiUrl)
                .header("Authorization", auth.authorizationHeader())
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw JmapException("Identity/get failed: HTTP ${response.code} ${response.message}")
                }
                decodeList(body, "Identity/get", Identity.serializer())
            }
        }

        /** Fetch the account's VacationResponse singleton (RFC 8621 §8), or null if the server does
         *  not advertise the vacationresponse capability. */
    suspend fun getVacationResponse(session: JmapSession, accountId: String, auth: JmapAuth): VacationResponse? =
        withContext(Dispatchers.IO) {
            if (!session.capabilities.containsKey(Jmap.VACATION_CAPABILITY)) return@withContext null
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.VACATION_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("VacationResponse/get")
                        addJsonObject {
                            put("accountId", accountId)
                            put("ids", JsonNull) // null = the singleton
                        }
                        add("v0")
                    }
                }
            }
            val body = postJmap(session, auth, payload)
            decodeList(body, "VacationResponse/get", VacationResponse.serializer()).firstOrNull()
                ?: VacationResponse()
        }

        /** Update the account's VacationResponse singleton (RFC 8621 §8). Sends all editable fields,
         *  writing explicit nulls to clear the dates / message. The server keeps the auto-reply
         *  server-side, so it works while the phone is off. */
    suspend fun setVacationResponse(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        vacation: VacationResponse,
    ): VacationResponse = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.VACATION_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("VacationResponse/set")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("update") {
                            putJsonObject("singleton") {
                                put("isEnabled", vacation.isEnabled)
                                put("fromDate", vacation.fromDate)
                                put("toDate", vacation.toDate)
                                put("subject", vacation.subject)
                                put("textBody", vacation.textBody)
                                put("htmlBody", vacation.htmlBody)
                            }
                        }
                    }
                    add("v0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "VacationResponse/set")
        if (args["updated"]?.jsonObject?.containsKey("singleton") != true) {
            val type = args["notUpdated"]?.jsonObject?.get("singleton")?.jsonObject
                ?.get("type")?.jsonPrimitive?.content
            throw JmapException("Couldn't save the auto-reply" + (type?.let { " ($it)" } ?: ""))
        }
        vacation.copy(id = "singleton")
    }

        /** Fetch the account's Quota objects (RFC 9425), empty if the server has no quota capability. */
    suspend fun getQuotas(session: JmapSession, accountId: String, auth: JmapAuth): List<Quota> =
        withContext(Dispatchers.IO) {
            if (!session.capabilities.containsKey(Jmap.QUOTA_CAPABILITY)) return@withContext emptyList()
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.QUOTA_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Quota/get")
                        addJsonObject {
                            put("accountId", accountId)
                            put("ids", JsonNull) // null = all quotas for the account
                        }
                        add("q0")
                    }
                }
            }
            decodeList(postJmap(session, auth, payload), "Quota/get", Quota.serializer())
        }

        /** Fetch the account's Sieve scripts (RFC 9661), empty if the server has no sieve capability. */
    suspend fun getSieveScripts(session: JmapSession, accountId: String, auth: JmapAuth): List<SieveScript> =
        withContext(Dispatchers.IO) {
            if (!session.capabilities.containsKey(Jmap.SIEVE_CAPABILITY)) return@withContext emptyList()
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.SIEVE_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("SieveScript/get")
                        addJsonObject {
                            put("accountId", accountId)
                            put("ids", JsonNull)
                        }
                        add("s0")
                    }
                }
            }
            decodeList(postJmap(session, auth, payload), "SieveScript/get", SieveScript.serializer())
        }

    /** Ask the server to validate an uploaded Sieve blob; returns null if valid, else the error text. */
    suspend fun validateSieve(session: JmapSession, accountId: String, blobId: String, auth: JmapAuth): String? =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.SIEVE_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("SieveScript/validate")
                        addJsonObject {
                            put("accountId", accountId)
                            put("blobId", blobId)
                        }
                        add("s0")
                    }
                }
            }
            val args = methodResponseArgs(postJmap(session, auth, payload), "SieveScript/validate")
            when (val err = args["error"]) {
                null, JsonNull -> null
                else -> err.jsonObject["description"]?.jsonPrimitive?.contentOrNull
                    ?: err.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    ?: err.toString()
            }
        }

        /** Create or update the named Sieve script from an already-uploaded blob and make it the
         *  active script. Pass [existingId] to update in place, or null to create. Throws
         *  [JmapException] if the server rejects the write. */
    suspend fun saveSieveScript(
        session: JmapSession,
        accountId: String,
        name: String,
        blobId: String,
        existingId: String?,
        auth: JmapAuth,
    ) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.SIEVE_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("SieveScript/set")
                    addJsonObject {
                        put("accountId", accountId)
                        if (existingId != null) {
                            putJsonObject("update") {
                                putJsonObject(existingId) { put("blobId", blobId) }
                            }
                            put("onSuccessActivateScript", existingId)
                        } else {
                            putJsonObject("create") {
                                putJsonObject("new") {
                                    put("name", name)
                                    put("blobId", blobId)
                                }
                            }
                            put("onSuccessActivateScript", "#new")
                        }
                    }
                    add("s0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "SieveScript/set")
        if (existingId != null) {
            if (args["updated"]?.jsonObject?.containsKey(existingId) != true) {
                val type = args["notUpdated"]?.jsonObject?.get(existingId)?.jsonObject
                    ?.get("description")?.jsonPrimitive?.content
                throw JmapException("Couldn't save filters" + (type?.let { " ($it)" } ?: ""))
            }
        } else if (args["created"]?.jsonObject?.get("new") == null) {
            val type = args["notCreated"]?.jsonObject?.get("new")?.jsonObject
                ?.get("description")?.jsonPrimitive?.content
            throw JmapException("Couldn't save filters" + (type?.let { " ($it)" } ?: ""))
        }
    }

        /**
         * Send a plain-text email: create a draft (Email/set) and submit it (EmailSubmission/set) in
         */
    suspend fun sendEmail(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        identityId: String,
        from: EmailAddress,
        to: List<EmailAddress>,
        cc: List<EmailAddress> = emptyList(),
        bcc: List<EmailAddress> = emptyList(),
        subject: String,
        textBody: String,
        /** The HTML body. Null is not "no html part": the part is ALWAYS written, from [textBody]
         *  wrapped by [htmlOfPlainText] — a message never leaves this client as text only. */
        htmlBody: String? = null,
        draftMailboxId: String,
        sentMailboxId: String,
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        attachments: List<EmailBodyPart> = emptyList(),
        /** Ask for a read receipt: writes `Disposition-Notification-To:` naming [from] (RFC 8098). */
        requestReceipt: Boolean = false,
            /**
             * An explicit SMTP envelope (RFC 8621 §7.5). null — the ordinary case — lets the server
             */
        envelope: SubmissionEnvelope? = null,
                /** This sender's own OpenPGP public key as the ready value of an `Autocrypt:` header
                 *  (Autocrypt Level 1 §2.1), or null. Flat, NOT folded: the server writes the field, so
                 *  folding here would put literal CRLFs inside a JSON string. Whether an account may
                 *  announce its key at all is decided in core/data, never here. */
        autocryptHeader: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        withAutocryptHeaderFallback(session, autocryptHeader != null) { withAutocryptHeader, point ->
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.MAIL_CAPABILITY)
                    add(Jmap.SUBMISSION_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Email/set")
                        addJsonObject {
                            put("accountId", accountId)
                            putJsonObject("create") {
                                putJsonObject("draft") {
                                    putJsonArray("from") { addJsonObject { addAddress(from) } }
                                    putJsonArray("to") { to.forEach { addJsonObject { addAddress(it) } } }
                                    if (cc.isNotEmpty()) {
                                        putJsonArray("cc") { cc.forEach { addJsonObject { addAddress(it) } } }
                                    }
                                    if (bcc.isNotEmpty()) {
                                        putJsonArray("bcc") { bcc.forEach { addJsonObject { addAddress(it) } } }
                                    }
                                    put("subject", subject)
                                    if (inReplyTo.isNotEmpty()) {
                                        putJsonArray("inReplyTo") { inReplyTo.forEach { add(it) } }
                                    }
                                    if (references.isNotEmpty()) {
                                        putJsonArray("references") { references.forEach { add(it) } }
                                    }
                                        // Where a read receipt should come back (RFC 8098). No Email
                                        // property exists, so RFC 8621 §4.1.2's `header:<name>:<form>`
                                        // is set on create. The address is `from`, which for a
                                        // delegated send is not the submitting identity.
                                    if (requestReceipt) {
                                        putJsonArray("header:Disposition-Notification-To:asAddresses") {
                                            addJsonObject { addAddress(from) }
                                        }
                                    }
                                        // The sender's own OpenPGP key (Autocrypt Level 1 §2.1), same
                                        // §4.1.2 seam as the receipt above, in the `asText` form.
                                    if (withAutocryptHeader && autocryptHeader != null) {
                                        put(AUTOCRYPT_HEADER_PROPERTY, autocryptHeader)
                                    }
                                    addAttachments(attachments)
                                    putJsonObject("keywords") { put("\$draft", true); put("\$seen", true) }
                                    putJsonObject("mailboxIds") { put(draftMailboxId, true) }
                                    putJsonArray("textBody") {
                                        addJsonObject { put("partId", "textbody"); put("type", "text/plain") }
                                    }
                                    putJsonArray("htmlBody") {
                                        addJsonObject { put("partId", "htmlbody"); put("type", "text/html") }
                                    }
                                    putJsonObject("bodyValues") {
                                        putJsonObject("textbody") { put("value", textBody) }
                                        putJsonObject("htmlbody") { put("value", htmlBody ?: htmlOfPlainText(textBody)) }
                                    }
                                }
                            }
                        }
                        add("e0")
                    }
                    addJsonArray {
                        add("EmailSubmission/set")
                        addJsonObject {
                            put("accountId", accountId)
                            put("create", submissionCreate(identityId, envelope))
                            putJsonObject("onSuccessUpdateEmail") {
                                putJsonObject("#sub") {
                                    put("mailboxIds/$sentMailboxId", true)
                                    put("mailboxIds/$draftMailboxId", JsonNull)
                                    put("keywords/\$draft", JsonNull)
                                }
                            }
                        }
                        add("s0")
                    }
                }
            }
            val request = Request.Builder()
                .url(session.apiUrl)
                .header("Authorization", auth.authorizationHeader())
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val created = httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw JmapException("Send failed: HTTP ${response.code} ${response.message}")
                }
                val emailArgs = methodResponseArgs(body, "Email/set")
                (emailArgs["notCreated"] as? JsonObject)?.get("draft")?.let {
                    throw JmapException("Could not create the message: $it")
                }
                    // THE POINT OF NO RETURN, here because this is the first line that knows: the
                    // create was not refused, so a message exists on the server. Everything below may
                    // throw what it likes; no replay may follow ([withAutocryptHeaderFallback]).
                point.pass()
                val subArgs = methodResponseArgs(body, "EmailSubmission/set")
                (subArgs["notCreated"] as? JsonObject)?.get("sub")?.let {
                    throw JmapException("Could not send the message: $it")
                }
                createdEmailId(emailArgs) to subArgs
            }
            // A CREATED submission is not a delivered message: a recipient refused before queueing
            // arrives here, never in `notCreated`, and no bounce follows it (#183). Asked outside the
            // `use` above so the first response is closed before the second request goes out.
            val (emailId, submissionArgs) = created
            failIfRecipientsWereRefused(session, accountId, auth, submissionArgs)
            emailId
        }
    }

    /** The Email id minted for the [creationId] creation ("draft" by default) in an Email/set or
     *  Email/import response. */
    private fun createdEmailId(args: JsonObject, creationId: String = "draft"): String? =
        (args["created"] as? JsonObject)?.get(creationId)?.jsonObject
            ?.get("id")?.jsonPrimitive?.contentOrNull

            /**
             * RFC 8621 §4.8 `Email/import`: one message in [mailboxIds], carrying [keywords], received at
             */
    suspend fun importEmail(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        blobId: String,
        mailboxIds: Set<String>,
        keywords: Set<String>,
        receivedAt: String?,
    ): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/import")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("emails") {
                            putJsonObject("m0") {
                                put("blobId", blobId)
                                putJsonObject("mailboxIds") { mailboxIds.forEach { put(it, true) } }
                                putJsonObject("keywords") { keywords.forEach { put(it, true) } }
                                if (receivedAt != null) put("receivedAt", receivedAt)
                            }
                        }
                    }
                    add("i0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Import failed: HTTP ${response.code} ${response.message}")
            }
            val args = methodResponseArgs(body, "Email/import")
            (args["notCreated"] as? JsonObject)?.get("m0")?.let {
                throw JmapException("Could not import the message: $it")
            }
            createdEmailId(args, "m0")
                ?: throw JmapException("Email/import answered without the id of the created message")
        }
    }

            /**
             * FAIL the send if the server refused a recipient (#183). `notCreated` is silent on the
             */
    private suspend fun failIfRecipientsWereRefused(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        submissionSetArgs: JsonObject,
    ) {
        val refusals = runCatching {
            val submissionId = ((submissionSetArgs["created"] as? JsonObject)?.get("sub") as? JsonObject)
                ?.get("id")?.let { it as? JsonPrimitive }?.contentOrNull
                ?: return@runCatching emptyList<String>()
            deliveryRefusals(submissionDeliveryStatus(session, accountId, auth, submissionId))
        }.getOrDefault(emptyList())
        if (refusals.isEmpty()) return
        // Permanent: the server has ruled on this delivery. Five auto-retries would put five copies
        // in Sent, and on a PARTIAL refusal they would deliver the message five times over to the
        // recipient that IS valid — worse than the defect being fixed.
        throw JmapException("Not delivered to " + refusals.joinToString("; "), permanent = true)
    }

    /** The `deliveryStatus` object of one submission, read in its own request; null if unreadable. */
    private fun submissionDeliveryStatus(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        submissionId: String,
    ): JsonObject? {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.SUBMISSION_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("EmailSubmission/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonArray("ids") { add(submissionId) }
                    }
                    add("g0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            val args = methodResponseArgsOrNull(body, "EmailSubmission/get") ?: return@use null
            val entry = (args["list"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@use null
            entry["deliveryStatus"] as? JsonObject
        }
    }

            /** Raw RFC 5322 bytes built CLIENT-side (PGP/MIME: the structured Email/set body assembly
             *  cannot carry the protocol=/micalg= parameters). Uploads the message as a blob, then one
             *  request: Email/import into Drafts + EmailSubmission/set referencing it. */
    suspend fun importAndSendEmail(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        identityId: String,
        rawMessage: ByteArray,
        draftMailboxId: String,
        sentMailboxId: String,
        /** As in [sendEmail]: an explicit SMTP envelope, or null to let the server derive one. */
        envelope: SubmissionEnvelope? = null,
    ): String? = withContext(Dispatchers.IO) {
        val blobId = uploadBlob(session, accountId, rawMessage, "message/rfc822", auth).blobId
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
                add(Jmap.SUBMISSION_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/import")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("emails") {
                            putJsonObject("draft") {
                                put("blobId", blobId)
                                putJsonObject("mailboxIds") { put(draftMailboxId, true) }
                                putJsonObject("keywords") { put("\$draft", true); put("\$seen", true) }
                            }
                        }
                    }
                    add("i0")
                }
                addJsonArray {
                    add("EmailSubmission/set")
                    addJsonObject {
                        put("accountId", accountId)
                        put("create", submissionCreate(identityId, envelope))
                        putJsonObject("onSuccessUpdateEmail") {
                            putJsonObject("#sub") {
                                put("mailboxIds/$sentMailboxId", true)
                                put("mailboxIds/$draftMailboxId", JsonNull)
                                put("keywords/\$draft", JsonNull)
                            }
                        }
                    }
                    add("s0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val created = httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Send failed: HTTP ${response.code} ${response.message}")
            }
            val importArgs = methodResponseArgs(body, "Email/import")
            (importArgs["notCreated"] as? JsonObject)?.get("draft")?.let {
                throw JmapException("Could not import the message: $it")
            }
            val subArgs = methodResponseArgs(body, "EmailSubmission/set")
            (subArgs["notCreated"] as? JsonObject)?.get("sub")?.let {
                throw JmapException("Could not send the message: $it")
            }
            createdEmailId(importArgs) to subArgs
        }
        // The twin of the check in [sendEmail], through the SAME helper: the PGP/MIME route submits
        // the same way and was refused the same way, in silence (#183). Two copies of this test is
        // what let the `notCreated`-only check go unnoticed on both paths for so long.
        val (emailId, submissionArgs) = created
        failIfRecipientsWereRefused(session, accountId, auth, submissionArgs)
        emailId
    }

            /**
             * Email/copy into [toAccountId]'s [mailboxId], then destroy the original (issue #31). The
             */
    suspend fun copyEmailToAccount(
        session: JmapSession,
        auth: JmapAuth,
        fromAccountId: String,
        toAccountId: String,
        emailId: String,
        mailboxId: String,
    ): Unit = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/copy")
                    addJsonObject {
                        put("fromAccountId", fromAccountId)
                        put("accountId", toAccountId)
                        putJsonObject("create") {
                            putJsonObject("copy") {
                                put("id", emailId)
                                putJsonObject("mailboxIds") { put(mailboxId, true) }
                                putJsonObject("keywords") { put("\$seen", true) }
                            }
                        }
                    }
                    add("c0")
                }
                addJsonArray {
                    add("Email/set")
                    addJsonObject {
                        put("accountId", fromAccountId)
                        putJsonArray("destroy") { add(emailId) }
                    }
                    add("d0")
                }
            }
        }
        val body = postJmap(session, auth, payload)
        val copyArgs = methodResponseArgs(body, "Email/copy")
        (copyArgs["notCreated"] as? JsonObject)?.get("copy")?.let {
            throw JmapException("Could not file the sent copy: $it")
        }
        val destroyArgs = methodResponseArgs(body, "Email/set")
        (destroyArgs["notDestroyed"] as? JsonObject)?.get(emailId)?.let {
            throw JmapException("Sent copy filed, but the original wasn't removed: $it")
        }
    }

        /** Save a plain-text draft in the Drafts mailbox (no submission). [attachments] are uploaded
         *  blobs referenced by the draft, so re-saving an edited draft keeps the files it carried
         *  (#63). Returns the created draft's server id, so an edit can later replace it. */
    suspend fun saveDraft(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        from: EmailAddress,
        to: List<EmailAddress>,
        cc: List<EmailAddress> = emptyList(),
        bcc: List<EmailAddress> = emptyList(),
        subject: String,
        textBody: String,
            /**
             * The `text/html` alternative to store beside [textBody] (#131), or null for a draft with
             */
        htmlBody: String? = null,
        draftMailboxId: String,
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        attachments: List<EmailBodyPart> = emptyList(),
        /** Ask for a read receipt: writes `Disposition-Notification-To:` naming [from] (RFC 8098). */
        requestReceipt: Boolean = false,
    ): String? {
        val args = emailSet(session, auth) {
            put("accountId", accountId)
            putJsonObject("create") {
                putJsonObject("draft") {
                    putJsonArray("from") { addJsonObject { addAddress(from) } }
                    // A draft may legitimately have no recipient yet (#69). Emit "to" only when
                    // there is one — an empty "to": [] is rejected on create by strict servers
                    // (e.g. Stalwart), which is what blocked saving a recipient-less draft.
                    if (to.isNotEmpty()) putJsonArray("to") { to.forEach { addJsonObject { addAddress(it) } } }
                    if (cc.isNotEmpty()) putJsonArray("cc") { cc.forEach { addJsonObject { addAddress(it) } } }
                    if (bcc.isNotEmpty()) putJsonArray("bcc") { bcc.forEach { addJsonObject { addAddress(it) } } }
                    put("subject", subject)
                    // Keep a reply draft threaded, so sending it later still joins its conversation.
                    if (inReplyTo.isNotEmpty()) putJsonArray("inReplyTo") { inReplyTo.forEach { add(it) } }
                    if (references.isNotEmpty()) putJsonArray("references") { references.forEach { add(it) } }
                    // The receipt request belongs in the stored draft too, so the copy on the
                    // server describes the message that would be sent (RFC 8621 §4.1.2 header
                    // property, `asAddresses` form; the address is `from`).
                    if (requestReceipt) {
                        putJsonArray("header:Disposition-Notification-To:asAddresses") {
                            addJsonObject { addAddress(from) }
                        }
                    }
                    addAttachments(attachments)
                    putJsonObject("keywords") { put("\$draft", true); put("\$seen", true) }
                    putJsonObject("mailboxIds") { put(draftMailboxId, true) }
                    putJsonArray("textBody") {
                        addJsonObject { put("partId", "body"); put("type", "text/plain") }
                    }
                        // The styling the composer stored, as the SECOND alternative and never in
                        // place of the first: the same shape `sendEmail` writes. Absent, nothing is
                        // emitted at all — a plain draft's create is unchanged (#131).
                    putJsonArray("htmlBody") {
                        addJsonObject { put("partId", "html"); put("type", "text/html") }
                    }
                    putJsonObject("bodyValues") {
                        putJsonObject("body") { put("value", textBody) }
                        putJsonObject("html") { put("value", htmlBody ?: htmlOfPlainText(textBody)) }
                    }
                }
            }
        }
        (args["notCreated"] as? JsonObject)?.get("draft")?.let {
            throw JmapException("Could not save the draft: $it")
        }
        return ((args["created"] as? JsonObject)?.get("draft") as? JsonObject)
            ?.get("id")?.jsonPrimitive?.contentOrNull
    }

            /** Refuses anything past [maxBytes]. The whole response is buffered — there is no framing to
             *  stream against — so the announced Content-Length is checked first and the read itself stops
             *  at the ceiling for a server that announces nothing, or lies. */
    suspend fun downloadBlob(
        session: JmapSession,
        accountId: String,
        blobId: String,
        type: String?,
        name: String?,
        auth: JmapAuth,
        maxBytes: Long = DownloadLimits.ATTACHMENT_MAX_BYTES,
    ): ByteArray = withContext(Dispatchers.IO) {
        val template = session.downloadUrl ?: throw JmapException("Server has no downloadUrl")
        val url = template
            .replace("{accountId}", accountId)
            .replace("{blobId}", blobId)
            .replace("{type}", encodePathSegment(type ?: "application/octet-stream"))
            .replace("{name}", encodePathSegment(name ?: "attachment"))
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader())
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw JmapException("Download failed: HTTP ${response.code} ${response.message}")
            }
            val declared = response.header("Content-Length")?.toLongOrNull()
            if (declared != null && declared > maxBytes) {
                throw ContentTooLargeException(
                    "Download is $declared bytes, over the $maxBytes limit.",
                    bytes = declared,
                    maxBytes = maxBytes,
                )
            }
            val body = response.body ?: return@use ByteArray(0)
            readAtMost(body.byteStream(), maxBytes)
        }
    }

    /** Read [stream] fully, or refuse as soon as it goes past [maxBytes]. */
    private fun readAtMost(stream: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw ContentTooLargeException(
                    "Download exceeds the $maxBytes limit.",
                    bytes = -1,
                    maxBytes = maxBytes,
                )
            }
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    /** Upload bytes as a blob via the session uploadUrl template; returns its blobId. */
    suspend fun uploadBlob(
        session: JmapSession,
        accountId: String,
        bytes: ByteArray,
        type: String?,
        auth: JmapAuth,
    ): UploadedBlob = withContext(Dispatchers.IO) {
        val template = session.uploadUrl ?: throw JmapException("Server has no uploadUrl")
        val url = template.replace("{accountId}", accountId)
        val mediaType = (type ?: "application/octet-stream").toMediaTypeOrNull()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader())
            .post(bytes.toRequestBody(mediaType))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Upload failed: HTTP ${response.code} ${response.message}")
            }
            val obj = json.parseToJsonElement(body).jsonObject
            UploadedBlob(
                blobId = obj["blobId"]?.jsonPrimitive?.contentOrNull
                    ?: throw JmapException("Upload response had no blobId"),
                type = obj["type"]?.jsonPrimitive?.contentOrNull ?: (type ?: "application/octet-stream"),
                size = obj["size"]?.jsonPrimitive?.longOrNull ?: bytes.size.toLong(),
            )
        }
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Epoch-millis as a JMAP UTCDate (e.g. "2026-06-23T00:00:00Z"). */
    private fun utcDate(millis: Long): String = java.time.Instant.ofEpochMilli(millis).toString()

    // ---- PushSubscription (RFC 8620 §7.2) ----------------------------------------------
    // Session-level: subscriptions belong to the credential, not to an account, so these
    // calls carry NO accountId and only need the core capability.

    /** All push subscriptions this credential holds on the server. */
    suspend fun getPushSubscriptions(session: JmapSession, auth: JmapAuth): List<PushSubscription> =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                putJsonArray("using") { add(Jmap.CORE_CAPABILITY) }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("PushSubscription/get")
                        addJsonObject { put("ids", JsonNull) }
                        add("c0")
                    }
                }
            }
            decodeList(postJmap(session, auth, payload), "PushSubscription/get", PushSubscription.serializer())
        }

        /**
         * Create a push subscription pointing at [subscription].url (the UnifiedPush endpoint).
         */
    suspend fun createPushSubscription(
        session: JmapSession,
        auth: JmapAuth,
        subscription: PushSubscription,
    ): PushSubscription = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") { add(Jmap.CORE_CAPABILITY) }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("PushSubscription/set")
                    addJsonObject {
                        putJsonObject("create") {
                            // Built by hand: server-set fields (id) must be absent, not null.
                            putJsonObject("sub") {
                                put("deviceClientId", subscription.deviceClientId)
                                put("url", subscription.url)
                                subscription.keys?.let { keys ->
                                    putJsonObject("keys") {
                                        put("p256dh", keys.p256dh)
                                        put("auth", keys.auth)
                                    }
                                }
                                subscription.expires?.let { put("expires", it) }
                                subscription.types?.let { types ->
                                    putJsonArray("types") { types.forEach { add(it) } }
                                }
                            }
                        }
                    }
                    add("c0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "PushSubscription/set")
        val created = args["created"]?.jsonObject?.get("sub")?.jsonObject
            ?: throw JmapException(
                "Couldn't create the push subscription" +
                    (args["notCreated"]?.jsonObject?.get("sub")?.let { ": $it" } ?: ""),
            )
        subscription.copy(
            id = created["id"]?.jsonPrimitive?.contentOrNull ?: subscription.id,
            expires = created["expires"]?.jsonPrimitive?.contentOrNull ?: subscription.expires,
        )
    }

    /** Confirm a subscription with the verificationCode received through the endpoint. */
    suspend fun verifyPushSubscription(
        session: JmapSession,
        auth: JmapAuth,
        subscriptionId: String,
        verificationCode: String,
    ): Unit = withContext(Dispatchers.IO) {
        val args = updatePushSubscription(session, auth, subscriptionId) {
            put("verificationCode", verificationCode)
        }
        if (args["updated"]?.jsonObject?.containsKey(subscriptionId) != true) {
            throw JmapException("Couldn't verify the push subscription")
        }
    }

        /** Push the subscription's expiry out to [expires] (UTCDate). Returns the value the server
         *  applied, which it MAY have capped below the request (RFC 8620 §7.2). */
    suspend fun updatePushSubscriptionExpires(
        session: JmapSession,
        auth: JmapAuth,
        subscriptionId: String,
        expires: String,
    ): String = withContext(Dispatchers.IO) {
        val args = updatePushSubscription(session, auth, subscriptionId) { put("expires", expires) }
        val updated = args["updated"]?.jsonObject
        if (updated?.containsKey(subscriptionId) != true) {
            throw JmapException("Couldn't renew the push subscription")
        }
        // A non-null updated value carries the properties the server changed differently.
        updated[subscriptionId]?.let { it as? JsonObject }
            ?.get("expires")?.jsonPrimitive?.contentOrNull
            ?: expires
    }

    /** Destroy a subscription (sign-out / endpoint rotation). Already-gone is success. */
    suspend fun destroyPushSubscription(
        session: JmapSession,
        auth: JmapAuth,
        subscriptionId: String,
    ): Unit = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") { add(Jmap.CORE_CAPABILITY) }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("PushSubscription/set")
                    addJsonObject { putJsonArray("destroy") { add(subscriptionId) } }
                    add("c0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "PushSubscription/set")
        val destroyed = args["destroyed"]?.jsonArray?.any { it.jsonPrimitive.content == subscriptionId } == true
        val notFound = args["notDestroyed"]?.jsonObject?.get(subscriptionId)
            ?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull == "notFound"
        if (!destroyed && !notFound) throw JmapException("Couldn't delete the push subscription")
    }

    /** Shared PushSubscription/set update envelope; returns the method response args. */
    private suspend fun updatePushSubscription(
        session: JmapSession,
        auth: JmapAuth,
        subscriptionId: String,
        patch: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val payload = buildJsonObject {
            putJsonArray("using") { add(Jmap.CORE_CAPABILITY) }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("PushSubscription/set")
                    addJsonObject {
                        putJsonObject("update") { putJsonObject(subscriptionId, patch) }
                    }
                    add("c0")
                }
            }
        }
        return methodResponseArgs(postJmap(session, auth, payload), "PushSubscription/set")
    }

        /** Open a long-lived JMAP push connection (EventSource/SSE, RFC 8620 §7.3), invoking
         *  [onStateChange] for each StateChange. Returns a Closeable to stop it. */
    fun openEventSource(
        session: JmapSession,
        auth: JmapAuth,
        onStateChange: (StateChange) -> Unit,
        onClosed: () -> Unit,
    ): Closeable {
        val template = session.eventSourceUrl
            ?: throw JmapException("Server does not advertise an eventSourceUrl")
        val url = template
            .replace("{types}", "Email,Mailbox")
            .replace("{closeafter}", "no")
            .replace("{ping}", PING_SECONDS.toString())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "text/event-stream")
            .build()
        // The server pings every PING_SECONDS; a read timeout a bit longer than that
        // turns a silently-dropped connection into onFailure so the caller can reconnect.
        val sseClient = httpClient.newBuilder()
            .readTimeout(PING_SECONDS + 30L, TimeUnit.SECONDS)
            .build()
            // The life of this connection is the one thing a bug report needs and the app cannot
            // observe from outside. Only the ORIGIN of the URL: what a reporter pastes in public must
            // not carry the credential the query or the userinfo can hold.
        val origin = eventSourceOrigin(url)
        // Every log call is wrapped: the sink comes from the caller, and a sink that throws must
        // never cost the reconnect below it. Diagnosing a dead push is worth a line, not the push.
        runCatching { log("event source: opening to $origin", null) }
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching { json.decodeFromString<StateChange>(data) }.getOrNull()?.let(onStateChange)
            }

            override fun onClosed(eventSource: EventSource) {
                runCatching { log("event source: closed by $origin", null) }
                onClosed()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    // A refused connection arrives with no throwable at all, only the response — an
                    // expired token answering 401 on the SSE endpoint is the likeliest. The status
                    // code, and only the status code: headers and body are never touched.
                val status = response?.let { " (HTTP ${it.code})" } ?: ""
                runCatching { log("event source: failed for $origin$status", t) }
                onClosed()
            }
        }
        val eventSource = EventSources.createFactory(sseClient).newEventSource(request, listener)
        return Closeable { eventSource.cancel() }
    }

    /** Run an Email/set call with the given argument object, surfacing JMAP errors.
     *  Returns the response args (which carry `newState`, `updated`, `destroyed`, …). */
    private suspend fun emailSet(
        session: JmapSession,
        auth: JmapAuth,
        args: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/set")
                    addJsonObject(args)
                    add("s0")
                }
            }
        }
        return methodResponseArgs(postWithRetry(session.apiUrl, auth.authorizationHeader(), payload), "Email/set")
    }

            /**
             * The read-side twin of [setInBatches], here because this layer holds the session and so the
             */
    private suspend fun <T> getInBatches(
        session: JmapSession,
        ids: List<String>,
        oneBatch: suspend (List<String>) -> List<T>,
    ): List<T> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(session.getBatchSize()).flatMap { oneBatch(it) }
    }

            /**
             * Aggregates the answers into ONE [EmailSetResult], so callers keep the bookkeeping they had
             */
    private suspend fun setInBatches(
        session: JmapSession,
        emailIds: List<String>,
        rethrowTransportFailure: Boolean,
        oneBatch: suspend (List<String>) -> JsonObject,
    ): EmailSetResult {
        if (emailIds.isEmpty()) return EmailSetResult(null, emptySet(), emptyMap())
        val batches = emailIds.chunked(session.setBatchSize())
        val done = mutableSetOf<String>()
        val failed = mutableMapOf<String, String>()
        var newState: String? = null
        batches.forEachIndexed { index, batch ->
            val args = try {
                oneBatch(batch)
            } catch (e: Exception) {
                if (e !is JmapException && e !is IOException) throw e
                if (rethrowTransportFailure) throw e
                // This batch AND every batch we will now not send.
                batches.drop(index).flatten().forEach { failed[it] = Jmap.SET_ERROR_TRANSPORT }
                return EmailSetResult(newState, done, failed)
            }
            val result = emailSetResult(args)
            done += result.done
            failed += result.failed
            result.newState?.let { newState = it }
        }
        return EmailSetResult(newState, done, failed)
    }

    /** Per-id outcome of an Email/set response (updated/destroyed vs notUpdated/notDestroyed). */
    private fun emailSetResult(args: JsonObject): EmailSetResult {
        val done = buildSet {
            (args["updated"] as? JsonObject)?.keys?.let { addAll(it) }
            (args["destroyed"] as? JsonArray)?.forEach { add(it.jsonPrimitive.content) }
        }
        val failed = buildMap {
            for (key in listOf("notUpdated", "notCreated", "notDestroyed")) {
                (args[key] as? JsonObject)?.forEach { (id, err) ->
                    put(id, (err as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull ?: "unknown")
                }
            }
        }
        return EmailSetResult(args["newState"]?.jsonPrimitive?.contentOrNull, done, failed)
    }

    /** POST a JMAP request body and return the response text, throwing on HTTP failure. */
    private suspend fun postJmap(session: JmapSession, auth: JmapAuth, payload: JsonObject): String =
        postWithRetry(session.apiUrl, auth.authorizationHeader(), payload)

            /**
             * Retries on the server's transient request-level limit (`error:limit`, HTTP 400) or a 429:
             */
    private suspend fun postWithRetry(url: String, authHeader: String, payload: JsonObject): String {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val (code, message, body) = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { r ->
                    Triple(r.code, r.message, r.body?.string().orEmpty())
                }
            }
            if (code in 200..299) return body
            val transient = code == 429 || (code == 400 && body.contains(JMAP_ERROR_LIMIT))
            if (transient && attempt < LIMIT_RETRY_MAX) {
                attempt++
                delay(LIMIT_RETRY_BASE_MS * attempt)
                continue
            }
                // Status only, never a slice of the response: this message reaches logcat, the UI and
                // the persisted outbox error, and the body is server-controlled text that can carry
            throw JmapException(
                "JMAP request failed: HTTP $code $message",
                httpCode = code,
                errorType = JMAP_ERROR_LIMIT.takeIf { code == 400 && body.contains(it) },
            )
        }
    }

    /** Like [methodResponseArgs] but returns null for an error/missing response instead of throwing. */
    private fun methodResponseArgsOrNull(body: String, expectedMethod: String): JsonObject? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val responses = root["methodResponses"]?.jsonArray ?: return null
        for (entry in responses) {
            val call = entry.jsonArray
            if (call[0].jsonPrimitive.content == expectedMethod) return call[1].jsonObject
        }
        return null
    }

    /** Find the args of a named method response, throwing on a JMAP-level error. */
    private fun methodResponseArgs(body: String, expectedMethod: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw JmapException("Could not parse JMAP response", it) }
        val responses = root["methodResponses"]?.jsonArray
            ?: throw JmapException("Response missing methodResponses")
        for (entry in responses) {
            val call = entry.jsonArray
            val name = call[0].jsonPrimitive.content
            val args = call[1].jsonObject
            if (name == "error") {
                val type = args["type"]?.jsonPrimitive?.content ?: "unknown"
                throw JmapException("JMAP method error: $type", errorType = type)
            }
            if (name == expectedMethod) return args
        }
        throw JmapException("No $expectedMethod response found")
    }

            /** A deserialization failure is re-thrown WITHOUT its cause and without any excerpt:
             *  kotlinx.serialization puts a slice of the offending JSON in its message, which for an
             *  `Email/get` is mail content, and that message ends up in logcat, on screen and persisted
             *  in the outbox error column. */
    private fun <T> decodeList(body: String, method: String, serializer: KSerializer<T>): List<T> {
        val list = methodResponseArgs(body, method)["list"]?.jsonArray ?: return emptyList()
        return try {
            list.map { json.decodeFromJsonElement(serializer, it) }
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, and decodeFromJsonElement
            // reports a structurally wrong element the same way.
            throw JmapException("Could not decode the $method response")
        }
    }

        /**
         * API URLs whose server rejected the `header:List-Unsubscribe*` properties, so the next
         */
    private val serversRefusingUnsubscribeHeaders: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** API URLs whose server rejected [RECEIPT_HEADER_PROPERTY]. Its OWN set, separate from
         *  [serversRefusingUnsubscribeHeaders] on purpose: the two refusals are independent, and one
         *  shared flag would cost a server that dislikes one of them the other as well. */
    private val serversRefusingReceiptHeader: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** API URLs whose server rejected [AUTOCRYPT_HEADER_PROPERTY] on an `Email/set` create. Its
         *  OWN set again — see [withAutocryptHeaderFallback]. Memory only, one entry per API URL. */
    private val serversRefusingAutocryptHeader: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    companion object {
        /** How often the server should ping the EventSource connection, in seconds. */
        private const val PING_SECONDS = 90L


                /**
                 * Shared by [getEmail] and [getEmailsWithBody], so a body served from the cache can never
                 */
        internal val EMAIL_BODY_PROPERTIES = listOf(
            "id", "blobId", "threadId", "subject", "preview", "receivedAt",
            "from", "replyTo", "to", "cc", "bcc", "messageId", "inReplyTo", "references",
            "hasAttachment", "keywords",
            "htmlBody", "textBody", "attachments", "bodyValues",
        )

                /**
                 * What every fetch that CACHES a list row asks the server for. ONE list, where there used
                 * to be three identical copies each carrying its own warning that the copies must agree:
                 * the cache's `@Upsert` replaces the row whole, so a path that omits a property erases
                 * what another path stored. A warning repeated three times is not a mechanism — this is.
                 *
                 * `attachments` (RFC 8621 §4.1.4) is the reason the list can draw a chip per file rather
                 * than one paperclip meaning "something is in here". It is a DERIVED property: the server
                 * computes it from the message structure it already parsed, it carries no body content,
                 * and asking for it needs neither `fetchTextBodyValues` nor `fetchHTMLBodyValues`. So it
                 * rides the page's existing chained `Email/get` and costs ZERO extra requests — which is
                 * the whole point. A fetch per visible row would be 50 round trips per page on a phone,
                 * and this app has been made slow by per-message work before.
                 */
        internal val EMAIL_LIST_PROPERTIES = listOf(
            "id", "threadId", "subject", "preview",
            "receivedAt", "from", "replyTo", "to", "cc", "bcc",
            "hasAttachment", "keywords", "attachments",
        )

                /**
                 * The search and crawl property set. Deliberately NOT [EMAIL_LIST_PROPERTIES]: neither
                 * path writes an `emails` row (search returns straight to the caller, the crawl feeds the
                 * FTS table, which has no attachment column), so `attachments` there would be bytes spent
                 * on nothing — and the crawl walks the WHOLE account, where "nothing" is measured in
                 * years of mail.
                 */
        internal val EMAIL_INDEX_PROPERTIES = listOf(
            "id", "threadId", "subject", "preview", "receivedAt",
            "from", "hasAttachment", "keywords", "mailboxIds",
        )

            /** The two header properties the reader's unsubscribe banner is built from (RFC 8621
             *  §4.1.3 header form). Named here once so the request and its fallback cannot disagree. */
        internal val UNSUBSCRIBE_PROPERTIES = listOf(
            "header:List-Unsubscribe:asText",
            "header:List-Unsubscribe-Post:asText",
        )

                /** RFC 8621 §4.1.3 `headers`: the id, so the answer can be keyed, and the header fields.
                 * Named once so the single-id and grouped forms cannot disagree. Nothing else belongs
                 *  in it — this is the ONE fetch that carries `headers`, and a body property here would put
                 *  whole messages on a call the prefetch makes for twenty ids at a time. */
        internal val HEADER_FIELDS_PROPERTIES = listOf("id", "headers")

                /** RFC 8098, in RFC 8621 §4.1.3's header form. `asText`, not `asAddresses`: the IMAP
                 *  path can only hand over raw header text, and one parser for both protocols is worth
                 * more. NOT the property [sendEmail] and [saveDraft] write — that one is `asAddresses`,
                 *  a create needing a value the server can re-emit. */
        internal const val RECEIPT_HEADER_PROPERTY = "header:Disposition-Notification-To:asText"

            /**
             * The property [sendEmail] writes the sender's own OpenPGP key into (Autocrypt Level 1
             */
        internal const val AUTOCRYPT_HEADER_PROPERTY = "header:Autocrypt:asText"

                /**
                 * The ONE failure [withAutocryptHeaderFallback] replays, extracted so it can be run rather
                 */
        internal fun namesAutocryptProperty(text: String?): Boolean =
            text != null && text.contains("Autocrypt", ignoreCase = true)

                /** The replay decision of [withAutocryptHeaderFallback], whole, as one pure function so a
                 *  test can EXECUTE it. [pastPointOfNoReturn] comes first: it is the only term about what
                 *  HAPPENED rather than about what a server wrote, and any server may put "autocrypt" in
                 *  any refusal — a recipient address is enough. */
        internal fun mayReplayWithoutAutocryptHeader(
            asked: Boolean,
            pastPointOfNoReturn: Boolean,
            failure: String?,
        ): Boolean = asked && !pastPointOfNoReturn && namesAutocryptProperty(failure)

            /** Method-level errors that mean "I do not accept this argument" (RFC 8620 §3.6.2), i.e.
             *  the ones a rejected property arrives as. */
        private val PROPERTY_REJECTION_ERRORS = setOf("invalidArguments", "unknownMethod")

                /**
                 * The only failure the unsubscribe-header fallback replays. Two shapes, servers answering
                 */
        private fun isPropertyRejection(e: JmapException): Boolean = when {
            e.errorType in PROPERTY_REJECTION_ERRORS -> true
            e.errorType == JMAP_ERROR_LIMIT -> false
            else -> e.httpCode == 400
        }

        /** RFC 8620 §3.6.1 request-level limit error (e.g. Stalwart's maxConcurrentRequests). */
        private const val JMAP_ERROR_LIMIT = "urn:ietf:params:jmap:error:limit"

                /**
                 * RFC 8620 §3.6.1's `error:limit` or a plain 429 — the ONE failure
                 */
        private fun isRateLimit(e: JmapException): Boolean =
            e.errorType == JMAP_ERROR_LIMIT || e.httpCode == 429

        /** Retries for the transient limit/429 error, with a linear backoff step. */
        private const val LIMIT_RETRY_MAX = 4
        private const val LIMIT_RETRY_BASE_MS = 120L

                /**
                 * Whether the redirect that took [requested] to [landedAt] cost us the `Authorization`
                 */
        internal fun redirectDroppedAuthorization(requested: HttpUrl, landedAt: HttpUrl): Boolean {
            if (requested.scheme != landedAt.scheme) return false
            return requested.host != landedAt.host || requested.port != landedAt.port
        }

            /**
             * Defensively upgrade the session-advertised URLs from http:// to https:// when the
             */
        internal fun upgradeSessionUrls(session: JmapSession, sessionUrl: String): JmapSession {
            if (!sessionUrl.startsWith("https://", ignoreCase = true)) return session
            return session.copy(
                apiUrl = upgradeScheme(session.apiUrl)!!,
                downloadUrl = upgradeScheme(session.downloadUrl),
                uploadUrl = upgradeScheme(session.uploadUrl),
                eventSourceUrl = upgradeScheme(session.eventSourceUrl),
            )
        }

        /** Rewrite a leading `http://` to `https://`; leave null and already-https URLs untouched. */
        private fun upgradeScheme(url: String?): String? =
            if (url != null && url.startsWith("http://", ignoreCase = true)) {
                "https://" + url.substring("http://".length)
            } else {
                url
            }

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            // JMAP fields like cc/to/replyTo are `Type[]|null`; coerce an explicit
            // null to the property default (e.g. emptyList) instead of failing.
            coerceInputValues = true
        }

        internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
                // Discovery relies on following 3xx redirects, but never across a scheme change, in
                // either direction. What this refuses is the REQUEST, not a header leak: OkHttp
            .followSslRedirects(false)
                // The auth gateway (Authelia) in front of a public endpoint: its session cookie per hop,
                // and a redirect to its login page surfaced as a 401. Inert when no cookie is set.
            .addInterceptor(GatewayRedirectGuard())
                // #1090 a fleet account's two endpoints: mesh first, public (+ bearer) as the fallback. A host
                // that is not a registered fleet endpoint passes through both untouched. The bearer interceptor
                // runs BEFORE the legacy per-domain one, so a synced token wins and the header is set once.
            .addInterceptor(EndpointRouteInterceptor(FleetTransport.router))
            .addNetworkInterceptor(FleetBearerInterceptor(FleetTransport.router, FleetTransport.bearers))
            .addNetworkInterceptor(GatewayCookieInterceptor())
            .build()
    }
}

    /**
     * Build the `Email/query` filter for a [SearchQuery] (RFC 8621 §4.4.1): one condition per
     */
internal fun searchFilter(query: SearchQuery, excludeMailboxIds: List<String> = emptyList()): JsonObject {
    val conditions = mutableListOf<JsonObjectBuilder.() -> Unit>()
    if (query.text.isNotBlank()) conditions.add { put("text", query.text.trim()) }
    if (query.from.isNotBlank()) conditions.add { put("from", query.from.trim()) }
    if (query.recipient.isNotBlank()) {
        val recipient = query.recipient.trim()
        conditions.add {
            put("operator", "OR")
            putJsonArray("conditions") {
                addJsonObject { put("to", recipient) }
                addJsonObject { put("cc", recipient) }
            }
        }
    }
    if (query.subject.isNotBlank()) conditions.add { put("subject", query.subject.trim()) }
    if (query.hasAttachment) conditions.add { put("hasAttachment", true) }
    // RFC 8621 §4.4.1 `hasKeyword`, with the IMAP `\Flagged` flag under its JMAP name `$flagged`
    // (RFC 8621 §4.1.1) — escaped here because Kotlin would otherwise read it as a template.
    if (query.flagged) conditions.add { put("hasKeyword", "\$flagged") }
    query.afterMillis?.let { ms -> conditions.add { put("after", jmapUtcDate(ms)) } }
    query.beforeMillis?.let { ms -> conditions.add { put("before", jmapUtcDate(ms)) } }
    // Exclude Trash/Junk exactly as the IMAP walk skips them (parity across servers): a message
    // living ONLY in an excluded mailbox drops out, while one still filed elsewhere (Inbox and
    // Trash) is kept via its other mailbox. `inMailboxOtherThan` is one condition of the outer AND.
    if (excludeMailboxIds.isNotEmpty()) conditions.add {
        putJsonArray("inMailboxOtherThan") { excludeMailboxIds.forEach { add(it) } }
    }
    return buildJsonObject {
        if (conditions.size == 1) {
            conditions[0]()
        } else {
            put("operator", "AND")
            putJsonArray("conditions") {
                conditions.forEach { condition -> addJsonObject(condition) }
            }
        }
    }
}

/** JMAP UTCDate (RFC 8620 §1.4) from epoch millis. */
private fun jmapUtcDate(millis: Long): String = java.time.Instant.ofEpochMilli(millis).toString()

    /**
     * The `create` argument of the EmailSubmission/set that sends a staged message — ONE builder for
     */
private fun submissionCreate(identityId: String, envelope: SubmissionEnvelope?): JsonObject =
    buildJsonObject {
        putJsonObject("sub") {
            put("emailId", "#draft")
            put("identityId", identityId)
            if (envelope != null) {
                putJsonObject("envelope") {
                    putJsonObject("mailFrom") { put("email", envelope.mailFrom) }
                    putJsonArray("rcptTo") {
                        envelope.rcptTo.forEach { addJsonObject { put("email", it) } }
                    }
                }
            }
        }
    }

/** Write a JMAP EmailAddress object ({name?, email}) into the current JSON object. */
private fun JsonObjectBuilder.addAddress(address: EmailAddress) {
    address.name?.let { put("name", it) }
    put("email", address.email)
}

    /**
     * Write the `attachments` array of an Email/set create from already-uploaded blobs. Shared by the
     */
private fun JsonObjectBuilder.addAttachments(attachments: List<EmailBodyPart>) {
    val blobs = attachments.filter { it.blobId != null }
    if (blobs.isEmpty()) return
    putJsonArray("attachments") {
        blobs.forEach { att ->
            addJsonObject {
                put("blobId", att.blobId)
                put("type", att.type ?: "application/octet-stream")
                att.name?.let { put("name", it) }
                // Inline (cid) images keep their Content-ID + inline disposition so the server
                // assembles multipart/related and the htmlBody's `cid:` refs resolve.
                put("disposition", att.disposition ?: "attachment")
                att.cid?.trim()?.trim('<', '>')
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("cid", it) }
                if (att.size > 0) put("size", att.size)
            }
        }
    }
}

    /**
     * How far a send got, for the ONE question [JmapClient.withAutocryptHeaderFallback] asks before it
     */
internal class SendPointOfNoReturn {
    /** True once the server's answer showed something exists server-side. Never goes back. */
    var passed: Boolean = false
        private set

    fun pass() {
        passed = true
    }
}

    /**
     * The recipients an `EmailSubmission`'s [deliveryStatus] (RFC 8621 §7.5) says were REFUSED, each
     */
internal fun deliveryRefusals(deliveryStatus: JsonObject?): List<String> {
    if (deliveryStatus == null) return emptyList()
    return deliveryStatus.entries.mapNotNull { (recipient, status) ->
        val entry = status as? JsonObject ?: return@mapNotNull null
        val delivered = (entry["delivered"] as? JsonPrimitive)?.contentOrNull
        if (delivered != "no") return@mapNotNull null
            // The address and the server's own reply, and STRICTLY nothing else: this text is thrown
            // as an exception message, persisted in the outbox row's lastError and printed on screen.
            // No subject, no body, no ids, no JSON excerpt.
        val reply = (entry["smtpReply"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (reply == null) recipient else "$recipient: $reply"
    }
}

/**
 * THE one place a message's mailbox membership is edited, as RFC 8620 §5.3 patch paths:
 * `mailboxIds/<id>` = true puts it in one mailbox, `mailboxIds/<id>` = null takes it out of one,
 * and every other mailbox the message belongs to is untouched.
 *
 * WHY THIS IS A FUNCTION, AND TOP-LEVEL SO A TEST CAN EXECUTE IT. JMAP `mailboxIds` is the
 * message's COMPLETE folder membership, and in this app's labels model every category IS a
 * mailbox. Patching the whole property with `{target: true}` therefore does not move a message:
 * it deletes every other label and folder it was in, on the server, permanently. That is data
 * loss wearing the clothes of a rendering bug, it shipped once already (00127a847), and it came
 * back with the rewrite because the fix lived at five call sites instead of in one function.
 *
 * [add] and [remove] are independent, which is what makes ADD and REMOVE the primitives and a
 * move the pair of them. Removing the mailbox that is also being added is dropped rather than
 * emitted: the two paths contradict each other inside one patch, and which of them a server
 * honours is not a thing to leave to a server.
 *
 * Only `Email/set` UPDATE goes through here. `Email/set` create and `Email/import` legitimately
 * write the whole `mailboxIds` object, because a message that does not exist yet has no
 * membership to preserve.
 */
internal fun JsonObjectBuilder.putMembershipPatch(add: String?, remove: String?) {
    if (add != null) put("mailboxIds/$add", JsonPrimitive(true))
    if (remove != null && remove != add) put("mailboxIds/$remove", JsonNull)
}

/** [text] as an HTML body: escaped, line breaks as `<br>`, in a `<div>`. The JMAP client's own copy of
 *  core:data's `plainTextToHtml` (this module cannot see core:data): the floor under `htmlBody`. */
internal fun htmlOfPlainText(text: String): String {
    val unified = text.replace("\r\n", "\n").replace('\r', '\n')
    if (unified.isEmpty()) return "<div><br></div>"
    return "<div>" + unified.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") + "</div>"
}
