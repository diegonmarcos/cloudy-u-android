package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Shizuku as this device sees it, and the one tap that moves it forward. */
object ShizukuStatus {
    private const val PKG = "moe.shizuku.privileged.api"

    fun installed(ctx: Context): Boolean = ctx.packageManager.getLaunchIntentForPackage(PKG) != null

    fun current(ctx: Context): ShizukuState =
        ShizukuState.of(installed(ctx), ShizukuAdb.isAvailable(), ShizukuAdb.isGranted())

    /** Permission needed -> ask for it; otherwise open the Shizuku app (where its service is started). */
    fun act(ctx: Context): String = when (current(ctx)) {
        ShizukuState.PERMISSION_NEEDED -> { ShizukuAdb.requestPermission { }; "Approve the Shizuku prompt." }
        ShizukuState.NOT_INSTALLED -> "Shizuku is not installed."
        else -> ctx.packageManager.getLaunchIntentForPackage(PKG)?.let {
            runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; "Opened Shizuku."
        } ?: "Shizuku is not installed."
    }
}

/** Reads every layer off the device into [ChannelFacts]. Blocking: call off the main thread. */
object ChannelReader {

    fun read(ctx: Context, connecting: Boolean = false, now: Long = System.currentTimeMillis()): ChannelFacts {
        val app = ctx.applicationContext
        // A wedged server accepts and never answers: probe (2 s window) before any exec.
        val owns = ChannelModePrefs.ownsServer()
        val bridge = RishBridge.providers.contains("com.diegonmarcos.superapp")
        val serverUp = owns && LocalShellChannel.isReady(app) && LocalShellChannel.probe(app)
        val uid = if (serverUp) ShellUid.of(LocalShellChannel.exec(app, "id")) else null
        var pid: String? = null
        var up: String? = null
        if (serverUp) {
            val nice = AdbShellBootstrap.niceName()
            val line = LocalShellChannel.exec(app,
                "p=\$(pgrep -f '${nice.dropLast(1)}[${nice.last()}]' | head -1); [ -n \"\$p\" ] && echo \"\$p \$(ps -o etime= -p \$p)\"")?.trim()
            if (!line.isNullOrBlank()) {
                pid = line.substringBefore(' ').takeIf { p -> p.all { it.isDigit() } && p.isNotEmpty() }
                up = line.substringAfter(' ', "").trim().ifBlank { null }
            }
        }
        val installed = ShizukuStatus.installed(app)
        val running = ShizukuAdb.isAvailable()
        return ChannelFacts(
            mode = ChannelModePrefs.current(app),
            probedAt = now,
            devOptions = devOptions(app),
            wirelessDebug = WirelessDebugging.isOn(app),
            onWifi = onWifi(app),
            notificationsAllowed = runCatching { NotificationManagerCompat.from(app).areNotificationsEnabled() }.getOrNull(),
            adbPaired = EmbeddedAdbChannel.everPaired(app) || EmbeddedAdbChannel.isReady(app),
            // Connected = a live `echo` round trip just now (4 s leash), never the socket flag (it stays true on a dead link).
            adbConnected = EmbeddedAdbChannel.isReady(app) && EmbeddedAdbChannel.probe(app),
            adbEndpoint = EmbeddedAdbChannel.endpoint(app),
            serverPort = AdbShellBootstrap.port(),
            serverRunning = serverUp,
            serverUid = uid,
            serverUptime = up,
            serverPid = pid,
            shizukuInstalled = installed,
            shizukuRunning = running,
            shizukuGranted = ShizukuAdb.isGranted(),
            shizukuVersion = if (running) ShizukuAdb.version() else null,
            connecting = connecting,
            ownsServer = owns,
            bridgeDeclared = bridge,
            bridgeUp = bridge && SuperappBridgeChannel.isReady(app),
        )
    }

    private fun devOptions(ctx: Context): Boolean? = runCatching {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    }.getOrNull()

    private fun onWifi(ctx: Context): Boolean? = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }.getOrNull()
}

