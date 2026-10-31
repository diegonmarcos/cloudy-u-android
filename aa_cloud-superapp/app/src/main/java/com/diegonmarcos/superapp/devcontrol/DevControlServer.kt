package com.diegonmarcos.superapp.devcontrol

import com.diegonmarcos.superapp.devtools.BuildConfig as DevBuildConfig
import com.diegonmarcos.superapp.devtools.DevControlBridge
import com.diegonmarcos.superapp.devtools.DevControlPrefs
import com.diegonmarcos.superapp.devtools.DiagnosticsPush
import com.diegonmarcos.superapp.battery.EnergyStore
import com.diegonmarcos.superapp.system.Trace
import com.diegonmarcos.superapp.system.ShizukuUserService
import com.diegonmarcos.superapp.system.CrashLogger
import com.diegonmarcos.superapp.App
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.apps.PhoneSmartFolders
import com.diegonmarcos.superapp.apps.PhoneFolders
import com.diegonmarcos.superapp.apps.PhoneAppClassifier
import com.diegonmarcos.superapp.battery.SysfsProc
import com.diegonmarcos.superapp.battery.PowerStateReceiver
import com.diegonmarcos.superapp.battery.EnergyWatchdog
import com.diegonmarcos.superapp.battery.EnergyLedger

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Loopback HTTP/1.1 control surface for the app — same role Termux:API
 * plays for the OS, but reachable AND functional from this device's
 * shell (no signature gating because we bind to 127.0.0.1).
 *
 * Endpoints (Bearer token required unless marked NO-AUTH):
 *
 *   GET  /ping                    → pong                       [NO-AUTH]
 *   GET  /info                    → {version, vc, port}        [NO-AUTH]
 *   GET  /state                   → {section, mode, …}; 503 if no live host
 *   POST /haptic?preset=X         → fire haptic preset
 *                                    (gemini_stream, tick, start, end)
 *   POST /goto?target=URI         → onTileClicked(target)
 *                                    section: / page: / action: / http(s):
 *                                    / intent: / app: / stub:
 *   POST /action?type=X           → dispatchHomeAction(X)
 *
 *   /state, /haptic, /goto and /action all need the foreground Activity,
 *   so all four answer {"ok":bool,"reason":str,"message":str} — the same
 *   vocabulary /update already uses. reason is one of delivered |
 *   no_live_host | host_timeout | host_threw; 200 only for delivered, 503
 *   when nothing was listening, 500 when the Activity threw. They used to
 *   answer "ok" whether or not an Activity existed (#367).
 *   POST /update                  → enqueue an update check (libs:updater)
 *                                  → {"ok":bool,"message":"…"}; 503 if not started
 *   POST /restart                 → kill+relaunch the app process
 *
 * The server runs on a single accept-loop thread; each connection is
 * handled inline (response is short — no need for a thread pool).
 * Workload that touches UI is posted onto the main Looper via
 * [DevControlBridge] and WAITED ON — see [dispatchToHost]. Posting without
 * waiting is what let these routes answer "ok" for work a backgrounded app
 * never ran.
 */
object DevControlServer {

    private const val TAG = "DevControl"
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = DevControlPrefs(app)
        if (!prefs.enabled) return
        if (!running.compareAndSet(false, true)) return
        val port = prefs.port
        val token = prefs.token
        thread = Thread({ runServer(app, port, token) }, "DevControl-$port").apply {
            isDaemon = true
            start()
        }
        // Best-effort: auto-reconnect the embedded adb channel via mDNS on
        // app start, so a paired device with Wireless Debugging ON comes back
        // online after every update/relaunch with NO manual connect port.
        // Silent no-op if not paired / WD off. Off the main + server threads.
        Thread {
            runCatching { com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel.autoArm(app) }
        }.apply { isDaemon = true; name = "adb-autoconnect"; start() }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        thread?.interrupt()
        thread = null
    }

    /** True if the accept loop is currently listening. */
    fun isRunning(): Boolean = running.get()

    /**
     * Returns the socket's actual bind address (e.g. "127.0.0.1") so
     * the About page can verify it really is loopback-only.
     * Returns null if not running.
     */
    fun boundHost(): String? {
        val addr = server?.inetAddress ?: return null
        return addr.hostAddress
    }

    /** True iff the listener is bound to a loopback address (127.x.x.x
     *  or ::1). False positives are impossible — boundHost is read from
     *  the actual ServerSocket. */
    fun isLoopbackOnly(): Boolean {
        val addr = server?.inetAddress ?: return false
        return addr.isLoopbackAddress
    }

    private fun runServer(ctx: Context, port: Int, token: String) {
        try {
            server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
            Log.i(TAG, "listening on 127.0.0.1:$port")
            while (running.get()) {
                val s = server?.accept() ?: break
                runCatching { handle(ctx, s, token) }.onFailure { Log.w(TAG, "handle: $it") }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "server died: $t")
        } finally {
            runCatching { server?.close() }
            running.set(false)
        }
    }

    private fun handle(ctx: Context, socket: Socket, token: String) {
        // #ipc a gateway request DETACHES the socket to [gatewayPool] (it waits on the target
        // app, up to 30 s with a cold start); everything else is answered inline, as before.
        var detached = false
        try {
            val s = socket
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val writer = PrintWriter(s.getOutputStream(), false)

            val first = reader.readLine() ?: return
            val parts = first.split(" ")
            if (parts.size < 3) { reply(writer, "400 Bad Request", "bad request\n"); return }
            val method = parts[0]
            val pathAndQuery = parts[1]
            val path = pathAndQuery.substringBefore('?')
            val query = parseQuery(pathAndQuery.substringAfter('?', ""))

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            val auth = headers["authorization"]
                ?.removePrefix("Bearer ")
                ?.removePrefix("bearer ")
                ?.trim().orEmpty()
            val authed = auth == token

            // #ipc THE MESH GATEWAY (MeshGateway): /app/<id|pkg>/api/<op> forwarded to that app over
            // the fleet IPC layer, and /api/fleet/apps. The one door to every fleet app's routes.
            if (path.startsWith(MeshGateway.PREFIX) || path.trimEnd('/') == "/api/fleet/apps") {
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len > MeshGateway.MAX_BODY) { reply(writer, "413 Payload Too Large", "body over ${MeshGateway.MAX_BODY} bytes\n"); return }
                val body = com.diegonmarcos.superapp.devtools.FleetIpcWire.readBody(reader, len)
                detached = true
                gatewayPool.execute {
                    try {
                        val r = MeshGateway.handle(ctx, path, query, body, authed)
                            ?: com.diegonmarcos.superapp.devtools.FleetIpcWire.Reply(404, "not found\n")
                        reply(writer, statusOf(r.status), r.body, r.type)
                    } catch (t: Throwable) {
                        Log.w(TAG, "gateway: $t")
                    } finally { runCatching { socket.close() } }
                }
                return
            }

            // Normalise both legacy flat paths AND the new /api/{group}/{op}
            // layout to a single canonical op name. The op name is what
            // the routing + the /api/docs catalog below both key on, so
            // there's exactly ONE source of truth for which endpoints
            // exist.
            val op = canonicalOp(path)

            // ENDPOINT CATALOG — declared once, used by both the
            // routing switch AND /api/docs. Adding an endpoint = one
            // entry here + one branch in the switch below. /api/docs
            // updates automatically.
            //
            // auth = false for diagnostic endpoints that are SAFE to
            // expose unauth'd because (a) they only emit data ABOUT
            // this app, and (b) the server binds on 127.0.0.1 so
            // anything reaching it already owns the device shell.

            when (op) {
                "docs" -> {
                    reply(writer, "200 OK", endpointDocsJson(DevControlPrefs(ctx).port), "application/json")
                    return
                }
                "system/ping" -> { reply(writer, "200 OK", "pong\n"); return }
                "system/info" -> {
                    val body = """{"version":"${BuildConfig.VERSION_NAME}","vc":${BuildConfig.VERSION_CODE},"sha":"${BuildConfig.GIT_SHORT_SHA}","port":${DevControlPrefs(ctx).port}}"""
                    reply(writer, "200 OK", body, "application/json")
                    return
                }
                "diagnostics/logcat" -> { reply(writer, "200 OK", readLogcat(query["n"]?.toIntOrNull() ?: 300)); return }
                "diagnostics/trace"  -> { reply(writer, "200 OK", readTraceTail(ctx, query["n"]?.toIntOrNull() ?: 300)); return }
                "diagnostics/crashes" -> { reply(writer, "200 OK", readCrashes(ctx)); return }
                "diagnostics/exits"  -> { reply(writer, "200 OK", readExitReasons(ctx, query["n"]?.toIntOrNull() ?: 20)); return }
                "diagnostics/navgeometry" -> {
                    // The bottom nav has now rendered differently ON-DEVICE
                    // than under Robolectric twice (2026-09-19: the icon-only
                    // halo both before and after the indicator kill switch)
                    // while every JVM assertion stayed green. This route ends
                    // the divergence class: it walks the LIVE view tree on the
                    // main thread and reports every rect, background class and
                    // drawable bounds — the device testifies about itself.
                    val d = dispatchToHost("diagnostics/navgeometry") { host -> navGeometryJson(host) }
                    reply(writer, d.status,
                        d.value ?: """{"error":"${jsonEscape(d.message)}"}""", "application/json")
                    return
                }
                "diagnostics/bundle" -> { reply(writer, "200 OK", diagnosticRecord(ctx), "application/json"); return }
                "diagnostics/download" -> {
                    val name = "cloud-diag-${BuildConfig.APPLICATION_ID}-${BuildConfig.GIT_SHORT_SHA}.json"
                    val saved = DiagnosticsPush.downloadBundle(ctx, name, diagnosticRecord(ctx))
                    reply(writer, "200 OK", """{"downloaded":${saved != null},"file":"${jsonEscape(saved ?: "")}"}""", "application/json"); return
                }
                "diagnostics/push" -> {
                    val code = DiagnosticsPush.pushToCloud(diagnosticRecord(ctx))
                    reply(writer, "200 OK", """{"posted":${code in 200..299},"http":$code,"sink":"${jsonEscape(DevBuildConfig.LOG_SINK_URL)}"}""", "application/json"); return
                }
                "system/about" -> { reply(writer, "200 OK", aboutJson(), "application/json"); return }
            }

            if (!authed) { reply(writer, "401 Unauthorized", "unauthorized\n"); return }

            // Authenticated endpoints
            when (op) {
                "state" -> {
                    // Read on the main thread and report whether it was read at
                    // all. The old line was host()?.stateSnapshot() ?: emptyMap()
                    // answered 200, so "the activity is gone" and "the activity
                    // has an empty state map" arrived as the same {} (#367).
                    val dispatch = dispatchToHost(op) { it.stateSnapshot() }
                    if (dispatch.delivered) {
                        val body = (dispatch.value ?: emptyMap()).entries
                            .joinToString(",", "{", "}") {
                                "\"${jsonEscape(it.key)}\":\"${jsonEscape(it.value)}\""
                            }
                        reply(writer, "200 OK", body, "application/json")
                    } else {
                        reply(writer, dispatch.status, ackJson(dispatch), "application/json")
                    }
                }
                "haptic" -> {
                    val preset = query["preset"] ?: "tick"
                    val dispatch = dispatchToHost("$op preset=$preset") {
                        it.firePresetHaptic(preset)
                    }
                    reply(writer, dispatch.status, ackJson(dispatch, "preset", preset), "application/json")
                }
                "nav/goto" -> {
                    val target = query["target"]
                    if (target.isNullOrBlank()) {
                        reply(writer, "400 Bad Request", "missing target\n")
                    } else {
                        val dispatch = dispatchToHost("$op target=$target") {
                            it.onTileFromServer(target)
                        }
                        reply(writer, dispatch.status, ackJson(dispatch, "target", target), "application/json")
                    }
                }
                "nav/action" -> {
                    val type = query["type"]
                    if (type.isNullOrBlank()) {
                        reply(writer, "400 Bad Request", "missing type\n")
                    } else {
                        val dispatch = dispatchToHost("$op type=$type") {
                            it.onActionFromServer(type)
                        }
                        reply(writer, dispatch.status, ackJson(dispatch, "type", type), "application/json")
                    }
                }
                "system/update" -> {
                    // DELIBERATELY NOT VIA DevControlBridge.host(). That is a
                    // WeakReference live only between the Activity's onResume
                    // and onPause, so every screen-off fleet update was dropped
                    // by the safe-call while this line still answered
                    // "update queued" — see Updater.requestCheck (#280).
                    // The ack is now the outcome, and a failure is a 503.
                    val ack = com.diegonmarcos.superapp.updater.Updater
                        .requestCheck(ctx, "devcontrol:$op")
                    reply(
                        writer,
                        if (ack.ok) "200 OK" else "503 Service Unavailable",
                        """{"ok":${ack.ok},"message":"${jsonEscape(ack.message)}"}""",
                        "application/json",
                    )
                }
                "system/restart" -> {
                    // A reply cannot follow the restart — by then the process is
                    // gone. So the one honest thing to report is the precondition
                    // that decides whether the restart can happen at all:
                    // DevControlBridge.restartApp needs a launcher intent for this
                    // package and RETURNS SILENTLY without one. The old
                    // unconditional "restarting…" reported that silent return as a
                    // restart, which is this ticket's defect in its third form
                    // (#367). The deeper fix — restartApp handing back whether it
                    // scheduled anything — belongs in DevControlBridge, which this
                    // ticket does not own; see the report for #367.
                    val relaunch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                    if (relaunch == null) {
                        reply(
                            writer, "503 Service Unavailable",
                            "{\"ok\":false,\"reason\":\"no_launch_intent\",\"message\":\"" +
                                "this package resolves no launcher intent, so restartApp " +
                                "would kill the process with nothing scheduled to bring " +
                                "it back\"}",
                            "application/json",
                        )
                    } else {
                        reply(
                            writer, "200 OK",
                            "{\"ok\":true,\"reason\":\"restart_scheduled\",\"message\":\"" +
                                "relaunch intent resolved; killing the process now\"}",
                            "application/json",
                        )
                        writer.flush()
                        DevControlBridge.runOnMain { DevControlBridge.restartApp(ctx) }
                    }
                }
                "phone/classify" -> { reply(writer, "200 OK", phoneClassifyJson(ctx), "application/json") }
                "phone/new_apps" -> { reply(writer, "200 OK", phoneNewAppsJson(ctx), "application/json") }
                "battery/state" -> { reply(writer, "200 OK", batteryStateJson(ctx), "application/json") }
                "battery/reset_history" -> {
                    com.diegonmarcos.superapp.battery.BatteryRepository.clear(ctx)
                    reply(writer, "200 OK", """{"ok":true,"message":"battery SoT history and counted-cycle counters cleared; recording restarts with the next battery event"}""", "application/json")
                }
                "sysfs/diagnostic" -> { reply(writer, "200 OK", sysfsDiagnosticJson(), "application/json") }
                "battery/properties" -> { reply(writer, "200 OK", batteryPropertiesJson(ctx), "application/json") }
                "battery/snapshot" -> {
                    // Capture + persist a charge/PD snapshot (native + dumpsys
                    // via adb if connected). Best run at <30%, cool, plugged.
                    reply(writer, "200 OK",
                        com.diegonmarcos.superapp.battery.ChargeSnapshot.capture(ctx).toString(),
                        "application/json")
                }
                "battery/snapshots" -> {
                    reply(writer, "200 OK",
                        com.diegonmarcos.superapp.battery.ChargeSnapshot.recent(ctx).toString(),
                        "application/json")
                }
                "energy/self" -> { reply(writer, "200 OK", energySelfJson(), "application/json") }
                "energy/attribution" -> { reply(writer, "200 OK", energyAttributionJson(ctx), "application/json") }
                "energy/samples" -> { reply(writer, "200 OK", energySamplesJson(ctx), "application/json") }
                "energy/exact" -> { reply(writer, "200 OK", energyExactJson(ctx), "application/json") }
                "energy/reset" -> {
                    com.diegonmarcos.superapp.battery.EnergyLedger.reset()
                    runCatching { com.diegonmarcos.superapp.battery.EnergyStore(ctx).clear() }
                    reply(writer, "200 OK", """{"ok":true,"message":"energy ledger + sample store cleared"}""", "application/json")
                }
                // ── libs:shizuku-adb-debug-tools — self-contained adb shell ──
                "adb/status" -> { reply(writer, "200 OK", adbStatusJson(ctx), "application/json") }
                "adb/netinfo" -> { reply(writer, "200 OK", adbNetInfoJson(ctx), "application/json") }
                "adb/pair" -> {
                    // Shizuku-exact: no host, no port. AdbPairingService discovers
                    // _adb-tls-pairing._tcp over mDNS (a typed port was how pairing
                    // ended up against the CONNECT port → "Connection reset"), posts
                    // the "Enter pairing code" notification, pairs in the foreground,
                    // then connects + starts the plane. code= is optional: a script
                    // can hand it over here instead of typing it into the shade.
                    val r = runCatching { com.diegonmarcos.superapp.adbdebug.AdbPairingService.start(ctx, query["code"]) }
                    val msg = if (r.isSuccess) "pairing notification posted — enter the code there (or pass code=)"
                              else "could not start pairing service: ${r.exceptionOrNull()?.message}"
                    reply(writer, "200 OK", """{"ok":${r.isSuccess},"message":"${jsonEscape(msg)}"}""", "application/json")
                }
                "adb/autoconnect" -> {
                    // mDNS auto-discovery of the local adbd connect service —
                    // no manual connect port. Needs paired + Wireless Debugging ON.
                    val (ok, msg) = com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel.autoArm(ctx)
                    reply(writer, "200 OK", """{"ok":$ok,"message":"${jsonEscape(msg)}"}""", "application/json")
                }
                "adb/connect" -> {
                    // host = IP from the main Wireless-debugging screen, port = the
                    // CONNECT port (distinct from the pairing port). After a pair.
                    val host = query["host"] ?: "127.0.0.1"
                    val port = query["port"]?.toIntOrNull()
                    if (port == null) {
                        reply(writer, "400 Bad Request", "need host=<ip>&port=<connectPort>\n")
                    } else {
                        val (ok, msg) = com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel.connect(ctx, host, port)
                        reply(writer, "200 OK", """{"ok":$ok,"message":"${jsonEscape(msg)}"}""", "application/json")
                    }
                }
                "adb/server-command" -> {
                    // The one-liner to run ONCE per boot (via adb / Wireless
                    // Debugging) to start OUR shell-domain app_process server.
                    val cmd = com.diegonmarcos.superapp.adbdebug.AdbShellBootstrap.serverCommand(ctx)
                    val body = """{"command":"${jsonEscape(cmd)}","port":${com.diegonmarcos.superapp.adbdebug.AdbShellBootstrap.port()},"note":"Run once per boot. Grants the SHELL SELinux domain our server needs; after this the app is self-contained (no Shizuku app)."}"""
                    reply(writer, "200 OK", body, "application/json")
                }
                "adb/diagnostics" -> {
                    val bundle = query["bundle"] ?: "charger"
                    reply(writer, "200 OK",
                        com.diegonmarcos.superapp.adbdebug.AdbDiagnostics.runBundle(ctx, bundle),
                        "application/json")
                }
                "adb/exec" -> {
                    val cmd = query["cmd"]
                    if (cmd.isNullOrBlank()) {
                        reply(writer, "400 Bad Request", "missing cmd\n")
                    } else {
                        val ch = com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)
                        val out = ch?.exec(ctx, cmd)
                            ?: "ERR: no shell channel ready — ${com.diegonmarcos.superapp.adbdebug.LocalShellChannel.status(ctx)}\n"
                        reply(writer, "200 OK", out)
                    }
                }
                "devcontrol/control" -> {
                    // #904 one Control-panel switch from the fleet API: the same DeviceControls entry
                    // the tile flips, its blocked reason honoured first, so a terminal brings the mesh
                    // up (?id=mesh&on=1) without a tap. Without `on`: the live state, never a remembered boolean.
                    val id = query["id"].orEmpty()
                    val on = query["on"]
                    val c = com.diegonmarcos.superapp.configs.DeviceControls.byId[id]
                    val o = org.json.JSONObject().put("id", id)
                    when {
                        c == null -> o.put("ok", false).put("detail", "unknown control '$id'")
                        on == null -> o.put("ok", true).put("state", c.read(ctx)).put("blocked", c.blocked(ctx))
                        c.set == null -> o.put("ok", false).put("detail", "'$id' is not a switch")
                        else -> {
                            val why = c.blocked(ctx)
                            if (why.isNotBlank()) o.put("ok", false).put("detail", why)
                            else c.set.invoke(ctx, on == "1" || on == "true")
                                .let { v -> o.put("ok", v.ok).put("detail", v.detail).put("state", c.read(ctx)) }
                        }
                    }
                    reply(writer, "200 OK", o.toString(), "application/json")
                }
                "devcontrol/tuning" -> {
                    // Per-entry state of data/device-tuning.json as DeviceTuning
                    // last recorded it — read-only, so a terminal can check why it
                    // still gets signal 9 without a shell round trip of its own.
                    reply(writer, "200 OK", com.diegonmarcos.superapp.system.DeviceTuning.toJson(ctx), "application/json")
                }
                "adb/grant-dump" -> {
                    // Self-grant DUMP through whichever shell channel is up
                    // (the local server or Shizuku) so dumpsys also works
                    // in-process. Signature|privileged|DEVELOPMENT perm, so
                    // `pm grant` is allowed from the shell domain.
                    val ch = com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)
                    val out = ch?.exec(ctx, "pm grant ${ctx.packageName} android.permission.DUMP")
                    val held = ctx.checkSelfPermission("android.permission.DUMP") ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    val body = """{"channel":"${jsonEscape(ch?.name() ?: "none")}","ran":${out != null},"held":$held,"output":"${jsonEscape(out ?: "no shell channel ready")}"}"""
                    reply(writer, "200 OK", body, "application/json")
                }
                "adb/sfc" -> {
                    // Samsung Super Fast Charging verdict — runs the data-driven
                    // `samsung-sfc` bundle through the shell ladder and parses
                    // dumpsys battery into {tier, verdict, reasons[]}. No drained
                    // battery needed: surfaces device watt-ceiling + SFC toggle +
                    // peak-current-ever so "why isn't 100W fast" is answered now.
                    reply(writer, "200 OK",
                        com.diegonmarcos.superapp.adbdebug.SfcVerdict.run(ctx),
                        "application/json")
                }
                else -> reply(writer, "404 Not Found", "not found — see /api/docs\n")
            }
        } finally {
            if (!detached) runCatching { socket.close() }
        }
    }

    /** #ipc the mesh gateway's workers: forwarded requests wait on IPC, never on the accept loop. */
    private val gatewayPool = java.util.concurrent.Executors.newFixedThreadPool(6) { r ->
        Thread(r, "mesh-gateway").apply { isDaemon = true }
    }

    private fun statusOf(code: Int) = "$code " + when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        413 -> "Payload Too Large"; 500 -> "Internal Server Error"; 502 -> "Bad Gateway"
        503 -> "Service Unavailable"; 504 -> "Gateway Timeout"; else -> "Status"
    }

    /** Map both `/ping` (legacy flat) and `/api/system/ping` (new) to
     *  the canonical op name `system/ping`. The routing + the /api/docs
     *  catalog both key on this canonical name. New endpoints SHOULD
     *  be added under the v1/{group}/{op} form only; legacy aliases
     *  are kept so existing curls in the user's Configs/About panel
     *  don't break. */
    private fun canonicalOp(path: String): String {
        // Strip /api/ prefix if present.
        val stripped = path.removePrefix("/api/").removePrefix("/")
        // Legacy flat → canonical group/op mapping. Keep in sync with
        // the docs catalog below.
        return when (stripped) {
            "ping"     -> "system/ping"
            "info"     -> "system/info"
            "about"    -> "system/about"
            "logcat"   -> "diagnostics/logcat"
            "trace"    -> "diagnostics/trace"
            "crashes"  -> "diagnostics/crashes"
            "exits"    -> "diagnostics/exits"
            "navgeometry" -> "diagnostics/navgeometry"
            "goto"     -> "nav/goto"
            "action"   -> "nav/action"
            "update"   -> "system/update"
            "restart"  -> "system/restart"
            else       -> stripped
        }
    }

    /** Returns the API docs as JSON — single source of truth lives
     *  here, mirrors exactly what the routing switch handles. */
    private fun endpointDocsJson(port: Int): String {
        // Each entry: [op, methods, auth, description, paramsCSV]
        val rows = listOf(
            Spec("docs",                "GET",  false, "This endpoint — JSON catalog of every route, method, and auth requirement", ""),
            Spec("system/ping",         "GET",  false, "Health probe — returns 'pong'", ""),
            Spec("system/info",         "GET",  false, "App build info: version, vc, sha, port", ""),
            Spec("system/about",        "GET",  false, "Full About-page data as JSON: app build, device, stack (languages/frameworks/build times), and the folder/sitemap/AST trees", ""),
            Spec("system/update",       "POST", true,  "Enqueue an update check via libs:updater — replies {ok,message}; 503 when it could not be started", ""),
            Spec("system/restart",      "POST", true,  "Restart the SuperApp process; {ok,reason,message}, 503 if no launcher intent resolves", ""),
            Spec("diagnostics/logcat",  "GET",  false, "Recent logcat lines, threadtime format", "n=lines (default 300)"),
            Spec("diagnostics/trace",   "GET",  false, "Tail of Trace.kt's trace.log", "n=lines (default 300)"),
            Spec("diagnostics/crashes", "GET",  false, "All crash files concatenated, newest first", ""),
            Spec("diagnostics/exits",   "GET",  false, "The OS's own record of recent process deaths (ApplicationExitInfo): ANR / native crash / lmkd / signal — the deaths CrashLogger cannot see; ANR records inline the system-captured trace head", "n=records (default 20)"),
            Spec("diagnostics/navgeometry", "GET", false, "The LIVE bottom-nav view tree, measured on-device: every rect, background class and drawable bounds — for the renders Robolectric cannot reproduce; needs the app foregrounded", ""),
            Spec("diagnostics/bundle",  "GET",  false, "Full debug bundle (logcat+trace+crashes+device) as one OpenObserve JSON record", ""),
            Spec("diagnostics/download","GET",  false, "Write the debug bundle to public Downloads; returns the filename", ""),
            Spec("diagnostics/push",    "GET",  false, "POST the debug bundle to the cloud log sink (OpenObserve via build.json::diagnostics.log_sink_url)", ""),
            Spec("state",               "GET",  true,  "Snapshot of MainActivity's live state map (section, label, mode, …); 503 if no live foreground activity", ""),
            Spec("haptic",              "POST", true,  "Fire a named haptic preset on the device; {ok,reason,message}, 503 if no live foreground activity", "preset=name (default 'tick')"),
            Spec("nav/goto",            "POST", true,  "Navigate to a tile target (section:X / page:X/Y / action:X / url); {ok,reason,message}, 503 if no live foreground activity", "target=string"),
            Spec("nav/action",          "POST", true,  "Fire one of MainActivity.onActionFromServer's verbs; {ok,reason,message}, 503 if no live foreground activity", "type=string"),
            Spec("phone/classify",      "GET",  true,  "Every launchable installed app + the folder PhoneAppClassifier routes it to (debug surface for the Home Apps/Phone tab)", ""),
            Spec("phone/new_apps",      "GET",  true,  "Just the apps that fell to the sink folder (_New Apps) — direct view of what's not yet covered by phone_folders.match_keywords", ""),
            Spec("battery/state",       "GET",  true,  "The battery Source of Truth's report (libs:battery BatteryRepository.report → BatteryTruth): the reading as reported, the normalised + smoothed current, power, rate in %/h and W, capacity, time to empty/full, since-last-charge, the charge in progress, power in/out, counted cycles, the charger, and the session list — the same object the Battery badge, the strip popup and About › Battery draw", ""),
            Spec("battery/reset_history","GET", true,  "DELETE the battery SoT's recorded history (battery_sot.db samples) and its counted-cycle counters. Recording restarts with the next battery event.", ""),
            Spec("sysfs/diagnostic",    "GET",  true,  "Per-path readability check for every kernel sysfs/proc file the app touches. Returns ✓ OK + preview when readable, ✗ does-not-exist / not-readable / read-failed otherwise. THIS is the answer to 'why isn't sysfs working even though no perm is needed' — hardened Androids block specific power_supply nodes via SELinux.", ""),
            Spec("battery/properties",  "GET",  true,  "Every raw BatteryManager.BATTERY_PROPERTY_* value and sticky ACTION_BATTERY_CHANGED extra exactly as the device reports it (no scaling, no sign), from BatteryRepository.rawDump — the SoT's own reader.", ""),
            Spec("battery/snapshot",    "GET",  true,  "Capture + persist ONE charging snapshot: native fields (level/current/voltage/temp/power) + (if embedded-adb is connected) dumpsys battery truth — Max charging current/voltage, Charging state, IC-auth, and the raw last ACTION_BATTERY_CHANGED line (charge_type/charger_type/hvc/mcc/mcv). Run at <30% cool while charging to capture whether fast-charge (mcv→9000) engages. Also available as a button in Battery Usage Details.", ""),
            Spec("battery/snapshots",   "GET",  true,  "Newest-first history of stored charging snapshots (capped 50) from battery/snapshot — compare across SOC levels to see exactly when/if fast-charge negotiates.", ""),
            Spec("energy/self",         "GET",  true,  "Intra-app energy ledger — which subsystem INSIDE Cloud SuperApp spent the most CPU-ms / wakeups / bytes since the window start (ui.galaxy, music.session, bg.battery_worker, bg.energy_sampler, …). Answers 'what in our own app drains battery'.", ""),
            Spec("energy/attribution",  "GET",  true,  "Device-level Tier-1 watchdog attribution over stored samples: idle baseline mA, marginal mA per state (screen-on / audio / weak-cellular / high-cpu / bright-screen), per-foreground-app avg draw + energy proxy, and our own self cpu/net cost over the window.", ""),
            Spec("energy/samples",      "GET",  true,  "Raw recent energy-watchdog samples (last 200): per-sample whole-device draw_ma + state vector (screen, brightness, foreground pkg, cpu load, signal, wifi, audio).", ""),
            Spec("energy/reset",        "GET",  true,  "Clear the intra-app ledger + the watchdog sample store to start a fresh measurement window.", ""),
            Spec("energy/exact",        "GET",  true,  "EXACT per-app mAh (Tier 2): `dumpsys batterystats --charged` in shell context (uid 2000) through the app's own privileged adb channel (embedded adb / app_process server, never Shizuku), per uid. channel=null when it is not paired (pair on Configs > Network > ADB Shell).", ""),
            Spec("fleet/apps",          "GET",  true,  "#ipc THE MESH GATEWAY: this server is the phone's one fleet door. Lists every fleet app installed behind it (id, pkg, label, IPC door). Each app's own routes are /app/<id|pkg>/api/<op> on this port (GET, or POST with a body), forwarded to that app over the fleet IPC layer (signature-guarded, waking it); its /app/<pkg>/api/docs is its catalog. Bearer token on every gateway route.", ""),
            Spec("adb/status",          "GET",  true,  "libs:shizuku-adb-debug-tools shell-channel ladder: per-channel ready/status for 'embedded-adb' (PRIMARY — our on-device adb client paired to localhost Wireless Debugging, the self-contained 'we ARE Shizuku' path), 'local-server' (our app_process server), and 'shizuku' (fallback); which is active, whether DUMP is held, and the data-driven bundle ids.", ""),
            Spec("adb/pair",            "GET",  true,  "Embedded adb client, the Shizuku way: starts AdbPairingService, which discovers the pairing port over mDNS (_adb-tls-pairing._tcp — never typed), posts the 'Enter pairing code' notification, pairs in a foreground service, then discovers _adb-tls-connect._tcp, connects and starts the privileged plane. code= optional (6 digits from 'Pair device with pairing code'); omitted, type it into the notification.", "code=<6digits>"),
            Spec("adb/connect",         "GET",  true,  "Embedded adb client: connect to the local adbd after pairing. host=IP from the main Wireless-debugging screen (default 127.0.0.1), port=the CONNECT port (distinct from the pairing port). On success the 'embedded-adb' channel goes ready and diagnostics run with zero third-party deps.", "host=<ip>&port=<connectPort>"),
            Spec("adb/autoconnect",     "GET",  true,  "Embedded adb client: auto-discover the local adbd via mDNS (_adb-tls-connect._tcp, advertised by Wireless Debugging) and connect — NO manual port. Needs the device already paired + Wireless Debugging ON. Also runs automatically on app start, so reconnect-after-update is hands-free.", ""),
            Spec("adb/server-command",  "GET",  true,  "Returns the exact one-liner to run ONCE per boot (via adb / Wireless Debugging) to start OUR self-contained shell-domain app_process server (AdbShellServer). This is the only privilege bootstrap; after it the app needs no third-party Shizuku app. {command,port,note}.", ""),
            Spec("adb/diagnostics",     "GET",  true,  "Run a DATA-DRIVEN diagnostic bundle (build.json::shizuku_diagnostics.bundles[]) through the shell-channel ladder (local-server first, Shizuku fallback) and return {bundle,label,channel,ok,results:[{id,cmd,out}]}. Bundles: charger (dumpsys battery+usb, power_supply nodes, typec, charge props), battery, usb, thermal, pd. THE endpoint that surfaces the USB-PD/PPS negotiation behind 'why is the charger at 3W not 35W' when SELinux blocks /sys/class/power_supply/*.", "bundle=charger|battery|usb|thermal|pd (default charger)"),
            Spec("adb/exec",            "GET",  true,  "Generic 'adb shell' passthrough — runs `sh -c <cmd>` in shell context (uid 2000) through the active channel and returns raw stdout. Full adb-equivalent power; token-gated + loopback-only. Use for one-off commands not covered by a bundle.", "cmd=<shell command>"),
            Spec("devcontrol/control",  "GET",  true,  "#904 Flip one Control-panel switch (the DeviceControls catalog, build.json::ui.control_panel ids): ?id=mesh&on=1|0 → {ok, detail, state}; without on → the live state and the blocked reason. The tile's own set/blocked run, so nothing the panel refuses is done here.", "id=<control id>&on=1|0"),
            Spec("devcontrol/tuning",   "GET",  true,  "Device tuning state — every entry of data/device-tuning.json (the Android 12+ phantom-process-killer fix the Cloud Terminal needs, …) with the state DeviceTuning recorded on its last pass through this app's own shell: APPLIED / PENDING (no shell yet) / FAILED (+ truncated output) + timestamp. Read-only; the pass itself runs after every successful plane connect (pairing, app start).", ""),
            Spec("adb/grant-dump",      "GET",  true,  "Self-grant android.permission.DUMP via `pm grant` through the active shell channel, so dumpsys also works IN-PROCESS. DUMP is signature|privileged|DEVELOPMENT, so pm grant from the shell domain is allowed. Returns {channel,ran,held,output}.", ""),
            Spec("adb/sfc",             "GET",  true,  "Samsung Super Fast Charging verdict — runs the data-driven `samsung-sfc` bundle (getprop model + dumpsys battery) through the shell-channel ladder and parses it into {tier,verdict,reasons[],device_max_watts,high_voltage_engaged,saved_max_current_ma,cable_suspect,sfc_setting_on}. Tiers: FAST_HV (negotiated) / SLOW_5V (charger-PPS or thermal suspect) / FULL_OR_TAPERING (full → healthy, not denied) / NOT_CHARGING. Answers 'why isn't my 100W cable fast-charging' WITHOUT a drained battery: surfaces the device watt-ceiling, whether the SFC toggle is on, and the peak current ever recorded. Parser is pure + JVM-tested against protocol fixtures (libs:shizuku-adb-debug-tools/SfcVerdict).", ""),
        )
        val sb = StringBuilder()
        sb.append("""{"port":""").append(port).append(',')
        sb.append(""""base":"http://127.0.0.1:""").append(port).append("\",")
        sb.append(""""auth":"Bearer <token> in Authorization header (token visible in Configs/About → Dev control HTTP)",""")
        sb.append(""""path_styles":["/api/{group}/{op} (preferred)","/{op} (legacy flat aliases — same handler)"],""")
        sb.append(""""endpoints":[""")
        rows.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append(""""op":"""").append(jsonEscape(r.op)).append("\",")
            sb.append(""""path":"/api/""").append(jsonEscape(r.op)).append("\",")
            sb.append(""""method":"""").append(jsonEscape(r.method)).append("\",")
            sb.append(""""auth":""").append(r.auth).append(',')
            sb.append(""""description":"""").append(jsonEscape(r.description)).append("\",")
            sb.append(""""params":"""").append(jsonEscape(r.params)).append('"')
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private data class Spec(
        val op: String,
        val method: String,
        val auth: Boolean,
        val description: String,
        val params: String,
    )

    /** Walk every launchable activity + report the folder
     *  PhoneAppClassifier routes it to. Debug surface for the Home
     *  Apps/Phone tab — directly answers "why is app X in folder Y?". */
    /** Intra-app energy ledger — which subsystem inside Cloud SuperApp
     *  spent the most CPU / wakeups / bytes since the window start. */
    private fun energySelfJson(): String {
        val (since, stats) = com.diegonmarcos.superapp.battery.EnergyLedger.snapshot()
        val sorted = stats.entries.sortedByDescending { it.value.cpuNanos }
        val sb = StringBuilder("""{"since_ms":""").append(since).append(',')
        sb.append(""""tags":[""")
        var first = true
        for ((tag, s) in sorted) {
            if (!first) sb.append(','); first = false
            sb.append("""{"tag":"""").append(jsonEscape(tag)).append('"').append(',')
            sb.append(""""cpu_ms":""").append(s.cpuNanos / 1_000_000L).append(',')
            sb.append(""""calls":""").append(s.calls).append(',')
            sb.append(""""wakeups":""").append(s.wakeups).append(',')
            sb.append(""""bytes":""").append(s.bytes).append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Exact per-app mAh from dumpsys batterystats through the privileged adb channel (Tier 2). */
    private fun energyExactJson(ctx: Context): String {
        val repo = com.diegonmarcos.superapp.battery.BatteryRepository
        val channel = repo.privilegedChannel(ctx)
        val sb = StringBuilder("{")
        sb.append(""""channel":""").append(channel?.let { "\"" + jsonEscape(it) + "\"" } ?: "null").append(',')
        val apps = if (channel == null) null else runCatching { repo.privileged(ctx)?.apps }.getOrNull()
        sb.append(""""apps":""")
        if (apps == null) sb.append("null") else {
            sb.append('[')
            apps.forEachIndexed { i, a ->
                if (i > 0) sb.append(',')
                val (pkg, label) = repo.appLabel(ctx, a.uid)
                sb.append("""{"pkg":"""").append(jsonEscape(pkg)).append('"').append(',')
                sb.append(""""label":"""").append(jsonEscape(label)).append('"').append(',')
                sb.append(""""uid":""").append(a.uid).append(',')
                sb.append(""""mah":""").append("%.2f".format(java.util.Locale.US, a.mAh)).append('}')
            }
            sb.append(']')
        }
        sb.append('}')
        return sb.toString()
    }

    /** Device-level state → draw attribution + per-foreground-app +
     *  our own self cost, computed over the stored sample window. */
    private fun energyAttributionJson(ctx: Context): String {
        val m = com.diegonmarcos.superapp.battery.EnergyWatchdog.attribution(ctx)
        return mapToJson(m)
    }

    /** Raw recent watchdog samples (last 200). */
    private fun energySamplesJson(ctx: Context): String {
        val rows = runCatching { com.diegonmarcos.superapp.battery.EnergyStore(ctx).recent(200) }
            .getOrDefault(emptyList())
        val sb = StringBuilder("[")
        var first = true
        for (s in rows) {
            if (!first) sb.append(','); first = false
            sb.append('{')
            sb.append(""""ts":""").append(s.ts).append(',')
            sb.append(""""draw_ma":""").append(s.drawMa).append(',')
            sb.append(""""batt_pct":""").append(s.battPct).append(',')
            sb.append(""""screen_on":""").append(s.screenOn).append(',')
            sb.append(""""brightness":""").append(s.brightness).append(',')
            sb.append(""""charging":""").append(s.charging).append(',')
            sb.append(""""fg_pkg":"""").append(jsonEscape(s.fgPkg)).append('"').append(',')
            sb.append(""""cpu_load":""").append(s.cpuLoadPct).append(',')
            sb.append(""""mobile_dbm":""").append(s.mobileSignalDbm).append(',')
            sb.append(""""wifi_rssi":""").append(s.wifiRssi).append(',')
            sb.append(""""audio":""").append(s.audioActive).append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /** Minimal recursive Map/List/primitive → JSON for the attribution
     *  payload (it's already a clean Map<String,Any> tree). */
    private fun mapToJson(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"${jsonEscape(v)}\""
        is Boolean, is Int, is Long, is Double, is Float -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") {
            "\"${jsonEscape(it.key.toString())}\":${mapToJson(it.value)}"
        }
        is List<*> -> v.joinToString(",", "[", "]") { mapToJson(it) }
        else -> "\"${jsonEscape(v.toString())}\""
    }

    private fun phoneClassifyJson(ctx: Context): String {
        val folders = com.diegonmarcos.superapp.apps.PhoneFolders.loadFromBuildConfig()
        val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE)
            as android.content.pm.LauncherApps
        val me = android.os.Process.myUserHandle()
        val pm = ctx.packageManager
        val sb = StringBuilder("[")
        var first = true
        for (info in launcher.getActivityList(null, me)) {
            val pkg = info.applicationInfo.packageName
            val label = info.label.toString()
            val folderId = com.diegonmarcos.superapp.apps.PhoneAppClassifier
                .classify(pkg, label, folders)
            // Install-source debug fields — exactly what
            // PhoneSmartFolders.install_source_not reads, so we can see
            // why a given app does/doesn't land in Alternative Sources.
            var installing: String? = null
            var initiating: String? = null
            var isSystem = false
            runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                isSystem = (ai.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                    android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            }
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val src = pm.getInstallSourceInfo(pkg)
                    installing = src.installingPackageName
                    initiating = src.initiatingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    installing = pm.getInstallerPackageName(pkg)
                }
            }
            if (!first) sb.append(','); first = false
            sb.append("""{"pkg":"""").append(jsonEscape(pkg)).append('"').append(',')
            sb.append(""""label":"""").append(jsonEscape(label)).append('"').append(',')
            sb.append(""""folder":"""").append(jsonEscape(folderId)).append('"').append(',')
            sb.append(""""installing":""")
                .append(if (installing == null) "null" else "\"${jsonEscape(installing!!)}\"").append(',')
            sb.append(""""initiating":""")
                .append(if (initiating == null) "null" else "\"${jsonEscape(initiating!!)}\"").append(',')
            sb.append(""""system":""").append(isSystem)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /** Only the apps that landed in the sink folder (new_apps by
     *  default) — focused view for "what's still uncategorised?". */
    private fun phoneNewAppsJson(ctx: Context): String {
        val folders = com.diegonmarcos.superapp.apps.PhoneFolders.loadFromBuildConfig()
        val sinkId = com.diegonmarcos.superapp.apps.PhoneFolders.sinkFolderId(folders)
        val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE)
            as android.content.pm.LauncherApps
        val me = android.os.Process.myUserHandle()
        val sb = StringBuilder("""{"sink_folder_id":"""")
        sb.append(jsonEscape(sinkId)).append('"').append(',')
        sb.append(""""apps":[""")
        var first = true
        for (info in launcher.getActivityList(null, me)) {
            val pkg = info.applicationInfo.packageName
            val label = info.label.toString()
            val folderId = com.diegonmarcos.superapp.apps.PhoneAppClassifier
                .classify(pkg, label, folders)
            if (folderId != sinkId) continue
            if (!first) sb.append(','); first = false
            sb.append("""{"pkg":"""").append(jsonEscape(pkg)).append('"').append(',')
            sb.append(""""label":"""").append(jsonEscape(label)).append('"').append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** The battery SoT's report as JSON — every value the Battery badge, the
     *  strip popup and Configs › About › Battery draw, computed once by
     *  libs:battery BatteryTruth (signed: + into the battery, − out). */
    private fun batteryStateJson(ctx: Context): String {
        val r = com.diegonmarcos.superapp.battery.BatteryRepository.report(ctx)
            ?: return """{"ok":false,"message":"no battery broadcast"}"""
        val x = r.reading
        val o = org.json.JSONObject()
        fun put(k: String, v: Any?) { o.put(k, v ?: org.json.JSONObject.NULL) }
        put("nowMs", x.nowMs); put("levelPct", x.levelPct); put("status", x.status); put("plugged", x.plugged)
        put("health", x.health); put("technology", x.technology); put("tempC", x.tempC); put("voltageMv", x.voltageMv)
        put("rawCurrentNow", x.rawCurrentNow); put("rawCurrentAvg", x.rawCurrentAvg); put("chargeCounterUah", x.counterUah)
        put("systemChargeRemainingMs", x.systemChargeRemainingMs); put("screenOn", x.screenOn)
        put("currentNowMa", r.currentNowMa); put("currentAvgMa", r.currentAvgMa); put("smoothedMa", r.smoothedMa)
        put("powerW", r.powerW); put("ratePctH", r.ratePctH); put("rateW", r.rateW); put("rateSource", r.rateSource.name)
        put("capacityMah", r.capacityMah); put("capacityFromCounter", r.capacityFromCounter); put("ratedMah", r.ratedMah)
        put("healthPct", r.healthPct)
        put("toEmptyMs", r.toEmptyMs); put("toFullMs", r.toFullMs); put("toFullSource", r.toFullSource.name)
        put("toEmptyAtAvgMs", r.toEmptyAtAvgMs); put("toFullAtAvgMs", r.toFullAtAvgMs)
        put("systemCycles", r.systemCycles); put("cycleEstimate", r.cycleEstimate)
        put("cumulativeChargedUah", r.cumulativeChargedUah); put("peakFullUah", r.peakFullUah)
        put("chargerLiveW", r.chargerLiveW); put("chargerMaxW", r.chargerMaxW); put("chargerSource", r.chargerSource)
        r.since?.let { s ->
            put("since", org.json.JSONObject().put("fromTs", s.fromTs).put("fromPct", s.fromPct).put("approximate", s.approximate)
                .put("wallMs", s.wallMs).put("onBatteryMs", s.onBatteryMs).put("usedPct", s.usedPct)
                .put("topUps", s.topUps).put("topUpPct", s.topUpPct)
                .put("avgPctPerHour", s.avgPctPerHour ?: org.json.JSONObject.NULL).put("avgW", r.sinceAvgW ?: org.json.JSONObject.NULL)
                .put("screenOnMs", s.screenOnMs).put("screenOffMs", s.screenOffMs)
                .put("screenOnPct", s.screenOnPct).put("screenOffPct", s.screenOffPct))
        } ?: put("since", null)
        val f = com.diegonmarcos.superapp.battery.BatteryTruth.powerFlow(r)
        put("powerFlow", org.json.JSONObject().put("netW", f.netW ?: org.json.JSONObject.NULL)
            .put("consumptionW", f.consumptionW ?: org.json.JSONObject.NULL).put("consumptionSource", f.consumptionSource)
            .put("inW", f.inW ?: org.json.JSONObject.NULL).put("inEstimated", f.inEstimated))
        val sessions = org.json.JSONArray()
        for (s in r.sessions.takeLast(60)) sessions.put(org.json.JSONObject()
            .put("charging", s.charging).put("startTs", s.startTs).put("endTs", s.endTs)
            .put("startPct", s.startPct).put("endPct", s.endPct).put("plugged", s.plugged).put("ongoing", s.ongoing)
            .put("pctPerHour", s.pctPerHour ?: org.json.JSONObject.NULL).put("avgW", s.avgW ?: org.json.JSONObject.NULL)
            .put("maxTempC", s.maxTempC ?: org.json.JSONObject.NULL))
        put("sessions", sessions)
        return o.toString()
    }

    /** Every raw battery value as the device reports it, grouped by where it
     *  comes from — read by the SoT's own BatteryRepository.rawDump, so no
     *  BatteryManager property is read anywhere else in the app. */
    private fun batteryPropertiesJson(ctx: Context): String {
        val bm = org.json.JSONObject(); val sticky = org.json.JSONObject()
        for ((k, v) in com.diegonmarcos.superapp.battery.BatteryRepository.rawDump(ctx)) {
            val (group, key) = k.substringBefore('.') to k.substringAfter('.')
            (if (group == "bm") bm else sticky).put(key, v ?: org.json.JSONObject.NULL)
        }
        return org.json.JSONObject().put("batteryManager", bm).put("stickyExtras", sticky).toString()
    }

    /** Per-path readability snapshot of every kernel sysfs/proc file
     *  the app touches. Sorted: failing paths first so the user sees
     *  what's blocked on their device immediately, then OKs.
     *  The frontline answer to "AccuBattery's trick isn't working
     *  for me" — exposes whether the kernel actually allows the
     *  read or whether SELinux is denying it. */
    private fun sysfsDiagnosticJson(): String {
        val all = com.diegonmarcos.superapp.battery.SysfsProc.sysfsReadDiagnostic()
        val sorted = all.sortedBy { (_, status) -> if (status.startsWith("✗")) 0 else 1 }
        val sb = StringBuilder("""{"paths":[""")
        var first = true
        for ((path, status) in sorted) {
            if (!first) sb.append(','); first = false
            sb.append('{')
            sb.append(""""path":"""").append(jsonEscape(path)).append('"').append(',')
            sb.append(""""status":"""").append(jsonEscape(status)).append('"')
            sb.append('}')
        }
        sb.append("],")
        val total = all.size
        val ok = all.count { (_, status) -> status.startsWith("✓") }
        sb.append(""""summary":{"total":""").append(total).append(',')
        sb.append(""""ok":""").append(ok).append(',')
        sb.append(""""blocked":""").append(total - ok).append('}')
        sb.append('}')
        return sb.toString()
    }

    /** Device network layout — every NIC + its IPs, plus each
     *  ConnectivityManager Network with transports + link addresses +
     *  routes. Answers "which interface owns 10.0.0.9 and what's the real
     *  Wi-Fi IP" so the embedded-adb pairing targets the right address. */
    private fun adbNetInfoJson(ctx: Context): String {
        val sb = StringBuilder("{")
        // ── java.net interfaces ──
        sb.append(""""interfaces":[""")
        runCatching {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            var first = true
            for (nif in java.util.Collections.list(ifaces)) {
                val addrs = nif.inetAddresses
                val list = java.util.Collections.list(addrs)
                if (!first) sb.append(','); first = false
                sb.append('{')
                sb.append(""""name":"""").append(jsonEscape(nif.name)).append('"').append(',')
                sb.append(""""up":""").append(runCatching { nif.isUp }.getOrDefault(false)).append(',')
                sb.append(""""addrs":[""")
                list.forEachIndexed { i, a ->
                    if (i > 0) sb.append(',')
                    sb.append('"').append(jsonEscape(a.hostAddress ?: "")).append('"')
                }
                sb.append("]}")
            }
        }
        sb.append("],")
        // ── ConnectivityManager networks ──
        sb.append(""""networks":[""")
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.allNetworks.forEachIndexed { i, n ->
                if (i > 0) sb.append(',')
                val caps = cm.getNetworkCapabilities(n)
                val lp = cm.getLinkProperties(n)
                sb.append('{')
                sb.append(""""wifi":""").append(caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ?: false).append(',')
                sb.append(""""cell":""").append(caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ?: false).append(',')
                sb.append(""""vpn":""").append(caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ?: false).append(',')
                sb.append(""""iface":"""").append(jsonEscape(lp?.interfaceName ?: "")).append('"').append(',')
                sb.append(""""addrs":[""")
                lp?.linkAddresses?.forEachIndexed { j, la ->
                    if (j > 0) sb.append(',')
                    sb.append('"').append(jsonEscape(la.address.hostAddress ?: "")).append('"')
                }
                sb.append("]}")
            }
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Shell-channel ladder status (self-contained local-server first,
     *  Shizuku fallback) + the data-driven bundle ids available via
     *  /api/adb/diagnostics. */
    private fun adbStatusJson(ctx: Context): String {
        val channels = com.diegonmarcos.superapp.adbdebug.ShellChannels.all
        val active = com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)
        val bundles = com.diegonmarcos.superapp.adbdebug.AdbDiagnostics.bundleIds()
        val dumpHeld = ctx.checkSelfPermission("android.permission.DUMP") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val sb = StringBuilder("{")
        sb.append(""""active":"""").append(jsonEscape(active?.name() ?: "none")).append('"').append(',')
        sb.append(""""dump_in_process":""").append(dumpHeld).append(',')
        sb.append(""""channels":[""")
        channels.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append(""""name":"""").append(jsonEscape(c.name())).append('"').append(',')
            sb.append(""""ready":""").append(c.isReady(ctx)).append(',')
            sb.append(""""status":"""").append(jsonEscape(c.status(ctx))).append('"')
            sb.append('}')
        }
        sb.append("],")
        // Wireless Debugging keep-alive: is the switch on, did the last pass
        // get the channel back, and if not, why.
        sb.append(""""keepalive":{""")
        com.diegonmarcos.superapp.system.WirelessDebugKeeper.Status.rows(ctx).forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(jsonEscape(k)).append("\":\"").append(jsonEscape(v)).append('"')
        }
        sb.append("},")
        sb.append(""""bundles":[""")
        bundles.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            sb.append('"').append(jsonEscape(b)).append('"')
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * How long [dispatchToHost] waits for the main thread to run the work it
     * posted before it calls the request undelivered.
     *
     * Not a budget for the work itself — a navigation hop or a haptic pulse is
     * microseconds once the Looper picks it up. It is the bound on a main thread
     * that is wedged or being torn down, and therefore the bound on how long one
     * debug-server connection can hold the accept loop.
     */
    private const val HOST_DISPATCH_TIMEOUT_SECONDS = 5L

    /**
     * What actually became of a request that needed the foreground Activity,
     * and the sentence that says so.
     *
     * Deliberately the same shape as Updater.Ack (#280): the {"ok","message"}
     * pair already on the wire for POST /api/system/update, so a caller that
     * understands that route needs no new vocabulary for these.
     *
     * [reason] is the machine-readable half, and it exists because `ok` alone
     * cannot separate "the app was not listening" from "the app refused". Those
     * two want opposite things from whoever is driving the fleet — the first is
     * "wake the screen and retry", the second is "stop retrying and read the
     * stack" — and a caller that cannot tell them apart will do the wrong one.
     *
     * Values: delivered | no_live_host | host_timeout | host_threw.
     */
    private class HostDispatch<T>(
        val reason: String,
        val message: String,
        val value: T?,
    ) {
        val delivered: Boolean get() = reason == "delivered"

        /** 200 only for work observed to have run; 503 for "ask again later". */
        val status: String get() = when (reason) {
            "delivered"  -> "200 OK"
            "host_threw" -> "500 Internal Server Error"
            else         -> "503 Service Unavailable"
        }
    }

    /** The wire body for a [HostDispatch], plus one optional echoed parameter. */
    private fun ackJson(d: HostDispatch<*>, field: String = "", value: String = ""): String {
        val echo = if (field.isEmpty()) "" else ",\"$field\":\"${jsonEscape(value)}\""
        return "{\"ok\":${d.delivered},\"reason\":\"${d.reason}\"," +
            "\"message\":\"${jsonEscape(d.message)}\"$echo}"
    }

    /**
     * Run [work] against the registered foreground Activity and report whether a
     * live Activity actually ran it.
     *
     * THE ROUTES THAT NEEDED THIS WERE ANSWERING "ok" TO NOTHING AT ALL.
     *
     * DevControlBridge.runOnMain is a bare Handler.post, and DevControlBridge
     * .host() is a WeakReference the Activity registers in onResume and clears
     * in onPause. The handlers for nav/goto, nav/action, haptic and state each
     * posted their work and replied on the very next line — on the server
     * thread, before the posted block had run. With the app backgrounded, host()
     * was null, the safe-call discarded the request, and the caller was told
     * "ok\n". Every automated check standing on those routes was passing on a
     * reply that asserted nothing, which is a test that asserts nothing wearing
     * a transport layer (#367).
     *
     * WHY THERE IS NO QUEUE HERE, UNLIKE system/update. An update can be
     * enqueued now and acknowledged later, so #280 routed it around the Activity
     * through WorkManager and made the enqueue the outcome. Navigation has no
     * such escape: it needs the live Activity, so there is nothing to queue and
     * no honest way to defer. A navigation request that arrives with no live
     * host is legitimately a FAILURE, and the only correct thing this can do is
     * say so rather than paper over it.
     *
     * WHY THIS BLOCKS, for the reason Updater.requestCheck states verbatim: the
     * debug server handles each connection inline on its own accept-loop thread,
     * never on the main Looper. Waiting costs this one request and nothing else,
     * and a reply sent before the work resolved is the same unverified optimism
     * this function exists to delete.
     */
    private fun <T> dispatchToHost(
        what: String,
        work: (DevControlBridge.ActivityHost) -> T,
    ): HostDispatch<T> {
        val hadHost = AtomicBoolean(false)
        val value = AtomicReference<T?>(null)
        val thrown = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)

        DevControlBridge.runOnMain {
            try {
                DevControlBridge.host()?.let { host ->
                    hadHost.set(true)
                    value.set(work(host))
                }
            } catch (t: Throwable) {
                thrown.set(t)
            } finally {
                done.countDown()
            }
        }

        if (!done.await(HOST_DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            val msg = "$what was posted to the main thread and had still not run " +
                "${HOST_DISPATCH_TIMEOUT_SECONDS}s later — the UI thread is blocked or " +
                "the process is being torn down. NOT delivered."
            Log.e(TAG, msg)
            return HostDispatch("host_timeout", msg, null)
        }
        thrown.get()?.let { t ->
            val msg = "$what reached the foreground activity and it refused: " +
                "${t.javaClass.simpleName}: ${t.message ?: "no detail"}"
            Log.e(TAG, msg, t)
            return HostDispatch("host_threw", msg, null)
        }
        if (!hadHost.get()) {
            val msg = "$what was NOT delivered: no foreground activity is registered " +
                "with DevControlBridge, so there is no live screen to act on. The " +
                "activity registers in onResume and unregisters in onPause — wake the " +
                "device, bring the app to the foreground, and send this again."
            Log.w(TAG, msg)
            return HostDispatch("no_live_host", msg, null)
        }
        val msg = "$what was delivered to the foreground activity"
        Log.i(TAG, msg)
        return HostDispatch("delivered", msg, value.get())
    }

    private fun reply(
        w: PrintWriter, status: String, body: String,
        contentType: String = "text/plain",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        w.print("HTTP/1.1 $status\r\n")
        w.print("Content-Type: $contentType; charset=utf-8\r\n")
        w.print("Content-Length: ${bytes.size}\r\n")
        w.print("Connection: close\r\n\r\n")
        w.print(body)
        w.flush()
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) return@mapNotNull null
            val k = URLDecoder.decode(it.substring(0, eq), "UTF-8")
            val v = URLDecoder.decode(it.substring(eq + 1), "UTF-8")
            k to v
        }.toMap()
    }

    /** Full About-page data as JSON — app build, device, stack
     *  (languages/frameworks/build times) and the folder/sitemap/AST trees.
     *  Served by GET /api/system/about (auth-free, same nature as
     *  system/info). All sourced from BuildConfig + Build → no UI needed,
     *  reproducible per build. */
    internal fun aboutJson(): String {
        fun dec(s: String) = runCatching {
            String(android.util.Base64.decode(s, android.util.Base64.DEFAULT))
        }.getOrDefault("")
        val langs = dec(BuildConfig.UI_STACK_LANGUAGES_JSON_B64).ifBlank { "{}" }
        val fws   = dec(BuildConfig.UI_STACK_FRAMEWORKS_JSON_B64).ifBlank { "[]" }
        return buildString {
            append("{")
            append(""""app":{""")
            append(""""applicationId":"${jsonEscape(BuildConfig.APPLICATION_ID)}",""")
            append(""""versionName":"${jsonEscape(BuildConfig.VERSION_NAME)}",""")
            append(""""versionCode":${BuildConfig.VERSION_CODE},""")
            append(""""gitSha":"${jsonEscape(BuildConfig.GIT_SHORT_SHA)}",""")
            append(""""buildTimestamp":"${jsonEscape(BuildConfig.BUILD_TIMESTAMP)}",""")
            append(""""buildType":"${jsonEscape(BuildConfig.BUILD_TYPE)}"},""")
            append(""""device":{""")
            append(""""manufacturer":"${jsonEscape(android.os.Build.MANUFACTURER)}",""")
            append(""""model":"${jsonEscape(android.os.Build.MODEL)}",""")
            append(""""android":"${jsonEscape(android.os.Build.VERSION.RELEASE)}",""")
            append(""""sdk":${android.os.Build.VERSION.SDK_INT}},""")
            append(""""stack":{""")
            append(""""languages":$langs,""")
            append(""""frameworks":$fws,""")
            append(""""buildAvgSecs":${BuildConfig.STACK_BUILD_AVG_SECS},""")
            append(""""buildLastSecs":${BuildConfig.STACK_BUILD_LAST_SECS},""")
            append(""""gradleConfigMs":${BuildConfig.STACK_GRADLE_CONFIG_MS}},""")
            append(""""trees":{""")
            append(""""folders":"${jsonEscape(dec(BuildConfig.UI_STACK_FOLDER_TREE_B64))}",""")
            append(""""sitemap":"${jsonEscape(dec(BuildConfig.UI_ASM_TREE_B64))}",""")
            append(""""ast":"${jsonEscape(dec(BuildConfig.UI_AST_TREE_B64))}"}""")
            append("}")
        }
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** Combined debug bundle → the OpenObserve record (reuses the same
     *  logcat/trace/crashes readers). Used by /diagnostics/{bundle,download,push}. */
    private fun diagnosticRecord(ctx: android.content.Context): String {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
        return DiagnosticsPush.buildRecord(
            appId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toString(),
            gitSha = BuildConfig.GIT_SHORT_SHA,
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            androidRelease = android.os.Build.VERSION.RELEASE,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            tsIso = ts,
            logcat = readLogcat(500),
            trace = readTraceTail(ctx, 500),
            crashes = readCrashes(ctx),
        )
    }

    /** Newest N lines from the shared [LogPipe] stream. This app holds
     *  READ_LOGS for the cross-app viewer, so the content is device-wide — and
     *  precisely because of that it must NOT exec its own `logcat`: each exec
     *  would be another unpersistable consent prompt. */
    private fun readLogcat(n: Int): String =
        com.diegonmarcos.superapp.devtools.LogPipe.tail(n)

    /** Reads the tail of Trace.kt's trace.log file. */
    private fun readTraceTail(ctx: android.content.Context, n: Int): String = runCatching {
        val f = java.io.File(ctx.getExternalFilesDir(null), "trace/trace.log")
        if (!f.exists()) return@runCatching "trace.log not present\n"
        val all = f.readLines()
        all.takeLast(n).joinToString("\n") + "\n"
    }.getOrElse { "trace read failed: $it\n" }

    /** Lists + concatenates every crash report from BOTH the private
     *  external-files crash dir and (best-effort) the public Downloads
     *  copies that CrashLogger writes via MediaStore. */
    private fun readCrashes(ctx: android.content.Context): String = runCatching {
        val dir = java.io.File(ctx.getExternalFilesDir(null), "crashes")
        if (!dir.exists()) return@runCatching "no crashes directory yet\n"
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) return@runCatching "no crash files\n"
        files.joinToString("\n\n──────────────────────────\n\n") { "[${it.name}]\n" + it.readText() }
    }.getOrElse { "crash read failed: $it\n" }

    /** The OS's own record of WHY each recent process died —
     *  ActivityManager.getHistoricalProcessExitReasons. CrashLogger sees only
     *  uncaught Java throws; an ANR, a native/hwui crash, an lmkd kill or a
     *  plain signal leaves its store EMPTY, and until this endpoint existed
     *  those deaths were unreadable from off the device (2026-09-18: two
     *  silent Home-screen deaths 30s apart, zero evidence in crashes/, trace
     *  ring quiet — this route is what would have named the killer). For ANR
     *  records the system-captured stack is inlined (head only) so the guilty
     *  frame arrives in the reply rather than staying on a wedged phone. */
    /** The live bottom nav as JSON, read on the main thread from the foreground activity: the
     *  island host's rect, its items in bar order, and which one holds the pill. The island is
     *  Compose (libs:bottomnav, #531), so there is no child-View tree to walk; its geometry is
     *  measured by the lib's own tests and superapp's BottomNavGeometryTest. */
    private fun navGeometryJson(host: DevControlBridge.ActivityHost): String = runCatching {
        val act = host as? android.app.Activity
            ?: return """{"error":"host is not an Activity"}"""
        val nav = (host as? NavIslandSource)?.navIsland()
            ?: return """{"error":"no bottom_nav_island in the foreground activity"}"""
        org.json.JSONObject().apply {
            put("density", act.resources.displayMetrics.density.toDouble())
            put("class", nav.javaClass.name)
            put("rect", org.json.JSONArray(listOf(nav.left, nav.top, nav.right, nav.bottom, nav.width, nav.height)))
            put("visibility", nav.visibility)
            put("selected", nav.selectedId ?: org.json.JSONObject.NULL)
            put("items", org.json.JSONArray(nav.items.map { it.id }))
        }.toString()
    }.getOrElse { """{"error":"${jsonEscape(it.toString())}"}""" }

    private fun readExitReasons(ctx: android.content.Context, n: Int): String {
        // Function-level SDK gate, not inside the lambda: lintVital's NewApi
        // check (fatal on release) reliably traces only this form.
        if (android.os.Build.VERSION.SDK_INT < 30)
            return """{"error":"ApplicationExitInfo requires API 30+"}"""
        return runCatching {
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val reasonNames = mapOf(
            android.app.ApplicationExitInfo.REASON_EXIT_SELF to "EXIT_SELF",
            android.app.ApplicationExitInfo.REASON_SIGNALED to "SIGNALED",
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY to "LOW_MEMORY",
            android.app.ApplicationExitInfo.REASON_CRASH to "CRASH_JAVA",
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE to "CRASH_NATIVE",
            android.app.ApplicationExitInfo.REASON_ANR to "ANR",
            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "INIT_FAILURE",
            android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE to "PERMISSION_CHANGE",
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "EXCESSIVE_RESOURCE",
            android.app.ApplicationExitInfo.REASON_USER_REQUESTED to "USER_REQUESTED",
            android.app.ApplicationExitInfo.REASON_USER_STOPPED to "USER_STOPPED",
            android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED to "DEPENDENCY_DIED",
            android.app.ApplicationExitInfo.REASON_OTHER to "OTHER",
            android.app.ApplicationExitInfo.REASON_FREEZER to "FREEZER",
            android.app.ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE to "PACKAGE_STATE_CHANGE",
            android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED to "PACKAGE_UPDATED",
        )
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val sb = StringBuilder("[")
        am.getHistoricalProcessExitReasons(ctx.packageName, 0, n).forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("""{"when":"""").append(fmt.format(java.util.Date(r.timestamp)))
                .append("""","reason":"""").append(reasonNames[r.reason] ?: "code_${r.reason}")
                .append("""","status":""").append(r.status)
                .append(""","importance":""").append(r.importance)
                .append(""","description":"""").append(jsonEscape(r.description ?: "")).append('"')
            if (r.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                val trace = runCatching {
                    r.traceInputStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                if (trace != null)
                    sb.append(""","anr_trace_head":"""")
                        .append(jsonEscape(trace.take(6000).replace("\t", "  ").replace("\r", "")))
                        .append('"')
            }
            sb.append('}')
        }
        sb.append(']').toString()
        }.getOrElse { """{"error":"${jsonEscape(it.toString())}"}""" }
    }
}

/** The foreground activity's bottom-nav island, for the debug API's geometry route (the activity owns it; the server must not look it up by id). */
interface NavIslandSource {
    fun navIsland(): com.diegonmarcos.superapp.bottomnav.BottomNavIslandView?
}
