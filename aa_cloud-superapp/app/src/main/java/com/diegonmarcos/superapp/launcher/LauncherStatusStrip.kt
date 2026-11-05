package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.uikit.KitDenseMaterial
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Base64
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.battery.BatteryEstimatePopup
import com.diegonmarcos.superapp.battery.BatteryIconView
import com.diegonmarcos.superapp.cal.TasksPopup
import com.diegonmarcos.superapp.network.BtLinks
import com.diegonmarcos.superapp.network.GpsProbe
import com.diegonmarcos.superapp.network.MobileLink
import com.diegonmarcos.superapp.network.MobileProbe
import com.diegonmarcos.superapp.network.NetworkInfoPopup
import com.diegonmarcos.superapp.network.NetworkSections
import com.diegonmarcos.superapp.network.SignalLevels
import com.diegonmarcos.superapp.network.TetherModel
import com.diegonmarcos.superapp.network.UsbDataModel
import com.diegonmarcos.superapp.network.WgLink
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import com.diegonmarcos.superapp.ui.ShellFxState
import com.diegonmarcos.superapp.ui.SystemInfoPopup
import com.diegonmarcos.superapp.zoomies.PetStrengthView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Top status strip, drawn in Compose, shown when the SuperApp is the active default launcher AND the LauncherTheme is Cloud.
 * It replaces the hidden system status bar with our own 3-cluster row + bottom hairline:
 *
 *   ┌──────────────────────────────────────────────────────────────────┐
 *   │ [5G][WiFi][BT][WG][KDE][ADB][Data][HS][GPS]  dd-MM-yyyy HH:mm EEE  [R][S][C][Bat] │
 *   │  ····  ····  ····   (signal dots, 3dp)                           │
 *   │ ──────────────────────────────────────────────────────────────── │  hairline
 *   └──────────────────────────────────────────────────────────────────┘
 *
 *   (WHICH elements sit in which cluster, in which order, is build.json::home_status_bar: StatusBarDecl / StatusBarPrefs.)
 *   LEFT   network labels (declared order; NetworkSections.ORDER is the popup's), each over a 4-dot row (mobile / WiFi /
 *          WG carry a signal level, GPS the fix quality, BT the count of connected devices, 4 = four or more; KDE / ADB /
 *          Data / HS keep the same empty footprint so every icon stays aligned). Tinted by state, all event-driven:
 *          ConnectivityManager callback (5G / WiFi / WG), RSSI_CHANGED + the data SIM's signal callback (dots), a
 *          Settings.Global observer (ADB = USB or Wireless debugging), USB_STATE + OTG attach/detach (Data),
 *          TETHER_STATE_CHANGED + WIFI_AP_STATE_CHANGED (HS). WG is lit only ON the mesh (WgLink: a hub handshake under
 *          3 min), NOT for "a VPN transport exists" - the engine's DNS-only tunnel is a VPN too. The mobile label is
 *          MobileLink's: the network type with data on, "Call" with data off, "SOS", grey with no service.
 *   CENTRE date + time, monospace, at the true screen centre (aligned with the island below), ticking on ACTION_TIME_TICK.
 *   RIGHT  RAM% . Storage% (/data) . CPU load . the battery icon, re-read on the minute tick and on a 10 s ticker while
 *          Configs > Launcher > "Live system stats" is on.
 *
 * It was LauncherStatusStripView, a 935-line LinearLayout that built every TextView, dot and column by hand. Now
 * [StatusStripModel] owns the receivers and the readings as Compose state, and [LauncherStatusStrip] only draws them.
 */

/** One left-cluster icon: its label, text tint, and the dots row under it (level 0..4, or -1 for the empty footprint). */
internal data class StripChip(
    val label: String, val tint: Int, val dots: Int = SignalLevels.NONE, val dotsTint: Int = tint,
    /** Instead of the four signal dots: one dot per app (ADB: SuperApp, Cloud Store), each in its own tint, with the spoken form. */
    val peerDots: List<Int>? = null, val peerDescription: String = "",
)

/** A tool's pet: a FIXED animal whose gait follows the tool's strength. */
internal data class ToolPet(
    val animal: String, val variant: String,
    val type: String, val onLevel: Int, val buckets: List<Int>,
)

/** The strip's readings and the system listeners that keep them current. [start] / [stop] mirror the strip being on screen. */
class StatusStripModel(private val context: Context) {

    private val resources = context.resources

    /** #1446 The speedmeter beside the network chips: the last reading vs the rolling 24 h average. */
    internal val speed = com.diegonmarcos.superapp.network.SpeedMeter(context)

    // ── Pets: data-driven from BuildConfig.STATUS_PETS_B64 (build.json::status_pets) ──
    var petsEnabled = false
        private set
    var petPx = 0
        private set
    private var gaits: List<String> = listOf("idle", "walk", "walk_fast", "run")
    private val toolCfg = HashMap<String, ToolPet>()

    /** Each pet's current gait, by tool id. */
    internal val gait = mutableStateMapOf<String, String>()
    internal fun petOf(toolId: String): ToolPet? = toolCfg[toolId]?.takeIf { petsEnabled }

    /** Line 1's own top padding, and the only dp in the clock's vertical offset: [centreTopReservePx] subtracts it so the
     *  reserve is measured from the strip's top edge rather than from Line 1's. */
    val innerRowTopPadPx = (3 * resources.displayMetrics.density).toInt()

