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
 * (the throttle [prefetchBudget], the 250 MB cap [BODY_CACHE_MAX_BYTES_PER_ACCOUNT], the 90-day window).
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

/** Header pages (100 messages each) one folder backfills per run. A longer window continues in the next run. */
internal const val FULL_SYNC_PAGES_PER_FOLDER = 20

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
)

/**
 * One run for one account. Order: evict what left the window, headers of every folder (inbox first), bodies of every
 * folder (inbox first, newest first, in batches), then attachments if the policy allows. Every step re-reads
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
        val budget = prefetchBudget(c0, PrefetchTrigger.LIST_SYNC)
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
                if (conditions().unmetered) {
                    var done = false
                    var n = 0
                    while (!done && n < FULL_SYNC_PAGES_PER_FOLDER) {
                        halted()?.let { stop = it; return finish(folderCount, pages, bodies, attachments, evicted, errors, stop, false) }
                        done = port.backfillHeaders(folder, cutoff)
                        n++
                        pages++
                    }
                    if (!done) pending = true // unmetered only: a longer window continues in the next run
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
                    if (c0.unmetered && port.missingBodies(folder, 1).isNotEmpty()) pending = true
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
        return finish(folderCount, pages, bodies, attachments, evicted + evictedAfter, errors, stop, pending)
    }

    private fun finish(
        folders: Int, pages: Int, bodies: Int, attachments: Int, evicted: Int, errors: Int, stop: FullSyncStop, pending: Boolean,
    ) = FullSyncResult(folders, pages, bodies, attachments, evicted, errors, stop, morePending = pending && stop != FullSyncStop.CAP)

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
