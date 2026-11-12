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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.diegonmarcos.superapp.network.NetworkSignal
import com.diegonmarcos.superapp.network.SignalLevels
import com.diegonmarcos.superapp.network.TetherModel
import com.diegonmarcos.superapp.network.UsbDataModel
import com.diegonmarcos.superapp.network.WgLink
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import com.diegonmarcos.superapp.ui.ShellFxState
import com.diegonmarcos.superapp.ui.SystemInfoPopup
import com.diegonmarcos.superapp.zoomies.PetStrengthView
import org.json.JSONObject
import androidx.lifecycle.compose.currentStateAsState
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
 *          Configs > Launcher > "Live system stats" is on. Under it (#1673) the Permission Access Status row: Mic / Camera /
 *          Location, coloured while any app uses them (PermAccessWatch callbacks), a tap opens the 24 h privacy dashboard.
 *
 * It was LauncherStatusStripView, a 935-line LinearLayout that built every TextView, dot and column by hand. Now
 * [StatusStripModel] owns the receivers and the readings as Compose state, and [LauncherStatusStrip] only draws them.
 */

/** One left-cluster icon: its label, text tint, and the dots row under it (level 0..4, or -1 for the empty footprint). */
internal data class StripChip(
    val label: String, val tint: Int, val dots: Int = SignalLevels.NONE, val dotsTint: Int = tint,
    /** Instead of the four signal dots: ADB's ONE dot (the broker's live status), with the spoken form. */
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
    internal fun metricText(id: String): String = when (id) { "ram" -> ram; "storage" -> storage; "cpu" -> cpu; "fps" -> "F${com.diegonmarcos.superapp.system.FpsMeter.fps}"; else -> "" }
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
    /** #1684 TypC: the last USB_STATE extras + OTG list, and the battery's EXTRA_PLUGGED, so either broadcast re-derives the cable. */
    private var usbExtras: Map<String, Boolean> = emptyMap()
    private var usbOtg: List<String> = emptyList()
    private var plugged = 0
    internal var cable = com.diegonmarcos.superapp.network.UsbDataModel.Cable.NONE
        private set
    /** #1684 WebS / SSHd: the last real loopback round trips (null = not checked yet). */
    @Volatile internal var websProbe: com.diegonmarcos.superapp.network.LocalDoors.Probe? = null
        private set
    @Volatile internal var sshdProbes: List<com.diegonmarcos.superapp.network.LocalDoors.Probe> = emptyList()
        private set
    private val doorsChecking = java.util.concurrent.atomic.AtomicBoolean(false)
    private var hasAdb = false
    private var hasHotspot = false
    private var hasKde = false
    /** The central broker's last live reading (null until the first answer). */
    private var brokerStatus: com.diegonmarcos.superapp.shellclient.ShellStatus? = null
    private var brokerPinned = false
    private var wifiLevel = SignalLevels.NONE
    private var mobile = MobileLink.Reading(MobileLink.Kind.NO_SERVICE, MobileLink.OFF_LABEL, WgLink.TINT_OFF, 0, "", null)
    private val mobileWatch = MobileProbe.Watch(context) { signalOnly -> refreshMobile(signalOnly) }
    private var mobileInputs: MobileLink.Inputs? = null
    private val gpsWatch = GpsProbe.StripWatch(context) { applyIconTints() }
    /** #1673 the Permission Access Status row's live Mic / Camera / Location states: callbacks between [start] and [stop] only. */
    val permAccess = PermAccessWatch(context, PermAccessUi.brokerRead(context))
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
        // #1550 a Wireless debugging / adb switch flipped: the dot re-checks the transport now (no cached green).
        override fun onChange(selfChange: Boolean) {
            refreshAdb()
            runCatching { com.diegonmarcos.superapp.shellbroker.ShellBroker.invalidate(context) }
            refreshBroker()
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { mainHandler.post { refreshNetworkFromConnectivity() } }
        // #1684 a network came or went (the mesh tun included): the doors' binds may have changed, re-check them once.
        override fun onLost(network: Network) { mainHandler.post { refreshNetworkFromConnectivity(); refreshDoors() } }
        override fun onAvailable(network: Network) { mainHandler.post { refreshNetworkFromConnectivity(); refreshDoors() } }
    }

    /** True between [start] and [stop]: the receivers, callbacks and tickers below exist only then. */
    var running = false
        private set

    /**
     * Register everything and take a first reading of each. Called when the strip's host becomes visible (ON_START, see
     * [StripLifecycle]) and undone by [stop] when it no longer is. Idempotent.
     */
    fun start() {
        if (running) return
        running = true
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
        gpsWatch.start()
        permAccess.start()
        (context as? android.app.Activity)?.window?.let { com.diegonmarcos.superapp.system.FpsMeter.attach(it, mainHandler) }
        refreshAdb()
        com.diegonmarcos.superapp.system.AdbReadiness.addListener(brokerListener)
        refreshBroker()
        refreshDoors()
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
        if (!running) return
        running = false
        runCatching { context.unregisterReceiver(timeReceiver) }
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { context.unregisterReceiver(usbReceiver) }
        runCatching { context.unregisterReceiver(tetherReceiver) }
        runCatching { context.unregisterReceiver(rssiReceiver) }
        runCatching { context.unregisterReceiver(btReceiver) }
        BtLinks.onProxy = null
        runCatching { context.contentResolver.unregisterContentObserver(adbObserver) }
        mobileWatch.stop()
        gpsWatch.stop()
        permAccess.stop()
        com.diegonmarcos.superapp.system.FpsMeter.detach()
        runCatching {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(networkCallback)
        }
        mainHandler.removeCallbacks(metricsTicker)
        com.diegonmarcos.superapp.system.AdbReadiness.removeListener(brokerListener)
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
        val p = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        if (p != plugged) { plugged = p; deriveCable(); applyIconTints() }
        if (pct >= 0) toolCfg["battery"]?.let { updatePet("battery", bucketLevel(pct, it.buckets)) }
    }

    /** USB cable DATA-transfer state from ACTION_USB_STATE (+ any attached OTG device). The rule is UsbDataModel's, the same
     *  one the popup's Data section prints. */
    private fun refreshUsb(intent: Intent?) {
        val extras = UsbDataModel.EXTRA_KEYS.associateWith { intent?.getBooleanExtra(it, false) == true }
        val otg = runCatching {
            (context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager)?.deviceList?.keys?.toList()
        }.getOrNull() ?: emptyList()
        usbExtras = extras; usbOtg = otg
        deriveCable()
        usbTetherFn = extras["rndis"] == true || extras["ncm"] == true
        hasHotspot = TetherModel.State(tetherIfaces, apState, usbTetherFn).active
        applyIconTints()
    }

    /** #1684 TypC + the Data light from the last USB_STATE and battery broadcasts (no polling). */
    private fun deriveCable() {
        val st = UsbDataModel.state(usbExtras, plugged, usbOtg)
        hasUsbData = st.data
        cable = st.cable
    }

    /**
     * #1684 WebS + SSHd: one real loopback round trip each (HTTP HEAD on the webserver's health route; TCP connect + SSH
     * banner per declared door), off the main thread. Called when the strip becomes visible (start / resume) and on network
     * change events; never on a timer. One check at a time.
     */
    internal fun refreshDoors() {
        if (!running || !doorsChecking.compareAndSet(false, true)) return
        runCatching {
            Thread {
                try {
                    val d = com.diegonmarcos.superapp.network.LocalDoors.declared
                    val w = runCatching { com.diegonmarcos.superapp.network.LocalDoors.probeHttp(d.webs, d.timeoutMs) }.getOrNull()
                    val ss = d.sshd.mapNotNull { runCatching { com.diegonmarcos.superapp.network.LocalDoors.probeSsh(it, d.timeoutMs) }.getOrNull() }
                    mainHandler.post { websProbe = w; sshdProbes = ss; applyIconTints() }
                } finally { doorsChecking.set(false) }
            }.apply { name = "strip-doors"; isDaemon = true }.start()
        }.onFailure { doorsChecking.set(false) }
    }

    /** Pin door readings (tests). */
    internal fun noteDoors(webs: com.diegonmarcos.superapp.network.LocalDoors.Probe?, sshd: List<com.diegonmarcos.superapp.network.LocalDoors.Probe>) {
        websProbe = webs; sshdProbes = sshd; applyIconTints()
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

    /** Take the broker's live reading. [pin] freezes it against refreshes (a test's fixed reading). */
    /** #1678 the one readiness source's change listener (AdbReadiness owns the broker callback). */
    private val brokerListener: (com.diegonmarcos.superapp.shellclient.ShellStatus) -> Unit = { st -> mainHandler.post { noteBroker(st) } }

    internal fun noteBroker(st: com.diegonmarcos.superapp.shellclient.ShellStatus, pin: Boolean = false) {
        if (pin) brokerPinned = true else if (brokerPinned) return
        brokerStatus = st
        applyIconTints()
    }

    /** One live check (an `id -u` round trip, off the main thread) when the strip opens or the screen resumes; no timer. */
    internal fun refreshBroker() {
        Thread {
            val r = runCatching { com.diegonmarcos.superapp.shellbroker.ShellBroker.status(context) }.getOrNull()
            if (r != null) { com.diegonmarcos.superapp.system.AdbReadiness.publish(r); mainHandler.post { noteBroker(r) } }
        }.start()
    }

    private fun refreshAdb() {
        val cr = context.contentResolver
        val usb = runCatching { android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
        // Wireless debugging: the lib's own read (Settings.Global adb_wifi_enabled, API 30+).
        val wifi = runCatching { com.diegonmarcos.superapp.adbdebug.WirelessDebugging.isOn(context) }.getOrDefault(false)
        hasAdb = usb || wifi
        applyIconTints()
    }

    /** Wi-Fi RSSI -> 0..4 (RSSI_CHANGED_ACTION + every connectivity change). #1666: from the Wi-Fi network's capabilities
     *  ([NetworkSignal.wifiRssi]), never WifiManager.getConnectionInfo: that is a location access on every call once location
     *  is granted, and RSSI_CHANGED fires every few seconds, which kept the location dot lit in the background. */
    private fun refreshWifiLevel() {
        wifiLevel = if (!hasWifi) SignalLevels.NONE else runCatching { SignalLevels.wifi(NetworkSignal.wifiRssi(context)) }
            .getOrDefault(SignalLevels.NONE)
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
            // ADB: the label follows USB / Wireless debugging; the ONE dot is the central broker's live status.
            NetworkSections.ADB to StripChip(label.getValue(NetworkSections.ADB), if (hasAdb) on else off,
                peerDots = AdbPeerDots.tints(brokerStatus), peerDescription = AdbPeerDots.describe(brokerStatus)),
            // #1684 TypC (was "Data"): white = data, amber = charge-only cable, grey = no cable.
            NetworkSections.DATA to StripChip(label.getValue(NetworkSections.DATA), StripDoors.cableTint(cable, on, off)),
            // #1684 WebS / SSHd: white only from a real loopback round trip; grey = down; dim = not checked / not declared.
            NetworkSections.WEBS to StripDoors.websState(websProbe).let { st -> StripChip(label.getValue(NetworkSections.WEBS), StripDoors.tint(st, on, off),
                peerDots = listOf(StripDoors.dot(st)), peerDescription = StripDoors.describe(st, listOfNotNull(websProbe))) },
            NetworkSections.SSHD to StripDoors.sshdState(sshdProbes).let { st -> StripChip(label.getValue(NetworkSections.SSHD), StripDoors.tint(st, on, off),
                peerDots = listOf(StripDoors.dot(st)), peerDescription = StripDoors.describe(st, sshdProbes)) },
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

    private val metricsIo by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "strip-metrics").apply { isDaemon = true } } }

    /** RAM + /data storage + CPU load into the right cluster. The reads (a binder call, StatFs, /proc) run on a worker
     *  thread so the 10s tick never hitches the foreground app; only the result is applied on the main thread. */
    private fun refreshMetrics() {
        runCatching { metricsIo.execute {
            val ramPct = runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.let {
                    val info = ActivityManager.MemoryInfo()
                    it.getMemoryInfo(info)
                    ((1.0 - info.availMem.toDouble() / info.totalMem.toDouble()) * 100).toInt()
                }
            }.getOrNull()
            val storagePct = runCatching {
                val stat = StatFs(Environment.getDataDirectory().absolutePath)
                val total = stat.blockCountLong * stat.blockSizeLong
                val avail = stat.availableBlocksLong * stat.blockSizeLong
                ((1.0 - avail.toDouble() / total.toDouble()) * 100).toInt()
            }.getOrNull()
            // The raw 1-minute /proc/loadavg figure, the SAME source + value the SystemInfoPopup shows, suffixed "s";
            // NOT a core-normalised percentage (that rounded to 0 on an idle phone).
            val load = runCatching { com.diegonmarcos.superapp.battery.SysfsProc.cpuLoad() }.getOrNull()
            mainHandler.post {
                runCatching {
                    if (ramPct != null) {
                        ram = "R$ramPct%"
                        toolCfg["ram"]?.let { c -> updatePet("ram", bucketLevel(ramPct, c.buckets)) }
                    }
                    if (storagePct != null) {
                        storage = "S$storagePct%"
                        toolCfg["storage"]?.let { c -> updatePet("storage", bucketLevel(storagePct, c.buckets)) }
                    }
                    load?.let { c ->
                        cpu = "C%.2fs".format(c.loadAvg1m)
                        // the pet bucket still wants an int "level"; the normalised load% serves purely for that
                        val lvlPct = (c.loadAvg1m / c.cores.coerceAtLeast(1) * 100).toInt().coerceAtLeast(0)
                        toolCfg["cpu"]?.let { cfg -> updatePet("cpu", bucketLevel(lvlPct, cfg.buckets)) }
                    }
                }
            }
        } }
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
    // #1522 the ADB dot is re-checked on every resume (the broker caches a verdict for seconds), never on a timer.
    StripLifecycle(model, onStart = { model.start() }, onResume = { model.refreshBroker(); model.refreshDoors() }, onStop = { model.stop() })
    StatusStripBody(model, ctx)
}

/**
 * The strip's model runs only while its host is VISIBLE (started), not for as long as it is composed. The launcher's
 * activity stays composed while another app is in front, and the model used to stay started with it: the 10 s
 * RAM / CPU / storage ticker, the minute tick, the battery, Wi-Fi signal and all-networks callbacks and the passive
 * location listener kept running for a strip nobody could see. [onStart] runs on ON_START (and at once when the host is
 * already started), [onStop] on ON_STOP and when the strip leaves the composition, [onResume] on every ON_RESUME. A start
 * takes a fresh reading of everything, so what the strip shows on return is the state then, not the state it left.
 */
@Composable
internal fun StripLifecycle(key: Any, onStart: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, key) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> onStart()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> onResume()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o); onStop() }
    }
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
            left = { StripCluster(layout.getValue(StatusBarDecl.LEFT), model, ctx, host) },
            reserve = { h -> Spacer(Modifier.testTag("strip:reserve").height(with(density) { h.toDp() })) },
            // CENTRE: the clock, below the camera punch-hole / pet row.
            clock = { StripCluster(layout.getValue(StatusBarDecl.CENTRE), model, ctx, host) },
            // #1673 the resources line, and directly under it the Permission Access Status row (Mic / Camera / Location).
            right = {
                Column(horizontalAlignment = Alignment.End) {
                    StripCluster(layout.getValue(StatusBarDecl.RIGHT), model, ctx, host)
                    PermAccessStatusRow(model.permAccess) { r -> PermAccessUi.show(ctx, host, r) }
                }
            },
            stopwatch = { StopwatchReadout(host) },
        )
        // ── Bottom hairline: faint white separator. ShellActivity nudges the toolbar island 6 dp below it. ──
        Spacer(Modifier.fillMaxWidth().height(with(density) { maxOf(1, (density.density * 0.75f).toInt()).toDp() }).background(Color(0x33FFFFFF)))
    }
}

