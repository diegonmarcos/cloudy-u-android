package com.diegonmarcos.superapp.configs

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.system.AdbReadiness

/**
 * #1833 Quick actions ▸ Panels ▸ Security: the capability behind its tiles. [DeviceControls.byId] holds one entry per id (the
 * registry the declaration is checked against); each entry calls in here.
 *
 * Same honesty rules as every other tile: a read looks at the device (Settings.Global / Settings.Secure, ConnectivityManager,
 * LocationManager, a real shell round trip), never at a preference; a write goes WRITE_SECURE_SETTINGS first, else through the
 * shell broker (libs:shell-broker allowlist: `settings put|delete`, `cmd location set-location-enabled`), and its verdict is the
 * re-read.
 */
object SecurityControls {

    /** A settings write's outcome: what was done, and whether the re-read agrees. */
    data class Write(val ok: Boolean, val detail: String)

    // ── Accessibility services of the fleet ────────────────────────────────

    /** The fleet's own accessibility services, by control id. The component is the one each app's manifest declares. */
    val A11Y = linkedMapOf(
        "access_keyboard" to "com.diegonmarcos.cloudkeyboard/com.diegonmarcos.cloudkeyboard.grabber.GrabberSelectionService",
        "access_superapp" to "com.diegonmarcos.superapp/com.diegonmarcos.superapp.system.LockScreenAccessibilityService",
        "access_vault" to "com.diegonmarcos.cloudvault/com.x8bit.bitwarden.Accessibility.AccessibilityService",
    )

    /** Packages counted as the fleet's own (a service of theirs that is NOT in [A11Y] is an extra one, e.g. a library's). */
    const val FLEET_PREFIX = "com.diegonmarcos."

    /** Fleet libraries that keep their upstream package id (the vendored GitSync clone), counted as the fleet's own too. */
    val FLEET_LIB_PACKAGES = setOf("com.viscouspot.gitsync")

    private fun fleet(component: String): Boolean =
        component.startsWith(FLEET_PREFIX) || component.substringBefore('/') in FLEET_LIB_PACKAGES

    private const val ENABLED = "enabled_accessibility_services"
    private const val MASTER = "accessibility_enabled"

    /** `pkg/.Cls` and `pkg/pkg.Cls` are the same component; the stored list may use either. */
    fun normalize(flat: String): String {
        val t = flat.trim()
        val slash = t.indexOf('/')
        if (slash <= 0) return t
        val pkg = t.substring(0, slash)
        val cls = t.substring(slash + 1)
        return pkg + "/" + (if (cls.startsWith(".")) pkg + cls else cls)
    }

    /** The stored colon-separated list, normalized, blanks dropped. */
    fun parse(list: String?): List<String> =
        list.orEmpty().split(':').map { normalize(it) }.filter { it.isNotBlank() && it != "null" }

    /** [list] with [component] present ([on]) or absent, order kept, nothing duplicated. */
    fun edit(list: String?, component: String, on: Boolean): List<String> {
        val c = normalize(component)
        val cur = parse(list).filter { it != c }.distinct()
        return if (on) cur + c else cur
    }

    /** Fleet services enabled now that are not one of [A11Y] (the "other" tile). */
    fun extras(list: String?): List<String> {
        val known = A11Y.values.map { normalize(it) }.toSet()
        return parse(list).filter { fleet(it) && it !in known }
    }

    fun enabledList(ctx: Context): String? =
        runCatching { Settings.Secure.getString(ctx.contentResolver, ENABLED) }.getOrNull()

    fun a11yOn(ctx: Context, id: String): Boolean? {
        val c = A11Y[id] ?: return null
        return normalize(c) in parse(enabledList(ctx))
    }

    fun a11yInstalled(ctx: Context, id: String): Boolean {
        val c = A11Y[id]?.let { ComponentName.unflattenFromString(it) } ?: return false
        return runCatching { ctx.packageManager.getServiceInfo(c, 0); true }.getOrDefault(false)
    }

    fun setA11y(ctx: Context, id: String, on: Boolean): Write {
        val c = A11Y[id] ?: return Write(false, "unknown service $id")
        return writeList(ctx, edit(enabledList(ctx), c, on)) { a11yOn(ctx, id) == on }
    }

    fun extrasOn(ctx: Context): Boolean = extras(enabledList(ctx)).isNotEmpty()

