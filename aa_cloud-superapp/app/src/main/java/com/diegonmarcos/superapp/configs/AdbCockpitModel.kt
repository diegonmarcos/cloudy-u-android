package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.adbdebug.ChannelAction
import com.diegonmarcos.superapp.adbdebug.ChannelActions
import com.diegonmarcos.superapp.adbdebug.ChannelFacts
import com.diegonmarcos.superapp.adbdebug.ChannelLog
import com.diegonmarcos.superapp.adbdebug.ChannelMode
import com.diegonmarcos.superapp.adbdebug.ChannelState
import com.diegonmarcos.superapp.adbdebug.ConnState
import com.diegonmarcos.superapp.adbdebug.ConnectPlan
import com.diegonmarcos.superapp.adbdebug.LaunchVia
import com.diegonmarcos.superapp.adbdebug.ShizukuState

/**
 * The ADB Shell page's tabs and its Status cockpit as DATA (pure, no Android): the four tabs, the dependency-ordered step chain
 * (Perms, Setup, Shizuku, Local Server, Embedded, Connection), each step's light / message / actions, the master health pill and
 * the Auto-fix order. Every input is a [ChannelFacts] (the real probe of the device, `ChannelReader.read`) or a [PermsDigest]
 * (the real Permissions catalogue); nothing here is a mock. [AdbCockpit] draws exactly this.
 */
internal object AdbCockpitModel {
    /** The page's tabs, declared in build.json (`adb-shell.tabs`, hidden pages); Status is the first and the landing tab. */
    enum class Tab(val pageId: String, val label: String) {
        STATUS("adb-status", "Status"), SHELL("adb-term", "Shell"), PERMS("adb-perms", "Perms"), ALL("adb-all", "All");

        companion object { fun of(pageId: String?): Tab = values().firstOrNull { it.pageId == pageId } ?: ALL }
    }

    enum class Light { GREEN, AMBER, RED, GREY }

    /** The step ids, in dependency order: each row depends on the ones above it. */
    const val PERMS = "perms"
    const val SETUP = "setup"
    const val SHIZUKU = "shizuku"
    const val LOCAL = "local"
    const val EMBEDDED = "embedded"
    const val CONNECTION = "connection"
    val STEP_ORDER = listOf(PERMS, SETUP, SHIZUKU, LOCAL, EMBEDDED, CONNECTION)

    /** Every verb a row offers. */
    enum class Do(val label: String) {
        OPEN_PERMS("Perms"), OPEN_DEV("Dev Options"), OPEN_WD("Wireless Debug"),
        SHIZUKU_START("Start"), SHIZUKU_AUTH("Authorize"),
        LOCAL_PAIR("Pair"), LOCAL_START("Start"), LOCAL_STOP("Stop"), LOCAL_RESTART("Restart"),
        EMB_INIT("Init"), EMB_RESET("Reset"),
        CONNECT("Connect"), DISCONNECT("Disconnect"), RECONNECT("Reconnect"),
    }

    data class Act(val what: Do, val enabled: Boolean = true, val why: String? = null, val primary: Boolean = false)
    data class Sub(val label: String, val light: Light)
    data class Step(
        val id: String, val name: String, val light: Light, val message: String, val acts: List<Act> = emptyList(),
        val subs: List<Sub> = emptyList(), val dim: Boolean = false, val badge: String? = null,
    )

    /** What the Permissions catalogue says about the permissions the channel needs: [missing] names, and whether pairing's notification is one. */
    data class PermsDigest(val total: Int, val missing: List<String>, val notificationsMissing: Boolean = false)

    /** Inputs of the chain besides the facts. [lastFailure] = why the last Connect / Auto-fix run FAILED (not "waiting for you"), or null. */
    data class Inputs(val facts: ChannelFacts, val perms: PermsDigest?, val via: LaunchVia = LaunchVia.ADB, val lastFailure: String? = null)

    /** The mode selector's three entries; AUTO is a fourth stored value the selector shows as "Auto" with every mode row live. */
    val SELECTABLE = listOf(ChannelMode.SHIZUKU, ChannelMode.LOCAL_SERVER, ChannelMode.EMBEDDED_ONLY)

    fun modeName(m: ChannelMode): String = when (m) {
        ChannelMode.SHIZUKU -> "Shizuku"; ChannelMode.LOCAL_SERVER -> "Local Server"; ChannelMode.EMBEDDED_ONLY -> "Embedded"; ChannelMode.AUTO -> "Auto"
    }