/**
 * #1646 the live stopwatch time, left of the date. It reads the ONE stopwatch the date dropdown opens (TasksPopup) and exists
 * only while that runs. It ticks (once a second, on the second) only while the launcher is STARTED and the stopwatch runs:
 * the effect is keyed on both, so a stop or an idle stopwatch cancels it, and nothing runs in the background.
 */
/** #1646 the readout's tick runs only while the launcher is started AND the stopwatch runs. */
internal fun stopwatchShouldTick(running: Boolean, started: Boolean): Boolean = running && started

@Composable
internal fun StopwatchReadout(host: View) {
    // #1668 the lib's one stopwatch (libs:clock-tools), the same state the date dropdown drives.
    val sw = com.diegonmarcos.clocktools.ui.rememberStopwatch()
    if (!sw.running) return
    val started = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        .currentStateAsState().value.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    fun elapsed() = com.diegonmarcos.clocktools.ClockLogic.elapsed(sw, com.diegonmarcos.clocktools.ClockEngine.now())
    var ms by remember(sw) { mutableStateOf(elapsed()) }
    LaunchedEffect(started, sw) {
        while (stopwatchShouldTick(running = true, started = started)) { ms = elapsed(); kotlinx.coroutines.delay(1000L - ms % 1000L) }
    }
    Tappable("strip:stopwatch", onTap = { r -> TasksPopup.show(host, r) }) {
        Text(
            TasksPopup.formatStopwatchBar(ms), color = Color(0xFFE9D8FD), fontSize = 12.sp, fontFamily = MONO_BOLD, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
            style = androidx.compose.ui.text.TextStyle(shadow = Shadow(Color(0xCC000000), Offset(0f, 1f), 4f)),
        )
    }
}

