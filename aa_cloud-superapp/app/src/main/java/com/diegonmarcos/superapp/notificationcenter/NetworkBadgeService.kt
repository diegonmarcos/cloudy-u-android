package com.diegonmarcos.superapp.notificationcenter

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.network.NetworkBadgeModel
import com.diegonmarcos.superapp.network.MeshTransport
import com.diegonmarcos.superapp.network.NetworkBadgeModel.Act
import com.diegonmarcos.superapp.network.FleetDns
import com.diegonmarcos.superapp.network.WgState
import com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge
import org.json.JSONObject
import com.wireguard.android.backend.Tunnel
import com.wireguard.crypto.Key
import java.util.concurrent.Executors

/**
 * The persistent "Mesh" badge (one of the two "Network" group badges, with Data): the mesh's state in the shade.
 *
 * ONE notification, and it is the foreground-service notification: nothing
 * else in the fleet posts one for the VPN (the engine APK's VpnService never
 * calls startForeground), so there is nothing to merge into and nothing to
 * duplicate. What it shows is [NetworkBadgeModel]'s card; what it reads comes
 * through [WgState.backend], i.e. INetBackend - no WireGuard logic lives here.
 *
 * Refresh is EVENT-DRIVEN: the app's own connect/disconnect (the
 * [WgState.stateListener] hook) and the system's VPN-transport network
 * callback, which also catches the engine dropping the tunnel or another app
 * taking the slot. The only timer runs while connected AND the screen is on,
 * every [TICK_MS] (5 min); the screen-on edge refreshes at once (handshake ages
 * are computed from the handshake timestamp at that moment, so nothing needs to
 * tick while the screen is off); disconnected, nothing is scheduled at all.
 * Binder calls block, so all of it runs on one worker thread. It stays a
 * foreground service because it is the only keep-alive indicator of the VPN.
 *
 * Without the Android 13+ POST_NOTIFICATIONS grant the badge posts nothing and
 * polls nothing (the shade would hide it anyway); BadgeServices names the
 * missing grant in the Notify pane.
 */
