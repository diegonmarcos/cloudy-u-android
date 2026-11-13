package com.diegonmarcos.superapp.configs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.superapp.adbdebug.LocalHotspot
import com.diegonmarcos.superapp.adbdebug.WifiDirect
import com.diegonmarcos.superapp.perms.PermsRequest
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1682 P3 step 4e: the pairing network helpers, moved verbatim in behaviour from the SuperApp's Permissions screen to the owner's ADB
 * Shell page (Perms tab). Wireless debugging needs a Wi-Fi network; these CREATE one (a device-local hotspot, then Wi-Fi Direct as the
 * fallback for builds that refuse the hotspot while Wireless debugging is being enabled). They are not channel controls: the connecting
 * itself is the cockpit's. Three platform preconditions each, in order: the Wi-Fi radio on, Location services on, then the permission.
 */
@Composable
fun PairingHelpers() {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    var bump by remember { mutableStateOf(0) }
    val ui = remember { Handler(Looper.getMainLooper()) }
    fun refresh() { ui.post { bump++ } }

    fun say(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun startHotspot() = runCatching {
        LocalHotspot.start(ctx) { status ->
            ui.post {
                when (status) {
                    is LocalHotspot.Status.Active -> say("Local WiFi up - SSID: ${status.ssid} - pass: ${status.passphrase}. Now press Connect.")
                    is LocalHotspot.Status.Failed -> say("Couldn't create local WiFi (${status.reason}). Some devices (e.g. certain " +
                        "Samsung builds) refuse Wireless Debugging while a local hotspot is active - any throwaway WiFi network works too.")
                    LocalHotspot.Status.Idle -> {}
                }
                bump++
            }
        }
    }.getOrElse { say("Local WiFi failed: ${it.message}") }

    fun startDirect() = runCatching {
        WifiDirect.start(ctx) { status ->
            ui.post {
                when (status) {
                    is WifiDirect.Status.Active -> say("WiFi Direct up (host ${status.ownerIp ?: "?"}). Now press Connect.")
                    is WifiDirect.Status.Failed -> say("Couldn't create WiFi Direct group (${status.reason}).")
                    WifiDirect.Status.Idle -> {}
                }
                bump++
            }
        }
    }.getOrElse { say("WiFi Direct failed: ${it.message}") }

    val hotspotPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) startHotspot()
        else say("Location is required by Android to create a local WiFi hotspot")
    }
    val directPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDirect() else say("That permission is required by Android to create a WiFi Direct group")
    }

    /** The two shared preconditions; false = the person was sent to fix one and taps again. */
    fun preconditions(what: String): Boolean {
        val app = ctx.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wifi?.isWifiEnabled != true) {
            say("Turn WiFi ON first, then tap again ($what needs the WiFi radio up).")
            runCatching { ctx.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            return false
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        if (lm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !lm.isLocationEnabled) {
            say("Turn Location ON, then tap again (the platform requires it for $what).")
            runCatching { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return false
        }
        return true
    }

    fun requestHotspot() {
        if (!preconditions("a hotspot")) return
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startHotspot()
        else hotspotPerm.launch(PermsRequest.expand(listOf(Manifest.permission.ACCESS_FINE_LOCATION)).toTypedArray())
    }

    fun requestDirect() {
        if (!preconditions("WiFi Direct")) return
        // NEARBY_WIFI_DEVICES (33+) or ACCESS_FINE_LOCATION (below 33).
        val perm = if (Build.VERSION.SDK_INT >= 33) "android.permission.NEARBY_WIFI_DEVICES" else Manifest.permission.ACCESS_FINE_LOCATION
        if (ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) startDirect() else directPerm.launch(perm)
    }

    @Suppress("UNUSED_VARIABLE") val reread = bump
    val hotspot = LocalHotspot.status()
    val direct = WifiDirect.status()
    Column(Modifier.testTag("adb:pairing-helpers")) {
        KitStoreRowLine { KitStoreCaption("Pairing network helpers: Wireless debugging needs a Wi-Fi network, and these create one.", color = p.textSecondary) }
        KitStoreRowLine {
            KitStoreQuick(if (hotspot is LocalHotspot.Status.Active) "Local WiFi up" else "Create local WiFi (for pairing)", KitStoreRow.BLUE_FILL,
                Modifier.testTag("adb:pair:hotspot")) { requestHotspot() }
        }
        if (hotspot is LocalHotspot.Status.Active) KitStoreRowLine {
            KitStoreCaption("Local WiFi up - SSID: ${hotspot.ssid}  -  password: ${hotspot.passphrase}", color = p.ok, modifier = Modifier.testTag("adb:pair:hotspot-info"))
        }
        KitStoreRowLine {
            KitStoreQuick(if (direct is WifiDirect.Status.Active) "WiFi Direct up" else "Create WiFi Direct (for pairing)", KitStoreRow.BLUE_FILL,
                Modifier.testTag("adb:pair:direct")) { requestDirect() }
        }
        if (direct is WifiDirect.Status.Active) KitStoreRowLine {
            KitStoreCaption("WiFi Direct up - SSID: ${direct.ssid}  -  password: ${direct.passphrase}" + (direct.ownerIp?.let { "  -  owner IP: $it" } ?: ""),
                color = p.ok, modifier = Modifier.testTag("adb:pair:direct-info"))
        }
    }
}