    /** The camera punch-hole's height in RAW PIXELS, from WindowInsetsCompat.Type.displayCutout(). 0 until the first
     *  inset dispatch, and on any phone whose screen has no cutout at all. */
    var cutoutTopPx by mutableIntStateOf(0)
        private set
    /** The cutout's side safe insets in raw pixels (a cutout at a screen edge, landscape): the row keeps clear of them. */
    var insetLeftPx by mutableIntStateOf(0)
        private set
    var insetRightPx by mutableIntStateOf(0)
        private set

    // ── What the Compose side reads ──
    internal var chips by mutableStateOf<Map<String, StripChip>>(emptyMap())
        private set
    var clock by mutableStateOf("")
        private set
    var ram by mutableStateOf("R 0%")
        private set
    var storage by mutableStateOf("S 0%")
        private set
    var cpu by mutableStateOf("C 0s")
        private set
    /** The clock's pattern, declared on the clock element (build.json::home_status_bar). */
    internal val clockFormat: String by lazy {
        StatusBarDecl.declared.elements.firstOrNull { it.kind == "clock" }?.format ?: "dd-MM-yyyy HH:mm EEE"
    }

    /** The reading a declared `metric` element shows. */
    internal fun metricText(id: String): String = when (id) { "ram" -> ram; "storage" -> storage; "cpu" -> cpu; else -> "" }
    var batteryPct by mutableIntStateOf(-1)
        private set
    var batteryCharging by mutableStateOf(false)
        private set

    // ── Inputs ──
    private var hasWifi = false
    private var hasCellular = false
    private var hasVpn = false
    private var hasBluetooth = false
    private var btCount = 0
    private var hasUsbData = false
    private var hasAdb = false
    private var hasHotspot = false
    private var hasKde = false
    /** The adb channel of each app that has one (SuperApp, Cloud Store), as the other apps' providers last said. */
    private var adbPeers: List<com.diegonmarcos.superapp.adbdebug.PeerStatus> = emptyList()
    private var adbPeersPinned = false
    private var wifiLevel = SignalLevels.NONE
    private var mobile = MobileLink.Reading(MobileLink.Kind.NO_SERVICE, MobileLink.OFF_LABEL, WgLink.TINT_OFF, 0, "", null)
    private val mobileWatch = MobileProbe.Watch(context) { signalOnly -> refreshMobile(signalOnly) }
    private var mobileInputs: MobileLink.Inputs? = null
    private val gpsWatch = GpsProbe.StripWatch(context) { applyIconTints() }
    private var wgLink = WgLink.Reading(WgLink.State.OFF, SignalLevels.NONE, "")
    private var tetherIfaces: List<String> = emptyList()
    private var apState: Int? = null
    private var usbTetherFn = false
    private val wgReading = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wgRefresh = Runnable { refreshWgLevel() }

    private val metricsTicker = object : Runnable {
        override fun run() {
            refreshMetrics()
            // Reschedule only while live system stats are ON (Configs > Launcher > "Live system stats").
            // OFF = frozen numbers, no 10s wakeup.
            if (LauncherSettingsPrefs(context).toggle("status_live")) mainHandler.postDelayed(this, 10_000)
        }
    }

    init {
        parsePetsConfig()
        applyIconTints()
    }

    /**
     * How far below the strip's top edge the clock has to start: whichever is taller, the Line-0 pet row it has always
     * been aligned to, or the display cutout. Measured from the strip's top edge: in launcher mode the system bars are
     * hidden, systemBars().top is dispatched as 0, shell_linear's paddingTop is 0 and this strip therefore sits at window
     * y=0, which is the same origin displayCutout() measures from.
     */
    private fun centreTopReservePx(): Int =
        maxOf(if (petsEnabled) petPx else 0, cutoutTopPx - innerRowTopPadPx)

    /** The reserve above the clock, in raw pixels. */
    fun clockReservePx(): Int = centreTopReservePx()

