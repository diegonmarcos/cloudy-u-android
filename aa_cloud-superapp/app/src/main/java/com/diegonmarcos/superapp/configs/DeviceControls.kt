package com.diegonmarcos.superapp.configs

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.adbdebug.WirelessDebugging
import com.diegonmarcos.superapp.system.WirelessDebugKeeper
import com.diegonmarcos.superapp.devcontrol.DevControlServer
import com.diegonmarcos.superapp.devtools.DevControlPrefs
import com.diegonmarcos.superapp.firewall.FirewallController
import com.diegonmarcos.superapp.floatingnav.FloatingNavService
import com.diegonmarcos.superapp.network.WgState
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import com.diegonmarcos.superapp.system.ModePrefs
import com.diegonmarcos.superapp.updater.AutoUpdatePrefs
import com.wireguard.android.backend.Tunnel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * What Configs ▸ Panel ▸ Control can ACTUALLY do, one entry per control id
 * declared in `build.json::ui.control_panel`.
 *
 * ── The rule this file exists to enforce ──────────────────────────────────
 * NEVER RENDER A SWITCH THAT LIES. A switch that flips under the thumb while
 * the device does not move is worse than no page at all: it is a control the
 * owner will trust once, act on, and be wrong about — and nothing on screen
 * will ever say so. So every control here is one of exactly three things, and
 * which one it is was decided from what this APK holds, not from what would
 * look tidiest in a row of switches:
 *
 *   1. REAL SWITCH, in-process. [Control.set] is non-null and [Control.blocked]
 *      never fires. The displayed state comes from [Control.read], which asks
 *      the platform — never a boolean we kept from the last write.
 *   2. REAL SWITCH, permission-gated. [Control.set] is non-null but
 *      [Control.blocked] returns the missing grant, and the row draws disabled
 *      with that sentence under it and a way to go grant it. The permissions
 *      in question are ones this app already declares and the privileged plane
 *      already hands it (WRITE_SECURE_SETTINGS, WRITE_SETTINGS,
 *      ACCESS_NOTIFICATION_POLICY, SYSTEM_ALERT_WINDOW) — "gated" means not
 *      granted YET on this device, not out of reach.
 *   3. NOT A SWITCH. [Control.set] is null, so [ControlFragment] draws a row
 *      that opens the exact system screen for it and shows the live state
 *      read-only beside it. Wi-Fi, mobile data, Bluetooth and airplane mode
 *      are all here, and the reason is in each entry.
 *
 * ── Why a write is not believed ───────────────────────────────────────────
 * Every [set] returns a [Verdict] computed by RE-READING the same source
 * [read] uses, so `ok` means the device agrees, never that a call did not
 * throw. That is the shape [WirelessDebugging.set] already proved in this
 * repo, and the reason the Wireless-debugging entry below is three lines: it
 * was already right.
 *
 * ── Where the data is, where the capability is ────────────────────────────
 * build.json owns PRESENTATION — which controls exist, their group, order,
 * wording and icon. This file owns CAPABILITY. The split is deliberate and is
 * not the usual data/code line: a command and the read-back that verifies it
 * are ONE fact, and putting the command in JSON while its verification stayed in
 * Kotlin is how the two would drift into a switch that reports success from a
 * command that stopped working three Android versions ago.
 *
 * Blocking: [read] and [set] both may block (binder calls, shell channels).
 * [ControlFragment] calls them off the main thread; nothing here posts to the
 * UI itself.
 */
object DeviceControls {

    /** The outcome of a write, decided by re-reading the device. */
    data class Verdict(val ok: Boolean, val detail: String)