    /**
     * The mode rows the chosen mode uses (highlighted); the others dim to "switch mode to use". Every mode but Auto is ONE
     * self-contained channel: Local Server is its own row and never Shizuku's, whatever the launcher choice says.
     */
    @Suppress("UNUSED_PARAMETER")
    fun active(mode: ChannelMode, via: LaunchVia = LaunchVia.ADB): Set<String> = when (mode) {
        ChannelMode.SHIZUKU -> setOf(SHIZUKU)
        ChannelMode.LOCAL_SERVER -> setOf(LOCAL)
        ChannelMode.EMBEDDED_ONLY -> setOf(EMBEDDED)
        ChannelMode.AUTO -> setOf(SHIZUKU, LOCAL, EMBEDDED)
    }

    private fun tri(v: Boolean?): Light = when (v) { true -> Light.GREEN; false -> Light.AMBER; null -> Light.GREY }

    fun perms(d: PermsDigest?, f: ChannelFacts): Step {
        val acts = listOf(Act(Do.OPEN_PERMS))
        if (d == null) return Step(PERMS, "Perms", Light.GREY, "checking...", acts, badge = "light only")
        if (d.missing.isEmpty()) return Step(PERMS, "Perms", Light.GREEN, if (d.total == 0) "nothing required" else "all granted", acts, badge = "light only")
        // Pairing types its code into a notification: without it the adb path cannot even start, so that one is red.
        val blocked = d.notificationsMissing && !f.adbPaired && f.mode != ChannelMode.SHIZUKU
        return Step(PERMS, "Perms", if (blocked) Light.RED else Light.AMBER,
            "${d.missing.size} missing" + (": " + d.missing.take(2).joinToString(", ") + if (d.missing.size > 2) "..." else ""), acts, badge = "light only")
    }

    fun setup(f: ChannelFacts): Step {
        val used = ChannelState.used(ChannelState.ROW_DEV, f.mode)
        val dev = tri(f.devOptions)
        val wd = when { f.wirelessDebug == true && f.onWifi == false -> Light.AMBER; else -> tri(f.wirelessDebug) }
        val light = if (!used) Light.GREY else listOf(dev, wd).let { l ->
            when { Light.AMBER in l -> Light.AMBER; Light.GREY in l -> Light.GREY; else -> Light.GREEN }
        }
        val msg = when {
            !used -> "not used in ${modeName(f.mode)} mode"
            f.devOptions == false -> "Developer Options are off"
            f.wirelessDebug == false -> "Wireless Debugging is off"
            f.wirelessDebug == true && f.onWifi == false -> "Wireless Debugging is on, but the phone is not on Wi-Fi"
            f.devOptions == null || f.wirelessDebug == null -> "state unknown"
            else -> "Developer Options on, Wireless Debugging on"
        }
        return Step(SETUP, "Setup", light, msg,
            listOf(Act(Do.OPEN_DEV, primary = used && f.devOptions != true), Act(Do.OPEN_WD, primary = used && f.devOptions == true && f.wirelessDebug != true)),
            subs = listOf(Sub("Developer Options", dev), Sub("Wireless Debugging", wd)))
    }

    private fun dimMsg(on: Boolean, msg: String) = if (on) msg else "switch mode to use · $msg"

    fun shizuku(f: ChannelFacts, on: Boolean): Step {
        val s = f.shizuku
        val (light, msg) = when (s) {
            ShizukuState.UP -> Light.GREEN to ("installed · running · authorized" + (f.shizukuVersion?.let { " ($it)" } ?: ""))
            ShizukuState.PERMISSION_NEEDED -> Light.AMBER to "running · authorization needed"
            ShizukuState.NOT_RUNNING -> Light.AMBER to "installed · not running"
            ShizukuState.NOT_INSTALLED -> Light.RED to "not installed"
        }
        return Step(SHIZUKU, "Shizuku", if (on) light else Light.GREY, dimMsg(on, msg),
            listOf(
                Act(Do.SHIZUKU_START, s != ShizukuState.NOT_INSTALLED, if (s == ShizukuState.NOT_INSTALLED) "Shizuku is not installed" else null, primary = on && s == ShizukuState.NOT_RUNNING),
                Act(Do.SHIZUKU_AUTH, s == ShizukuState.PERMISSION_NEEDED, if (s == ShizukuState.PERMISSION_NEEDED) null else "needs Shizuku running first", primary = on && s == ShizukuState.PERMISSION_NEEDED)),
            dim = !on, badge = if (on) "active mode" else "inactive")
    }