/** `uid=2000(shell) gid=...` -> "uid 2000"; anything else null. */
object ShellUid {
    fun of(idOutput: String?): String? =
        idOutput?.let { Regex("""uid=(\d+)""").find(it)?.groupValues?.get(1) }?.let { "uid $it" }

    /** The exit status a command printed with `; echo rc=$?`, or null. */
    fun rc(out: String?): Int? = out?.let { Regex("""rc=(\d+)\s*$""").find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
}

/**
 * The actions of the ADB Shell page, each blocking and each logging its outcome (and the commands'
 * exit codes) to [ChannelLog.shared]. Returns the line the page shows.
 */
object ChannelOps {
    private val log get() = ChannelLog.shared

    fun disconnect(ctx: Context): String {
        EmbeddedAdbChannel.disconnect(ctx)
        log.add("action", "disconnect: embedded adb session closed (the pairing is kept)")
        return "Embedded adb disconnected; the pairing is kept."
    }

    fun reconnect(ctx: Context): String {
        val msg = ShellAccess.ensure(ctx) { }
        log.add("action", "reconnect: $msg")
        return msg
    }

    /** The channels allowed to launch the server, the chosen launcher first. */
    fun launchSources(ctx: Context): List<ShellChannel> =
        LaunchViaPrefs.order(ctx, ShellChannels.all.filter { it !== LocalShellChannel && it.isReady(ctx) })

    /** Starts (or replaces) the local server through a ready non-server channel. */
    fun startServer(ctx: Context, restart: Boolean): String {
        val sources = launchSources(ctx)
        val via = sources.firstOrNull() ?: run {
            log.add("action", "server: no adb or Shizuku session to start it with")
            return "No adb or Shizuku channel is up to start the server with - Reconnect first."
        }
        if (restart) { stopServer(ctx); Thread.sleep(500) }
        val ok = AdbShellBootstrap.ensureServer(ctx, sources, force = true)
        val line = if (ok) "Local server running (started via ${via.name()})."
        else "The server did not come up (${AdbShellBootstrap.bootstrapState(ctx)})."
        log.add("action", (if (restart) "restart" else "start") + " server via ${via.name()}: " + if (ok) "up" else "did not come up")
        return line
    }

    fun stopServer(ctx: Context): String {
        val nice = AdbShellBootstrap.niceName()
        val via = ShellChannels.all.firstOrNull { it !== LocalShellChannel && it.isReady(ctx) }
            ?: ShellChannels.all.firstOrNull { it.isReady(ctx) }
            ?: run { log.add("action", "stop server: no channel is up to send it"); return "No channel is up to stop it with." }
        val out = via.exec(ctx, "pkill -f '${nice.dropLast(1)}[${nice.last()}]'; echo rc=\$?")
        log.add("action", "stop server via ${via.name()}: rc=${ShellUid.rc(out) ?: "?"}")
        return "Stop sent to the local server."
    }

    /** An `id` round trip over the active route; the output is shown to the person. */
    fun test(ctx: Context): String {
        val ch = ShellChannels.active(ctx)
        if (ch == null) { log.add("test", "no route: nothing answered"); return "No route: nothing answered." }
        val t0 = System.currentTimeMillis()
        val out = ch.exec(ctx, "id; echo rc=\$?")?.trim()
        val ms = System.currentTimeMillis() - t0
        log.add("test", "via ${ch.name()}: rc=${ShellUid.rc(out) ?: "?"} ${ShellUid.of(out) ?: "no uid"} in $ms ms")
        return "via ${ch.name()} (${ms} ms):\n" + (out ?: "no output")
    }

    /** Posts the pairing notification (the code is typed into it). Needs notifications; says so when blocked. */
    fun pair(ctx: Context): String {
        val allowed = runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrDefault(true)
        if (!allowed) {
            log.add("pair", "not started: notifications are blocked for this app")
            return "Allow notifications first: the pairing code is typed into one."
        }
        return runCatching { AdbPairingService.start(ctx) }
            .map { log.add("pair", "pairing notification posted"); "Pairing notification is up - open \"Pair device with pairing code\" and enter the code there." }
            .getOrElse { log.add("pair", "could not start: ${it.message}"); "Could not start pairing: ${it.message}" }
    }

