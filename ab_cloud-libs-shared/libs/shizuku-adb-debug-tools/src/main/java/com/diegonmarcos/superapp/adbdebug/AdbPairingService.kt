package com.diegonmarcos.superapp.adbdebug

import com.diegonmarcos.superapp.perms.AdbMdns
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.core.SilentChannels
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Shizuku pairing flow, exactly — Shizuku's `AdbPairingService`:
 *
 *  1. discover `_adb-tls-pairing._tcp` over mDNS ([AdbMdns]) — the port in
 *     the "Pair device with pairing code" dialog, never typed by the user;
 *  2. a persistent notification whose "Enter pairing code" action is a
 *     [RemoteInput]: the user opens the Android dialog, reads the code, types
 *     it into the shade — this app never has to be in front;
 *  3. pair in THIS foreground service (SPAKE2 over TLS, libadb through
 *     [AdbManager] — bundled Conscrypt for the exported keying material);
 *  4. then discover `_adb-tls-connect._tcp`, connect with the now-trusted key,
 *     prove the session with a shell round trip, and run whatever the app
 *     hangs on [onConnected]. The connected session IS the shell channel:
 *     nothing further is started through it.
 *
 * One deliberate difference: Shizuku pairs with 127.0.0.1; we pair with the
 * address the advert resolves to, because [EmbeddedAdbChannel] pins its
 * socket to the Wi-Fi Network (WireGuard coexistence) and loopback is not
 * reachable through a bound Network.
 */
class AdbPairingService : Service() {

    private var mdns: AdbMdns? = null
    @Volatile private var host: InetAddress? = null
    @Volatile private var port = 0
    @Volatile private var pendingCode: String? = null
    @Volatile private var busy = false
    private var channel = CHANNEL_ID

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Silent in Cloud Store (a new "_silent_v2" channel, LOW: no sound, no pop-up), unchanged elsewhere.
        channel = SilentChannels.ensure(this, CHANNEL_ID, "Wireless debugging pairing", NotificationManager.IMPORTANCE_HIGH)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_CODE -> {
                startForeground(NOTIF_ID, notification(statusLine()))
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()
                    ?: intent.getStringExtra(KEY_CODE)
                if (code.isNullOrBlank()) show("No code entered — tap \"Enter pairing code\" again")
                else submit(code.trim())
            }
            else -> {
                startForeground(NOTIF_ID, notification(statusLine()))
                PairingClaim.take()   // the other adb app (AdbPeers) refuses to pair while this one holds the window
                intent?.getStringExtra(KEY_CODE)?.takeIf { it.isNotBlank() }?.let { pendingCode = it.trim() }
                startDiscovery()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        PairingClaim.release()
        mdns?.stop()
        super.onDestroy()
    }

    private fun startDiscovery() {
        mdns?.stop()
        mdns = AdbMdns(this, AdbMdns.TLS_PAIRING) { h, p ->
            host = h; port = p
            show(statusLine())
            pendingCode?.let { submit(it) }
        }.also { it.start() }
    }

    private fun statusLine(): String = when {
        port == 0 -> "Open Developer options ▸ Wireless debugging ▸ \"Pair device with pairing code\", then enter the code here"
        else -> "Pairing service found on port $port — enter the 6-digit code"
    }

    private fun submit(code: String) {
        if (busy) return
        val h = host
        if (h == null || port == 0) {
            pendingCode = code
            show("Code saved — waiting for the pairing dialog (open \"Pair device with pairing code\")")
            return
        }
        busy = true
        pendingCode = null
        Thread { pairConnectStart(h, port, code) }.start()
    }

