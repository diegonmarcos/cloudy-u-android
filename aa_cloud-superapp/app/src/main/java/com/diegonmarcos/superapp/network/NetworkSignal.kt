package com.diegonmarcos.superapp.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * #1666 Signal levels for the status strip, home boards and the energy sampler WITHOUT a location-class call.
 *
 * WifiManager.getConnectionInfo is a location access on Android (the platform notes FINE_LOCATION on every call once the
 * permission is granted, because the answer can carry the SSID / BSSID), so anything on a timer or a broadcast must not call
 * it. The Wi-Fi network's NetworkCapabilities carries the same RSSI as its signal strength, and getNetworkCapabilities strips
 * the location-sensitive fields without noting a location access. Below API 29 (no capability signal strength, and no
 * location indicator on those releases) the old read stays.
 */
object NetworkSignal {

    /** The connected Wi-Fi network's RSSI in dBm, or null when there is none / it cannot be read. Never throws. */
    fun wifiRssi(ctx: Context): Int? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            @Suppress("DEPRECATION")
            cm.allNetworks.asSequence().mapNotNull { cm.getNetworkCapabilities(it) }
                .firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
                ?.signalStrength?.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
        } else {
            @Suppress("DEPRECATION")
            (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.connectionInfo?.rssi
        }
    }.getOrNull()
}
