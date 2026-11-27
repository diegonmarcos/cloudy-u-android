package app.sterna.core.data.mail

import android.util.Log
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailHeader
import kotlinx.coroutines.CancellationException

/**
 * Background full sync (#1768): a WorkManager job keeps the whole "Keep mail offline for" window (90 days by default)
 * downloaded, headers, full bodies and, by [AttachmentPolicy], attachments, so opening the app needs no fetch. This file
 * is the pure engine (no Android, no Room): [FullSync] drives a [FullSyncPort] that [MailRepository] implements over JMAP
 * and Room. It builds on #1752 (cache-first, never a delete on a short answer: the engine only ever ADDS) and #1763
 * (the throttle [prefetchBudget], the cap [BODY_CACHE_MAX_BYTES_PER_ACCOUNT], the window). #1952: the window defaults to
 * every message, the header backfill and the bodies continue on ANY link until the store is whole, and every step resumes
 * from what the store holds ([HeaderResume], [FullSyncPort.missingBodies]), so a restart never starts again from zero.
 */

/** Which attachments the background sync downloads. A tap always downloads, whatever this says. */
enum class AttachmentPolicy {
    ALWAYS, WIFI_ONLY, OFF;

    companion object {
        /** Wi-Fi only: a metered link never spends data on files nobody asked for. */
        val DEFAULT = WIFI_ONLY

        fun from(stored: String?): AttachmentPolicy =
            stored?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }
}

/** May the background sync download attachments right now? Battery saver and no network always say no. */
fun attachmentsAllowed(policy: AttachmentPolicy, c: PrefetchConditions): Boolean = when {
    !c.connected || c.batterySaver -> false
    else -> when (policy) {
        AttachmentPolicy.ALWAYS -> true
        AttachmentPolicy.WIFI_ONLY -> c.unmetered
        AttachmentPolicy.OFF -> false
    }
}

/** Downloaded attachment bytes one account may hold before the oldest messages' files go, even inside the window. */
internal const val ATTACHMENT_CACHE_MAX_BYTES_PER_ACCOUNT = 250L * 1024 * 1024

/** The sync stops FILLING at this share of a cap, so the eviction that trims to the cap never undoes what was just
 *  fetched (no fetch/evict churn on every run). */
internal const val FILL_CEILING_PERCENT = 90

/** Attachments one unmetered run downloads at most / a metered run (policy ALWAYS) downloads at most. */
internal const val FULL_SYNC_ATTACHMENTS_PER_RUN = 200
internal const val FULL_SYNC_ATTACHMENTS_PER_RUN_METERED = 10

/** #1869 messages whose inline images one unmetered run downloads at most; the rest continue in the next run. */
internal const val FULL_SYNC_INLINE_PER_RUN = 100

/** May the background sync download inline images right now? Unmetered, connected and not on battery saver: they ride
 *  with the body (the reader paints them from disk) but are bytes no metered link should spend unasked. */
fun inlineImagesAllowed(c: PrefetchConditions): Boolean = c.connected && c.unmetered && !c.batterySaver

/** #1869 step 9: text bodies one METERED run fetches (about 200, serially, batches of [PREFETCH_BATCH]): the whole window
 *  reaches the phone on mobile data too, text only; inline images and attachments stay Wi-Fi only. */
internal const val FULL_SYNC_METERED_TEXT_PER_RUN = 200

/** The body budget of one full-sync run: [FULL_SYNC_METERED_TEXT_PER_RUN] on a metered link, the list-sync throttle
 *  ([prefetchBudget]) otherwise (unmetered: the whole window; battery saver or offline: nothing). */
fun fullSyncBodyBudget(c: PrefetchConditions): PrefetchBudget =
    if (c.connected && !c.batterySaver && !c.unmetered) PrefetchBudget(FULL_SYNC_METERED_TEXT_PER_RUN, PREFETCH_BATCH, 1)
    else prefetchBudget(c, PrefetchTrigger.LIST_SYNC)

/** #1869 step 9: calendar parts (`text/calendar`, an invite) one run stores at most, and the largest one taken. They are
 *  fetched on ANY link (a few KB each) and whatever the attachment policy, so an invite card renders offline. */
