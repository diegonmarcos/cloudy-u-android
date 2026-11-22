package com.diegonmarcos.cloudnav.tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** WorkManager periodic job (15 min is Android's floor): no foreground service, no wake lock held between runs. */
class EngineWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString(WakeSources.KEY_KIND)?.let { n -> WakeKind.values().firstOrNull { it.name == n } } ?: WakeKind.TICK
        runCatching { EngineRunner.tick(applicationContext, wake = kind) }
        return Result.success()
    }
}

object EngineScheduler {
    private const val NAME = "tracker-engine"

    /** Starts / stops the periodic job to match [cfg]; safe to call as often as you like. */
    fun apply(ctx: Context, cfg: EngineConfig = EnginePrefs(ctx).config) {
        val wm = WorkManager.getInstance(ctx)
        if (!cfg.enabled) { wm.cancelUniqueWork(NAME); WakeSources.disarm(ctx); return }
        // wake sources first; the periodic tick is only the fallback, relaxed while at least one source is armed
        WakeSources.arm(ctx)
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<EngineWorker>(WakePolicy.tickMinutes(WakeSources.armed), TimeUnit.MINUTES).build())
    }
}

object EngineRunner {
    private val directExecutor = Executor { it.run() }

    /** One engine pass: sample the step counter + heading, then do whatever [TriggerPlanner] says is due. */
    suspend fun tick(ctx: Context, now: Long = System.currentTimeMillis(), wake: WakeKind = WakeKind.TICK) {
        val prefs = EnginePrefs(ctx); val cfg = prefs.config
        if (!cfg.enabled) return
        val db = EngineDb.get(ctx)
        var st = prefs.state

        val counter = readStepCounter(ctx)
        var delta = 0L
        if (counter != null) {
            val last = prefs.lastCounter
            delta = if (last < 0) 0 else if (counter >= last) counter - last else counter  // reboot resets the counter
            prefs.lastCounter = counter
            val heading = if (delta > 0) readHeading(ctx, db.lastMeasuredFix()?.first) else null
            db.addSample(now, delta, heading)
            prefs.lastSampleMs = now
        }
        st = st.copy(stepsSinceAccurate = st.stepsSinceAccurate + delta, stepsSinceMotion = st.stepsSinceMotion + delta)

        // moving = the step cadence, or a wake source (activity, significant motion, geofence exit) that has not lapsed; STILL clears it
        val moving = WakePolicy.effectiveMoving(BatteryPolicy.isMoving(delta), prefs.wake, now)
        if (wake == WakeKind.ACTIVITY_STILL && cfg.useCell) {
            // a new STAY begins: one cheap network fix (never GPS) so the geofence is centred on it
            locate(ctx, Provider.NETWORK)?.let { db.addFix(PlaceFix(now, it.latitude, it.longitude, false, it.accuracy.toDouble()), "net") }
        }
        for (a in TriggerPlanner.due(cfg, st, now, moving)) when (a) {
            Action.ACCURATE_FIX -> {
                val want = BatteryPolicy.accurateProvider(cfg, moving)   // still: network / cell only, never GPS
                if (want == null) { st = st.copy(lastAccurateMs = now); continue }
                                // GPS can't fix indoors: fall back to cell towers rather than skip the fix (only when that source is on)
                var used = want
                var loc = locate(ctx, want)
                if (loc == null && want == Provider.GPS && cfg.useCell) { loc = locate(ctx, Provider.NETWORK); used = Provider.NETWORK }
                if (loc != null) {
                    val fix = PlaceFix(now, loc.latitude, loc.longitude, false, loc.accuracy.toDouble())
                    val prev = db.lastMeasuredFix()
                    if (prev != null && prev.second <= 150 && fix.accuracyM <= 150 && now - prev.first.ts < 12 * 3_600_000L) {
                        val m = DeadReckoning.measuredStepM(LatLon(prev.first.lat, prev.first.lon), LatLon(fix.lat, fix.lon), st.stepsSinceAccurate)
                        prefs.stepLengthM = DeadReckoning.calibrate(prefs.stepLengthM, m)
                    }
                    db.addFix(fix, if (used == Provider.GPS) "gps" else "net")
                    st = st.copy(lastAccurateMs = now, lastCellMs = now, stepsSinceAccurate = 0, stepsSinceMotion = 0)
                }
            }
            Action.CELL_UPDATE -> {
                val loc = locate(ctx, Provider.NETWORK)
                if (loc != null) db.addFix(PlaceFix(now, loc.latitude, loc.longitude, false, loc.accuracy.toDouble()), "cell")
                st = st.copy(lastCellMs = now)  // a miss waits for the next hour, not every 15 min
            }
            Action.MOTION_ESTIMATE -> {
                val anchor = db.lastMeasuredFix()
                if (anchor != null) {
                    val legs = db.legsSince(anchor.first.ts)
                    val e = DeadReckoning.predict(LatLon(anchor.first.lat, anchor.first.lon), anchor.second, legs, prefs.stepLengthM)
                    db.addFix(PlaceFix(now, e.lat, e.lon, true, e.radiusM), "predicted")
                    st = st.copy(stepsSinceMotion = 0)
                }
            }
        }
        prefs.state = st
        runCatching { WakeSources.arm(ctx) }   // re-arm the one-shot significant-motion sensor and the geofence at the current STAY
        runCatching { StepsRepository(ctx).ingestHealth() }   // #1851 Health Connect steps + sleep into the one store
        runCatching { NavTimelineExtra.tick(ctx, now, moving = moving) }   // #1114: the timeline store follows every pass
    }

    private fun granted(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private suspend fun locate(ctx: Context, provider: Provider): Location? {
        val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        val name = when (provider) {
            Provider.GPS -> if (fine) LocationManager.GPS_PROVIDER else return null
            Provider.NETWORK -> if (fine || coarse) LocationManager.NETWORK_PROVIDER else return null
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(name)) return null
        return withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                val sig = CancellationSignal()
                cont.invokeOnCancellation { sig.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(lm, name, sig, directExecutor) { l -> if (cont.isActive) cont.resume(l) }
                } catch (_: SecurityException) { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    /** Steps since boot, or null with no step-counter sensor / ACTIVITY_RECOGNITION. */
    private suspend fun readStepCounter(ctx: Context): Long? {
        if (android.os.Build.VERSION.SDK_INT >= 29 && !granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)) return null
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return firstEvent(sm, s)?.let { it.values[0].toLong() }
    }

    private suspend fun readHeading(ctx: Context, near: PlaceFix?): Double? {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return null
        val ev = firstEvent(sm, s) ?: return null
        val r = FloatArray(9); val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(r, ev.values)
        SensorManager.getOrientation(r, o)
        var deg = Math.toDegrees(o[0].toDouble())
        if (near != null) deg += GeomagneticField(near.lat.toFloat(), near.lon.toFloat(), 0f, System.currentTimeMillis()).declination  // magnetic -> true north
        return (deg % 360 + 360) % 360
    }

    private class Snap(val values: FloatArray)

    private suspend fun firstEvent(sm: SensorManager, s: Sensor): Snap? = withTimeoutOrNull(4_000L) {
        suspendCancellableCoroutine<Snap?> { cont ->
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) { sm.unregisterListener(this); if (cont.isActive) cont.resume(Snap(e.values.clone())) }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            cont.invokeOnCancellation { sm.unregisterListener(l) }
            if (!sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)) cont.resume(null)
        }
    }
}