    /**
     * One control.
     *
     * @param read live state from the system; null when the platform will not
     *   say. NEVER a remembered boolean.
     * @param observed whether [read] LOOKS AT THE THING, or only at a
     *   preference this app wrote down about it. There is no default: every
     *   control has to answer it, because a control added without answering it
     *   would default to claiming more than it knows, and the whole point of
     *   the light is that it claims nothing it cannot support. False does NOT
     *   mean the switch is broken — the preference is still what the switch is
     *   for, and flipping it still works. It means the row cannot be lit green
     *   or red, because the mechanism the preference is supposed to drive
     *   might have died without the preference hearing about it. See
     *   [ControlFragment], which draws those rows "? Not verifiable".
     * @param set null ⇒ category 3, this is not a switch and [open] is the
     *   only thing the row can do.
     * @param blocked "" ⇒ the switch is usable; anything else is the reason it
     *   is disabled, shown verbatim to the owner.
     * @param open the exact system screen for this control. Present on every
     *   category-3 row (it is their whole point) and on the gated switches (it
     *   is where the missing grant lives).
     */
    data class Control(
        val read: (Context) -> Boolean?,
        val observed: Boolean,
        val set: ((Context, Boolean) -> Verdict)? = null,
        val blocked: (Context) -> String = { "" },
        val open: ((Context) -> Unit)? = null,
    )

    // ── The catalog ──────────────────────────────────────────────────────
    // Keyed by the same ids build.json::ui.control_panel declares. An id
    // declared there and missing here is DROPPED by the fragment rather than
    // drawn, because a control that does nothing is the same lie as a switch
    // that does nothing.

