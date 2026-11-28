package app.sterna.core.data.mail

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Longest the first network refresh of a cold start waits for the list's first rows (an empty store paints none). */
const val COLD_START_PAINT_WAIT_MS = 5_000L

/** How long a sync started at process start waits to learn whether an activity (and so a list to paint) follows. */
const val COLD_START_ACTIVITY_WAIT_MS = 4_000L

/**
 * #1952 cold start: the list's first rows are painted from the store BEFORE any network call. The activity [arm]s the
 * gate, the list reports [rowsPainted] on its first frame holding rows, and the list's first refresh [awaitRows] (at most
 * [COLD_START_PAINT_WAIT_MS], so an empty store still syncs). [network] logs the first server connection with its place
 * in that order (logcat tag MailList, no message data), which is what the emulator walk reads.
 */
class ColdStartGate(
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** Milliseconds since the PROCESS started (what the owner feels), or -1 where unknown. */
    private val processAge: () -> Long = { -1L },
) {
    private val painted = CompletableDeferred<Unit>()
    private val armed = AtomicBoolean(false)
    private val networkSeen = AtomicBoolean(false)
    @Volatile private var armedAt = 0L

    private val activity = CompletableDeferred<Unit>()

    /** The activity exists: rows will be painted. */
    fun arm() {
        if (armed.compareAndSet(false, true)) { armedAt = now(); log("cold-start armed ms=0 process-age=${processAge()}") }
        activity.complete(Unit)
    }

    /** Process start, before any activity: a sync started this early waits [COLD_START_ACTIVITY_WAIT_MS] to learn whether
     *  an activity follows (then it waits for its rows) or this is a worker's process (then it goes on). */
    fun armProcess() {
        if (armed.compareAndSet(false, true)) { armedAt = now(); log("cold-start armed ms=0 process-age=${processAge()}") }
    }

    fun rowsPainted() {
        // Logged BEFORE the waiters are released, so the log's order is the real order.
        if (!painted.isCompleted) log("cold-start rows-painted ms=${if (armed.get()) now() - armedAt else -1} process-age=${processAge()}")
        painted.complete(Unit)
    }

    /** The list's first read of the store came back empty: nothing will be painted, the sync may start. */
    fun nothingToPaint() {
        if (painted.complete(Unit)) log("cold-start store-empty ms=${if (armed.get()) now() - armedAt else -1}")
    }

    val rowsWerePainted: Boolean get() = painted.isCompleted

    /** Returns at once when not armed (no activity: a worker, a test) or already painted. */
    suspend fun awaitRows(timeoutMs: Long = COLD_START_PAINT_WAIT_MS) {
        if (!armed.get() || painted.isCompleted) return
        if (!activity.isCompleted) withTimeoutOrNull(COLD_START_ACTIVITY_WAIT_MS) { activity.await() } ?: return
        withTimeoutOrNull(timeoutMs) { painted.await() }
    }

    /** A step of the list's own start (logcat tag MailList, no message data): where the time before the first row goes. */
    fun mark(step: String) {
        if (armed.get() && !painted.isCompleted) log("cold-start mark=$step ms=${now() - armedAt} process-age=${processAge()}")
    }

    /** #1952 a step of the process's own start, from the Application on (logcat tag MailList): armed or not, with the process age. */
    fun early(step: String) {
        log("app-init step=$step ms=${if (armed.get()) now() - armedAt else -1} process-age=${processAge()}")
    }

    fun network(what: String) {
        if (networkSeen.compareAndSet(false, true)) {
            log("cold-start first-network what=$what after-rows=${painted.isCompleted} ms=${if (armed.get()) now() - armedAt else -1}")
        }
    }
}

/** The process's one gate. */
val ColdStart = ColdStartGate(
    log = { runCatching { Log.i("MailList", it) } },
    processAge = { runCatching { android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() }.getOrDefault(-1L) },
)