internal const val FULL_SYNC_CALENDAR_PER_RUN = 50
internal const val FULL_SYNC_CALENDAR_MAX_BYTES = 512L * 1024

/** #1869 step 9: raw sources of stored ciphertext one run keeps locally (decrypting then needs no network). */
internal const val FULL_SYNC_CIPHERTEXT_PER_RUN = 50

/** Header pages (100 messages each) one folder backfills per run. A longer window continues in the next run. */
internal const val FULL_SYNC_PAGES_PER_FOLDER = 20

/** #1952 the same on a metered link: 4 pages, the 400 headers the Inbox backfills per refresh ([JMAP_BACKFILL_BATCH]). */
internal const val FULL_SYNC_PAGES_PER_FOLDER_METERED = 4

/**
 * #1952 where the next OLDER header page of a folder starts: right after the oldest header the store holds, never at the
 * top again. [anchorId] null (an empty folder) is the only case that reads from position 0.
 */
internal data class HeaderResume(val anchorId: String?, val anchorOffset: Int) {
    companion object {
        fun after(oldestCachedId: String?): HeaderResume = HeaderResume(oldestCachedId, if (oldestCachedId != null) 1 else 0)

        /** The server no longer knows the anchor: continue at the count of headers held, still not at zero. */
        fun positionWithoutAnchor(cachedCount: Int): Int = cachedCount.coerceAtLeast(0)
    }
}

/** How often the job runs. Push state changes and connectivity regain trigger a run on top of this. */
object FullSyncSchedule {
    /** No live push: every 15 minutes, the WorkManager minimum. */
    const val NO_PUSH_PERIOD_MINUTES = 15L

    /** With push (JMAP EventSource / UnifiedPush) the state change triggers the run; this is only the safety net. */
    const val PUSH_PERIOD_MINUTES = 180L

    const val BACKOFF_SECONDS = 30L

    /** After this many failed attempts in a row the cycle is given up (the next period tries again). */
    const val MAX_ATTEMPTS = 8

    fun periodMinutes(hasPush: Boolean): Long = if (hasPush) PUSH_PERIOD_MINUTES else NO_PUSH_PERIOD_MINUTES
}

/** A folder to keep in the window; only the role matters to the rules ([inbox] folders may warm keys, no other). */
data class FullSyncFolder(val id: String, val inbox: Boolean)

/** One attachment of a stored body still to download. */
data class AttachmentJob(val emailId: String, val partKey: String, val bytes: Long)

/** What the engine needs from storage and the server; [MailRepository] implements it. */
interface FullSyncPort {
    /** The account's folders (network). */
    suspend fun folders(): List<FullSyncFolder>

    /** Incremental header sync of one folder: Email/changes against the stored cursor, newest page when cold. */
    suspend fun syncHeaders(folder: FullSyncFolder)

    /** One OLDER chunk of headers for [folder]; true = the window's far edge is reached (no network when the cache
     *  already holds mail older than [cutoffMillis]). */
    suspend fun backfillHeaders(folder: FullSyncFolder, cutoffMillis: Long?): Boolean

    /** Newest first, at most [limit]: ids in the window with a header and no stored body. */
    suspend fun missingBodies(folder: FullSyncFolder, limit: Int): List<String>

    /** One batched Email/get for [ids]; every body is stored. The port, never the engine, owns the key-import rule. */
    suspend fun fetchBodies(folder: FullSyncFolder, ids: List<String>)

    /** Attachments of stored bodies inside the window not on disk yet, newest message first, at most [limit]. */
    suspend fun missingAttachments(limit: Int): List<AttachmentJob>

    suspend fun fetchAttachment(job: AttachmentJob)

    /** #1869 newest first, at most [limit]: stored, NOT encrypted bodies inside the window that name inline images
     *  none of which is stored yet. */
    suspend fun missingInlineImages(limit: Int): List<String> = emptyList()

    /** #1869 download and store the inline images of the stored body [emailId]. */
    suspend fun fetchInlineImages(emailId: String) {}

