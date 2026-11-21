package com.diegonmarcos.superapp.configs

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import com.diegonmarcos.superapp.configs.DeviceControls.Probe

/**
 * #1846 Quick actions > Network and > UI: the capability behind the tiles those two tabs add. Which tiles each tab draws is
 * build.json::ui.control_panel (group `tab` field); the tiles are DeviceControls.byId entries; this file only answers "what does the device say" and "what can the tap do".
 *
 * Every tile here has a live read and a [Probe] whose detail names the source and the value (like the Security tab, #1833). A tap
 * toggles when Android lets an app (WRITE_SETTINGS / WRITE_SECURE_SETTINGS), else opens the exact system screen; a hold opens it.
 * [detailed] gives the tiles that moved onto the two tabs (Wi-Fi, mobile data, Bluetooth, airplane mode, DND ...) a detail line.
 */
object QuickControls {

    const val TAB_UI = "ui"
    const val TAB_NETWORK = "network"

    fun onOff(b: Boolean?) = when (b) { true -> "on"; false -> "off"; null -> "unreadable" }

    /** The source an existing tile reads, for the detail line it did not have. */
    val SOURCES: Map<String, String> = mapOf(
        "wifi" to "WifiManager.isWifiEnabled",
        "mobile_data" to "TelephonyManager.isDataEnabled",
        "bluetooth" to "BluetoothAdapter.isEnabled",
        "airplane_mode" to "Settings.Global airplane_mode_on",
        "mesh" to "WireGuard backend tunnel",
        "firewall" to "FirewallPrefs (desired state)",
        "dnd" to "NotificationManager interruption filter",
        "rotation_lock" to "Settings.System accelerometer_rotation (lock = 0)",
        "auto_brightness" to "Settings.System screen_brightness_mode",
        "floating_nav" to "FloatingNavService",
        "admin_mode" to "LauncherSettingsPrefs",
        "all_anim" to "LauncherSettingsPrefs",
        "haptics" to "LauncherSettingsPrefs",
    )

    /** The reading ControlFragment draws: [p] as is, or, for a tile that moved here without a [Probe], its source and value. */
    fun detailed(id: String, p: Probe): Probe =
        if (p.detail.isNotBlank()) p else SOURCES[id]?.let { Probe(p.state, "$it: ${onOff(p.state)}") } ?: p

    // ── readings (pure enough to test: the platform calls are the only impure part) ──

    fun hotspot(ctx: Context): Boolean? = runCatching {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wm) as Boolean
    }.getOrNull()

    fun nfc(ctx: Context): Boolean? = runCatching {
        android.nfc.NfcAdapter.getDefaultAdapter(ctx.applicationContext)?.isEnabled
    }.getOrNull()

    /** Private DNS: Settings.Global private_dns_mode. "off" is off; "opportunistic" (also the unset default) and "hostname" are on. */
    fun privateDnsMode(ctx: Context): String? =
        runCatching { Settings.Global.getString(ctx.contentResolver, "private_dns_mode") }.getOrNull()

    fun privateDnsOn(mode: String?): Boolean = mode != "off"

    fun alwaysOnVpn(ctx: Context): String? =
        runCatching { Settings.Secure.getString(ctx.contentResolver, "always_on_vpn_app") }.getOrNull()

    fun keepScreenOn(ctx: Context): Int? =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN) }.getOrNull()

    fun screenTimeoutMs(ctx: Context): Int? =
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()

    fun fontScale(ctx: Context): Float? =
        runCatching { Settings.System.getFloat(ctx.contentResolver, Settings.System.FONT_SCALE) }.getOrNull()

    /** The next value after [cur] in [steps], wrapping; the first step when [cur] is not one of them. */
    fun <T> next(steps: List<T>, cur: T?): T = steps[(steps.indexOfFirst { it == cur } + 1).let { if (it <= 0) 0 else it % steps.size }]

    val TIMEOUT_STEPS_MS = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000)
    val FONT_STEPS = listOf(0.85f, 1.0f, 1.15f, 1.3f)
    const val DEFAULT_TIMEOUT_MS = 30_000
    const val DEFAULT_FONT = 1.0f

    fun timeoutLabel(ms: Int?): String = when {
        ms == null -> "unreadable"
        ms < 60_000 -> "${ms / 1000} s"
        else -> "${ms / 60_000} min"
    }

    const val NEEDS_WRITE = "\"Modify system settings\" is not granted to this app yet."
}