/** One cluster: its declared elements, left to right in the order [StatusBarDecl.layout] gave them. */
@Composable
private fun StripCluster(elements: List<StatusElement>, model: StatusStripModel, ctx: Context, host: View) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // #1684 a thin separator where the network group changes (5G WiFi BT HS GPS | WG KDE | WebS ADB SSHd | TypC).
        var prev: String? = null
        for (el in elements) {
            if (NetworkSections.separatorBetween(prev, el.id)) GroupSeparator(prev + "|" + el.id)
            StripElementView(el, model, ctx, host)
            prev = el.id
        }
    }
}

/** #1684 the strip's group separator: a 1 dp hairline, the label's height, with 3 dp each side. */
@Composable
private fun GroupSeparator(tag: String) {
    Box(Modifier.padding(horizontal = 3.dp).width(1.dp).height(14.dp).background(Color(0x66FFFFFF)).testTag("strip:sep:$tag"))
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
            // #1522 the ADB icon (one dot: the broker's live status) opens the ADB Shell page itself, not the network dropdown.
            "adb_shell_page" -> com.diegonmarcos.superapp.adbdebug.AdbShellLink.open(ctx)
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
                        if (chip.peerDots != null) PeerDots(chip.peerDots, chip.peerDescription, name = el.id)
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
                            if (el.opens == "battery_popup") BatteryEstimatePopup.show(c, this, openStats = { com.diegonmarcos.superapp.batterystats.BatteryStatsPage.open(c) }, extra = { com.diegonmarcos.superapp.system.StatusDropdowns.BatterySinceCharge(c) })
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
    stopwatch: @Composable () -> Unit = {},
) {
    androidx.compose.ui.layout.Layout(
        contents = listOf(left, { reserve(reservePx) }, clock, right, stopwatch), modifier = modifier,
    ) { (lm, resm, cm, rm, sm), c ->
        val free = androidx.compose.ui.unit.Constraints()
        val l = lm.map { it.measure(free) }; val res = resm.map { it.measure(free) }
        val ck = cm.map { it.measure(free) }; val r = rm.map { it.measure(free) }
        val sw = sm.map { it.measure(free) }
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
            // #1646 the stopwatch readout: centred in the empty region between the left cluster and the date, scaled down
            // (never moved onto either) when that region is narrower than it.
            val lEnd = (w(l) * s).roundToInt() + gapPx
            val dateStart = ((width - w(ck) * s) / 2f).roundToInt() - gapPx
            val region = (dateStart - lEnd).coerceAtLeast(0)
            sw.forEach {
                val ss = if (it.width > region && it.width > 0) region.toFloat() / it.width else 1f
                val x = lEnd + ((region - it.width * ss) / 2f).roundToInt()
                it.placeWithLayer(x, top + reserveH + (((h(ck) * s) - it.height * ss) / 2f).roundToInt()) { scaleX = ss; scaleY = ss; transformOrigin = origin }
            }
        }
    }
}

/** The one factor the strip's clusters and clock are drawn at: 1 when [needed] fits [room], else room / needed. */
internal fun stripScale(room: Int, needed: Int): Float =
    if (needed <= 0 || needed <= room) 1f else (room.coerceAtLeast(0).toFloat() / needed).coerceAtMost(1f)