    /** #1869 step 9: calendar parts of stored bodies inside the window not on disk yet, newest first, at most [limit]. */
    suspend fun missingCalendarParts(limit: Int): List<AttachmentJob> = emptyList()

    suspend fun fetchCalendarPart(job: AttachmentJob) {}

    /** #1869 step 9: stored ciphertext whose raw source is not kept locally yet, newest first, at most [limit]. */
    suspend fun missingCiphertext(limit: Int): List<String> = emptyList()

    /** Keep the raw source (ciphertext as received, never anything decrypted) of [emailId] locally. */
    suspend fun fetchCiphertext(emailId: String) {}

    suspend fun bodyBytes(): Long
    suspend fun attachmentBytes(): Long

    /** Drop what left the window and, past the caps, the oldest; returns how many bodies went. */
    suspend fun evict(): Int
}

enum class FullSyncStop { NONE, OFFLINE, BATTERY_SAVER, CAP, BUDGET, STOPPED }

data class FullSyncResult(
    val folders: Int,
    val headerPages: Int,
    val bodies: Int,
    val attachments: Int,
    val evicted: Int,
    val errors: Int,
    val stop: FullSyncStop,
    /** True when the window is not complete yet and a further run (a continuation) should follow. */
    val morePending: Boolean,
    /** #1869 messages whose inline images this run stored. */
    val inlineImages: Int = 0,
    /** #1869 step 9: calendar parts and ciphertext sources this run stored. */
    val calendarParts: Int = 0,
    val ciphertexts: Int = 0,
)

/**
 * One run for one account. Order: evict what left the window, headers of every folder (inbox first), bodies of every
 * folder (inbox first, newest first, in batches), inline images of the stored bodies (#1869, unmetered), then
 * attachments if the policy allows. Every step re-reads
 * [conditions]: battery saver switched on mid-run, or the link going away, ends the run safely.
 */