    /** The checklist's buttons: each opens the exact screen (or runs the one action) and says what it did. */
    fun setup(ctx: Context, action: SetupAction): String {
        fun launch(vararg tries: Intent): Boolean {
            for (i in tries) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (i.resolveActivity(ctx.packageManager) != null && runCatching { ctx.startActivity(i) }.isSuccess) return true
            }
            return runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
        val line = when (action) {
            SetupAction.OPEN_ABOUT_PHONE -> { launch(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)); "Opened About phone: tap Build number seven times." }
            SetupAction.OPEN_DEVELOPER_OPTIONS -> { launch(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); "Opened Developer options." }
            // Developer options with the "Wireless debugging" row highlighted (the same extra Shizuku's own pairing guide uses).
            SetupAction.OPEN_WIRELESS_DEBUGGING -> {
                launch(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "toggle_adb_wireless"))
                "Opened Developer options at Wireless debugging."
            }
            SetupAction.OPEN_WIFI -> {
                if (Build.VERSION.SDK_INT >= 29) launch(Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS))
                else launch(Intent(Settings.ACTION_WIFI_SETTINGS))
                "Opened Wi-Fi."
            }
            SetupAction.OPEN_NOTIFICATION_SETTINGS -> {
                launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                "Opened this app's notification settings."
            }
            SetupAction.PAIR -> {
                val r = pair(ctx)
                if (r.startsWith("Pairing")) launch(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "toggle_adb_wireless"))
                r
            }
            SetupAction.REQUEST_SHIZUKU, SetupAction.OPEN_SHIZUKU -> ShizukuStatus.act(ctx)
        }
        log.add("setup", "${action.name.lowercase()}: $line")
        return line
    }
}

/** The Connect sequence on a real device: each step does the one thing it names and says what happened. */
class DeviceConnectExecutor(private val ctx: Context) : ConnectExecutor {
    private val app = ctx.applicationContext
    override fun facts(): ChannelFacts = ChannelReader.read(app, connecting = false)

    override fun run(step: StepId): StepResult = when (step) {
        StepId.DEV_OPTIONS -> StepResult(false, "off; nothing in the app can turn it on")
        StepId.WIRELESS_DEBUG -> {
            val r = WirelessDebugging.set(app, true)
            if (r.ok) StepResult(true, "turned on via ${r.channel}")
            else StepResult(false, "could not be turned on from here (${r.channel})")
        }
        StepId.PAIR -> {
            val said = ChannelOps.pair(app)
            if (said.startsWith("Pairing")) StepResult(false, "notification posted", needsUser = true)
            else StepResult(false, said)
        }
        StepId.ADB_CONNECT -> EmbeddedAdbChannel.autoConnect(app).let { (ok, msg) -> StepResult(ok, msg) }
        StepId.SHIZUKU_RUNNING ->
            if (ShizukuAdb.isAvailable()) StepResult(true, "running") else StepResult(false, if (ShizukuStatus.installed(app)) "installed, not running" else "not installed")
        StepId.SHIZUKU_PERMISSION -> {
            ShizukuAdb.requestPermission { }
            StepResult(false, "prompt shown", needsUser = true)
        }
        StepId.START_SERVER -> {
            val said = ChannelOps.startServer(app, restart = false)
            if (said.startsWith("Local server running")) StepResult(true, said) else StepResult(false, said)
        }
        StepId.VERIFY -> {
            val ch = ShellChannels.active(app)
            val out = ch?.exec(app, "id; echo rc=\$?")?.trim()
            val uid = ShellUid.of(out)
            if (ch != null && (uid == "uid 2000" || uid == "uid 0")) StepResult(true, "via ${ch.name()}: $uid")
            else StepResult(false, if (ch == null) "no route answered" else "via ${ch.name()}: ${uid ?: "no uid in the answer"}")
        }
    }
}