    /**
     * #407 - THE CLOCK MUST CLEAR THE CAMERA AT EVERY SCALE STEP. The strip draws INSIDE the display cutout (the window is
     * LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES), so the one child on the screen's centre line, where a Samsung punch-hole
     * is, keeps its distance by the hole's OWN measurement. It cannot be a dp: SystemDisplay.applyScale writes `wm density`,
     * so a dp clearance shrinks at a reduced Scale step while the camera stays the same pixels. displayCutout() is reported
     * in raw pixels off the real cutout, and unlike systemBars() it is still dispatched while the status bar is hidden.
     *
     * The listener returns the insets UNCHANGED: the strip reads the cutout, it does not consume it; the siblings dispatched
     * after it (the toolbar island) and ShellActivity's own shell_linear listener still see what they saw before #407.
     */
    internal fun readCutoutFrom(host: View) {
        ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
            noteInsets(insets)
            insets
        }
        // The listener only fires if an ancestor's dispatch reaches this host with the cutout still in it, and the shell's
        // ancestors (the drawer, the padded shell frame) are free to consume or rewrite what they pass down. So ALSO ask the
        // window what the cutout is, when the strip attaches and whenever it is laid out: the window's own root insets are the
        // truth the camera is measured against, whatever the dispatch did on the way here.
        // The root insets only FILL IN what no dispatch has said (cutout still unknown); a dispatched value is never overwritten by them.
        fun fromRoot() { if (cutoutTopPx == 0) ViewCompat.getRootWindowInsets(host)?.let { noteInsets(it) } }
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { fromRoot(); v.requestApplyInsets() }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fromRoot() }
        if (host.isAttachedToWindow) fromRoot()
    }

    /** Take the cutout's top height and side insets from [insets]: Type.displayCutout() first, the DisplayCutout's own safe insets as the fallback. */
    internal fun noteInsets(insets: WindowInsetsCompat) {
        val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val dc = insets.displayCutout
        noteCutout(maxOf(c.top, dc?.safeInsetTop ?: 0))
        val l = maxOf(c.left, dc?.safeInsetLeft ?: 0); val r = maxOf(c.right, dc?.safeInsetRight ?: 0)
        if (l != insetLeftPx) insetLeftPx = l
        if (r != insetRightPx) insetRightPx = r
    }

    /** The camera cutout's height in raw pixels, as the window dispatched it. */
    internal fun noteCutout(top: Int) { if (top != cutoutTopPx) cutoutTopPx = top }

    /** Decode BuildConfig.STATUS_PETS_B64 into the per-tool animal config. Empty/disabled-safe: petsEnabled stays false and
     *  the strip renders as the plain icon row. */
    private fun parsePetsConfig() {
        runCatching {
            val b64 = BuildConfig.STATUS_PETS_B64
            if (b64.isBlank()) return
            val o = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
            petsEnabled = o.optBoolean("enabled", false)
            petPx = (o.optInt("px", 20) * resources.displayMetrics.density).toInt()
            o.optJSONArray("gaits")?.let { g -> gaits = (0 until g.length()).map { g.getString(it) } }
            val tools = o.optJSONObject("tools") ?: return
            for (k in tools.keys()) {
                val t = tools.getJSONObject(k)
                val buckets = t.optJSONArray("buckets")?.let { b -> (0 until b.length()).map { b.getInt(it) } } ?: emptyList()
                toolCfg[k] = ToolPet(
                    t.optString("animal"), t.optString("variant"),
                    t.optString("type", "bool"), t.optInt("on_level", gaits.lastIndex), buckets,
                )
                gait[k] = gaits.firstOrNull() ?: "idle"
            }
        }
    }

    private fun gaitForLevel(level: Int): String = gaits.getOrElse(level.coerceIn(0, gaits.lastIndex)) { gaits.last() }

    /** percent -> level by counting how many ascending [buckets] it clears. */
    private fun bucketLevel(pct: Int, buckets: List<Int>): Int = buckets.count { pct >= it }

    private fun updatePet(toolId: String, level: Int) {
        if (toolCfg.containsKey(toolId)) gait[toolId] = gaitForLevel(level)
    }

    private fun updateBoolPet(toolId: String, on: Boolean) {
        val cfg = toolCfg[toolId] ?: return
        updatePet(toolId, if (on) cfg.onLevel else 0)
    }

    // ── Listeners ──
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            refreshTime()
            // Piggy-back metrics on the once-a-minute tick, but ONLY while live stats are on. The clock keeps ticking.
            if (LauncherSettingsPrefs(context).toggle("status_live")) refreshMetrics()
            // A WireGuard handshake ages with no event to say so: re-read it on the clock's own tick.
            refreshWgLevel()
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refreshBattery(i) }
    }
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // ATTACHED / DETACHED of an OTG device carry no USB_STATE: re-read the sticky one.
            val state = if (i.action == USB_STATE) i
                else runCatching { context.registerReceiver(null, IntentFilter(USB_STATE)) }.getOrNull()
            refreshUsb(state)
        }
    }
    private val tetherReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refreshTether(i) }
    }
    /** Adapter on/off, ACL + profile connects/disconnects, battery levels: re-read BT. */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            BtLinks.noteBattery(i)
            refreshBluetooth()
        }
    }
    private val rssiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refreshWifiLevel() }
    }
    /** USB debugging / Wireless debugging flips: Settings.Global, observed, never polled. */
    private val adbObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshAdb() }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { mainHandler.post { refreshNetworkFromConnectivity() } }
        override fun onLost(network: Network) { mainHandler.post { refreshNetworkFromConnectivity() } }
        override fun onAvailable(network: Network) { mainHandler.post { refreshNetworkFromConnectivity() } }
    }

    /** Register everything and take a first reading of each. The strip being composed calls this. */
    fun start() {
        runCatching {
            context.registerReceiver(timeReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
            })
        }
        runCatching {
            val battery = context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (battery != null) refreshBattery(battery)
        }
        runCatching {
            // ACTION_USB_STATE is sticky: registering returns the current state, so the icon is right from the start.
            val usb = context.registerReceiver(usbReceiver, IntentFilter(USB_STATE))
            if (usb != null) refreshUsb(usb)
            context.registerReceiver(usbReceiver, IntentFilter().apply {
                addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
                addAction("android.hardware.usb.action.USB_DEVICE_DETACHED")
            })
        }
        runCatching {
            // Both sticky: registering hands back the current tethering / AP state.
            context.registerReceiver(tetherReceiver, IntentFilter(TETHER_STATE))?.let { refreshTether(it) }
            context.registerReceiver(tetherReceiver, IntentFilter(AP_STATE))?.let { refreshTether(it) }
        }
        runCatching { context.registerReceiver(rssiReceiver, IntentFilter(android.net.wifi.WifiManager.RSSI_CHANGED_ACTION)) }
        runCatching {
            // System broadcasts only: NOT_EXPORTED still hears the Bluetooth stack, no other app can poke it.
            androidx.core.content.ContextCompat.registerReceiver(context, btReceiver, IntentFilter().apply {
                BtLinks.ACTIONS.forEach { addAction(it) }
            }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        BtLinks.onProxy = { mainHandler.post { refreshBluetooth() } }
        runCatching {
            val cr = context.contentResolver
            cr.registerContentObserver(android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ADB_ENABLED), false, adbObserver)
            cr.registerContentObserver(android.provider.Settings.Global.getUriFor(ADB_WIFI), false, adbObserver)
        }
        mobileWatch.start()
        speed.start()
        gpsWatch.start()
        refreshAdb()
        mainHandler.post(adbPeersTicker)
        refreshWgLevel()
        runCatching {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
        }
        refreshTime()
        refreshNetworkFromConnectivity()
        refreshMetrics()   // one render: static numbers even when live stats are off
        // The 10s RAM/CPU/storage loop only while enabled. Off keeps the frozen values on screen and skips the polling.
        if (LauncherSettingsPrefs(context).toggle("status_live")) mainHandler.post(metricsTicker)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(timeReceiver) }
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { context.unregisterReceiver(usbReceiver) }
        runCatching { context.unregisterReceiver(tetherReceiver) }
        runCatching { context.unregisterReceiver(rssiReceiver) }
        runCatching { context.unregisterReceiver(btReceiver) }
        BtLinks.onProxy = null
        runCatching { context.contentResolver.unregisterContentObserver(adbObserver) }
        mobileWatch.stop()
        speed.stop()
        gpsWatch.stop()
        runCatching {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(networkCallback)
        }
        mainHandler.removeCallbacks(metricsTicker)
        mainHandler.removeCallbacks(adbPeersTicker)
        mainHandler.removeCallbacks(wgRefresh)
    }

    // ── Readings ──
    internal fun refreshTime() {
        clock = SimpleDateFormat(clockFormat, Locale.US).format(Date())
    }

    private fun refreshBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        batteryPct = pct
        batteryCharging = charging
        if (pct >= 0) toolCfg["battery"]?.let { updatePet("battery", bucketLevel(pct, it.buckets)) }
    }

    /** USB cable DATA-transfer state from ACTION_USB_STATE (+ any attached OTG device). The rule is UsbDataModel's, the same
     *  one the popup's Data section prints. */
    private fun refreshUsb(intent: Intent?) {
        val extras = UsbDataModel.EXTRA_KEYS.associateWith { intent?.getBooleanExtra(it, false) == true }
        val otg = runCatching {
            (context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager)?.deviceList?.keys?.toList()
        }.getOrNull() ?: emptyList()
        hasUsbData = UsbDataModel.state(extras, 0, otg).data
        usbTetherFn = extras["rndis"] == true || extras["ncm"] == true
        hasHotspot = TetherModel.State(tetherIfaces, apState, usbTetherFn).active
        applyIconTints()
    }

    /** TETHER_STATE_CHANGED ("tetherArray" = tethered interfaces) or WIFI_AP_STATE_CHANGED ("wifi_state"); each updates its half. */
    private fun refreshTether(i: Intent) {
        when (i.action) {
            TETHER_STATE -> tetherIfaces = runCatching { i.getStringArrayListExtra("tetherArray") }.getOrNull()?.toList() ?: emptyList()
            AP_STATE -> apState = i.getIntExtra("wifi_state", -1).takeIf { it >= 0 }
        }
        hasHotspot = TetherModel.State(tetherIfaces, apState, usbTetherFn).active
        applyIconTints()
    }

    /** Take the adb channels' readings. [pin] freezes them against the background refresh (a test's fixed reading). */
    internal fun noteAdbPeers(list: List<com.diegonmarcos.superapp.adbdebug.PeerStatus>, pin: Boolean = false) {
        if (pin) adbPeersPinned = true else if (adbPeersPinned) return
        adbPeers = list
        applyIconTints()
    }

    /** Every 15 s while the strip is on screen: one blocking pass (a probe and a binder call to the other app) off the main thread. */
    private val adbPeersTicker = object : Runnable {
        override fun run() {
            Thread {
                val r = runCatching { com.diegonmarcos.superapp.adbdebug.AdbPeerReader.read(context, wake = false) }.getOrNull()   // #1502 the 15 s tick never starts Store
                if (r != null) mainHandler.post { noteAdbPeers(r) }
            }.start()
            mainHandler.postDelayed(this, 15_000)
        }
    }

    private fun refreshAdb() {
        val cr = context.contentResolver
        val usb = runCatching { android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
        // Wireless debugging: the lib's own read (Settings.Global adb_wifi_enabled, API 30+).
        val wifi = runCatching { com.diegonmarcos.superapp.adbdebug.WirelessDebugging.isOn(context) }.getOrDefault(false)
        hasAdb = usb || wifi
        applyIconTints()
    }

    /** Wi-Fi RSSI -> 0..4 (RSSI_CHANGED_ACTION + every connectivity change). No location needed for RSSI. */
    private fun refreshWifiLevel() {
        wifiLevel = if (!hasWifi) SignalLevels.NONE else runCatching {
            @Suppress("DEPRECATION")
            val rssi = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                ?.connectionInfo?.rssi
            SignalLevels.wifi(rssi)
        }.getOrDefault(SignalLevels.NONE)
        applyIconTints()
    }

    /** WG state + level from the engine (WgLink: tunnel state + freshest peer handshake). The engine is another process
     *  (binder), so it is read off the main thread, at most one read in flight. */
    private fun refreshWgLevel() {
        if (!wgReading.compareAndSet(false, true)) return
        val app = context.applicationContext
        val vpn = hasVpn
        Thread {
            val r = try { NetworkInfoPopup.wgLink(app, vpn) }
                catch (_: Throwable) { WgLink.derive(false, emptyList(), 0L) }
                finally { wgReading.set(false) }
            mainHandler.post { wgLink = r; applyIconTints() }
        }.start()
    }

    /** The mobile icon's state (MobileLink) from the data SIM: pushed by its telephony callbacks, the data / roaming /
     *  airplane switches and every connectivity change. Cheap binder reads. */
    private fun refreshMobile(signalOnly: Boolean = false) {
        val probe = MobileProbe
        mobile = runCatching {
            val prev = mobileInputs
            val inputs = if (signalOnly && prev != null) probe.withLiveSignal(prev) else probe.read(context, hasCellular)
            mobileInputs = inputs
            MobileLink.derive(inputs)
        }.getOrDefault(mobile)
        applyIconTints()
    }

    /** Walk all known networks and decide which labels light up. VPN is checked across ALL networks (a VPN can co-exist
     *  with the active Wi-Fi / cellular network, and the WG indicator should be bright then). */
    private fun refreshNetworkFromConnectivity() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var wifi = false; var cell = false; var vpn = false
        runCatching {
            cm?.allNetworks?.forEach { n ->
                cm.getNetworkCapabilities(n)?.let { c ->
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cell = true
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true
                }
            }
        }
        hasWifi = wifi; hasCellular = cell; hasVpn = vpn
        refreshBluetooth(apply = false)
        refreshMobile()
        refreshWifiLevel()   // also applies the tints
        // Any network change can take the hubs away (Wi-Fi -> cellular, a VPN swap): re-ask the engine, debounced,
        // instead of trusting the VPN transport (the DNS-only tunnel is one too).
        mainHandler.removeCallbacks(wgRefresh)
        mainHandler.postDelayed(wgRefresh, 500)
    }

    /** Adapter on/off (the BT label's tint) + connected devices (its dots), one BtLinks reading. */
    private fun refreshBluetooth(apply: Boolean = true) {
        val r = runCatching { BtLinks.read(context) }.getOrNull()
        hasBluetooth = r?.adapterOn == true
        btCount = r?.count ?: 0
        if (apply) applyIconTints()
    }

    private fun applyIconTints() {
        val on = WgLink.TINT_ON
        val off = WgLink.TINT_OFF
        val label = NetworkSections.LABELS
        val locationOn = runCatching { GpsProbe.locationOn(context) }.getOrDefault(false)
        hasKde = runCatching {
            com.diegonmarcos.superapp.kdeconnect.KdeConnectManager.connectedIds().isNotEmpty()
        }.getOrDefault(false)
        val wgTint = WgLink.tint(wgLink.state)
        val btTint = BtLinks.tint(hasBluetooth)
        chips = mapOf(
            // Mobile dots = the data SIM's signal, kept with data off ("Call") and for SOS; 0 with no service.
            NetworkSections.CELLULAR to StripChip(mobile.label, mobile.tint, mobile.level),
            NetworkSections.WIFI to (if (hasWifi) on else off).let { StripChip(label.getValue(NetworkSections.WIFI), it, wifiLevel) },
            // BT dots = connected devices (0..4, 4 = four or more); all four grey at 0.
            NetworkSections.BLUETOOTH to StripChip(label.getValue(NetworkSections.BLUETOOTH), btTint, BtLinks.dots(btCount)),
            NetworkSections.MESH to StripChip(label.getValue(NetworkSections.MESH), wgTint,
                if (wgLink.onMesh) wgLink.level else SignalLevels.NONE),
            NetworkSections.KDE to StripChip(label.getValue(NetworkSections.KDE), if (hasKde) on else off),
            // ADB: the label follows USB / Wireless debugging; the two dots are the SuperApp's and Cloud Store's own channels.
            NetworkSections.ADB to StripChip(label.getValue(NetworkSections.ADB), if (hasAdb) on else off,
                peerDots = AdbPeerDots.tints(adbPeers), peerDescription = AdbPeerDots.describe(adbPeers)),
            NetworkSections.DATA to StripChip(label.getValue(NetworkSections.DATA), if (hasUsbData) on else off),
            NetworkSections.HOTSPOT to StripChip(label.getValue(NetworkSections.HOTSPOT), if (hasHotspot) on else off),
            // GPS: white with location on; dots = the passive / last fix's quality (0 = none).
            NetworkSections.GPS to (if (locationOn) on else off).let { StripChip(label.getValue(NetworkSections.GPS), it, runCatching { gpsWatch.dots(locationOn) }.getOrDefault(0)) },
        )
        // Pets: on -> run (energetic), off -> idle. on_level is data-driven.
        updateBoolPet("cellular", mobile.kind == MobileLink.Kind.DATA)
        updateBoolPet("wifi", hasWifi)
        updateBoolPet("vpn", wgLink.onMesh)
        updateBoolPet("bluetooth", hasBluetooth)
        updateBoolPet("usb", hasUsbData)
        updateBoolPet("adb", hasAdb)
        updateBoolPet("hotspot", hasHotspot)
        updateBoolPet("kde", hasKde)
    }

    /** RAM + /data storage + CPU load into the right cluster. Cheap reads, safe on the main thread. */
    private fun refreshMetrics() {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.let {
                val info = ActivityManager.MemoryInfo()
                it.getMemoryInfo(info)
                val pct = ((1.0 - info.availMem.toDouble() / info.totalMem.toDouble()) * 100).toInt()
                ram = "R$pct%"
                toolCfg["ram"]?.let { c -> updatePet("ram", bucketLevel(pct, c.buckets)) }
            }
        }
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val avail = stat.availableBlocksLong * stat.blockSizeLong
            val pct = ((1.0 - avail.toDouble() / total.toDouble()) * 100).toInt()
            storage = "S$pct%"
            toolCfg["storage"]?.let { c -> updatePet("storage", bucketLevel(pct, c.buckets)) }
        }
        runCatching {
            // The raw 1-minute /proc/loadavg figure, the SAME source + value the SystemInfoPopup shows, suffixed "s";
            // NOT a core-normalised percentage (that rounded to 0 on an idle phone).
            com.diegonmarcos.superapp.battery.SysfsProc.cpuLoad()?.let { c ->
                cpu = "C%.2fs".format(c.loadAvg1m)
                // the pet bucket still wants an int "level"; the normalised load% serves purely for that
                val lvlPct = (c.loadAvg1m / c.cores.coerceAtLeast(1) * 100).toInt().coerceAtLeast(0)
                toolCfg["cpu"]?.let { cfg -> updatePet("cpu", bucketLevel(lvlPct, cfg.buckets)) }
            }
        }
    }

    private companion object {
        const val USB_STATE = "android.hardware.usb.action.USB_STATE"
        /** ConnectivityManager.ACTION_TETHER_STATE_CHANGED (hidden constant, sticky since API 8). */
        const val TETHER_STATE = "android.net.conn.TETHER_STATE_CHANGED"
        /** WifiManager.WIFI_AP_STATE_CHANGED_ACTION (hidden constant, sticky). */
        const val AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        /** Settings.Global.ADB_WIFI_ENABLED (@hide, API 30+). */
        const val ADB_WIFI = "adb_wifi_enabled"
    }
}

