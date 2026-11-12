package com.diegonmarcos.superapp.launcher

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.launcher.PermAccess.Sensor
import com.diegonmarcos.superapp.launcher.PermAccess.SensorState

/**
 * #1673 The live Mic / Camera / Location states of the launcher's Permission Access Status row. Callbacks only, never a timer:
 * [start] registers them when the launcher becomes visible (the strip's ON_START) and [stop] releases every one of them when it
 * is hidden (ON_STOP / leaving the composition). Nothing here is a location-class call (test-location-use-scoped.sh).
 *
 *  Mic       AudioManager.registerAudioRecordingCallback: the active recordings of EVERY app (public API, no permission).
 *  Camera    CameraManager.registerAvailabilityCallback: a camera another app (or this one) opened is "unavailable".
 *  Location  AppOpsManager.startWatchingActive(FINE / COARSE_LOCATION) sees other apps only with WATCH_APPOPS, a permission
 *            Android gives the system alone. Without it the state is UNKNOWN, except a one-shot app-ops read through the shell
 *            broker when the launcher comes to the front (no polling) that found a location op running lights it.
 *  When a watcher with WATCH_APPOPS is allowed, the same op watch names the app using Mic / Camera / Location.
 */
class PermAccessWatch(private val context: Context, private val brokerRead: ((String) -> String?)? = null) {

    var mic by mutableStateOf(SensorState.UNKNOWN); private set
    var camera by mutableStateOf(SensorState.UNKNOWN); private set
    var location by mutableStateOf(SensorState.UNKNOWN); private set
    /** The app(s) the op watch saw using a sensor, when it can see them (WATCH_APPOPS); empty otherwise. */
    var users by mutableStateOf<Map<Sensor, Set<String>>>(emptyMap()); private set

    /** True between [start] and [stop]: every callback below is registered only then. */
    var running = false
        private set
    /** The number of platform callbacks currently registered (0 after [stop]): what the lifecycle test pins. */
    var registered = 0
        private set

    private val main = Handler(Looper.getMainLooper())
    private var micWatching = false
    private var micActive = false
    private var camWatching = false
    private val camOpen = HashSet<String>()
    private var locWatching = false
    private var opWatching = false
    private var locSeen = false
    private val activeOps = HashMap<String, MutableSet<String>>() // op -> packages
    @Volatile private var generation = 0

    fun state(s: Sensor): SensorState = when (s) { Sensor.MIC -> mic; Sensor.CAMERA -> camera; Sensor.LOCATION -> location }

    // minSdk 26: the recording callback (API 24) always exists.
    private val recCb: AudioManager.AudioRecordingCallback =
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                micActive = !configs.isNullOrEmpty(); publish()
            }
        }

    private val camCb = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) { camOpen.remove(cameraId); publish() }
        override fun onCameraUnavailable(cameraId: String) { camOpen.add(cameraId); publish() }
    }

    private val opCb: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) AppOpsManager.OnOpActiveChangedListener { op, _, pkg, active ->
        main.post {
            val set = activeOps.getOrPut(op) { HashSet() }
            if (active) set.add(pkg) else set.remove(pkg)
            publish()
        }
    } else null

    /** WATCH_APPOPS lets the op watch see every app; without it Android shows this app its own ops only. */
    private fun watchesAllApps(): Boolean =
        runCatching { context.checkSelfPermission(WATCH_APPOPS) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun start() {
        if (running) return
        running = true
        val gen = ++generation
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        micWatching = false
        if (am != null) runCatching {
            am.registerAudioRecordingCallback(recCb, main); registered++; micWatching = true
            micActive = am.activeRecordingConfigurations.isNotEmpty()
        }
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        camWatching = false
        camOpen.clear()
        // Registering hands back each camera's current availability through the callback.
        if (cm != null) runCatching { cm.registerAvailabilityCallback(camCb, main); registered++; camWatching = true }
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        locWatching = false
        opWatching = false
        activeOps.clear()
        if (ops != null && opCb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
            ops.startWatchingActive(WATCHED_OPS, context.mainExecutor, opCb as AppOpsManager.OnOpActiveChangedListener)
            registered++; opWatching = true
            locWatching = watchesAllApps()
        }
        locSeen = false
        publish()
        // The one-shot seed of Location (and the app names) when no live source can see other apps: one broker read on
        // coming to the front, never repeated while visible.
        val read = brokerRead
        if (!locWatching && read != null) Thread({
            val text = runCatching { read(PermAccess.dumpsysCommand(PermAccess.LOCATION_OPS)) }.getOrNull()
            val running = PermAccess.locationRunning(PermAccess.parseDumpsys(text, System.currentTimeMillis()))
            main.post { if (this.running && gen == generation) { locSeen = running; publish() } }
        }, "perm-access-seed").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running) return
        running = false
        generation++
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null && micWatching) runCatching { am.unregisterAudioRecordingCallback(recCb); registered-- }
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm != null && camWatching) runCatching { cm.unregisterAvailabilityCallback(camCb); registered-- }
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        if (ops != null && opCb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && opWatching)
            runCatching { ops.stopWatchingActive(opCb as AppOpsManager.OnOpActiveChangedListener); registered-- }
        registered = registered.coerceAtLeast(0)
        micWatching = false; camWatching = false; locWatching = false; opWatching = false
        // Hidden: nothing is watched, so nothing is claimed.
        mic = SensorState.UNKNOWN; camera = SensorState.UNKNOWN; location = SensorState.UNKNOWN
    }

    private fun publish() {
        val pk = { op: String -> activeOps[op].orEmpty() }
        val micUsers = pk(AppOpsManager.OPSTR_RECORD_AUDIO)
        val camUsers = pk(AppOpsManager.OPSTR_CAMERA)
        val locUsers = pk(AppOpsManager.OPSTR_FINE_LOCATION) + pk(AppOpsManager.OPSTR_COARSE_LOCATION)
        mic = PermAccess.state(micWatching, micActive || micUsers.isNotEmpty())
        camera = PermAccess.state(camWatching, camOpen.isNotEmpty() || camUsers.isNotEmpty())
        location = PermAccess.state(locWatching, locUsers.isNotEmpty() || locSeen)
        users = mapOf(Sensor.MIC to micUsers, Sensor.CAMERA to camUsers, Sensor.LOCATION to locUsers).filterValues { it.isNotEmpty() }
    }

    companion object {
        const val WATCH_APPOPS = "android.permission.WATCH_APPOPS"
        val WATCHED_OPS = arrayOf(
            AppOpsManager.OPSTR_RECORD_AUDIO, AppOpsManager.OPSTR_CAMERA,
            AppOpsManager.OPSTR_FINE_LOCATION, AppOpsManager.OPSTR_COARSE_LOCATION,
        )
    }
}
