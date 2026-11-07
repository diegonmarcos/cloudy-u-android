package com.diegonmarcos.superapp.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.notificationcenter.MeshDownAlert
import com.wireguard.android.backend.Tunnel
import com.wireguard.crypto.Key
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #1120 The Android half of "connected by default, reconnects until the owner taps Disconnect".
 *
 * [MeshSupervisor] decides (pure); this reads the world, acts, and keeps the mesh-down alert honest:
 *  - started by App.onCreate and by the boot receiver; it listens to the default network (a change or a loss)
 *    and to the VPN transport (the tunnel dropping), and a 15-minute WorkManager job is the backstop for a
 *    process that died with nothing to wake it;
 *  - one worker thread, events coalesced for [DEBOUNCE_MS], failures retried with [MeshSupervisor.backoffMs];
 *  - it connects through the same calls Configs > Cloud Mesh makes (MeshTransport.connect + toTunnelConfig),
 *    never a second tunnel path, and never when the owner tapped Disconnect.
 */
object MeshSupervisorRunner {
    private const val TAG = "MeshSupervisor"
    private const val PREFS = "mesh_supervisor"
    private const val DEBOUNCE_MS = 1_500L
    /** While the tunnel is NOT healthy and nothing sets a sooner look: 5 min (it was 30 s). A healthy tunnel has no self-kick at all. */
    internal const val WATCH_MS = 5 * 60_000L
    private const val WORK = "mesh-supervisor"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mesh-supervisor").apply { isDaemon = true } }
    @Volatile private var started = false
    private var pending: Runnable? = null
    @Volatile private var lastCapSig = ""

    /** The part of a capability report worth a look: which transports, validated, internet. */
    internal fun capSig(wifi: Boolean, cell: Boolean, eth: Boolean, vpn: Boolean, validated: Boolean, internet: Boolean): String =
        listOf(wifi, cell, eth, vpn, validated, internet).joinToString("") { if (it) "1" else "0" }

