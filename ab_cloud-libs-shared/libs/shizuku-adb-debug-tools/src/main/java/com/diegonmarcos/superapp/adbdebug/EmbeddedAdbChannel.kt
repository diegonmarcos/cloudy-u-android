package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * PRIMARY, fully self-contained channel — the embedded ADB client.
 *
 * Pairs with the phone's own Wireless-Debugging adbd on 127.0.0.1 ([pair])
 * then connects ([connect]); thereafter [exec] runs commands via the
 * `exec:` service as uid 2000, so dumpsys/usb/sysfs all work — no
 * third-party Shizuku app, no PC. This is the "we ARE Shizuku" channel.
 *
 * Non-rooted reality: Wireless Debugging + the pairing code are entered
 * once per boot (Shizuku and LADB require the same). Only root removes it.
 */
object EmbeddedAdbChannel : ShellChannel {

    override fun name(): String = "embedded-adb"

    override fun isReady(ctx: Context): Boolean =
        runCatching { AdbManager.getInstance(ctx).isConnected }.getOrDefault(false)

    /**
     * Run a command and capture its stdout. Uses the `exec:` service, NOT
     * `shell:`: `shell:` opens an interactive PTY session that never EOFs,
     * so a plain readText() blocks forever; `exec:` runs the command, pipes
     * raw stdout, and closes the stream when it exits — exactly what we want
     * for one-shot capture. A bounded reader thread guarantees the caller
     * (the synchronous DevControlServer handler) can never hang even if a
     * command misbehaves.
     */
    override fun exec(ctx: Context, command: String): String? =
        execStream(ctx, command, stdin = null, timeoutMs = EXEC_TIMEOUT_MS)

    /**
     * A trivial round trip on a SHORT leash. Connection state is not
     * usability: the channel can be "connected" and still never answer, and
     * discovering that by way of a 29 MB install that hangs for the whole
     * transfer window costs the batch its entire wall clock.
     */
    override fun probe(ctx: Context): Boolean =
        execStream(ctx, "echo shell-ok", stdin = null, timeoutMs = PROBE_TIMEOUT_MS)
            ?.contains("shell-ok") == true

    /**
     * `exec:` is bidirectional, so the same stream that returns stdout can
     * carry the file in — which is exactly how `adb install` streams an APK.
     */
    override fun execWithStdin(ctx: Context, command: String, stdin: java.io.File): String? =
        execStream(ctx, command, stdin, TRANSFER_TIMEOUT_MS)