    private fun pairConnectStart(h: InetAddress, p: Int, code: String) {
        try {
            show("Pairing on port $p…")
            val (paired, pairMsg) = EmbeddedAdbChannel.pair(this, h.hostAddress ?: h.hostName, p, code)
            if (!paired) {
                show("Pairing failed: $pairMsg — open the dialog again and re-enter the code")
                port = 0; host = null
                startDiscovery()
                return
            }
            mdns?.stop()
            show("Paired — looking for the wireless-debugging service…")
            val found = CountDownLatch(1)
            var cHost: InetAddress? = null
            var cPort = 0
            val connectMdns = AdbMdns(this, AdbMdns.TLS_CONNECT) { ch, cp -> cHost = ch; cPort = cp; found.countDown() }
            connectMdns.start()
            found.await(CONNECT_DISCOVERY_MS, TimeUnit.MILLISECONDS)
            connectMdns.stop()
            val ch = cHost
            if (ch == null) {
                finish("Paired, but no _adb-tls-connect service appeared — is Wireless debugging still ON?")
                return
            }
            EmbeddedAdbChannel.disconnect(this)
            val (connected, connectMsg) = EmbeddedAdbChannel.connect(this, ch.hostAddress ?: ch.hostName, cPort)
            if (!connected) { finish("Paired, but connect failed: $connectMsg"); return }
            // The connected session IS the shell channel: nothing further is started through it.
            val live = EmbeddedAdbChannel.probe(this)
            runCatching { onConnected?.invoke(applicationContext) }
            finish(if (live) "Paired and connected — shell channel up (round trip answered)"
                   else "Paired and connected, but the shell round trip got no answer — tap Reconnect")
        } finally {
            busy = false
        }
    }

    private fun show(text: String) {
        ChannelLog.shared.add("pair", text)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notification(text))
    }

    /** Done (either way): leave a plain, dismissible result and go away. */
    private fun finish(text: String) {
        ChannelLog.shared.add("pair", text)
        stopForeground(STOP_FOREGROUND_REMOVE)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(
            RESULT_ID,
            NotificationCompat.Builder(this, channel)
                .apply { SilentChannels.quiet(this@AdbPairingService, this) }
                .setSmallIcon(applicationInfo.icon)
                .setContentTitle("Wireless debugging")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build())
        stopSelf()
    }

    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val reply = PendingIntent.getForegroundService(
            this, 1, Intent(this, AdbPairingService::class.java).setAction(ACTION_CODE), flags)
        val stop = PendingIntent.getService(
            this, 2, Intent(this, AdbPairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val input = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build()
        return NotificationCompat.Builder(this, channel)
            .apply { SilentChannels.quiet(this@AdbPairingService, this) }
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Wireless debugging pairing")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(NotificationCompat.Action.Builder(0, "Enter pairing code", reply)
                .addRemoteInput(input).setAllowGeneratedReplies(false).build())
            .addAction(0, "Cancel", stop)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "adb_pairing"
        private const val NOTIF_ID = 6073
        private const val RESULT_ID = 6074
        private const val ACTION_START = "com.diegonmarcos.superapp.adbdebug.PAIR_START"
        private const val ACTION_CODE = "com.diegonmarcos.superapp.adbdebug.PAIR_CODE"
        private const val ACTION_STOP = "com.diegonmarcos.superapp.adbdebug.PAIR_STOP"
        private const val KEY_CODE = "pairing_code"
        private const val CONNECT_DISCOVERY_MS = 20_000L

        /** What the consuming app runs once the channel is up (its privileged
         *  plane). Set once at app start; the service outlives any screen. */
        @JvmStatic @Volatile var onConnected: ((Context) -> Unit)? = null

        /** Post the pairing notification and start discovering the pairing
         *  service. [code] lets a script hand the code over without the shade;
         *  the port is still discovered, never given. */
        @JvmStatic
        fun start(ctx: Context, code: String? = null) {
            val app = ctx.applicationContext
            val i = Intent(app, AdbPairingService::class.java).setAction(ACTION_START)
            if (!code.isNullOrBlank()) i.putExtra(KEY_CODE, code)
            // One phone has ONE pairing window. If the other adb app (SuperApp / Cloud Store) is already waiting for the code,
            // two services would race for it: refuse here with a plain reason instead. The peer read crosses a process
            // boundary, so it runs off the caller's (main) thread; an app that is not installed or not answering never blocks.
            Thread {
                val holder = runCatching { AdbPeerReader.pairingHeldElsewhere(app) }.getOrNull()
                if (holder != null) {
                    val why = AdbPeers.pairingRefusal(holder)
                    ChannelLog.shared.add("pair", why)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(app, why, android.widget.Toast.LENGTH_LONG).show()
                    }
                } else {
                    runCatching { ContextCompat.startForegroundService(app, i) }
                        .onFailure { ChannelLog.shared.add("pair", "could not start the pairing service: ${it.message}") }
                }
            }.start()
        }
    }
}