    /**
     * When to look again on our own: null = never (a healthy tunnel waits for the network / VPN callbacks and the
     * 15-minute WorkManager job). Otherwise the sooner of the backoff and the alert debounce, else [WATCH_MS].
     */
    internal fun nextLookMs(wanted: Boolean, installed: Boolean, healthy: Boolean, retryAt: Long?, debounceAt: Long?, now: Long): Long? {
        if (!wanted || !installed) return null
        val at = listOfNotNull(retryAt, debounceAt).minOrNull()
        if (healthy && at == null) return null
        return (at?.minus(now) ?: WATCH_MS).coerceIn(DEBOUNCE_MS, WATCH_MS)
    }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Register the listeners and the backstop once per process. Cheap to call again. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!started) {
            synchronized(this) {
                if (started) return@synchronized
                started = true
                val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                runCatching {
                    cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) { kick(app) }
                        override fun onLost(network: Network) { kick(app) }
                        // Only a real change of transport / validation kicks; signal-strength updates arrive here too.
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                            val sig = capSig(
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET), caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                            if (sig != lastCapSig) { lastCapSig = sig; kick(app) }
                        }
                    })
                }
                runCatching {
                    cm.registerNetworkCallback(
                        NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                        object : ConnectivityManager.NetworkCallback() {
                            override fun onLost(network: Network) { kick(app) }
                        })
                }
                runCatching {
                    WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                        WORK, ExistingPeriodicWorkPolicy.KEEP,
                        PeriodicWorkRequestBuilder<MeshSupervisorWorker>(15, TimeUnit.MINUTES).build())
                }
            }
        }
        kick(app)
    }

    /** Look at the mesh soon (coalesced). */
    fun kick(ctx: Context, delayMs: Long = DEBOUNCE_MS) {
        val app = ctx.applicationContext
        main.post {
            pending?.let(main::removeCallbacks)
            val r = Runnable { runCatching { worker.execute { tick(app) } } }
            pending = r
            main.postDelayed(r, delayMs)
        }
    }

    /** The Reconnect button: forget the backoff and bring the tunnel down and up again. */
    fun reconnectNow(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { worker.execute { tick(app, force = true) } }
    }

    fun lastNote(ctx: Context): String = sp(ctx).getString("note", "").orEmpty()

    private fun note(ctx: Context, s: String) {
        if (lastNote(ctx) == s) return // unchanged: no preference write, no log line
        sp(ctx).edit().putString("note", s).apply()
        Log.i(TAG, s)
        // #1153 the supervisor's decisions (reconnects, retries, why it stopped) go to the journal the Logs section reads
        com.diegonmarcos.superapp.network.mesh.MeshEventLog.log(ctx, "supervisor", s)
    }

    fun netKind(ctx: Context): MeshSupervisor.Net {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = runCatching { cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) } }.getOrDefault(emptyList())
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        return when {
            caps.isEmpty() -> MeshSupervisor.Net.NONE
            caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } -> MeshSupervisor.Net.WIFI
            caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } -> MeshSupervisor.Net.MOBILE
            else -> MeshSupervisor.Net.OTHER
        }
    }

    /**
     * #1124 Label of the other app holding the VPN slot, or null when it is free. Android allows one VPN: an active
     * VPN network not owned by us (or a non-null VpnService.prepare while a foreign VPN is up) means connecting
     * automatically would displace it, so the supervisor must not claim it.
     */
    fun slotHolder(ctx: Context, ourTunnelUp: Boolean, consentNeeded: Boolean): String? {
        if (ourTunnelUp) return null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpnCaps = runCatching { cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) } }.getOrDefault(emptyList())
            .filter { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        if (vpnCaps.isEmpty()) return null
        val uids = vpnCaps.map { if (android.os.Build.VERSION.SDK_INT >= 29) it.ownerUid else -1 }
        val foreign = MeshSupervisor.foreignOwner(uids, android.os.Process.myUid())
        if (foreign == null) return null
        if (foreign < 0 && !consentNeeded) return null // owner unknown and prepare() is null: it is ours (or consent is held)
        val pm = ctx.packageManager
        val label = runCatching {
            pm.getNameForUid(foreign)?.let { pkg -> pm.getApplicationLabel(pm.getApplicationInfo(pkg.substringBefore(':'), 0)).toString() }
        }.getOrNull()
        return label ?: "another app"
    }

    private fun isInteractive(ctx: Context): Boolean =
        runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive }.getOrDefault(true)

    /** One look at the world and one action. Blocking: never on the main thread. */
    @Synchronized
    fun tick(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val policy = MeshIntent.policy(ctx)
        val backend = WgState.backend(ctx)
        val prefs = WgState.prefs(ctx)
        val installed = runCatching { backend.isEngineInstalled() }.getOrDefault(false)
        var up = installed && runCatching { backend.getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)
        val s = sp(ctx)

        // Screen off and the tunnel up with no alert open: nothing to decide, nothing to write.
        if (!force && up && !isInteractive(ctx) && !s.getBoolean("raised", false) && s.getLong("down_since", 0L) == 0L) return

        if (force && policy.wanted && installed) {
            runCatching { backend.setState(WgState.tunnel, Tunnel.State.DOWN, null) }
            up = false
            s.edit().putInt("failures", 0).putLong("last_attempt", 0L).apply()
        }
        val consentNeeded = installed && runCatching { backend.consentIntent() != null }.getOrDefault(false)
        val holder = if (installed) slotHolder(ctx, up, consentNeeded) else null
        val d = MeshSupervisor.decide(MeshSupervisor.Input(
            policy = policy, engineInstalled = installed, up = up,
            hasKey = prefs.interfacePrivateKey.isNotBlank(),
            consentNeeded = consentNeeded,
            net = netKind(ctx), nowMs = now,
            lastAttemptMs = s.getLong("last_attempt", 0L), failures = s.getInt("failures", 0),
            slotHeldBy = holder,
        ))
        var retryAt = d.retryAtMs
        if (d.connect) {
            s.edit().putLong("last_attempt", now).apply()
            var result: Tunnel.State? = null
            val failure = runCatching {
                MeshTransport.connect(ctx) { backend.setState(WgState.tunnel, Tunnel.State.UP, prefs.toTunnelConfig()).also { result = it } }
            }.exceptionOrNull()
            up = result == Tunnel.State.UP
            prefs.tunnelEnabled = up
            if (up) {
                s.edit().putInt("failures", 0).apply()
                note(ctx, "reconnected")
            } else {
                val f = s.getInt("failures", 0) + 1
                s.edit().putInt("failures", f).apply()
                retryAt = now + MeshSupervisor.backoffMs(f)
                note(ctx, "reconnect failed (${failure?.message ?: "tunnel stayed down"}) - retry in ${MeshSupervisor.backoffMs(f) / 1000}s")
            }
        } else {
            note(ctx, MeshSupervisor.explain(d.why, holder))
            if (d.why == MeshSupervisor.Why.UP) s.edit().putInt("failures", 0).apply()
        }

        // The mesh-down alert: debounced, with the hub / peers named, withdrawn by itself on recovery.
        val peers = prefs.peers()
        val stats = if (up) runCatching { backend.getStatistics(WgState.tunnel) }.getOrNull() else null
        val reading = MeshDownModel.Reading(
            wanted = policy.wanted, engineInstalled = installed, up = up, tunnel = WgState.tunnel.name,
            peers = peers.map { p ->
                val hs = runCatching { stats?.peer(Key.fromBase64(p.publicKey))?.latestHandshakeEpochMillis() }.getOrNull() ?: 0L
                MeshDownModel.PeerReading(p.name.ifBlank { p.publicKey.take(8) }, if (hs <= 0L) null else ((now - hs) / 1000).coerceAtLeast(0))
            },
            staleS = 300L,
        )
        val before = MeshDownModel.State(s.getLong("down_since", 0L), s.getBoolean("raised", false))
        val (after, effect) = MeshDownModel.step(before, reading, now)
        s.edit().putLong("down_since", after.downSinceMs).putBoolean("raised", after.raised).apply()
        when (effect) {
            MeshDownModel.Effect.RAISE -> MeshDownAlert.raise(ctx, reading, after.downSinceMs)
            MeshDownModel.Effect.CLEAR -> MeshDownAlert.clear(ctx)
            MeshDownModel.Effect.NONE -> {}
        }

        // Look again: at the backoff or the end of the debounce, else every WATCH_MS while the mesh is wanted but NOT healthy.
        // A healthy tunnel has no self-kick: the network / VPN callbacks and the 15-minute job wake it when that changes.
        val healthy = up && d.why == MeshSupervisor.Why.UP && !after.raised && after.downSinceMs == 0L
        val debounceAt = if (!after.raised && after.downSinceMs > 0) after.downSinceMs + MeshDownModel.DEBOUNCE_MS else null
        nextLookMs(policy.wanted, installed, healthy, retryAt, debounceAt, now)?.let { kick(ctx, it) }
    }
}

/** The backstop: a process that died with nothing to wake it is looked at again here (WorkManager survives it). */
class MeshSupervisorWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        MeshSupervisorRunner.start(applicationContext)
        MeshSupervisorRunner.tick(applicationContext)
        return Result.success()
    }
}

/** After a reboot the mesh comes back unless the owner switched Connect on boot off (then the reboot is a Disconnect). */
class MeshBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val p = MeshIntent.policy(context)
        if (!MeshSupervisor.wantedAfterBoot(p)) MeshIntent.setWanted(context, false)
        MeshSupervisorRunner.start(context)
    }
}
