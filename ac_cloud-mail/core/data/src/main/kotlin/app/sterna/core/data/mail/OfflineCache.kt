package app.sterna.core.data.mail

import app.sterna.core.jmap.JmapException
import kotlinx.coroutines.CancellationException

/**
 * How far back every message body is kept readable offline ("Keep mail offline for"): 30, 90, 180, 365 days or
 * all. [months] is the persisted-name scale (ONE..TWELVE keep their names), [days] the real span. The default is
 * [DEFAULT], every message (#1952: fully cached; build.json::mail_cache.bodies.default_window_days = 0).
 */
enum class OfflineWindow(val months: Int, val days: Int) {
    ONE(1, 30), THREE(3, 90), SIX(6, 180), TWELVE(12, 365), ALL(0, 0);

    /** Epoch millis of the oldest message inside the window, or null for [ALL]. */
    fun cutoffMillis(nowMillis: Long): Long? =
        if (days == 0) null else nowMillis - days * DAY_MILLIS

    companion object {
        val DEFAULT = ALL
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000

        fun from(stored: String?): OfflineWindow =
            stored?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }
}

/**
 * #1952 "Body cache limit": stored body bytes one account may hold. Past it the OLDEST bodies go first; headers never.
 * The names are the persisted format. The default is build.json::mail_cache.bodies.cap_mb.
 */
enum class BodyCacheLimit(val mb: Int) {
    MB_512(512), GB_1(1024), GB_2(2048), GB_3(3072), GB_5(5120), GB_10(10240);

    val bytes: Long get() = mb * 1024L * 1024L

    companion object {
        val DEFAULT = GB_3

        fun from(stored: String?): BodyCacheLimit =
            stored?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }
}

/** Largest attachment the offline sync downloads. [bytes] = 0 downloads none. */
enum class OfflineAttachmentLimit(val bytes: Long) {
    NONE(0), MB_5(5L shl 20), MB_25(25L shl 20), MB_100(100L shl 20);

    companion object {
        val DEFAULT = MB_25

        fun from(stored: String?): OfflineAttachmentLimit =
            stored?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }
}

/** The server (or the edge gate in front of it) still refuses the login after a token refresh. */
class SignInRequiredException(cause: Throwable) :
    Exception("Sign-in required: the server rejected the saved login", cause)

object AuthRetry {
    /** True when [t] or a cause is an HTTP 401 answer (mailbox or edge gate). */
    fun isAuthFailure(t: Throwable): Boolean {
        var c: Throwable? = t
        while (c != null) {
            if (c is SignInRequiredException) return true
            if (c is JmapException && c.httpCode == 401) return true
            c = c.cause
        }
        return false
    }

    /**
     * Run [block]; on a 401 call [refresh] (drop the cached session, force a token refresh) and run it
     * ONCE more. A second 401, or a refresh that itself fails, becomes [SignInRequiredException].
     */
    suspend fun <T> withRefreshOn401(refresh: suspend () -> Unit, block: suspend () -> T): T {
        try {
            return block()
        } catch (t: Throwable) {
            if (t is CancellationException || t is SignInRequiredException || !isAuthFailure(t)) throw t
        }
        try {
            refresh()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw SignInRequiredException(t)
        }
        try {
            return block()
        } catch (t: Throwable) {
            if (t !is CancellationException && t !is SignInRequiredException && isAuthFailure(t)) {
                throw SignInRequiredException(t)
            }
            throw t
        }
    }
}

/** Offline-first read: a stored copy is returned without ever touching [network]. */
object OfflineFirst {
    suspend fun <T : Any> read(local: suspend () -> T?, network: suspend () -> T): T =
        runCatching { local() }.getOrNull() ?: network()
}

/** What [OfflineSync] needs from storage and the server; [MailRepository] implements it. */
interface OfflineSyncPort {
    /** Ids of every message received at or after [cutoffMillis] (null = all), server and local. */
    suspend fun idsInWindow(cutoffMillis: Long?): List<String>

    /** Of [ids], those whose full body is already stored. */
    suspend fun cachedIds(ids: List<String>): Set<String>

    /** Fetch and store full bodies (and attachments up to [attachmentMaxBytes]) of [ids]. */
    suspend fun fetchAndStore(ids: List<String>, attachmentMaxBytes: Long)

    /** Drop stored bodies/attachments of messages received before [cutoffMillis]; returns how many. */
    suspend fun evictOlderThan(cutoffMillis: Long): Int
}

data class OfflineSyncResult(val fetched: Int, val evicted: Int)

class OfflineSync(private val port: OfflineSyncPort, private val batch: Int = 50) {
    suspend fun run(window: OfflineWindow, limit: OfflineAttachmentLimit, nowMillis: Long): OfflineSyncResult {
        val cutoff = window.cutoffMillis(nowMillis)
        val evicted = if (cutoff != null) port.evictOlderThan(cutoff) else 0
        val ids = port.idsInWindow(cutoff)
        val cached = port.cachedIds(ids)
        val missing = ids.filter { it !in cached }
        missing.chunked(batch).forEach { port.fetchAndStore(it, limit.bytes) }
        return OfflineSyncResult(fetched = missing.size, evicted = evicted)
    }
}