/** Where the strip is hooked into the shell: the ComposeView declared in activity_main.xml. */
object LauncherStatusStrip {

    /** Give the shell's strip slot (the ComposeView ShellActivity found) its model and content. Returns the model. */
    fun install(activity: Activity, host: ComposeView): StatusStripModel {
        val model = StatusStripModel(activity)
        // Reads the cutout, never consumes it: see StatusStripModel.readCutoutFrom.
        model.readCutoutFrom(host)
        host.setContent { KitDenseMaterial(androidx.compose.material3.MaterialTheme.colorScheme) { LauncherStripContent(model) } }
        return model
    }
}

private val INK_SHADOW = Shadow(Color(0xCC000000), Offset(0f, 1f), 3f)
private val MONO_BOLD = FontFamily.Monospace

/** Four tiny dots, filled left to right for [level] 0..4; the unfilled ones a faint copy of the tint. -1 draws nothing but keeps the footprint. */
@Composable
internal fun SignalDots(level: Int, tint: Int, modifier: Modifier = Modifier) {
    val l = level.coerceIn(-1, 4)
    val base = Color(tint)
    Row(modifier.padding(top = 1.dp)) {
        for (i in 0 until 4) {
            val a = when {
                l < 0 -> 0f
                i < l -> 1f
                else -> 0.3f
            }
            Box(
                Modifier.padding(start = if (i > 0) 1.5.dp else 0.dp).size(2.dp)
                    .alpha(a).background(base, CircleShape),
            )
        }
    }
}

