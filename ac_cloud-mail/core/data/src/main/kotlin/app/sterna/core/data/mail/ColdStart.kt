package app.sterna.core.data.mail

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Longest the first network refresh of a cold start waits for the list's first rows (an empty store paints none). */
const val COLD_START_PAINT_WAIT_MS = 2_500L

/**
 * #1952 cold start: the list's first rows are painted from the store BEFORE any network call. The activity [arm]s the
 * gate, the list reports [rowsPainted] on its first frame holding rows, and the list's first refresh [awaitRows] (at most
 * [COLD_START_PAINT_WAIT_MS], so an empty store still syncs). [network] logs the first server connection with its place
 * in that order (logcat tag MailList, no message data), which is what the emulator walk reads.
 */
class ColdStartGate(private val log: (String) -> Unit = {}, private val now: () -> Long = System::currentTimeMillis) {
    private val painted = CompletableDeferred<Unit>()
    private val armed = AtomicBoolean(false)
    private val networkSeen = AtomicBoolean(false)
    @Volatile private var armedAt = 0L

    fun arm() {
        if (armed.compareAndSet(false, true)) { armedAt = now(); log("cold-start armed") }
    }

    fun rowsPainted() {
        if (painted.complete(Unit)) log("cold-start rows-painted ms=${if (armed.get()) now() - armedAt else -1}")
    }

    val rowsWerePainted: Boolean get() = painted.isCompleted

    /** Returns at once when not armed (no activity: a worker, a test) or already painted. */
    suspend fun awaitRows(timeoutMs: Long = COLD_START_PAINT_WAIT_MS) {
        if (!armed.get() || painted.isCompleted) return
        withTimeoutOrNull(timeoutMs) { painted.await() }
    }

    fun network(what: String) {
        if (networkSeen.compareAndSet(false, true)) {
            log("cold-start first-network what=$what after-rows=${painted.isCompleted} ms=${if (armed.get()) now() - armedAt else -1}")
        }
    }
}

/** The process's one gate. */
val ColdStart = ColdStartGate(log = { runCatching { Log.i("MailList", it) } })