    val byId: Map<String, Control> = mapOf(

        // ─────────────────────────── Phone ───────────────────────────

        // CATEGORY 1. setTorchMode needs no permission at all (API 23+), and
        // the platform reports the flash unit's state back through
        // TorchCallback — see [Torch], which is the only writer of that state.
        "torch" to Control(
            // The camera service's own TorchCallback, not our last write.
            observed = true,
            read = { ctx -> Torch.state(ctx) },
            set = { ctx, on ->
                val flash = Torch.flashCameraId(ctx)
                if (flash == null) Verdict(false, "This device has no flash unit.")
                else {
                    runCatching { cameras(ctx)?.setTorchMode(flash, on) }
                    // The system's own answer, not ours. A torch the camera
                    // refused (another app holding it) leaves this false.
                    Verdict(Torch.state(ctx) == on, "Camera reports " + fmt(Torch.state(ctx)))
                }
            },
        ),

        // CATEGORY 2. setInterruptionFilter is real API, gated on the
        // notification-policy special access this app declares. PRIORITY, not
        // NONE, matching the DND toggle FloatingNavService already ships — two
        // switches for one thing must not mean two different things.
        "dnd" to Control(
            // NotificationManager's current interruption filter.
            observed = true,
            read = { ctx ->
                notifications(ctx)?.currentInterruptionFilter
                    ?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL }
            },
            set = { ctx, on ->
                val nm = notifications(ctx)
                runCatching {
                    nm?.setInterruptionFilter(
                        if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        else NotificationManager.INTERRUPTION_FILTER_ALL)
                }
                val now = nm?.currentInterruptionFilter
                    ?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL }
                Verdict(now == on, "Interruption filter is " + fmt(now))
            },
            blocked = { ctx ->
                if (notifications(ctx)?.isNotificationPolicyAccessGranted == true) ""
                else "Do Not Disturb access is not granted to this app yet."
            },
            open = { ctx -> perms(ctx, "Control panel: Do Not Disturb", "dnd_access") },
        ),

        // CATEGORY 2. ACCELEROMETER_ROTATION is a public Settings.System key
        // and WRITE_SETTINGS is the grant that opens it — the same one
        // LauncherConfigFragment already writes SCREEN_BRIGHTNESS through, so
        // the capability is proven in this APK. Note the inversion: the switch
        // says LOCKED, the setting says auto-rotate.
        "rotation_lock" to Control(
            // The Settings key the window manager itself obeys.
            observed = true,
            read = { ctx -> systemInt(ctx, Settings.System.ACCELEROMETER_ROTATION)?.let { it == 0 } },
            set = { ctx, on ->
                runCatching {
                    Settings.System.putInt(ctx.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
                }
                val now = systemInt(ctx, Settings.System.ACCELEROMETER_ROTATION)?.let { it == 0 }
                Verdict(now == on, "Auto-rotate is " + fmt(now?.not()))
            },
            blocked = { ctx ->
                if (Settings.System.canWrite(ctx)) ""
                else "\"Modify system settings\" is not granted to this app yet."
            },
            open = { ctx -> perms(ctx, "Control panel: system settings", "write_settings") },
        ),

        // CATEGORY 2, and the one control whose whole ladder was already
        // written: WirelessDebugging tries WRITE_SECURE_SETTINGS first, then a
        // live shell channel, and its Result is a re-read either way. Blocked
        // only when BOTH rungs are gone, which is exactly when it could not
        // land the write.
        "wireless_debugging" to Control(
            // Settings.Global.adb_wifi_enabled — the daemon's own flag.
            observed = true,
            read = { ctx -> WirelessDebugging.isOn(ctx) },
            set = { ctx, on ->
                // The keep-alive follows the owner's hand: switching debugging
                // off here must not be undone by the watchdog a minute later,
                // and switching it on here is asking for it to stay on.
                WirelessDebugKeeper.Prefs.setEnabled(ctx, on)
                val r = WirelessDebugging.set(ctx, on)
                Verdict(r.ok, "${r.channel}: ${r.detail}")
            },
            blocked = { ctx ->
                if (hasSecureSettings(ctx) || ShellChannels.active(ctx) != null) ""
                else "Needs WRITE_SECURE_SETTINGS or a live shell channel " +
                     "(Wireless debugging pairing / Shizuku); this device has neither."
            },
            open = { ctx -> open(ctx, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
        ),

        // NOT OBSERVED, same as auto_update: the preference is what the
        // keeper consults when it runs. Whether the last pass got the channel
        // back is on Configs ▸ Dev control and /api/adb/status, not here.
        "wireless_debugging_keepalive" to Control(  // = WirelessDebugKeeper.CONTROL_ID
            observed = false,
            read = { ctx -> WirelessDebugKeeper.Prefs.enabled(ctx) },
            set = { ctx, on ->
                WirelessDebugKeeper.Prefs.setEnabled(ctx, on)
                Verdict(WirelessDebugKeeper.Prefs.enabled(ctx) == on,
                    "Keep-alive is " + fmt(WirelessDebugKeeper.Prefs.enabled(ctx)))
            },
        ),

        // CATEGORY 3. WifiManager.setWifiEnabled has been a no-op for a normal
        // app since Android 10 — this app's own PermissionsFragment says so at
        // the hotspot preconditions — and Settings.Panel.ACTION_WIFI is the
        // sanctioned replacement: the system's own Wi-Fi sheet over our page.
        // READING is unprivileged, so the row still shows the truth.
        "wifi" to Control(
            // WifiManager.isWifiEnabled.
            observed = true,
            read = { ctx -> wifi(ctx)?.isWifiEnabled },
            open = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) open(ctx, Settings.Panel.ACTION_WIFI)
                else open(ctx, Settings.ACTION_WIFI_SETTINGS)
            },
        ),

        // CATEGORY 3. Turning carrier data on or off is MODIFY_PHONE_STATE,
        // which is signature-only — no development-flagged permission and no
        // special access reaches it, so the privileged plane cannot help
        // either. The internet panel is the whole switch board for data,
        // Wi-Fi and airplane in one sheet.
        "mobile_data" to Control(
            // TelephonyManager, falling back to the system's own key.
            observed = true,
            read = { ctx -> mobileDataEnabled(ctx) },
            open = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    open(ctx, Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                else open(ctx, Settings.ACTION_DATA_ROAMING_SETTINGS)
            },
        ),

        // CATEGORY 3. BluetoothAdapter.enable()/disable() are deprecated and
        // return false without acting for a non-privileged app from Android 13
        // on. Reading isEnabled is fine (BLUETOOTH_CONNECT, which this app
        // declares), so state stays honest while the flip goes to Settings.
        "bluetooth" to Control(
            // BluetoothAdapter.isEnabled.
            observed = true,
            read = { ctx ->
                runCatching {
                    (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                        ?.adapter?.isEnabled
                }.getOrNull()
            },
            open = { ctx -> open(ctx, Settings.ACTION_BLUETOOTH_SETTINGS) },
        ),

        // CATEGORY 3, and the sharpest example of why this file has three
        // categories instead of two. `airplane_mode_on` IS writable with the
        // WRITE_SECURE_SETTINGS this app holds — and writing it alone does
        // nothing, because what actually parks the radios is the
        // ACTION_AIRPLANE_MODE_CHANGED broadcast, which is protected and only
        // the system may send. A switch here would have flipped, persisted,
        // changed the status-bar icon, and left Wi-Fi and the modem running:
        // a lie with a receipt. Read-only state plus the real settings screen
        // is the honest version.
        "airplane_mode" to Control(
            // Settings.Global.airplane_mode_on.
            observed = true,
            read = { ctx -> globalInt(ctx, Settings.Global.AIRPLANE_MODE_ON)?.let { it == 1 } },
            open = { ctx -> open(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS) },
        ),

        // ─────────────────────────── Cloud ───────────────────────────

        // CATEGORY 1. The app owns this server outright. State is
        // isRunning() — the accept loop's own flag — and NOT the `enabled`
        // preference: the pref is what we WANT, the loop is what IS, and the
        // whole point of this row is being told when they disagree.
        "app_api" to Control(
            // The accept loop's own flag, which is why this row is honest.
            observed = true,
            read = { DevControlServer.isRunning() },
            set = { ctx, on ->
                val app = ctx.applicationContext
                DevControlPrefs(app).enabled = on
                if (on) DevControlServer.start(app) else DevControlServer.stop()
                val now = DevControlServer.isRunning()
                Verdict(now == on, "Listener is " + fmt(now) +
                    (DevControlServer.boundHost()?.let { " on $it" } ?: ""))
            },
        ),

        // CATEGORY 2. The tunnel is genuinely ours to raise and drop — the
        // same call NetworkInfoPopup's mesh toggle makes — but two things
        // outside this app gate it: the engine APK that owns the VpnService,
        // and the one-time VPN consent granted through it. Both are reported
        // by name rather than left to fail as a silent no-op.
        "mesh" to Control(
            // The WireGuard backend's tunnel state, asked live.
            observed = true,
            read = { ctx ->
                runCatching { WgState.backend(ctx).getState(WgState.tunnel) == Tunnel.State.UP }
                    .getOrNull()
            },
            set = { ctx, on ->
                com.diegonmarcos.superapp.network.MeshIntent.setWanted(ctx, on) // #1120 the owner's choice sticks
                val backend = WgState.backend(ctx)
                val attempt = runCatching {
                    if (on) backend.setState(WgState.tunnel, Tunnel.State.UP,
                                             WgState.prefs(ctx).toTunnelConfig())
                    else backend.setState(WgState.tunnel, Tunnel.State.DOWN, null)
                }
                val now = runCatching { backend.getState(WgState.tunnel) == Tunnel.State.UP }.getOrNull()
                Verdict(now == on, attempt.exceptionOrNull()?.message
                    ?: ("Tunnel is " + fmt(now)))
            },
            blocked = { ctx ->
                val backend = WgState.backend(ctx)
                when {
                    !backend.isEngineInstalled() ->
                        "The WireGuard engine APK is not installed on this device."
                    backend.consentIntent() != null ->
                        "VPN consent has not been granted to the engine yet."
                    else -> ""
                }
            },
            open = { ctx -> (ctx as? ShellActivity)?.nav?.openSectionPage("wg", "config") },
        ),

        // CATEGORY 2. FirewallController.start/stop drive a VpnService this
        // APK owns, so the flip is real; Android's one-time VPN consent is the
        // gate, and VpnService.prepare returning non-null IS the missing
        // consent. isEnabled is the desired-state store the controller
        // reconciles against — which is enough to drive the SWITCH, and not
        // enough to drive the light. See `observed` below.
        "firewall" to Control(
            // NOT OBSERVED. FirewallController.isEnabled is
            // FirewallPrefs.isEnabled — the DESIRED state, which that file
            // documents as "kept separate from whether the VpnService is
            // actually established right now". Nothing here can see the
            // service, so a firewall the system killed reads exactly like a
            // firewall that is running. That is the false green this page
            // exists to refuse, so this row shows Not verifiable until
            // something can report the tunnel itself.
            observed = false,
            read = { ctx -> FirewallController.isEnabled(ctx) },
            set = { ctx, on ->
                if (on) FirewallController.start(ctx) else FirewallController.stop(ctx)
                val now = FirewallController.isEnabled(ctx)
                Verdict(now == on, "Firewall is " + fmt(now))
            },
            blocked = { ctx ->
                if (runCatching { VpnService.prepare(ctx) }.getOrNull() == null) ""
                else "VPN consent has not been granted to this app yet — " +
                     "turning it on will ask for it."
            },
            open = { ctx -> open(ctx, Settings.ACTION_VPN_SETTINGS) },
        ),

        // CATEGORY 1. An app-owned preference. The periodic workers read this
        // exact key at runtime to decide whether to run, so writing it really
        // does stop auto-updating — the switch is honest. Whether those workers
        // are still scheduled is a different fact, and one nothing here reads.
        "auto_update" to Control(
            // NOT OBSERVED. The store is what the periodic workers consult
            // WHEN THEY RUN; whether WorkManager still has them scheduled is
            // a different fact and this cannot see it. Enabled and never
            // running again are indistinguishable from here.
            observed = false,
            read = { ctx -> AutoUpdatePrefs.enabled(ctx) },
            set = { ctx, on ->
                AutoUpdatePrefs.setEnabled(ctx, on)
                Verdict(AutoUpdatePrefs.enabled(ctx) == on,
                    "Auto-update is " + fmt(AutoUpdatePrefs.enabled(ctx)))
            },
        ),

        // ─────────────────────────── System ──────────────────────────

        // CATEGORY 2. The overlay needs SYSTEM_ALERT_WINDOW, which is a
        // user-granted special access. State is the SERVICE's own isRunning
        // flag rather than the preference, for the same reason app_api reads
        // its accept loop: the overlay can be killed out from under the pref.
        "floating_nav" to Control(
            // The service's own isRunning, set in onCreate/onDestroy.
            observed = true,
            // #775: `armed`, not isRunning — the service also runs headless
            // as the badge host with the button off.
            read = { FloatingNavService.isRunning && FloatingNavService.armed },
            set = { ctx, on ->
                FloatingNavService.setEnabled(ctx, on)
                // Starting or stopping a service is a REQUEST: isRunning flips
                // in onCreate/onDestroy on the main looper, after this call
                // returns. Reading it immediately would report every successful
                // start as a failure and snap the switch back off a service
                // that was coming up — the honesty rule inverted into a lie of
                // its own. So wait for the service to agree, briefly.
                val live = { FloatingNavService.isRunning && FloatingNavService.armed }
                val settled = awaitTrue(SERVICE_SETTLE_MS) { live() == on }
                Verdict(settled, "Overlay is " + fmt(live()))
            },
            blocked = { ctx ->
                if (Settings.canDrawOverlays(ctx)) ""
                else "\"Display over other apps\" is not granted to this app yet."
            },
            open = { ctx -> perms(ctx, "Control panel: overlay", "overlay") },
        ),

        // CATEGORY 1. Apps/Admin is this app's own global view mode. Written
        // through the activity when there is one, so the Home grid, drawer and
        // bottom-nav icons follow immediately; the store is written either way
        // so the flip survives without an activity to tell. What nothing
        // reports back is whether those surfaces actually repainted.
        "admin_mode" to Control(
            // NOT OBSERVED. ModePrefs is a stored preference; nothing reports
            // back that the Home grid, drawer and bottom nav actually
            // repainted into that mode.
            observed = false,
            read = { ctx -> ModePrefs(ctx).mode == "admin" },
            set = { ctx, on ->
                val want = if (on) "admin" else "apps"
                // The store first, unconditionally: it is what every reader
                // consults, so the verdict is the same with or without a shell
                // to tell. Then the repaint, which is UI work and therefore
                // posted — this runs on the panel's background thread.
                ModePrefs(ctx).mode = want
                (ctx as? ShellActivity)?.let { shell -> shell.runOnUiThread { shell.applyMode(want) } }
                Verdict(ModePrefs(ctx).mode == want, "Mode is " + ModePrefs(ctx).mode)
            },
        ),

        // CATEGORY 1 ×2. Every animated surface in this app asks
        // LauncherSettingsPrefs.anim() before drawing, and Haptics gates on
        // "haptics", so writing these stores really does change behaviour.
        // Nothing reports back that it did; see launcherToggle.
        "all_anim" to launcherToggle("all_anim"),
        "haptics" to launcherToggle("haptics"),

        // CATEGORY 2. SCREEN_BRIGHTNESS_MODE behind the same WRITE_SETTINGS
        // grant as rotation lock. LauncherConfigFragment forces this key to
        // MANUAL when its brightness slider moves, so this row is also how the
        // owner hands adaptive brightness back.
        "auto_brightness" to Control(
            // Settings.System.screen_brightness_mode.
            observed = true,
            read = { ctx ->
                systemInt(ctx, Settings.System.SCREEN_BRIGHTNESS_MODE)
                    ?.let { it == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }
            },
            set = { ctx, on ->
                runCatching {
                    Settings.System.putInt(ctx.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                }
                val now = systemInt(ctx, Settings.System.SCREEN_BRIGHTNESS_MODE)
                    ?.let { it == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }
                Verdict(now == on, "Adaptive brightness is " + fmt(now))
            },
            blocked = { ctx ->
                if (Settings.System.canWrite(ctx)) ""
                else "\"Modify system settings\" is not granted to this app yet."
            },
            open = { ctx -> perms(ctx, "Control panel: system settings", "write_settings") },
        ),
    )

    /** One of the launcher_settings toggles, by its declared id. */
    private fun launcherToggle(id: String) = Control(
        read = { ctx -> LauncherSettingsPrefs(ctx).toggle(id) },
        // NOT OBSERVED. The store is the only readable thing: every animated
        // surface asks LauncherSettingsPrefs before drawing and Haptics gates
        // on it, but neither reports back, so this cannot tell a toggle that
        // is being honoured from one every caller has quietly stopped asking
        // about. Same store, same preference, one flag.
        observed = false,
        set = { ctx, on ->
            val prefs = LauncherSettingsPrefs(ctx)
            prefs.setToggle(id, on)
            Verdict(prefs.toggle(id) == on, "Now " + fmt(prefs.toggle(id)))
        },
    )

    /**
     * The flash unit's state AS THE SYSTEM REPORTS IT.
     *
     * CameraManager has no getter for torch mode — the only way to know is to
     * be told, through [CameraManager.TorchCallback]. That callback is the ONLY
     * writer of [on] here, which is the whole point: a setTorchMode the camera
     * service refuses (another app holding the device, a thermal cutoff) never
     * reaches it, so the switch snaps back instead of claiming a torch that is
     * dark.
     *
     * Registration delivers the current mode of every flash unit immediately,
     * so the first read waits briefly for that first delivery rather than
     * reporting "unknown" for a state the platform already knows. Every later
     * read finds the value sitting there. Callers are off the main thread —
     * [ControlFragment] guarantees it — so the wait costs nothing visible.
     */
    private object Torch {
        @Volatile private var registered = false
        @Volatile private var on: Boolean? = null
        private val first = CountDownLatch(1)

        fun state(ctx: Context): Boolean? {
            register(ctx)
            runCatching { first.await(FIRST_READ_MS, TimeUnit.MILLISECONDS) }
            return on
        }

        /** The first camera that HAS a flash, or null on a device with none. */
        fun flashCameraId(ctx: Context): String? = runCatching {
            val cm = cameras(ctx)
            cm?.cameraIdList?.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()

        @Synchronized private fun register(ctx: Context) {
            if (registered) return
            val cm = cameras(ctx) ?: run { registered = true; first.countDown(); return }
            val flash = flashCameraId(ctx)
            if (flash == null) { registered = true; first.countDown(); return }
            runCatching {
                cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId != flash) return
                        on = enabled
                        first.countDown()
                    }
                    override fun onTorchModeUnavailable(cameraId: String) {
                        if (cameraId != flash) return
                        // Unavailable is not "off": the flash is held by
                        // something else and its state is not ours to state.
                        on = null
                        first.countDown()
                    }
                }, Handler(Looper.getMainLooper()))
                registered = true
            }
        }

        private const val FIRST_READ_MS = 500L
    }

    // ── The small shared reads ───────────────────────────────────────────

    private fun cameras(ctx: Context): CameraManager? =
        ctx.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private fun wifi(ctx: Context): WifiManager? =
        ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private fun notifications(ctx: Context): NotificationManager? =
        ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun hasSecureSettings(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun systemInt(ctx: Context, key: String): Int? =
        runCatching { Settings.System.getInt(ctx.contentResolver, key) }.getOrNull()

    private fun globalInt(ctx: Context, key: String): Int? =
        runCatching { Settings.Global.getInt(ctx.contentResolver, key) }.getOrNull()

    /**
     * Carrier data, read two ways because neither alone is reliable across the
     * supported range: [TelephonyManager.isDataEnabled] is the API answer but
     * throws on some builds and needs a permission the user can refuse, and
     * `mobile_data` is the Settings key the system itself keeps. Whichever
     * answers first is a real read; both silent ⇒ null, which the row draws as
     * "—" rather than guessing "off".
     */
    private fun mobileDataEnabled(ctx: Context): Boolean? {
        val tm = ctx.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        runCatching { tm?.isDataEnabled }.getOrNull()?.let { return it }
        return globalInt(ctx, "mobile_data")?.let { it == 1 }
    }

    /** #1515 a missing grant is fixed in Configs > Perms: open the page on that row (never a settings intent here). */
    private fun perms(ctx: Context, feature: String, id: String) {
        runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(ctx, feature, listOf(id)) }
    }

    private fun open(ctx: Context, action: String, data: android.net.Uri? = null) {
        runCatching {
            ctx.startActivity(Intent(action).apply {
                if (data != null) setData(data)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    /** Poll [check] until it holds or [timeoutMs] runs out. For the writes
     *  whose effect lands on another thread; callers are already off the main
     *  one, so the wait is invisible. */
    private fun awaitTrue(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() >= deadline) return false
            runCatching { Thread.sleep(SETTLE_POLL_MS) }.getOrElse { return check() }
        }
        return true
    }

    private const val SERVICE_SETTLE_MS = 1500L
    private const val SETTLE_POLL_MS = 50L

    private fun fmt(state: Boolean?): String = when (state) {
        true -> "on"
        false -> "off"
        null -> "unknown"
    }

    // ── The declaration this file implements ─────────────────────────────

    /**
     * @param icon a drawable name, resolved by [Sections.iconResFor] at render
     *   time. Presentation, so it lives in build.json with the label — but it
     *   is NOT optional there: that resolver falls back to a generic tile for
     *   a name it cannot find, so an undeclared or misspelled icon would ship
     *   as a row that looks deliberate and means nothing. The repo tester is
     *   what refuses it; see test-configs-panel-control.sh.
     */
    /**
     * @param flags every boolean the declaration put on this control, by name.
     *   A MAP rather than a field per flag on purpose: a derived group names
     *   the flag it gathers (`derive`), so adding "battery_hungry": true to a
     *   control is the whole of adding it to Battery Hungers. A named field
     *   here would make that a Kotlin edit, and a `when (group.id)` in the
     *   renderer would make it two.
     */
    data class Row(
        val id: String,
        val label: String,
        val subtitle: String,
        val icon: String,
        val flags: Map<String, Boolean> = emptyMap(),
    )

    /**
     * @param labelRes name of a string resource for [label], resolved by the
     *   fragment at render time. The raw [label] stays as the English fallback
     *   and as documentation of what the key says; a group heading that only
     *   existed in build.json could never reach the owner in Spanish.
     * @param derived true ⇒ this group owns no declaration of its own; its
     *   rows are the SAME [Row] values the functional groups above hold,
     *   gathered by a flag. Two tiles, one id, one [Control] behind it.
     */
    data class Group(
        val id: String,
        val label: String,
        val labelRes: String,
        val subtitle: String,
        val subtitleRes: String,
        val rows: List<Row>,
        val derived: Boolean = false,
        /**
         * A surface this group hands off to for the question its TILES cannot
         * answer, or "" for the groups that have no such question.
         *
         * Battery Hungers is the one that does. Its tiles are an editorial
         * judgement about mechanism — which controls are known to cost power —
         * and no tile on this page is or ever will be a measured per-app
         * figure, because the tiles are controls and the question is about
         * apps. The measured answer lives in libs:battery and is reached
         * through here rather than copied to here.
         */
        val details: String = "",
        val detailsRes: String = "",
    )

    /**
     * `build.json::ui.control_panel`, baked into BuildConfig at build time.
     *
     * Rows whose id has no [Control] behind it are dropped HERE, so the
     * fragment never has to ask whether something is renderable — an id nobody
     * implements is not a control, and a group left with none of them is not a
     * group.
     */
    val groups: List<Group> by lazy {
        val root = declaration
        val arr = root.optJSONArray("groups")

        // PASS ONE: the groups that DECLARE their controls. Nothing derived can
        // be resolved before these exist, because a derived group gathers the
        // very Row objects they produced — not copies of them, and not a second
        // parse of the same JSON, either of which would be the duplicate state
        // this design exists to avoid.
        val byIndex = arrayOfNulls<Group>(arr?.length() ?: 0)
        for (i in 0 until (arr?.length() ?: 0)) {
            val g = arr!!.getJSONObject(i)
            if (g.optString("derive").isNotBlank()) continue
            val ca = g.optJSONArray("controls")
            val rows = (0 until (ca?.length() ?: 0)).mapNotNull { j ->
                val o = ca!!.getJSONObject(j)
                val id = o.optString("id")
                if (id !in byId) null
                else Row(
                    id, o.optString("label", id), o.optString("subtitle", ""),
                    o.optString("icon", ""),
                    // Every boolean the declaration carried, whatever it is
                    // called. Reading only the flags this file knows about
                    // would make a new derived view a Kotlin edit again.
                    o.keys().asSequence()
                        .filter { o.opt(it) is Boolean }
                        .associateWith { o.optBoolean(it) },
                )
            }
            if (rows.isNotEmpty()) byIndex[i] = group(g, rows, derived = false)
        }

        // PASS TWO: the derived views, in their declared position. A group
        // whose flag gathers nothing is dropped exactly like an empty declared
        // one — an empty heading says a category exists and is unreachable.
        for (i in 0 until (arr?.length() ?: 0)) {
            val g = arr!!.getJSONObject(i)
            val flag = g.optString("derive")
            if (flag.isBlank()) continue
            val rows = byIndex.filterNotNull().filter { !it.derived }
                .flatMap { it.rows }.filter { it.flags[flag] == true }
            if (rows.isNotEmpty()) byIndex[i] = group(g, rows, derived = true)
        }

        byIndex.filterNotNull()
    }

    private fun group(g: JSONObject, rows: List<Row>, derived: Boolean) = Group(
        id = g.optString("id"),
        label = g.optString("label", g.optString("id")),
        labelRes = g.optString("label_res"),
        subtitle = g.optString("subtitle", ""),
        subtitleRes = g.optString("subtitle_res"),
        rows = rows,
        derived = derived,
        details = g.optString("details"),
        detailsRes = g.optString("details_res"),
    )

    /** A boolean the declaration carries on control [id] — e.g. a switch's
     *  shipped default — false when absent. */
    fun declaredFlag(id: String, flag: String): Boolean =
        groups.asSequence().flatMap { it.rows }.firstOrNull { it.id == id }?.flags?.get(flag) == true

    /** Tiles across the grid, declared. Presentation, so it travels with the
     *  labels rather than being a constant the owner cannot reach. */
    val columns: Int by lazy { declaration.optInt("columns", DEFAULT_COLUMNS).coerceIn(2, 6) }

    private const val DEFAULT_COLUMNS = 4

    private val declaration: JSONObject by lazy {
        runCatching {
            JSONObject(String(Base64.decode(BuildConfig.UI_CONTROL_PANEL_B64, Base64.NO_WRAP)))
        }.getOrDefault(JSONObject())
    }
}
