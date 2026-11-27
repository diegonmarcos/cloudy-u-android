package app.sterna.ui.inbox

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * #1952: what the thin line above the CACHED list says when the refresh did not land. The rows stay whatever the network answers; this
 * only tells the reader how old they are and why. [reason] is the failure as the server or the transport gave it (null when the device
 * is simply offline), [lastSyncAt] the last successful reconcile (epoch ms, null = never).
 */
internal data class CacheBanner(val notice: RefreshNotice, val reason: String?, val lastSyncAt: Long?)

/** The banner over [rowCount] cached rows, or null when there is nothing to say (the refresh landed) or no row to sit above (the
 *  zero-rows case has its own centred scene, with the same Retry). */
internal fun cacheBanner(rowCount: Int, offline: Boolean, error: String?, lastSyncAt: Long?): CacheBanner? {
    if (rowCount <= 0) return null
    return when (val notice = refreshNotice(offline, error)) {
        RefreshNotice.NONE -> null
        RefreshNotice.OFFLINE -> CacheBanner(notice, error, lastSyncAt)
        RefreshNotice.ERROR -> CacheBanner(notice, error, lastSyncAt)
    }
}

/** Did a refresh just LAND? Only then: it was running, it stopped, and it left no error (a failure and a wait abandoned at its
 *  budget both leave one). The last sync time moves on this and on nothing else. */
internal fun refreshLanded(wasRefreshing: Boolean, refreshing: Boolean, error: String?): Boolean =
    wasRefreshing && !refreshing && error == null

/** [at] (a wall-clock stamp, like the message dates) as "5 minutes ago", in the device's language. */
internal fun lastSyncAgo(at: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()

/**
 * #1952: the list's own stopwatch, read by the emulator walk from logcat (tag MailList). `created` counts the list ViewModels of this
 * process (one is the design), `first-row` is the time from the list screen entering the composition to the first frame holding rows.
 * No message data is logged.
 */
internal object FirstRowTiming {
    private const val TAG = "MailList"
    private val created = AtomicInteger(0)

    fun viewModelCreated() {
        runCatching { Log.i(TAG, "view-model created=${created.incrementAndGet()}") }
    }

    fun now(): Long = SystemClock.elapsedRealtime()

    fun painted(enteredAt: Long, rows: Int) {
        app.sterna.core.data.mail.ColdStart.rowsPainted()
        runCatching { Log.i(TAG, "first-row ms=${now() - enteredAt} rows=$rows view-models=${created.get()}") }
    }
}
