package app.sterna.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Foreground service holding JMAP EventSource connections so new mail arrives instantly, with no
 *  FCM. It reconnects when a connection drops and notifies for mail that arrived during the gap; a
 *  `generation` counter retires stale connections when the service is (re)started. */
class PushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Open connections, keyed by LOGIN id — one socket per login, not per account (issue #31). */
    private val connections = ConcurrentHashMap<String, Closeable>()

    /** Which login's connection carries each account, rebuilt on every arm. Without it [isConnected]
     *  can only be asked about accounts that happen to BE their own login. */
    private val carriedBy = ConcurrentHashMap<String, String>()

    @Volatile
    private var generation = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        Notifications.ensureChannels(this)
        // specialUse, not dataSync: Android 15+ budgets dataSync foreground services and then
        // forcibly stops them, which silently killed push on newer devices (#11).
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        // Android 15+ can refuse a foreground-service start outright, and the refusal lands HERE
        // rather than at the caller's startForegroundService. Left to propagate it takes the process
        // down, and the start being retried that is a crash loop (#98); swallowed,
        // [MailFetchWorker] keeps mail flowing and the account screen reports "Periodic".
        try {
            ServiceCompat.startForeground(this, Notifications.SERVICE_ID, Notifications.serviceNotification(this), type)
        } catch (e: RuntimeException) {
            Log.w(TAG, "startForeground refused; fallback poll carries delivery", e)
            stopSelf()
        }
    }

    /** Android 15+ FGS timeout (belt: specialUse should never receive one). Stop gracefully
     *  instead of taking the system's ANR/kill; [MailFetchWorker] keeps mail flowing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (type $fgsType); stopping, fallback poll takes over")
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gen = ++generation
        // A silent baseline reseed is allowed only on user-initiated arms, and even then only for
        // the inbox the user is looking at. Background re-arms must DIFF, or gap mail goes
        // unannounced.
        val userInitiated = intent?.getBooleanExtra(EXTRA_USER_INITIATED, false) ?: false
        scope.launch { reconnectAll(gen, userInitiated) }
        return START_STICKY
    }

    /** Consecutive failed or short-lived connections per login, and when the open one opened ([ReconnectBackoff]). */
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val openedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun reconnectAll(gen: Int, userInitiated: Boolean) {
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        failures.clear()
        carriedBy.clear()
        val store = application.container.accountStore
        val up = application.container.unifiedPushManager
        val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())
        // Honour the per-account notification opt-out; UnifiedPush-active accounts are served by
        // their endpoint (#17).
        val accounts = watched.filter { store.notificationsEnabled(it.id) && !up.isActive(it.id) }
        if (accounts.isEmpty()) {
            // The one arm that ends without even a summary line. Legitimate, and also what a
            // credential that failed to decrypt looks like from here — so say which of the two.
            Log.i(TAG, PushController.nothingToWatchLine(watched.size))
            stopSelf()
            return
        }
        // One EventSource per login (#31): a login's session carries StateChanges for the accounts
        // it is a MEMBER of. An ACL-shared sub-account's changes are NEVER delivered on it, so
        // [MailFetchWorker] polls linked sub-accounts every cycle.
        val groups = accounts.groupBy { store.account(it.id)?.loginKey() ?: it.id }
        // Record the grouping BEFORE opening anything, so the status line reads "no connection yet"
        // rather than "unknown account". Cleared at the top of every arm.
        groups.forEach { (loginId, group) -> group.forEach { carriedBy[it.id] = loginId } }
        groups.forEach { (loginId, group) -> watch(loginId, group, gen, userInitiated) }
        // [connections] now holds every handle this arm came away with — not the same claim as
        // "this many sockets are up"; see the summary's doc in [PushController].
        Log.i(TAG, PushController.armSummary(accounts.size, groups.size, connections.size))
    }

    /** (Re)establish the single push connection for one login and fan changes out to its accounts. */
    private suspend fun watch(loginId: String, group: List<AccountCredentials>, gen: Int, userInitiated: Boolean) {
        // An arm from a retired generation must not open a socket, and it says so: dropped here,
        // this login ends with no connection and nothing rescheduled. Read the generation ONCE.
        val current = generation
        if (gen != current) { Log.w(TAG, PushController.abandonedArmLine(loginId, gen, current)); return }
        val repo = application.container.mailRepository
        val currentId = application.container.accountStore.currentId()
        val unified = PushController.unifiedInboxVisible
        // The handle this arm ends up holding. An AtomicReference and not a captured `var`:
        // onClosed fires on the transport's own thread.
        val mine = AtomicReference<Closeable?>(null)
        runCatching {
            // The silent reseed is decided per ACCOUNT and not per arm: one arm covers every
            // watched account, but only the inbox on screen may be swallowed silently.
            group.forEach { credentials ->
                val reset = shouldResetBaseline(credentials.id, userInitiated, currentId, unified)
                // Per account, so one failure does not cost the whole group its connection — but
                // never silently: a failed seed leaves a stale baseline the next pass announces
                // against, hours later.
                runCatching { FetchAndNotify.run(this, credentials, resetBaselines = reset) }
                    .onFailure { Log.w(TAG, "Baseline pass failed for account ${credentials.id}", it) }
            }
            // Any credential in the group reaches the shared session; prefer the login's own.
            val owner = group.firstOrNull { it.id == loginId } ?: group.first()
            val watchedJmapIds = group.mapNotNull { it.jmapAccountId }.toSet()
            val handle = repo.openAccountPush(
                owner,
                onChanged = { if (gen == generation) scope.launch { group.forEach { onAccountChanged(it) } } },
                onClosed = {
                    // Drop the dead connection now, not at the delayed retry: a retired socket that
                    // leaves its entry behind keeps isConnected answering true with nothing to
                    retractOwnConnection(connections, loginId, mine.get())
                    val lived = openedAt.remove(loginId)?.let { android.os.SystemClock.elapsedRealtime() - it }
                    failures[loginId] = ReconnectBackoff.next(failures[loginId] ?: 0, lived)
                    // Deliberately silent when the generation is retired: this fires on every arm,
                    // so a line would print once per healthy arm while saying nothing about the race.
                    if (gen == generation) {
                        scheduleReconnect(loginId, group, gen)
                    }
                },
                watchedJmapAccountIds = watchedJmapIds,
            )
            // Store first, check after — whoever writes LAST checks LAST. The baseline pass can run
            // for tens of seconds, and checking BEFORE the write only narrows the defect (#130).
            mine.set(handle)
            connections[loginId] = handle
            openedAt[loginId] = android.os.SystemClock.elapsedRealtime()
            val after = generation
            if (gen != after) {
                retractOwnConnection(connections, loginId, handle)
                runCatching { handle.close() }
                Log.w(TAG, PushController.retiredHandleDroppedLine(loginId, gen, after))
            }
        }.onFailure {
            Log.e(TAG, "Push watch failed for login $loginId", it)
            failures[loginId] = ReconnectBackoff.next(failures[loginId] ?: 0, null)
            scheduleReconnect(loginId, group, gen)
        }
    }

    private fun scheduleReconnect(loginId: String, group: List<AccountCredentials>, gen: Int) {
        scope.launch {
            delay(ReconnectBackoff.delayMs(failures[loginId] ?: 0))
            // Read once, same reason as the arm guard above.
            val current = generation
            if (gen == current) {
                Log.i(TAG, "Reconnecting push for login $loginId")
                runCatching { connections.remove(loginId)?.close() }
                // Never user-initiated: a reconnect must announce the gap's mail, not swallow it.
                watch(loginId, group, gen, userInitiated = false)
            } else {
            // Dropping the retry is correct here, and it is also the end of this login's push for
            // this generation — the only trace it leaves is this line.
                Log.w(TAG, PushController.abandonedReconnectLine(loginId, gen, current))
            }
        }
    }

    private suspend fun onAccountChanged(credentials: AccountCredentials) {
        runCatching {
            // JMAP's StateChange has no per-mailbox granularity, so re-sync the whole watched set
            // with cheap per-folder deltas. IMAP IDLE only ever signals the INBOX.
            FetchAndNotify.run(
                this,
                credentials,
                includeExtras = credentials.protocol != MailProtocol.IMAP,
            )
        }.onFailure { Log.e(TAG, "onAccountChanged failed", it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        isRunning = false
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        carriedBy.clear()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PushService"
        private const val EXTRA_USER_INITIATED = "userInitiated"

        /** Whether the service object exists. Says nothing about whether any connection is open,
         *  the reconnect loop keeping the service alive through every failure. Only the status line
         *  may read it, and only after [isConnected] has said no. */
        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var instance: PushService? = null

        /**
         * Whether the connection carrying [accountId] is open right now. Asked with an ACCOUNT id,
         */
        fun isConnected(accountId: String): Boolean {
            val service = instance ?: return false
            return isCarriedByOpenConnection(accountId, service.carriedBy, service.connections.keys)
        }

        /** [userInitiated] gates the silent baseline reseed; which accounts get it is
         *  [shouldResetBaseline]'s call, made per account as they are armed. */
        fun start(context: Context, userInitiated: Boolean) {
            val intent = Intent(context, PushService::class.java)
                .putExtra(EXTRA_USER_INITIATED, userInitiated)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushService::class.java))
        }
    }
}

/**
 * Is [accountId] carried by one of the [openConnections] right now? The service holds ONE connection
 */
internal fun isCarriedByOpenConnection(
    accountId: String,
    carriedBy: Map<String, String>,
    openConnections: Set<String>,
): Boolean = (carriedBy[accountId] ?: accountId) in openConnections

/**
 * Take [loginId] out of [connections] only if the entry sitting there IS [mine], and say whether
 */
internal fun <T : Any> retractOwnConnection(
    connections: MutableMap<String, T>,
    loginId: String,
    mine: T?,
): Boolean {
    if (mine == null || connections[loginId] !== mine) return false
    connections.remove(loginId)
    return true
}