class NetworkBadgeService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "net-badge").apply { isDaemon = true } }
    private var tick: Runnable? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var debounce: Runnable? = null
    private var screenRx: BroadcastReceiver? = null

    // Worker-thread state, for uptime and for the last action's refusal.
    private var wasConnected: Boolean? = null
    private var upSince = 0L
    private var upExact = false
    private var note = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startForeground(NOTIF_ID, build(placeholder()))
        WgState.stateListener = { requestRefresh() }
        watchVpnTransport()
        watchScreen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> worker.execute { toggle(); refreshNow() }
            else -> requestRefresh() // refresh, renotify (swipe on 14+), restart
        }
        return START_STICKY
    }

    override fun onDestroy() {
        WgState.stateListener = null
        screenRx?.let { runCatching { unregisterReceiver(it) } }
        netCb?.let { runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } }
        tick?.let(main::removeCallbacks)
        debounce?.let(main::removeCallbacks)
        worker.shutdownNow()
        NotifyGroups.release(this, BADGE_ID)
        super.onDestroy()
    }

    // ── events ───────────────────────────────────────────────────────────

    private fun watchVpnTransport() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { requestRefresh() }
            override fun onLost(network: Network) { requestRefresh() }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        if (runCatching { cm.registerNetworkCallback(req, cb) }.isSuccess) netCb = cb
    }

    /** Screen off cancels the tick; screen on refreshes once (fresh ages, rx/tx) and, if connected, resumes it. */
    private fun watchScreen() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON) requestRefresh()
                else scheduleTick(false)
            }
        }
        screenRx = r
        ContextCompat.registerReceiver(this, r,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun interactive(): Boolean = runCatching { (getSystemService(POWER_SERVICE) as PowerManager).isInteractive }.getOrDefault(true)

    /** Coalesce a burst of events (a tunnel coming up fires several) into one read. */
    private fun requestRefresh() = main.post {
        debounce?.let(main::removeCallbacks)
        val r = Runnable { runCatching { worker.execute { refreshNow() } } }
        debounce = r
        main.postDelayed(r, DEBOUNCE_MS)
    }

    /** Light periodic refresh: scheduled only after a read that found the tunnel up, and only while the screen is on. */
    private fun scheduleTick(connected: Boolean) {
        main.post {
            tick?.let(main::removeCallbacks)
            tick = null
            if (!shouldTick(connected, interactive())) return@post
            val r = Runnable { runCatching { worker.execute { refreshNow() } } }
            tick = r
            main.postDelayed(r, TICK_MS)
        }
    }

    // ── worker thread ────────────────────────────────────────────────────

    private fun refreshNow() {
        if (!canPost(this)) return
        val snap = gather()
        scheduleTick(snap.connected)
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(snap))
        }
    }

    private fun gather(): NetworkBadgeModel.Snapshot {
        val backend = WgState.backend(this)
        val prefs = WgState.prefs(this)
        val installed = backend.isEngineInstalled()
        val up = installed && runCatching { backend.getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)
        val stats = if (up) runCatching { backend.getStatistics(WgState.tunnel) }.getOrNull() else null
        val now = System.currentTimeMillis()
        val dns = FleetDns.readAndroid(this)

        if (up && wasConnected != true) { upSince = now; upExact = wasConnected == false }
        if (!up) { upSince = 0; upExact = false; }
        wasConnected = up
        if (up) note = ""

        val rows = prefs.peers().map { p ->
            val st = runCatching { stats?.peer(Key.fromBase64(p.publicKey)) }.getOrNull()
            NetworkBadgeModel.PeerRow(
                name = p.name.ifBlank { p.publicKey.take(8).let { if (it.isEmpty()) "" else "$it…" } },
                endpoint = p.endpoint, allowedIps = p.allowedIps,
                lastHandshakeMs = st?.latestHandshakeEpochMillis() ?: 0L,
                rx = st?.rxBytes() ?: 0L, tx = st?.txBytes() ?: 0L,
            )
        }
        return NetworkBadgeModel.Snapshot(
            engineInstalled = installed, connected = up,
            tunnel = WgState.tunnel.name, profile = prefs.activeProfile,
            addresses = split(prefs.interfaceAddress), dns = split(prefs.interfaceDns),
            peers = rows,
            resolvers = dns.activeServers, resolversOnVpn = dns.onVpn,
            bridge = bridgeLine(), privateDns = NetworkBadgeModel.privateDnsLine(
                dns.mode, dns.specifier, dns.privateDnsActive, dns.privateDnsServer),
            alwaysOn = installed && runCatching { backend.isAlwaysOn }.getOrDefault(false),
            upSinceMs = upSince, upSinceExact = upExact, nowMs = now, note = note,
            path = if (up) MeshTransport.current(this)?.path?.label.orEmpty() else "",
            pathDetail = if (up) MeshTransport.current(this)?.detail.orEmpty() else "",
        )
    }

    /** The fleet DNS bridge's own state (FleetDnsBridge.stateJson), as one line. */
    private fun bridgeLine(): String = runCatching {
        val o = JSONObject(FleetDnsBridge.stateJson())
        NetworkBadgeModel.bridgeLine(
            o.optBoolean("listening"), o.optInt("port"),
            o.optString("route").takeIf { it.isNotEmpty() && it != "null" },
            o.optString("why").takeIf { it.isNotEmpty() && it != "null" } ?: o.optString("bind").takeIf { it.isNotEmpty() && it != "null" })
    }.getOrDefault("")

    /** Connect/Disconnect through the same calls Configs > Mesh makes. */
    private fun toggle() {
        val backend = WgState.backend(this)
        val prefs = WgState.prefs(this)
        if (!backend.isEngineInstalled()) { note = "Install Cloud-Lib-Net-Wg to connect"; return }
        val up = runCatching { backend.getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)
        com.diegonmarcos.superapp.network.MeshIntent.setWanted(this, !up) // #1120 Disconnect is sticky, Connect re-arms the reconnecting
        if (up) {
            runCatching { backend.setState(WgState.tunnel, Tunnel.State.DOWN, null) }
            MeshTransport.release(this)
            prefs.tunnelEnabled = false
            note = ""
        } else {
            // The same fallback ladder Configs > Mesh > Connect walks (Direct UDP, then the TLS-443 relay).
            var after: Tunnel.State? = null
            runCatching { MeshTransport.connect(this) { backend.setState(WgState.tunnel, Tunnel.State.UP, prefs.toTunnelConfig()).also { after = it } } }
                .onFailure { note = "Connect failed: ${it.message ?: it.javaClass.simpleName}" }
            prefs.tunnelEnabled = after == Tunnel.State.UP
            // A first connect needs the system VPN consent dialog, which a
            // notification action cannot raise: send the owner to the page.
            if (after != Tunnel.State.UP && note.isEmpty()) note = "Not connected - open More (VPN consent?)"
        }
    }

    // ── drawing ──────────────────────────────────────────────────────────

    private fun placeholder() = NetworkBadgeModel.Snapshot(
        engineInstalled = true, connected = false, tunnel = WgState.tunnel.name, nowMs = System.currentTimeMillis())

    private fun build(s: NetworkBadgeModel.Snapshot): Notification {
        val card = NetworkBadgeModel.card(s)
        val pinned = BadgeServices.pinned(this, BADGE_ID)
        val more = openMeshPage(this)
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0A0A0A.toInt())
            .setContentTitle(card.title)
            .setContentText(card.text)
            .setSubText("Cloud SA - Mesh")
            .setStyle(NotificationCompat.BigTextStyle().bigText(card.expanded))
            .setContentIntent(more)
            .setOngoing(pinned)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // mesh IPs and endpoints stay off the lockscreen
            .apply { if (pinned) setDeleteIntent(BadgeServices.repostOnDismiss(this@NetworkBadgeService, NOTIF_ID)) }
        for (a in card.actions) {
            val pi = when (a.act) {
                Act.ALWAYS_ON -> PendingIntent.getActivity(this, RC_ALWAYS_ON,
                    Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Act.TOGGLE -> servicePi(this, ACTION_TOGGLE, RC_TOGGLE)
                Act.MORE -> more
            }
            BadgeModel.add(b, BadgeModel.Action(a.act.name.lowercase(), when (a.act) {
                Act.ALWAYS_ON -> R.drawable.ic_act_shield
                Act.TOGGLE -> R.drawable.ic_act_power
                Act.MORE -> R.drawable.ic_act_more
            }, a.label, pi))
        }
        return NotifyGroups.attach(this, b, BADGE_ID).build()
            .apply { if (pinned) flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }

    companion object {
        const val NOTIF_ID = 7716
        const val CHANNEL_ID = "network_mesh"
        /** This badge's id in build.json::ui.notification_center.producers. */
        const val BADGE_ID = "network_mesh"

        private const val ACTION_TOGGLE = "com.diegonmarcos.superapp.network.TOGGLE"
        private const val RC_ALWAYS_ON = 0x4E31
        private const val RC_TOGGLE = 0x4E32
        private const val RC_MORE = 0x4E33
        internal const val TICK_MS = 5 * 60_000L

        /** The tick runs only while the tunnel is up and the screen is on. */
        internal fun shouldTick(connected: Boolean, interactive: Boolean): Boolean = connected && interactive
        private const val DEBOUNCE_MS = 400L

        /** The deep link of the Cloud Mesh page (build.json: section:wg, page:config/wg). */
        private const val MESH_PAGE = "page:config/wg"

        private fun split(csv: String) = csv.split(',').map(String::trim).filter(String::isNotEmpty)

        /** Android 13+ gates the shade on POST_NOTIFICATIONS; below it, always allowed. */
        fun canPost(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun openMeshPage(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, RC_MORE,
            Intent(ctx, MainActivity::class.java)
                .putExtra("shortcut_action", MESH_PAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun servicePi(ctx: Context, action: String, rc: Int): PendingIntent =
            PendingIntent.getForegroundService(
                ctx, rc, Intent(ctx, NetworkBadgeService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Network", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Mesh state: connection, IP, peers, traffic."
                    setShowBadge(false)
                },
            )
        }
    }
}