/** The height of one dots row (1 dp top pad + 2 dp dots): what the columns that carry none reserve. */
private val DOTS_ROW = 3.dp

/** The shared small monospace label of both clusters. */
@Composable
private fun IconLabel(text: String, tint: Int, modifier: Modifier = Modifier) {
    Text(
        text, color = Color(tint), fontSize = 10.sp, fontFamily = MONO_BOLD, fontWeight = FontWeight.Bold, maxLines = 1,
        style = androidx.compose.ui.text.TextStyle(shadow = INK_SHADOW),
        modifier = modifier.padding(horizontal = 3.dp),
    )
}

/** A tap target that reports its window bounds, so the popup it opens can hang under it. */
@Composable
private fun Tappable(tag: String, onTap: (IntRect) -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        modifier.testTag(tag).onGloballyPositioned { coords = it }.clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = {
                val c = coords?.takeIf { it.isAttached } ?: return@clickable
                val r = c.boundsInWindow()
                onTap(IntRect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
            },
        ),
    ) { content() }
}

/** The pet above a tool: a fixed animal whose gait follows the tool. Absent with pets off (its row collapses, as it did). */
@Composable
private fun PetAbove(model: StatusStripModel, toolId: String) {
    val cfg = model.petOf(toolId) ?: return
    if (!ShellFxState.pets) return
    val dp = with(LocalDensity.current) { model.petPx.toDp() }
    val gait = model.gait[toolId] ?: "idle"
    AndroidView(
        factory = { PetStrengthView(it).apply { setAnimal(cfg.animal, cfg.variant); setGait(gait) } },
        update = { it.setGait(gait) },
        modifier = Modifier.size(dp),
    )
}