    private fun execStream(
        ctx: Context, command: String, stdin: java.io.File?, timeoutMs: Long,
    ): String? = runCatching {
        val stream = AdbManager.getInstance(ctx).openStream("exec:$command")
        // Read INCREMENTALLY into a buffer on a worker thread so that, on
        // timeout (or a stream that never EOFs), we return whatever ARRIVED
        // instead of discarding it. Large dumps (full getprop / settings
        // list / dumpsys batterystats) stream in chunks; the old readText()
        // returned "" if the whole thing didn't finish inside the window.
        val acc = java.io.ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                val input = stream.openInputStream()
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(acc) { acc.write(buf, 0, n) }
                }
            }
        }
        reader.start()
        // Feed stdin on its own thread: the command may already be writing
        // stdout while we are still pushing bytes in, and a single-threaded
        // write-then-read deadlocks as soon as the output buffer fills.
        val writer = stdin?.let { file ->
            Thread {
                runCatching {
                    val out = stream.openOutputStream()
                    file.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    // Deliberately NOT closed: closing the output half closes
                    // the adb stream, and the command still has stdout to
                    // deliver. Size-prefixed protocols (`pm install -S <n>`)
                    // do not need EOF on stdin.
                    out.flush()
                }
            }.also { it.start() }
        }
        reader.join(timeoutMs)
        val timedOut = reader.isAlive
        if (timedOut) {
            runCatching { stream.close() } // unblock the read → thread exits
            reader.interrupt()
            reader.join(500)
        }
        writer?.let { if (it.isAlive) it.interrupt(); it.join(500) }
        val text = synchronized(acc) { acc.toString("UTF-8") }
        if (timedOut) "$text\n[…truncated at ${timeoutMs}ms — ${text.length} bytes]" else text
    }.getOrNull()

    private const val PREFS = "adb_shell"
    private const val KEY_PAIRED = "paired"

    /** adbd keeps the trust, so the only record of "this app was paired" is ours: set on
     *  the first successful pair or connect, never cleared (a revoked key shows as down). */
    private fun markPaired(ctx: Context) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAIRED, true).apply() }
    }

    private const val KEY_PORT = "last_connect_port"

    /** Where the live session points: "host:port", or just "host" when the port was found over mDNS and not kept. Null when not connected. */
    fun endpoint(ctx: Context): String? {
        if (!isReady(ctx)) return null
        val host = runCatching { AdbManager.getInstance(ctx).hostAddress }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val port = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PORT, 0) }.getOrDefault(0)
        return if (port > 0) "$host:$port" else host
    }

    /** adbd answered "pairing is required": it no longer trusts this app's key (revoked, or the key
     *  was regenerated). The record goes, so the page says "not paired" instead of a stale "paired". */
    internal fun forgetPaired(ctx: Context) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PAIRED).apply() }
    }

    private fun rejected(ctx: Context, e: Throwable) {
        if (e.message.orEmpty().contains("pairing is required", ignoreCase = true)) forgetPaired(ctx)
    }

    fun everPaired(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAIRED, false)
    }.getOrDefault(false)

    private const val PROBE_TIMEOUT_MS = 4_000L
    private const val EXEC_TIMEOUT_MS = 25_000L
    /** An APK is tens of MB and `pm` only answers once the commit has landed. */
    private const val TRANSFER_TIMEOUT_MS = 180_000L

    override fun status(ctx: Context): String =
        if (isReady(ctx)) "Connected — embedded adb to 127.0.0.1 (shell uid 2000)"
        else "Not paired/connected — POST /api/adb/pair then /api/adb/connect (Wireless Debugging)"

    /**
     * Pair with the local adbd using the 6-digit Wireless-Debugging code.
     * [host] is the IP shown in the pairing dialog. Android binds the
     * pairing daemon to the Wi-Fi interface, NOT loopback — so 127.0.0.1
     * gets ECONNREFUSED; pass the device's own shown IP (e.g. 10.0.0.9),
     * which the kernel `local` route table delivers locally ahead of any
     * wg0 route. [port] is the PAIRING port (from the pairing-code dialog).
     */
    fun pair(ctx: Context, host: String, port: Int, code: String): Pair<Boolean, String> = runCatching {
        val ok = onWifi(ctx) { AdbManager.getInstance(ctx).pair(host, port, code) }
        if (ok) markPaired(ctx)
        ok to (if (ok) "paired" else "pair returned false")
    }.getOrElse { false to "pair failed: ${it.message}" }

    /**
     * Connect to the local adbd. [host] is the IP from the main Wireless-
     * debugging screen; [port] is the CONNECT port (distinct from the
     * pairing port).
     */
    fun connect(ctx: Context, host: String, port: Int): Pair<Boolean, String> = runCatching {
        val ok = onWifi(ctx) { AdbManager.getInstance(ctx).connect(host, port) }
        if (ok) { markPaired(ctx); runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PORT, port).apply() } }
        ok to (if (ok) "connected" else "connect returned false")
    }.getOrElse { rejected(ctx, it); false to "connect failed: ${it.message}" }

    /**
     * Close the client's connection. After a Wi-Fi change the old socket can
     * still report connected while the adbd behind it is gone, and
     * [autoConnect] short-circuits on "already connected" — so a reconnect has
     * to start from here. The pairing is adbd's and is untouched.
     */
    fun disconnect(ctx: Context) {
        runCatching { AdbManager.getInstance(ctx).disconnect() }
    }

    /**
     * Auto-discover the local adbd via mDNS (`_adb-tls-connect._tcp`, which
     * Wireless Debugging advertises) and connect — NO manual connect port.
     * libadb's autoConnect runs the discovery + connect; we Wi-Fi-bind it so
     * the mDNS query + socket go over wlan0, not the WG tunnel. Requires the
     * device already paired (cert trusted) + Wireless Debugging ON. This is
     * what makes reconnect-after-update one-tap (or automatic on app start).
     */
    fun autoConnect(ctx: Context): Pair<Boolean, String> = runCatching {
        if (isReady(ctx)) return@runCatching true to "already connected"
        val ok = onWifi(ctx) { AdbManager.getInstance(ctx).autoConnect(ctx, 10_000) }
        if (ok) { markPaired(ctx); runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PORT).apply() } }
        ok to (if (ok) "auto-connected via mDNS" else "no _adb-tls-connect service found — Wireless Debugging ON + paired?")
    }.getOrElse { rejected(ctx, it); false to "autoconnect failed: ${it.message}" }

    /**
     * [autoConnect], proven by a shell round trip. "Connected" is a socket flag that stays true on a dead
     * link (the adbd behind it went away with a Wi-Fi change), and [autoConnect] short-circuits on it: so a
     * session that does not answer `echo` is closed and discovered again, and the result is true only when
     * the new one answers. This is the explicit Connect / Reconnect path of the adb modes.
     */
    fun connectLive(ctx: Context): Pair<Boolean, String> {
        if (isReady(ctx)) {
            if (probe(ctx)) return true to "already connected"
            disconnect(ctx)
        }
        val (ok, msg) = autoConnect(ctx)
        if (!ok) return false to msg
        return if (probe(ctx)) true to msg else false to "$msg, but the shell round trip got no answer"
    }

    /**
     * [autoConnect] for every IMPLICIT caller (app start, boot, keep-alive, a privileged tool that found no
     * channel): does nothing while the app's auto-arm switch is off ([ChannelAutoArm]). A tap on the ADB Shell
     * page is explicit and calls [autoConnect] itself.
     */
    fun autoArm(ctx: Context): Pair<Boolean, String> =
        if (ChannelAutoArm.enabled(ctx)) autoConnect(ctx) else false to ChannelAutoArm.OFF_MSG

    /**
     * Run [block] with the process temporarily pinned to the Wi-Fi
     * (non-VPN) network, so the socket libadb opens bypasses the WireGuard
     * tunnel. This is what makes pairing work with WG ON: the device's
     * own Wireless-Debugging IP (e.g. 10.0.0.9) collides with the WG mesh
     * subnet (10.0.0.0/24), so by default the app's connect to it is
     * captured by wg0's allowedIPs and RSTs. Binding to the Wi-Fi Network
     * routes it straight to the local adbd instead. The persistent
     * AdbConnection socket is created inside this window, so it stays
     * pinned to Wi-Fi; subsequent exec() streams multiplex over it.
     * Restores the previous binding in finally so the rest of the app
     * keeps its normal (WG-routed) networking.
     */
    private fun <T> onWifi(ctx: Context, block: () -> T): T {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return block()
        val wifi = cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null &&
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } ?: return block()
        val prev = cm.boundNetworkForProcess
        return try {
            cm.bindProcessToNetwork(wifi)
            block()
        } finally {
            cm.bindProcessToNetwork(prev)
        }
    }
}