class FullSync(
    private val port: FullSyncPort,
    private val conditions: () -> PrefetchConditions,
    private val policy: AttachmentPolicy,
    private val bodyCapBytes: Long = BODY_CACHE_MAX_BYTES_PER_ACCOUNT,
    private val attachmentCapBytes: Long = ATTACHMENT_CACHE_MAX_BYTES_PER_ACCOUNT,
    private val stopRequested: () -> Boolean = { false },
) {
    suspend fun run(window: OfflineWindow, nowMillis: Long): FullSyncResult {
        val cutoff = window.cutoffMillis(nowMillis)
        var stop = FullSyncStop.NONE
        var pending = false
        var pages = 0
        var bodies = 0
        var attachments = 0
        var inline = 0
        var errors = 0
        var folderCount = 0

        fun halted(): FullSyncStop? {
            val c = conditions()
            return when {
                stopRequested() -> FullSyncStop.STOPPED
                !c.connected -> FullSyncStop.OFFLINE
                c.batterySaver -> FullSyncStop.BATTERY_SAVER
                else -> null
            }
        }
        halted()?.let { return FullSyncResult(0, 0, 0, 0, 0, 0, it, morePending = false) }

        val c0 = conditions()
        val budget = fullSyncBodyBudget(c0)
        val evicted = guarded { port.evict() } ?: 0

        val folders = try {
            port.folders().sortedByDescending { it.inbox }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log("folder list failed: ${t.message}")
            return FullSyncResult(0, 0, 0, 0, evicted, 1, FullSyncStop.NONE, morePending = false)
        }
        folderCount = folders.size

        // 1. Headers: incremental first, then the older chunks until the window's far edge. A metered link keeps the
        //    incremental part (a few KB) and leaves the backfill to Wi-Fi.
        for (folder in folders) {
            halted()?.let { stop = it; return finish(folderCount, pages, bodies, attachments, evicted, errors, stop, false) }
            try {
                port.syncHeaders(folder)
                run {
                    var done = false
                    var n = 0
                    val pagesThisRun = if (conditions().unmetered) FULL_SYNC_PAGES_PER_FOLDER else FULL_SYNC_PAGES_PER_FOLDER_METERED
                    while (!done && n < pagesThisRun) {
                        halted()?.let { stop = it; return finish(folderCount, pages, bodies, attachments, evicted, errors, stop, false) }
                        done = port.backfillHeaders(folder, cutoff)
                        n++
                        pages++
                    }
                    if (!done) pending = true // #1952 any link: the folder continues in the next run from its oldest header
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                errors++
                log("headers of ${folder.id} failed: ${t.message}")
            }
        }

        // 2. Bodies of the whole window.
        var remaining = budget.maxIds
        val bodyCeiling = bodyCapBytes / 100 * FILL_CEILING_PERCENT
        bodyLoop@ for (folder in folders) {
            val attempted = HashSet<String>()
            while (true) {
                val h = halted()
                if (h != null) { stop = h; break@bodyLoop }
                if (remaining <= 0) {
                    stop = FullSyncStop.BUDGET
                    if (port.missingBodies(folder, 1).isNotEmpty()) pending = true // #1952 any link
                    break
                }
                if ((guarded { port.bodyBytes() } ?: 0L) >= bodyCeiling) { stop = FullSyncStop.CAP; break@bodyLoop }
                val want = minOf(budget.batchSize, remaining)
                val ids = try {
                    port.missingBodies(folder, attempted.size + want).filter { it !in attempted }.take(want)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    errors++; break
                }
                if (ids.isEmpty()) break
                attempted += ids
                try {
                    port.fetchBodies(folder, ids)
                    bodies += ids.size
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    errors++
                    log("bodies of ${folder.id} failed: ${t.message}")
                    break
                }
                remaining -= ids.size
            }
        }

        // 2b. #1869 Inline images of the stored bodies, newest first, so the reader paints them from disk. Inside the
        //     body cap (they are stored with the body), unmetered only, a budget per run and a continuation after it.
        if (stop != FullSyncStop.OFFLINE && stop != FullSyncStop.BATTERY_SAVER && stop != FullSyncStop.STOPPED && stop != FullSyncStop.CAP) {
            var left = FULL_SYNC_INLINE_PER_RUN
            val tried = HashSet<String>()
            inlineLoop@ while (left > 0 && inlineImagesAllowed(conditions())) {
                if (stopRequested()) { stop = FullSyncStop.STOPPED; break }
                if ((guarded { port.bodyBytes() } ?: 0L) >= bodyCeiling) { stop = FullSyncStop.CAP; break }
                val want = minOf(left, PREFETCH_BATCH)
                val ids = try {
                    port.missingInlineImages(tried.size + want).filter { it !in tried }.take(want)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    errors++; break
                }
                if (ids.isEmpty()) break
                for (id in ids) {
                    if (stopRequested()) { stop = FullSyncStop.STOPPED; break@inlineLoop }
                    if (!inlineImagesAllowed(conditions())) break@inlineLoop
                    tried += id
                    try {
                        port.fetchInlineImages(id)
                        inline++
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        errors++
                        log("inline images of $id failed: ${t.message}")
                    }
                    left--
                }
                if (left <= 0 && (guarded { port.missingInlineImages(tried.size + 1) } ?: emptyList()).any { it !in tried }) pending = true
            }
        }

        // 2c. #1869 step 9: calendar parts (any link, any policy: an invite is a few KB) and the raw sources of stored
        //     ciphertext (decrypt needs no network). Inside the attachments cap, a budget per run each.
        var calendar = 0
        var ciphertexts = 0
        if (stop != FullSyncStop.OFFLINE && stop != FullSyncStop.BATTERY_SAVER && stop != FullSyncStop.STOPPED) {
            val ceiling = attachmentCapBytes / 100 * FILL_CEILING_PERCENT
            val jobs = if ((guarded { port.attachmentBytes() } ?: 0L) < ceiling) guarded { port.missingCalendarParts(FULL_SYNC_CALENDAR_PER_RUN) }.orEmpty() else emptyList()
            for (job in jobs.filter { it.bytes <= FULL_SYNC_CALENDAR_MAX_BYTES }.take(FULL_SYNC_CALENDAR_PER_RUN)) {
                if (halted() != null) break
                if (guarded { port.fetchCalendarPart(job) } != null) calendar++ else errors++
            }
            val sealed = if ((guarded { port.attachmentBytes() } ?: 0L) < ceiling) guarded { port.missingCiphertext(FULL_SYNC_CIPHERTEXT_PER_RUN) }.orEmpty() else emptyList()
            for (id in sealed.take(FULL_SYNC_CIPHERTEXT_PER_RUN)) {
                if (halted() != null) break
                if (guarded { port.fetchCiphertext(id) } != null) ciphertexts++ else errors++
            }
        }

        // 3. Attachments, by policy, inside the window and the cap.
        if (stop != FullSyncStop.OFFLINE && stop != FullSyncStop.BATTERY_SAVER && stop != FullSyncStop.STOPPED) {
            var left = if (conditions().unmetered) FULL_SYNC_ATTACHMENTS_PER_RUN else FULL_SYNC_ATTACHMENTS_PER_RUN_METERED
            val attachCeiling = attachmentCapBytes / 100 * FILL_CEILING_PERCENT
            val tried = HashSet<AttachmentJob>()
            attachLoop@ while (left > 0 && attachmentsAllowed(policy, conditions())) {
                if (stopRequested()) { stop = FullSyncStop.STOPPED; break }
                var used = guarded { port.attachmentBytes() } ?: 0L
                if (used >= attachCeiling) { if (stop == FullSyncStop.NONE) stop = FullSyncStop.CAP; break }
                val jobs = try {
                    port.missingAttachments(tried.size + minOf(left, 20)).filter { it !in tried }.take(minOf(left, 20))
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    errors++; break
                }
                if (jobs.isEmpty()) break
                for (job in jobs) {
                    if (used + job.bytes > attachCeiling) { if (stop == FullSyncStop.NONE) stop = FullSyncStop.CAP; break@attachLoop }
                    if (!attachmentsAllowed(policy, conditions())) break@attachLoop
                    tried += job
                    try {
                        port.fetchAttachment(job)
                        attachments++
                        used += job.bytes
                        left--
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        errors++
                        log("attachment ${job.emailId}/${job.partKey} failed: ${t.message}")
                    }
                    if (left <= 0) { pending = c0.unmetered; break@attachLoop }
                }
            }
        }

        // 4. Trim to the caps (what the fill stopped short of by design is untouched).
        val evictedAfter = guarded { port.evict() } ?: 0
        return finish(folderCount, pages, bodies, attachments, evicted + evictedAfter, errors, stop, pending, inline).copy(
            calendarParts = calendar, ciphertexts = ciphertexts,
        )
    }

    private fun finish(
        folders: Int, pages: Int, bodies: Int, attachments: Int, evicted: Int, errors: Int, stop: FullSyncStop, pending: Boolean,
        inline: Int = 0,
    ) = FullSyncResult(
        folders, pages, bodies, attachments, evicted, errors, stop,
        morePending = pending && stop != FullSyncStop.CAP, inlineImages = inline,
    )

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        log("step failed: ${t.message}")
        null
    }

    private fun log(message: String) {
        try {
            Log.w("FullSync", message)
        } catch (_: RuntimeException) {
        }
    }
}

/**
 * Bodies of a folder that is NOT the inbox, ready to store (#1768). Stored RAW: an encrypted message is kept as the
 * ciphertext it is (opening it runs the decrypt flow, [MailRepository.openMessage]), and, the point of this being a
 * function of its inputs only, NOTHING here can reach the OpenPGP provider. A prefetched body in the inbox may hand the
 * sender's announced key to OpenKeychain; for Junk, Trash, Archive and every other folder that would plant a key for an
 * address the user never saw, so no key is imported here, ever, until the user opens the message.
 */
internal suspend fun storeRawBodies(
    emails: List<Email>,
    headersById: Map<String, List<EmailHeader>>,
    originOf: (List<EmailHeader>) -> String?,
    persist: suspend (Email) -> Boolean,
): Int {
    var stored = 0
    for (email in emails) {
        val origin = headersById[email.id]?.let(originOf)
        if (persist(email.copy(originalSender = origin))) stored++
    }
    return stored
}