    /** Off = every extra fleet service is taken out of the enabled list; on is not a thing (nothing to choose). */
    fun clearExtras(ctx: Context): Write {
        val list = enabledList(ctx)
        val gone = extras(list).toSet()
        if (gone.isEmpty()) return Write(!extrasOn(ctx), "no other fleet accessibility service is enabled")
        val w = writeList(ctx, parse(list).filter { it !in gone }) { !extrasOn(ctx) }
        return w.copy(detail = "removed " + gone.joinToString(", ") + ": " + w.detail)
    }

    private fun writeList(ctx: Context, next: List<String>, agrees: () -> Boolean): Write {
        val value = next.joinToString(":")
        if (holdsSecure(ctx)) {
            runCatching {
                Settings.Secure.putString(ctx.contentResolver, ENABLED, value.ifBlank { null })
                if (value.isNotBlank()) Settings.Secure.putInt(ctx.contentResolver, MASTER, 1)
            }
            if (agrees()) return Write(true, "self (WRITE_SECURE_SETTINGS)")
        }
        val cmds = if (value.isBlank()) listOf("settings delete secure $ENABLED")
                   else listOf("settings put secure $ENABLED $value", "settings put secure $MASTER 1")
        val out = shell(ctx, cmds) ?: return Write(false, NO_CHANNEL)
        return Write(agrees(), out)
    }

    // ── Developer options, USB debugging, the ADB shell channel ────────────

    fun devOptionsOn(ctx: Context): Boolean? =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrNull()

    fun usbDebuggingOn(ctx: Context): Boolean? =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrNull()

    fun setGlobal(ctx: Context, key: String, on: Boolean, read: (Context) -> Boolean?): Write {
        val v = if (on) 1 else 0
        if (holdsSecure(ctx)) {
            runCatching { Settings.Global.putInt(ctx.contentResolver, key, v) }
            if (read(ctx) == on) return Write(true, "self (WRITE_SECURE_SETTINGS)")
        }
        val out = shell(ctx, listOf("settings put global $key $v")) ?: return Write(false, NO_CHANNEL)
        return Write(read(ctx) == on, out)
    }

    /** The shell channel, asked with a real round trip (blocking, off the main thread). */
    fun shellUp(ctx: Context): Boolean = AdbReadiness.check(ctx, fresh = true).usable

    /** On = run the host's Connect chain and re-ask; off is not this app's to do (the channel ends with Wireless debugging). */
    fun setShell(ctx: Context, on: Boolean): Write {
        if (!on) return Write(!shellUp(ctx),
            "The channel is closed by turning Wireless debugging (or USB debugging) off, not from here.")
        runCatching { AdbReadiness.connector?.invoke(ctx.applicationContext) }
        val st = AdbReadiness.check(ctx, fresh = true)
        return Write(st.usable, st.linkText())
    }

    // ── VPN, location ──────────────────────────────────────────────────────

    /** Any network on the device carrying the VPN transport, whoever owns it. */
    fun vpnUp(ctx: Context): Boolean? = runCatching {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrNull()

    fun locationOn(ctx: Context): Boolean? = runCatching {
        (ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
    }.getOrNull()

    fun setLocation(ctx: Context, on: Boolean): Write {
        val out = shell(ctx, listOf("cmd location set-location-enabled $on")) ?: return Write(false, NO_CHANNEL)
        return Write(locationOn(ctx) == on, out)
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    fun holdsSecure(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun canWrite(ctx: Context): Boolean = holdsSecure(ctx) || AdbReadiness.usableNow(ctx)

    const val NO_CHANNEL = "Needs WRITE_SECURE_SETTINGS or a live shell channel (Wireless debugging pairing); this device has neither."

    /** Each command through the broker, one at a time; null when there is no channel. The joined output is the detail. */
    private fun shell(ctx: Context, cmds: List<String>): String? {
        val app = ctx.applicationContext
        val st = runCatching { ShellClient.status(app) }.getOrNull()
        if (st == null || !st.armed) return null
        return cmds.joinToString("; ") { c ->
            val r = runCatching { ShellClient.exec(app, c) }.getOrNull()
            val text = when (r) {
                is ShellResult.Ok -> (r.stdout + r.stderr).trim().ifBlank { "ok" }
                null -> "failed"
                else -> r.toString()
            }
            "$c -> $text"
        }
    }
}
