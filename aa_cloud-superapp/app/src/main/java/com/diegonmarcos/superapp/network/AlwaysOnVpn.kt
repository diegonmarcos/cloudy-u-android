package com.diegonmarcos.superapp.network

import android.content.Context
import android.provider.Settings
import com.diegonmarcos.superapp.bgpolicy.BgHost
import com.diegonmarcos.superapp.net.AidlBackend

/**
 * #1120 Android's Always-on VPN for the mesh engine: its REAL status (Settings.Secure `always_on_vpn_app` /
 * `always_on_vpn_lockdown`) and the change, made through the fleet's privileged shell channel
 * (the shell broker via libs:shell-client: the owner's embedded adb client, its app_process server ...) exactly as
 * FleetDns.writeAndroid changes Private DNS. With no channel armed nothing is attempted; the caller shows
 * [Unavailable.reason] and deep-links to Android's VPN settings.
 *
 * The parsing and the command text are PURE (AlwaysOnVpnTest); [read] and [write] are the thin Android edge.
 */
object AlwaysOnVpn {
    const val KEY_APP = "always_on_vpn_app"
    const val KEY_LOCKDOWN = "always_on_vpn_lockdown"

    /** What Android says right now. [pkg] null = no always-on app; [lockdown] null = unreadable. */
    data class Reading(val pkg: String?, val lockdown: Boolean?, val source: String)

    /** What the page shows: [on] null = unknown; [canChange] false comes with a [reason] and the VPN-settings deep link. */
    data class View(val on: Boolean?, val lockdown: Boolean?, val canChange: Boolean, val channel: String, val reason: String, val pkg: String?)

    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** `settings get` prints "null" for a missing key. */
    fun parseApp(out: String?): String? = out?.trim()?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    fun parseLockdown(out: String?): Boolean? = when (out?.trim()?.lineSequence()?.firstOrNull()?.trim()) {
        "1" -> true
        "0", "null" -> false
        else -> null
    }

    fun isOurs(pkg: String?, engine: String = AidlBackend.ENGINE_PKG): Boolean = pkg != null && pkg == engine

    /** The shell lines that switch Always-on [on] for [pkg]; lockdown goes off with it. */
    fun commands(on: Boolean, pkg: String): List<String> {
        require(PKG.matches(pkg)) { "not a package name: $pkg" }
        return if (on) listOf("settings put secure $KEY_APP $pkg")
        else listOf("settings delete secure $KEY_APP", "settings put secure $KEY_LOCKDOWN 0")
    }

    fun lockdownCommand(on: Boolean) = "settings put secure $KEY_LOCKDOWN ${if (on) 1 else 0}"

    /** The reason shown when the privileged channel is not there to change it. */
    const val NO_CHANNEL = "No privileged shell is armed (Configs > ADB Shell: pair Wireless debugging, or start Shizuku), so this app cannot change Android's setting. Open Android VPN settings to switch it there."

    fun read(ctx: Context): Reading {
        val cr = ctx.contentResolver
        val direct = runCatching { Settings.Secure.getString(cr, KEY_APP) to Settings.Secure.getString(cr, KEY_LOCKDOWN) }.getOrNull()
        if (direct != null && (direct.first != null || direct.second != null)) {
            return Reading(parseApp(direct.first), parseLockdown(direct.second), "settings")
        }
        val ch = runCatching { BgHost.shell(ctx) }.getOrNull()
        if (ch != null) {
            val app = runCatching { ch.exec("settings get secure $KEY_APP") }.getOrNull()
            val ld = runCatching { ch.exec("settings get secure $KEY_LOCKDOWN") }.getOrNull()
            if (app != null || ld != null) return Reading(parseApp(app), parseLockdown(ld), ch.channel)
        }
        // Nothing was set (a missing key reads null) or nothing could be read: no always-on app is the honest default.
        return Reading(null, if (direct != null) false else null, if (direct != null) "settings" else "none")
    }

    fun view(ctx: Context, engineAlwaysOn: Boolean?): View {
        val r = read(ctx)
        val ch = runCatching { BgHost.shell(ctx) }.getOrNull()
        val on = when {
            r.source == "none" -> engineAlwaysOn
            else -> isOurs(r.pkg) || engineAlwaysOn == true
        }
        return View(on, r.lockdown, ch != null, ch?.channel.orEmpty(), if (ch == null) NO_CHANNEL else "", r.pkg)
    }

    /** Switch Always-on for the engine; returns what happened, throws with the reason when it cannot. */
    fun write(ctx: Context, on: Boolean, engine: String = AidlBackend.ENGINE_PKG): String {
        val ch = BgHost.shell(ctx) ?: error(NO_CHANNEL)
        val out = commands(on, engine).joinToString("; ") { ch.exec(it) ?: error("the ${ch.channel} channel refused: $it") }
        val after = read(ctx)
        check(isOurs(after.pkg, engine) == on) {
            "the ${ch.channel} channel ran it but Android still reports always-on = ${after.pkg ?: "none"}" + (if (out.isNotBlank()) " ($out)" else "")
        }
        return if (on) "Always-on VPN is on for the mesh engine (via ${ch.channel})" else "Always-on VPN is off (via ${ch.channel})"
    }

    fun writeLockdown(ctx: Context, on: Boolean): String {
        val ch = BgHost.shell(ctx) ?: error(NO_CHANNEL)
        ch.exec(lockdownCommand(on)) ?: error("the ${ch.channel} channel refused the lockdown change")
        val after = read(ctx)
        check(after.lockdown == on) { "the ${ch.channel} channel ran it but Android still reports lockdown = ${after.lockdown}" }
        return if (on) "Kill switch (lockdown) is on - traffic is blocked without the VPN (via ${ch.channel})" else "Kill switch (lockdown) is off (via ${ch.channel})"
    }
}