    /**
     * The Local Server row: the adbd connection on 127.0.0.1 IS this mode's channel, so the light is green only when a real shell
     * round trip answers over it ([ChannelFacts.adbConnected] is an `echo` over the session, [ChannelFacts.serverRunning] a probe of
     * the loopback server), never a socket flag. The loopback server is an optional extra with its own Start / Stop / Restart; it is
     * launched over the adb session only and never by Connect or Auto-fix. Nothing in this row touches Shizuku.
     */
    @Suppress("UNUSED_PARAMETER")
    fun local(f: ChannelFacts, on: Boolean, via: LaunchVia = LaunchVia.ADB): Step {
        if (!f.ownsServer) return Step(LOCAL, "Local Server", Light.GREY, "this app runs no server of its own", dim = true, badge = "inactive")
        val en = ChannelActions.enablement(f)
        fun e(a: ChannelAction) = en.getValue(a)
        val adbPart = when {
            f.adbConnected -> "paired · connected" + (f.adbEndpoint?.let { " $it" } ?: "")
            f.adbPaired -> "paired · not connected"
            else -> "not paired"
        }
        val detail = listOfNotNull(f.serverUid, f.serverUptime?.let { "up $it" }).joinToString(", ")
        val serverPart = if (f.serverRunning) "server up" + (if (detail.isNotEmpty()) " ($detail)" else "") else "server not started (optional)"
        val shellUid = f.serverUid == null || f.serverUid == "uid 2000" || f.serverUid == "uid 0"
        val answers = f.adbConnected || (f.serverRunning && shellUid)
        val light = if (answers) Light.GREEN else Light.AMBER
        return Step(LOCAL, "Local Server", if (on) light else Light.GREY, dimMsg(on, "$adbPart · $serverPart"),
            listOf(
                Act(Do.LOCAL_PAIR, primary = on && !f.adbPaired),
                Act(Do.LOCAL_START, e(ChannelAction.RESTART_SERVER).enabled && !f.serverRunning, e(ChannelAction.RESTART_SERVER).why ?: if (f.serverRunning) "already running" else null),
                Act(Do.LOCAL_STOP, e(ChannelAction.STOP_SERVER).enabled, e(ChannelAction.STOP_SERVER).why),
                Act(Do.LOCAL_RESTART, e(ChannelAction.RESTART_SERVER).enabled, e(ChannelAction.RESTART_SERVER).why)),
            subs = listOf(Sub("adbd round trip", if (f.adbConnected) Light.GREEN else if (on) Light.AMBER else Light.GREY),
                Sub("Loopback server", if (f.serverRunning && shellUid) Light.GREEN else Light.GREY)),
            dim = !on, badge = if (on) "active mode" else "inactive")
    }

    fun embedded(f: ChannelFacts, on: Boolean): Step {
        val (light, msg) = when {
            f.adbConnected -> Light.GREEN to ("paired · connected" + (f.adbEndpoint?.let { " $it" } ?: ""))
            f.adbPaired -> Light.AMBER to "paired · not connected"
            else -> Light.AMBER to "not paired"
        }
        return Step(EMBEDDED, "Embedded", if (on) light else Light.GREY, dimMsg(on, msg),
            listOf(Act(Do.EMB_INIT, primary = on && !f.adbConnected), Act(Do.EMB_RESET, f.adbPaired || f.adbConnected, if (f.adbPaired || f.adbConnected) null else "nothing paired yet")),
            dim = !on, badge = if (on) "active mode" else "inactive")
    }

    /**
     * Can Connect do its job from the active mode row's state? It must not be waiting on the PERSON: Shizuku running and authorized,
     * the adb pairing done (then the connect is automatic), or a server already answering. [id] = the mode row. Each row reads only
     * its own channel's facts.
     */
    @Suppress("UNUSED_PARAMETER")
    fun modeReady(id: String, f: ChannelFacts, via: LaunchVia = LaunchVia.ADB): Boolean = when (id) {
        SHIZUKU -> f.shizuku == ShizukuState.UP
        EMBEDDED -> f.adbPaired
        LOCAL -> f.serverRunning || f.adbPaired
        else -> true
    }