@Composable
private fun LauncherStripContent(model: StatusStripModel) {
    val ctx = LocalView.current.context
    DisposableEffect(model) {
        model.start()
        onDispose { model.stop() }
    }
    StatusStripBody(model, ctx)
}

@Composable
internal fun StatusStripBody(model: StatusStripModel, ctx: Context) {
    val host = LocalView.current
    val density = LocalDensity.current
    // THE DECLARATION DRAWS THE BAR (build.json::home_status_bar): which elements, in which cluster, in which order, and
    // whether each shows, come from StatusBarPrefs.layout (the declaration plus the owner's overrides), never from a list here.
    val layout = StatusBarPrefs.layout(ctx)
    Column(Modifier.fillMaxWidth()) {
        // The clock sits at TRUE screen centre, aligned with the island below. Inside the safe area: 10 dp, and clear of a
        // cutout at the screen's side. The three clusters SHARE that width (StripRow): they never overlap and never leave it.
        val padStart = 10.dp + with(density) { model.insetLeftPx.toDp() }
        val padEnd = 10.dp + with(density) { model.insetRightPx.toDp() }
        val gapPx = with(density) { 4.dp.roundToPx() }
        StripRow(
            reservePx = model.clockReservePx(),
            dotsPx = with(density) { DOTS_ROW.roundToPx() },
            gapPx = gapPx,
            modifier = Modifier.fillMaxWidth().padding(start = padStart, end = padEnd, top = with(density) { model.innerRowTopPadPx.toDp() },
                bottom = with(density) { model.innerRowTopPadPx.toDp() }).testTag("strip:row"),
            left = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StripCluster(layout.getValue(StatusBarDecl.LEFT), model, ctx, host)
                    SpeedChip(model.speed)
                }
            },
            reserve = { h -> Spacer(Modifier.testTag("strip:reserve").height(with(density) { h.toDp() })) },
            // CENTRE: the clock, below the camera punch-hole / pet row.
            clock = { StripCluster(layout.getValue(StatusBarDecl.CENTRE), model, ctx, host) },
            right = { StripCluster(layout.getValue(StatusBarDecl.RIGHT), model, ctx, host) },
        )
        // ── Bottom hairline: faint white separator. ShellActivity nudges the toolbar island 6 dp below it. ──
        Spacer(Modifier.fillMaxWidth().height(with(density) { maxOf(1, (density.density * 0.75f).toInt()).toDp() }).background(Color(0x33FFFFFF)))
    }
}

