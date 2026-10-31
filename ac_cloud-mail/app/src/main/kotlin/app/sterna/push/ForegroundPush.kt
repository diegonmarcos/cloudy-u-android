package app.sterna.push

import android.app.Application
import android.content.Context
import android.util.Log
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * A JMAP EventSource held for the CURRENT account while the app is on screen, whatever the delivery
 * setting says. Without it, an open app only saw new mail when [PushService] happened to be armed:
 * with notifications off for the account, Battery saver on, or UnifiedPush ACTIVE (its relay batches
 * pushes), the inbox waited for the periodic worker or a pull-to-refresh — minutes, while Stalwart
 * announces the StateChange within a second of delivery.
 *
 * It costs nothing when [PushService] already carries the account (no second socket), and it is
 * closed the moment the activity stops, so background delivery stays the user's choice.
 */
object ForegroundPush {
    private const val TAG = "ForegroundPush"
    @Volatile private var failures = 0
    @Volatile private var openedAt = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var handle: Closeable? = null
    private var heldFor: String? = null
    private var job: Job? = null
    @Volatile private var foreground = false

    fun onForeground(context: Context) {
        foreground = true
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch { arm(app) }
    }

    fun onBackground() {
        foreground = false
        job?.cancel()
        job = null
        release()
    }

    @Synchronized
    private fun release() {
        runCatching { handle?.close() }
        handle = null
        heldFor = null
    }

    private suspend fun arm(app: Context) {
        val container = (app as Application).container
        val id = container.accountStore.currentId() ?: return
        val credentials = container.accountStore.credentials(id) ?: return
        if (!shouldHoldStream(credentials.protocol, PushService.isConnected(id))) return
        synchronized(this) { if (heldFor == id && handle != null) return }
        release()
        runCatching {
            // Catch up on anything that arrived while the app was away, then listen.
            FetchAndNotify.run(app, credentials)
            val h = container.mailRepository.openAccountPush(
                credentials,
                onChanged = { if (foreground) scope.launch { onChanged(app, credentials) } },
                onClosed = {
                    failures = ReconnectBackoff.next(failures, android.os.SystemClock.elapsedRealtime() - openedAt)
                    if (foreground) scheduleReconnect(app)
                },
            )
            synchronized(this) {
                if (!foreground) { runCatching { h.close() }; return }
                handle = h
                heldFor = id
                openedAt = android.os.SystemClock.elapsedRealtime()
            }
            Log.i(TAG, "foreground event source held for account $id")
        }.onFailure {
            Log.w(TAG, "foreground event source failed for account $id", it)
            failures = ReconnectBackoff.next(failures, null)
            scheduleReconnect(app)
        }
    }

    private fun scheduleReconnect(app: Context) {
        synchronized(this) { handle = null; heldFor = null }
        job = scope.launch {
            delay(ReconnectBackoff.delayMs(failures))
            if (foreground) arm(app)
        }
    }

    private suspend fun onChanged(app: Context, credentials: AccountCredentials) {
        runCatching { FetchAndNotify.run(app, credentials) }
            .onFailure { Log.w(TAG, "refresh after StateChange failed", it) }
    }
}

/** Hold a foreground stream only for JMAP (IMAP has no EventSource) and only when [PushService]
 *  is not already carrying this account — never two sockets for one login. */
internal fun shouldHoldStream(protocol: MailProtocol, pushServiceCarriesIt: Boolean): Boolean =
    protocol == MailProtocol.JMAP && !pushServiceCarriesIt