    /** Names of the upstream steps Connect is waiting on (Setup, and the active mode rows that need the person); empty = Connect can be tried. */
    fun notReady(steps: List<Step>, f: ChannelFacts, via: LaunchVia): List<String> {
        val act = active(f.mode, via)
        val modeRows = steps.filter { it.id in act }
        // Auto uses any one of its rows: it waits only when none is ready.
        val modeWait = if (f.mode == ChannelMode.AUTO) (if (modeRows.any { modeReady(it.id, f, via) }) emptyList() else modeRows.map { it.name })
        else modeRows.filter { !modeReady(it.id, f, via) }.map { it.name }
        val setup = steps.firstOrNull { it.id == SETUP }
        return listOfNotNull(setup?.takeIf { it.light != Light.GREEN && ChannelState.used(ChannelState.ROW_DEV, f.mode) }?.name) + modeWait
    }

    fun connection(i: Inputs, upstream: List<Step>): Step {
        val f = i.facts
        val conn = ChannelState.connState(f)
        val waiting = notReady(upstream, f, i.via)
        val (light, msg) = when {
            conn == ConnState.UP -> Light.GREEN to "connected via ${ChannelState.route(f)}"
            conn == ConnState.DEGRADED -> Light.AMBER to "degraded via ${ChannelState.route(f)}: ${ChannelState.degradedWhy(f)}"
            conn == ConnState.CONNECTING -> Light.AMBER to "connecting..."
            waiting.isNotEmpty() -> Light.GREY to "waiting on " + waiting.joinToString(" + ")
            i.lastFailure != null -> Light.RED to i.lastFailure
            else -> Light.AMBER to "ready · not connected"
        }
        val en = ChannelActions.enablement(f)
        return Step(CONNECTION, "Connection", light, msg, listOf(
            Act(Do.CONNECT, conn != ConnState.CONNECTING, if (conn == ConnState.CONNECTING) "a connect is running" else null, primary = conn == ConnState.DOWN),
            Act(Do.RECONNECT, en.getValue(ChannelAction.RECONNECT).enabled, en.getValue(ChannelAction.RECONNECT).why)))
    }

    /** The whole chain in [STEP_ORDER]. */
    fun steps(i: Inputs): List<Step> {
        val f = i.facts
        val act = active(f.mode, i.via)
        val head = listOf(perms(i.perms, f), setup(f), shizuku(f, SHIZUKU in act), local(f, LOCAL in act, i.via), embedded(f, EMBEDDED in act))
        return head + connection(i, head)
    }

    /** The master health pill: green connected / amber ready-but-off (or degraded) / red blocked / grey idle. */
    data class Master(val light: Light, val label: String, val detail: String)

    fun master(i: Inputs?): Master {
        if (i == null) return Master(Light.GREY, "Idle", "checking...")
        val f = i.facts
        val steps = steps(i)
        val conn = steps.last()
        val act = active(f.mode, i.via)
        return when (ChannelState.connState(f)) {
            ConnState.UP -> Master(Light.GREEN, "Connected", conn.message)
            ConnState.DEGRADED -> Master(Light.AMBER, "Degraded", conn.message)
            ConnState.CONNECTING -> Master(Light.AMBER, "Connecting", conn.message)
            ConnState.DOWN -> {
                val up = steps.filter { it.id == PERMS || it.id == SETUP || it.id in act }
                val waiting = notReady(steps, f, i.via)
                when {
                    up.any { it.light == Light.RED } -> Master(Light.RED, "Blocked", up.first { it.light == Light.RED }.let { it.name + ": " + it.message })
                    conn.light == Light.RED -> Master(Light.RED, "Blocked", conn.message)
                    waiting.isEmpty() -> Master(Light.AMBER, "Ready", "ready · not connected")
                    up.none { it.light == Light.AMBER } -> Master(Light.GREY, "Idle", conn.message)
                    else -> Master(Light.AMBER, "Setup needed", conn.message)
                }
            }
        }
    }

