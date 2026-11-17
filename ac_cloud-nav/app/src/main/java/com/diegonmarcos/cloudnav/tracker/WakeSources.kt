package com.diegonmarcos.cloudnav.tracker

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * The energy-efficient wake sources (#1145): Activity Recognition transitions (Play Services, optional: a phone without them
 * just never gets the events), the significant-motion trigger sensor and a geofence on exit from the current STAY. Each one
 * records what it saw in [WakeState] ([WakePolicy]) and starts one engine pass at once; the WorkManager tick stays as the fallback.
 */
object WakeSources {
    const val ACTION_ACTIVITY = "com.diegonmarcos.cloudnav.tracker.WAKE_ACTIVITY"
    const val ACTION_FENCE = "com.diegonmarcos.cloudnav.tracker.WAKE_FENCE"
    const val KEY_KIND = "wake_kind"
    private const val FENCE_ID = "current-stay"
    private const val WORK = "tracker-engine-wake"

    /** True once at least one source is armed, so the periodic tick can be relaxed. */
    @Volatile var armed = false
        private set
    private var motionListener: TriggerEventListener? = null

    /** Arms what is missing; safe to call as often as you like (app start, every pass, a wake). */
    fun arm(ctx: Context) {
        val app = ctx.applicationContext
        var any = false
        any = armActivity(app) or any
        any = armMotion(app) or any
        any = armFence(app) or any
        armed = any
    }

    fun disarm(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { ActivityRecognition.getClient(app).removeActivityTransitionUpdates(pending(app, ACTION_ACTIVITY)) }
        runCatching { LocationServices.getGeofencingClient(app).removeGeofences(pending(app, ACTION_FENCE)) }
        runCatching { motionListener?.let { (app.getSystemService(Context.SENSOR_SERVICE) as SensorManager).run { cancelTriggerSensor(it, getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)) } } }
        motionListener = null
        val p = EnginePrefs(app); p.wake = p.wake.copy(fenceRadiusM = 0.0)
        armed = false
    }

    private fun pending(ctx: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(ctx, action.hashCode(), Intent(ctx, WakeReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    private fun granted(ctx: Context, perm: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun armActivity(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 29 && !granted(ctx, android.Manifest.permission.ACTIVITY_RECOGNITION)) return false
        val types = listOf(DetectedActivity.STILL, DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE)
        val req = ActivityTransitionRequest(types.map {
            ActivityTransition.Builder().setActivityType(it).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
        })
        return runCatching { ActivityRecognition.getClient(ctx).requestActivityTransitionUpdates(req, pending(ctx, ACTION_ACTIVITY)); true }.getOrDefault(false)
    }

    private fun armMotion(ctx: Context): Boolean {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return false
        if (motionListener != null) return true
        val l = object : TriggerEventListener() {
            override fun onTrigger(e: TriggerEvent?) {
                motionListener = null   // one-shot: re-armed by the pass it starts
                handle(ctx, WakeKind.SIGNIFICANT_MOTION)
            }
        }
        return if (sm.requestTriggerSensor(l, s)) { motionListener = l; true } else false
    }

    /** A geofence around the current STAY's last measured fix; re-armed when that fix moves. Fires on exit only. */
    @SuppressLint("MissingPermission")
    private fun armFence(ctx: Context): Boolean {
        if (!granted(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)) return false
        val prefs = EnginePrefs(ctx); val w = prefs.wake
        val fix = EngineDb.get(ctx).lastMeasuredFix() ?: return w.fenced
        if (!WakePolicy.needsFence(w, fix.first.lat, fix.first.lon)) return true
        val r = WakePolicy.fenceRadiusM(fix.second)
        val fence = Geofence.Builder().setRequestId(FENCE_ID).setCircularRegion(fix.first.lat, fix.first.lon, r.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE).setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT).build()
        val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(fence).build()
        return runCatching {
            LocationServices.getGeofencingClient(ctx).addGeofences(req, pending(ctx, ACTION_FENCE))
            prefs.wake = prefs.wake.copy(fenceLat = fix.first.lat, fenceLon = fix.first.lon, fenceRadiusM = r)
            true
        }.getOrDefault(false)
    }

    /** Records [kind] and starts one engine pass now (a unique one-time job, so a burst of events runs one pass). */
    fun handle(ctx: Context, kind: WakeKind, now: Long = System.currentTimeMillis()) {
        val app = ctx.applicationContext
        val prefs = EnginePrefs(app)
        // #1770 the Maps tracker (user's Start) follows the same wake sources: its gate decides what to subscribe to
        gateSignalOf(kind)?.let { sig -> runCatching { com.diegonmarcos.cloudnav.maps.LocationTrackerService.signal(app, sig) } }
        if (!prefs.config.enabled) { runCatching { arm(app) }; return }   // engine off: still re-arm the one-shot sources for the tracker
        prefs.wake = WakePolicy.apply(prefs.wake, kind, now)
        // a geofence exit ends that fence: the next STILL arms a new one at the new STAY
        if (kind == WakeKind.GEOFENCE_EXIT) prefs.wake = prefs.wake.copy(fenceRadiusM = 0.0)
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<EngineWorker>().setInputData(Data.Builder().putString(KEY_KIND, kind.name).build()).build())
    }

    /** What the Maps tracker's gate calls the same wake (null for the periodic tick, which is not an event). */
    fun gateSignalOf(kind: WakeKind): com.diegonmarcos.cloudnav.maps.GateSignal? = when (kind) {
        WakeKind.ACTIVITY_STILL -> com.diegonmarcos.cloudnav.maps.GateSignal.ACTIVITY_STILL
        WakeKind.ACTIVITY_MOVING -> com.diegonmarcos.cloudnav.maps.GateSignal.ACTIVITY_MOVING
        WakeKind.GEOFENCE_EXIT -> com.diegonmarcos.cloudnav.maps.GateSignal.GEOFENCE_EXIT
        WakeKind.SIGNIFICANT_MOTION -> com.diegonmarcos.cloudnav.maps.GateSignal.SIGNIFICANT_MOTION
        WakeKind.TICK -> null
    }

    /** The wake kinds an Activity Recognition result carries (the latest transition decides). */
    fun kindOf(activityType: Int): WakeKind = if (activityType == DetectedActivity.STILL) WakeKind.ACTIVITY_STILL else WakeKind.ACTIVITY_MOVING
}

/** Receives the Activity Recognition and geofence PendingIntents. Not exported: only our own PendingIntents reach it. */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            WakeSources.ACTION_ACTIVITY -> {
                if (!ActivityTransitionResult.hasResult(intent)) return
                val last = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull() ?: return
                WakeSources.handle(ctx, WakeSources.kindOf(last.activityType))
            }
            WakeSources.ACTION_FENCE -> {
                val ev = GeofencingEvent.fromIntent(intent) ?: return
                if (!ev.hasError() && ev.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) WakeSources.handle(ctx, WakeKind.GEOFENCE_EXIT)
            }
        }
    }
}