/** #1446 The speedmeter: two 7 sp lines, the latest reading over the 24 h average, each in Mbps and MB/s; a tap runs a test. */
@Composable
private fun SpeedChip(speed: com.diegonmarcos.superapp.network.SpeedMeter) {
    val tint = Color.White
    val sh = androidx.compose.ui.text.TextStyle(shadow = Shadow(Color(0xCC000000), Offset(0f, 1f), 3f))
    val now = if (speed.testing) "testing..." else com.diegonmarcos.superapp.network.SpeedMath.line("now", speed.nowMbps)
    val avg = com.diegonmarcos.superapp.network.SpeedMath.line("24h", speed.avgMbps)
    Tappable("strip:speed", onTap = { speed.testNow() }, modifier = Modifier.padding(start = 6.dp).semantics {
        contentDescription = "Speed test. $now. $avg"
    }) {
        Column {
            Text(now, color = tint, fontSize = 7.sp, fontFamily = MONO_BOLD, maxLines = 1, softWrap = false, style = sh)
            Text(avg, color = tint.copy(alpha = 0.75f), fontSize = 7.sp, fontFamily = MONO_BOLD, maxLines = 1, softWrap = false, style = sh)
        }
    }
}

/** One cluster: its declared elements, left to right in the order [StatusBarDecl.layout] gave them. */
@Composable
private fun StripCluster(elements: List<StatusElement>, model: StatusStripModel, ctx: Context, host: View) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (el in elements) StripElementView(el, model, ctx, host)
    }
}