    /** The top bar's toggle: Disconnect while a route answers, else Connect. */
    fun toggle(f: ChannelFacts): Act {
        val up = ChannelState.route(f) != null
        if (!up) return Act(Do.CONNECT, !f.connecting, if (f.connecting) "a connect is running" else null, primary = true)
        val en = ChannelActions.enablement(f).getValue(ChannelAction.DISCONNECT)
        return Act(Do.DISCONNECT, en.enabled, en.why)
    }

    /**
     * The keep-alive panel on the Status cockpit: the ONE "Keep Wireless debugging on" switch (its state in [on]) plus the keeper's
     * real state read from [WdKeeper] — never a proxy (rule 16). [light] GREEN = switch on, the foreground keeper up and the channel
     * armed; AMBER = on but idle or not yet armed; RED = on but it cannot work (no WRITE_SECURE_SETTINGS); GREY = switch off.
     */
    data class KeepAlive(val on: Boolean, val light: Light, val status: String, val detail: String, val subs: List<Sub>)

    /**
     * Pure: shape the keep-alive panel from the switch and the keeper facts. [armed] Wireless debugging re-armed, [serviceRunning]
     * the foreground keeper thread/service is up, [canWrite] WRITE_SECURE_SETTINGS held, [batteryExempt] battery-optimisation off,
     * [lastRearm] a human "x ago" of the last re-arm or null.
     */
    fun keepAlive(on: Boolean, armed: Boolean, serviceRunning: Boolean, canWrite: Boolean, batteryExempt: Boolean, lastRearm: String?): KeepAlive {
        val light = when {
            !on -> Light.GREY
            !canWrite -> Light.RED
            serviceRunning && armed -> Light.GREEN
            else -> Light.AMBER
        }
        val status = when {
            !on -> "off"
            light == Light.RED -> "cannot run"
            serviceRunning -> "keeping the channel alive"
            else -> "on · idle"
        }
        val subs = listOf(
            Sub("Service", if (serviceRunning) Light.GREEN else if (on) Light.AMBER else Light.GREY),
            Sub("Armed", if (armed) Light.GREEN else if (on) Light.AMBER else Light.GREY),
            Sub("Secure settings", if (canWrite) Light.GREEN else Light.RED),
            Sub("Battery", if (batteryExempt) Light.GREEN else Light.AMBER),
        )
        val detail = if (!on) "the channel is not re-armed on its own; turn it on to keep the shell reachable"
            else (lastRearm?.let { "re-armed $it" } ?: "no re-arm yet") +
                (if (!canWrite) " · needs Write secure settings" else "") +
                (if (!batteryExempt) " · battery-optimised (may be killed)" else "")
        return KeepAlive(on, light, status, detail, subs)
    }

    /**
     * What Auto-fix walks, in order: grant the perms, then the SELECTED mode's own connect plan and nothing of another mode's
     * (adb modes: Developer Options, Wireless Debugging, pair, connect; Shizuku mode: Shizuku running, authorized), ending on a real
     * shell round trip. There is no server-start step: a connected adbd session is the channel.
     */
    fun autoFixOrder(mode: ChannelMode, via: LaunchVia, ownsServer: Boolean): List<String> =
        listOf("PERMS") + ConnectPlan.steps(mode, via, ownsServer).map { it.name }

    /** Auto-fix hands over to the person at these steps and never forces past them (the consent gates). */
    val USER_GATES = setOf("DEV_OPTIONS", "WIRELESS_DEBUG", "PAIR", "SHIZUKU_RUNNING", "SHIZUKU_PERMISSION")

    // ---- Logs ----
    enum class Sev { ERR, OK, INFO }

    private val ERR = Regex("(?i)fail|error|denied|refus|could not|did not|not running|no route|blocked|not paired|not connected|unreachable|timed out")
    private val OKW = Regex("(?i)\\bok\\b|\\bup\\b|running|\\bconnected\\b|done|granted|already ok|\\bon\\b")

    /** Severity tint of a log line: errors red, ok green, the rest plain. Errors win. */
    fun severity(e: ChannelLog.Entry): Sev = when {
        ERR.containsMatchIn(e.text) -> Sev.ERR
        OKW.containsMatchIn(e.text) -> Sev.OK
        else -> Sev.INFO
    }

    /** The filter box: empty = all; otherwise case-insensitive over the tag and the text. */
    fun filter(log: List<ChannelLog.Entry>, q: String): List<ChannelLog.Entry> =
        if (q.isBlank()) log else log.filter { it.tag.contains(q, true) || it.text.contains(q, true) }
}
