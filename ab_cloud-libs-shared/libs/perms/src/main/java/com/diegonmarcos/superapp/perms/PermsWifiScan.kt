package com.diegonmarcos.superapp.perms

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings

/**
 * #1519 exactly what blocks a Wi-Fi scan, as data (PermsWifiScanTest): fine location (an approximate-only grant is not
 * enough), NEARBY_WIFI_DEVICES on API 33+, the system Location switch (Samsung hides results while it is off) and the
 * scan throttle (4 per 2 minutes). The Wi-Fi page lists every blocker and sends "Fix in Perms" to the entries [Block.entryId].
 */
object PermsWifiScan {
    const val FIX = "Fix in Perms ›"

    data class Snapshot(
        val sdk: Int, val fine: Boolean, val coarse: Boolean, val nearby: Boolean, val locationOn: Boolean,
        val throttleOn: Boolean = true, val throttleRemaining: Int = Int.MAX_VALUE,
    )

    /** [hard] = no scan result can arrive; a throttle block is soft (it lifts by itself). */
    data class Block(val entryId: String, val text: String, val hard: Boolean = true)

    fun detect(s: Snapshot): List<Block> = buildList {
        if (!s.fine) add(Block("fine_location",
            if (s.coarse) "Fine location is missing: only approximate location is allowed" else "Fine location is not allowed"))
        if (s.sdk >= 33 && !s.nearby) add(Block("nearby_wifi", "Nearby Wi-Fi devices is not allowed"))
        if (!s.locationOn) add(Block("location_services", "Location services are off: Android hides scan results"))
        if (s.throttleOn && s.throttleRemaining <= 0) add(Block("wifi_scan_throttle", "Scan throttle reached: 4 scans per 2 minutes", hard = false))
    }

    fun hard(blocks: List<Block>): List<Block> = blocks.filter { it.hard }

    fun snapshot(ctx: Context, throttleOn: Boolean = throttleOn(ctx), throttleRemaining: Int = Int.MAX_VALUE): Snapshot {
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return Snapshot(Build.VERSION.SDK_INT, has(PermsRequest.FINE), has(PermsRequest.COARSE),
            has("android.permission.NEARBY_WIFI_DEVICES"), locationOn(ctx), throttleOn, throttleRemaining)
    }

    fun locationOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else @Suppress("DEPRECATION") (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
    }

    /** The developer option "Wi-Fi scan throttling"; on unless the setting says 0. */
    fun throttleOn(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, "wifi_scan_throttle_enabled", 1) != 0 }.getOrDefault(true)
}