/** One declared element: its pet row, then the drawing its `kind` names, whose tap opens what its `opens` names. */
@Composable
private fun StripElementView(el: StatusElement, model: StatusStripModel, ctx: Context, host: View) {
    val tasksLabel = stringResource(R.string.tasks_label)
    val open: (IntRect) -> Unit = { r ->
        when (el.opens) {
            "network_popup" -> NetworkInfoPopup.show(ctx, host, r, el.id)
            "system_popup" -> SystemInfoPopup.show(ctx, host, r)
            "tasks_popup" -> TasksPopup.show(host, r)
            else -> Unit
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        el.pet?.let { PetAbove(model, it) }
        when (el.kind) {
            "network" -> {
                val chip = model.chips[el.id] ?: return@Column
                Tappable("strip:${el.id}", onTap = open) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconLabel(chip.label, chip.tint)
                        if (chip.peerDots != null) PeerDots(chip.peerDots, chip.peerDescription)
                        else SignalDots(chip.dots, chip.dotsTint)
                    }
                }
            }
            "clock" -> Tappable("strip:${el.id}", onTap = open, modifier = Modifier.semantics {
                contentDescription = "${tasksLabel}, ${model.clock}"
            }) {
                Text(
                    model.clock, color = Color.White, fontSize = 12.sp, fontFamily = MONO_BOLD, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                    style = androidx.compose.ui.text.TextStyle(shadow = Shadow(Color(0xCC000000), Offset(0f, 1f), 4f)),
                )
            }
            "metric" -> Tappable("strip:${el.id}", onTap = open) {
                IconLabel(model.metricText(el.id), 0xFFFFFFFF.toInt())
            }
            "battery" -> AndroidView(
                factory = { c ->
                    BatteryIconView(c).apply {
                        // Tap -> the "Estimated battery last" popup + session metrics, one glance from the strip.
                        isClickable = true
                        // An icon-only button needs a label: TalkBack reads it, and the emulator driver taps it by it.
                        contentDescription = "Battery status"
                        setOnClickListener {
                            if (el.opens == "battery_popup") BatteryEstimatePopup.show(c, this) { com.diegonmarcos.superapp.batterystats.BatteryStatsPage.open(c) }
                        }
                    }
                },
                update = { it.setBattery(model.batteryPct, model.batteryCharging) },
                modifier = Modifier.padding(start = 4.dp).testTag("strip:${el.id}"),
            )
        }
    }
}

/**
 * The strip's one row: LEFT cluster | clock (under the reserve) | RIGHT cluster, inside the incoming width.
 *
 * Until this, the three were Box siblings each allowed the WHOLE width: at the Samsung A37's 411 dp the left cluster
 * (~210 dp) + clock (~144 dp) + right cluster (~130 dp) already need ~490 dp, so they drew over one another, and at a
 * larger font scale or a larger Screen zoom (340-360 dp) the clusters ran over the clock and the screen border.
 *
 * Now the clock stays centred, so each side gets the SAME room: needed = clock + 2 x max(left, right) + 2 gaps. When
 * that is more than the width, all three are scaled down by one factor (text, dots, pets, battery alike; the camera
 * reserve above the clock is NOT scaled - it is the camera's own pixels). Nothing scrolls, nothing is cut, and the
 * laid-out bounds (graphics-layer scaled) stay inside the row.
 */
@Composable
internal fun StripRow(
    reservePx: Int, dotsPx: Int, gapPx: Int, modifier: Modifier,
    left: @Composable () -> Unit, reserve: @Composable (Int) -> Unit, clock: @Composable () -> Unit, right: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        contents = listOf(left, { reserve(reservePx) }, clock, right), modifier = modifier,
    ) { (lm, resm, cm, rm), c ->
        val free = androidx.compose.ui.unit.Constraints()
        val l = lm.map { it.measure(free) }; val res = resm.map { it.measure(free) }
        val ck = cm.map { it.measure(free) }; val r = rm.map { it.measure(free) }
        fun w(ps: List<androidx.compose.ui.layout.Placeable>) = ps.maxOfOrNull { it.width } ?: 0
        fun h(ps: List<androidx.compose.ui.layout.Placeable>) = ps.maxOfOrNull { it.height } ?: 0
        val room = if (c.hasBoundedWidth) c.maxWidth else w(ck) + 2 * maxOf(w(l), w(r)) + 2 * gapPx
        val needed = w(ck) + 2 * maxOf(w(l), w(r))
        val s = stripScale(room - 2 * gapPx, needed)
        val reserveH = h(res)
        val clockBlock = reserveH + (h(ck) * s).roundToInt() + dotsPx
        val lh = (h(l) * s).roundToInt(); val rh = (h(r) * s).roundToInt() + dotsPx
        val height = maxOf(clockBlock, lh, rh).coerceIn(c.minHeight, c.maxHeight)
        val width = room.coerceIn(c.minWidth, c.maxWidth)
        layout(width, height) {
            val origin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            fun place(p: androidx.compose.ui.layout.Placeable, x: Int, y: Int) =
                p.placeWithLayer(x, y) { scaleX = s; scaleY = s; transformOrigin = origin }
            l.forEach { place(it, 0, (height - lh) / 2) }
            val top = (height - clockBlock) / 2
            res.forEach { it.place((width - it.width) / 2, top) }
            ck.forEach { place(it, ((width - it.width * s) / 2f).roundToInt(), top + reserveH) }
            r.forEach { place(it, (width - it.width * s).roundToInt(), (height - rh) / 2) }
        }
    }
}

/** The one factor the strip's clusters and clock are drawn at: 1 when [needed] fits [room], else room / needed. */
internal fun stripScale(room: Int, needed: Int): Float =
    if (needed <= 0 || needed <= room) 1f else (room.coerceAtLeast(0).toFloat() / needed).coerceAtMost(1f)
