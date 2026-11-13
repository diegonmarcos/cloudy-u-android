package com.diegonmarcos.superapp.devcontrol

import android.content.Context

/**
 * #1682 P3 step 4d: the owner's pairing / connect / diagnostics routes of the debug server, moved out of the SuperApp's DevControlServer
 * so the app that owns ADB serves them (the SuperApp today, Cloud Webserver after the flip). The server keeps its auth, its docs table
 * and its routing; for these routes it calls [handle] and writes the reply. Bodies moved verbatim.
 */
object AdbDevRoutes {
    /** What the server writes back: status line, body, and whether it is JSON. */
    class Reply(val status: String, val body: String, val json: Boolean = false)

    /** The routes [handle] serves (the server's `when` sends these here). */
    val ROUTES = setOf("adb/status", "keeper/status", "adb/netinfo", "adb/pair", "adb/autoconnect", "adb/connect",
        "adb/server-command", "adb/diagnostics", "adb/exec", "adb/sfc", "adb/grant-dump")

    /** Best-effort auto-reconnect of the embedded channel at app start (a paired phone with Wireless Debugging on comes back with no port). */
    fun autoConnectAtStart(app: Context) {
        Thread {
            runCatching { com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel.autoArm(app) }
        }.apply { isDaemon = true; name = "adb-autoconnect"; start() }
    }

    fun handle(ctx: Context, route: String, query: Map<String, String>): Reply? = when (route) {
        "adb/status" -> Reply("200 OK", adbStatusJson(ctx), true)
        "keeper/status" -> Reply("200 OK", com.diegonmarcos.superapp.adbdebug.WdKeeper.statusJson(ctx), true)
        "adb/netinfo" -> Reply("200 OK", adbNetInfoJson(ctx), true)
        "adb/pair" -> {
            val r = runCatching { com.diegonmarcos.superapp.adbdebug.AdbPairingService.start(ctx, query["code"]) }
            val msg = if (r.isSuccess) "pairing notification posted — enter the code there (or pass code=)"
                      else "could not start pairing service: ${r.exceptionOrNull()?.message}"
            Reply("200 OK", """{"ok":${r.isSuccess},"message":"${jsonEscape(msg)}"}""", true)
        }
        "adb/autoconnect" -> {
            val (ok, msg) = com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel.autoArm(ctx)
            Reply("200 OK", """{"ok":$ok,"message":"${jsonEscape(msg)}"}""", true)
        }
        "adb/connect" -> {
            val host = query["host"] ?: "127.0.0.1"
            val port = query["port"]?.toIntOrNull()
            if (port == null) Reply("400 Bad Request", "need host=<ip>&port=<connectPort>\n")
            else {
                val (ok, msg) = com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel.connect(ctx, host, port)
                Reply("200 OK", """{"ok":$ok,"message":"${jsonEscape(msg)}"}""", true)
            }
        }
        "adb/server-command" -> {
                    // The one-liner to run ONCE per boot (via adb / Wireless
                    // Debugging) to start OUR shell-domain app_process server.
                    val cmd = com.diegonmarcos.superapp.adbdebug.AdbShellBootstrap.serverCommand(ctx)
                    val body = """{"command":"${jsonEscape(cmd)}","port":${com.diegonmarcos.superapp.adbdebug.AdbShellBootstrap.port()},"note":"Run once per boot. Grants the SHELL SELinux domain our server needs; after this the app is self-contained (no Shizuku app)."}"""
                    Reply("200 OK", body, true)
        }
        "adb/diagnostics" -> Reply("200 OK", com.diegonmarcos.superapp.adbdebug.AdbDiagnostics.runBundle(ctx, query["bundle"] ?: "charger"), true)
        "adb/exec" -> {
            val cmd = query["cmd"]
            if (cmd.isNullOrBlank()) Reply("400 Bad Request", "missing cmd\n")
            else {
                val ch = com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)
                val out = ch?.exec(ctx, cmd)
                    ?: "ERR: no shell channel ready — ${com.diegonmarcos.superapp.adbdebug.LocalShellChannel.status(ctx)}\n"
                Reply("200 OK", out)
            }
        }
        "adb/grant-dump" -> {
            // Self-grant DUMP through whichever shell channel is up (the local server or Shizuku) so dumpsys also works in-process.
            // #1515 the line is built and run by the host's privileged plane (PrivilegedGrants), never here.
            val ch = com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)
            val out = if (ch == null) null else com.diegonmarcos.superapp.configs.AdbOwnerHost.grantDump(ctx) { ch.exec(ctx, it) }
            val held = ctx.checkSelfPermission("android.permission.DUMP") == android.content.pm.PackageManager.PERMISSION_GRANTED
            Reply("200 OK", """{"channel":"${jsonEscape(ch?.name() ?: "none")}","ran":${out != null},"held":$held,"output":"${jsonEscape(out ?: "no shell channel ready")}"}""", true)
        }
        "adb/sfc" -> Reply("200 OK", com.diegonmarcos.superapp.adbdebug.SfcVerdict.run(ctx), true)
        else -> null
    }

    fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** Shell-channel ladder status (self-contained local-server first,
     *  Shizuku fallback) + the data-driven bundle ids available via
     *  /api/adb/diagnostics. */
    fun adbStatusJson(ctx: Context): String {
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

    /** Device network layout — every NIC + its IPs, plus each
     *  ConnectivityManager Network with transports + link addresses +
     *  routes. Answers "which interface owns 10.0.0.9 and what's the real
     *  Wi-Fi IP" so the embedded-adb pairing targets the right address. */
    fun adbNetInfoJson(ctx: Context): String {
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

}
