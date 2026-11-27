package com.diegonmarcos.cloudnav.maps

import com.diegonmarcos.superapp.core.FleetNotify
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Foreground service that drives the GPS tracker. Lifecycle:
 *   • startService(Intent) — sets up the persistent notification,
 *     reads tuning prefs, subscribes the FusedLocationProvider
 *     callback. Idempotent: re-entry just refreshes the callback.
 *   • Each fix lands in [onLocationResult]: write to SQLite, update
 *     [MapsTrackerPrefs.lastFix*], drive the stop-detector.
 *   • stopService → tears down the callback + foreground notification.
 *
 * #1770 EVENT-DRIVEN, no polling loop: the platform subscription follows [TrackGate]. STILL = the PASSIVE listener plus a 12 min batched sample (#1851); the old note continues:
 * fixes) plus, since #1851, one low-rate batched sample every 10-15 min (off under battery saver) so a phone at rest still records; MOVING (Activity Recognition, geofence exit, significant motion or the user's Start,
 * signalled by Cloud Nav's WakeSources through [signal]) = ONE batched BALANCED_POWER fused request (interval 60 s, maxUpdateDelay 5 min,
 * 25 m minimum displacement) until the hold lapses or activity STILL arrives. Fixes pass the gate's accuracy / age / displacement filter
 * before they are stored. A place visit (Stop) is emitted when the phone has been still for the dwell time, closed when it leaves.
 *
 * Battery (older note): FusedLocationProvider does the heavy lifting (sensor
 * fusion + Doze-respect). We just feed it a `LocationRequest` built
 * from [MapsTrackingPrefs.intervalMovingMs]. The "tighten interval
 * while stopped" optimisation is handled by the provider itself
 * (Priority + minUpdateInterval) — we don't poll twice.
 *
 * Stop detection: simple ring buffer of the last N fixes; if their
 * centroid stays within `stopsRadiusM` for at least `stopsDwellMin`
 * minutes, emit one Stop row and pause the ring until the user moves
 * out of the radius. Cheap on-device; never blocks the main thread.
 */
class LocationTrackerService : Service() {

    private val TAG = "LocationTrackerService"

    private lateinit var trackerPrefs: MapsTrackerPrefs
    private lateinit var trackingPrefs: MapsTrackingPrefs
    private lateinit var db: MapsDb

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var callback: LocationCallback? = null

    // ── #1770 the gate: platform subscription follows TrackGate, nothing polls ──
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gate = GateState()
    private var passiveListener: LocationListenerCompat? = null
    private var fusedParams: FusedParams? = null
    private val expireRun = Runnable { val now = System.currentTimeMillis(); setGate(TrackGate.expire(gate, now), now) }
    private val stopRun = Runnable { emitStopAtAnchor() }

    // ── Stop-detector state — see detectStop() ─────────────────────
    private val recentBuf = ArrayDeque<MapsDb.PointRow>()
    private var activeStop: MapsDb.StopRow? = null
    /** Row id of the currently-active Stop in MapsDb. Captured from
     *  insertStop's return value on the MOVING→STOPPED transition so
     *  the matching STOPPED→MOVING patches ended_at on the same row
     *  without re-querying. -1 means no active stop. */
    private var activeStopId: Long = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        trackerPrefs  = MapsTrackerPrefs(this)
        trackingPrefs = MapsTrackingPrefs(this)
        db = MapsDb.get(this)
        ensureChannel()
        rehydrateDetectorState()
    }

    /** Repopulate the in-memory detector state from on-disk DB + prefs
     *  so a service revival (after Doze / OOM / low-memory kill) does
     *  NOT pay the full cold warm-up penalty before the first Stop can
     *  emit. Two restorations:
     *
     *  1) Ring buffer pre-fill — pulls the most recent points from the
     *     DB up to bufSize, filters to fixes within the last 2× dwell
     *     window (older points are from past sessions, not current
     *     dwelling) AND accuracy ≤ 100m (same gate `detectStop` uses on
     *     fresh fixes). Result: if the user was already stationary
     *     when the service died and is still stationary on revival,
     *     the very NEXT fix can clinch the Stop instead of waiting
     *     bufSize × intervalMs for the buffer to refill.
     *
     *  2) Active-stop restore — if `trackerPrefs.activeStopId` points
     *     at a Stop row that still has `ended_at = NULL`, restore it
     *     to the in-memory `activeStop` so the next centroid-exit
     *     correctly patches ended_at on that row instead of leaving
     *     it dangling forever and starting a fresh Stop next door.
     *     If the stored id is stale (row missing, already ended), the
     *     pref is wiped so we don't keep retrying it.
     */
    private fun rehydrateDetectorState() {
        val intervalMs = trackingPrefs.intervalMovingMs.coerceAtLeast(1_000)
        val dwellMs    = trackingPrefs.stopsDwellMin * 60_000L
        val bufSize    = maxOf(3, (dwellMs / intervalMs).toInt() + 1)
        val now        = System.currentTimeMillis()
        val cutoff     = now - dwellMs * 2L
        // recentPoints returns oldest-first; just append in order.
        val historic = db.recentPoints(bufSize * 2)
            .filter { it.ts >= cutoff && (it.accuracy ?: Float.MAX_VALUE) <= 100f }
        val seed = if (historic.size > bufSize) historic.takeLast(bufSize) else historic
        recentBuf.clear()
        for (p in seed) recentBuf.addLast(p)
        Log.i(TAG, "Detector buffer rehydrated: ${recentBuf.size}/$bufSize from DB")

        val savedId = trackerPrefs.activeStopId
        if (savedId > 0) {
            val row = db.getStop(savedId)
            if (row != null && row.endedAt == null) {
                activeStopId = savedId
                activeStop = MapsDb.StopRow(
                    startedAt = row.startedAt,
                    endedAt   = row.endedAt,
                    lat       = row.lat,
                    lon       = row.lon,
                )
                Log.i(TAG, "Active stop restored id=$savedId")
            } else {
                trackerPrefs.activeStopId = -1L
                Log.i(TAG, "Stale active_stop_id=$savedId cleared (row missing or already ended)")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!MapsPermissions.canTrackerRun(this)) {
            Log.w(TAG, "Tracker started without required permissions — stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!trackerPrefs.enabled) {
            Log.i(TAG, "Tracker enabled=false — stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        // #1666 the tracker runs only from the user's Start: an intent without ACTION_USER_START (a revival, a stale
        // caller, a keep-alive) is refused and switches tracking off, so nothing lights the location dot on its own.
        if (!isUserStart(intent?.action)) {
            Log.i(TAG, "Tracker start without the user's Start (${intent?.action}) — refused, tracking off")
            trackerPrefs.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        val card = buildNotification(lastSubtitle)
        startForeground(NOTIF_ID, card)
        NavCard.publish(this, NOTIF_ID, card)   // #1965 the Background Apps group
        NavCard.postSummary(this)
        // #1944 a published navigation change refreshes the card (throttled in NavCardState); cleared again in onDestroy.
        NavCardState.onChange = { main.post { runCatching { FleetNotify.post(this, NOTIF_ID, buildNotification(lastSubtitle)) } } }
        instance = this
        gate = GateState(lastAccepted = db.recentPoints(1).lastOrNull()?.let { GateFix(it.lat, it.lon, it.accuracy, it.ts) })
        armWake?.invoke(applicationContext)
        val now = System.currentTimeMillis()
        setGate(TrackGate.signal(gate, GateSignal.USER_START, now), now)
        // #1666 START_NOT_STICKY: a killed tracker is NOT revived by Android (the old START_STICKY + watchdog revival kept a
        // location foreground service coming back in the background). The user presses Start again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        NavCardState.onChange = null
        NavCard.cancelSummary(this)   // #1944 the card (stopForeground) and its group summary go together
        main.removeCallbacks(expireRun); main.removeCallbacks(stopRun)
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
        passiveListener?.let { runCatching { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) } }
        passiveListener = null
    }

    /** The state machine moved: re-plan the subscription, keep the one expiry timer, close or schedule the place visit. */
    private fun setGate(next: GateState, now: Long) {
        val prev = gate
        gate = next
        applyPlan(TrackGate.plan(next, (getSystemService(POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode == true))
        main.removeCallbacks(expireRun)
        if (next.mode == GateMode.MOVING) {
            main.removeCallbacks(stopRun)
            main.postDelayed(expireRun, (next.untilMs - now).coerceAtLeast(1_000L))
        } else if (prev.mode == GateMode.MOVING) {
            // went still: a visit begins once the phone has stayed still for the dwell time (one one-shot timer, no loop)
            main.removeCallbacks(stopRun)
            main.postDelayed(stopRun, trackingPrefs.stopsDwellMin * 60_000L)
        }
    }

    /** A wake source (Cloud Nav's WakeSources) signalled [sig]. */
    private fun onSignal(sig: GateSignal) {
        val now = System.currentTimeMillis()
        setGate(TrackGate.signal(gate, sig, now), now)
    }

    /** Passive listener always (it costs nothing); the batched balanced-power fused request only while the plan has one. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun applyPlan(plan: GatePlan) {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) return
        if (plan.passive && passiveListener == null) {
            val l = LocationListenerCompat { loc -> offerFix(loc) }
            val req = LocationRequestCompat.Builder(TrackGate.PASSIVE_MIN_INTERVAL_MS).setMinUpdateDistanceMeters(TrackGate.PASSIVE_MIN_DISTANCE_M).build()
            com.diegonmarcos.superapp.perms.LocationUse.note(this, SITE, "LocationManager PASSIVE_PROVIDER (other apps' fixes, no request of ours)",
                com.diegonmarcos.superapp.perms.LocationUse.Trigger.TRACKER)
            val ok = runCatching {
                LocationManagerCompat.requestLocationUpdates(getSystemService(LOCATION_SERVICE) as LocationManager, LocationManager.PASSIVE_PROVIDER, req,
                    ContextCompat.getMainExecutor(this), l)
            }.isSuccess
            if (ok) passiveListener = l
        }
        val want = plan.fused
        if (want == fusedParams) return
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null; fusedParams = want
        if (want == null) return
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, want.intervalMs)
            .setMinUpdateIntervalMillis(want.minIntervalMs)
            .setMaxUpdateDelayMillis(want.maxUpdateDelayMs)
            .setMinUpdateDistanceMeters(want.minDistanceM)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) offerFix(loc)
            }
        }
        callback = cb
        com.diegonmarcos.superapp.perms.LocationUse.note(this, SITE, "FusedLocationProviderClient.requestLocationUpdates (BALANCED_POWER, batched: 60 s while moving, 12 min while still)",
            com.diegonmarcos.superapp.perms.LocationUse.Trigger.TRACKER)
        fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
    }

    /** One fix from either source goes through the gate; an accepted one is stored, may close the open visit and may wake the gate. */
    private fun offerFix(loc: Location) {
        val now = System.currentTimeMillis()
        val f = GateFix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, loc.time)
        val o = TrackGate.offer(gate, f, now)
        android.util.Log.d("TrackGate", "offer ${loc.provider} ${o.reason} acc=${f.accuracyM} mode=${gate.mode}")   // #1851 evidence for the emulator walk
        if (!o.accepted) return
        ingest(loc)
        val open = activeStop
        if (open != null && TrackGate.leftStop(open.lat, open.lon, f, trackingPrefs.stopsRadiusM.toDouble())) {
            if (activeStopId > 0) db.updateStopEndedAt(activeStopId, f.timeMs)
            activeStop = null; activeStopId = -1L; trackerPrefs.activeStopId = -1L
        }
        setGate(o.state, now)
    }

    /** Still for the whole dwell time: the place the phone stopped at becomes one Stop row (a place visit). */
    private fun emitStopAtAnchor() {
        val g = gate
        val a = g.anchor ?: return
        if (g.mode != GateMode.STILL || activeStop != null) return
        val stop = MapsDb.StopRow(startedAt = a.timeMs, endedAt = null, lat = a.lat, lon = a.lon, accuracy = a.accuracyM)
        activeStopId = db.insertStop(stop)
        activeStop = stop
        trackerPrefs.activeStopId = activeStopId
        StopsEnricher.kickAsync(applicationContext)
    }

    /** Persist one GPS fix + update tracker prefs + run stop-detect. */
    private fun ingest(loc: Location) {
        val row = MapsDb.PointRow(
            ts        = loc.time,
            lat       = loc.latitude,
            lon       = loc.longitude,
            accuracy  = if (loc.hasAccuracy()) loc.accuracy else null,
            speed     = if (loc.hasSpeed()) loc.speed else null,
            bearing   = if (loc.hasBearing()) loc.bearing else null,
            altitude  = if (loc.hasAltitude()) loc.altitude else null,
        )
        db.insertPoint(row)
        db.appendFix(Fix(row.ts, row.lat, row.lon, row.accuracy?.toDouble(), row.speed?.toDouble(), if (loc.provider == LocationManager.PASSIVE_PROVIDER) "passive" else "fused"))   // #1851 the shared fix log
        trackerPrefs.lastFixLat = row.lat
        trackerPrefs.lastFixLon = row.lon
        trackerPrefs.lastFixTs  = row.ts
        detectStop(row)
        // Refresh the persistent notification's subtitle so the user
        // sees the live point count + accuracy as proof of life.
        lastSubtitle = "Points: ${db.pointCount()} · acc: ${row.accuracy?.toInt() ?: "?"}m"
        FleetNotify.post(this, NOTIF_ID, buildNotification(lastSubtitle))   // #1965 also refreshes the Background Apps card
    }

    /** Ring-buffer Stop detector. Holds the last [bufSize] points;
     *  if the centroid of those points sits within `stopsRadiusM` and
     *  the buffer spans at least `stopsDwellMin` minutes, emit a Stop
     *  row and remember it as `activeStop`. The Stop's end-time is
     *  patched in when the user leaves the radius.
     *
     *  Telemetry: every tick writes a snapshot of (bufFill, bufSize,
     *  spanMin, p90DistM, radiusEffM, avgAccM) + a decision string to
     *  [MapsTrackerPrefs] so the MyTrips dashboard can show WHY a Stop
     *  has / hasn't emitted instead of treating the detector as a
     *  black box. */
    private fun detectStop(row: MapsDb.PointRow) {
        // Ring-buffer size derived from the user-tuned dwell + interval
        // so the buffer ALWAYS spans at least `stopsDwellMin` minutes of
        // fixes. ceil(dwellMs / intervalMs) + 1 (+1 fix of margin to
        // not race the exact boundary). Floored at 3 so a degenerate
        // <30s dwell still gets a meaningful centroid.
        val intervalMs = trackingPrefs.intervalMovingMs.coerceAtLeast(1_000)
        val dwellMs    = trackingPrefs.stopsDwellMin * 60_000L
        val bufSize    = maxOf(3, (dwellMs / intervalMs).toInt() + 1)

        fun writeTelemetry(decision: String, p90: Double, radEff: Double, span: Double, avgA: Double) {
            trackerPrefs.lastDecision     = decision
            trackerPrefs.lastDecisionTs   = row.ts
            trackerPrefs.lastBufFill      = recentBuf.size
            trackerPrefs.lastBufSize      = bufSize
            trackerPrefs.lastSpanMin      = span.toFloat()
            trackerPrefs.lastP90DistM     = p90.toFloat()
            trackerPrefs.lastRadiusEffM   = radEff.toFloat()
            trackerPrefs.lastAvgAccM      = avgA.toFloat()
        }

        // Skip clearly-garbage fixes (accuracy > 100m or null). They'd
        // dominate the centroid + maxDist math and push every dwell
        // window over the radius even when the user is stationary.
        // The point is still INSERTED in DB (caller already did) so the
        // map shows it; we just don't let it pollute Stop detection.
        if ((row.accuracy ?: Float.MAX_VALUE) > 100f) {
            writeTelemetry("skipped_bad_acc", 0.0, 0.0, 0.0, row.accuracy?.toDouble() ?: 0.0)
            return
        }

        recentBuf.addLast(row)
        while (recentBuf.size > bufSize) recentBuf.removeFirst()

        // Centroid + 90th-percentile distance from centroid.
        // p90 (not max) so a single noisy outlier doesn't blow the
        // check — common under satellite-geometry drift at 5-min
        // windows. radius_eff = stops_radius_m + avgAcc so a tight
        // 100m centroid radius isn't rejected by ±40m reported noise.
        val cLat = recentBuf.map { it.lat }.average()
        val cLon = recentBuf.map { it.lon }.average()
        val dists = recentBuf.map { haversine(it.lat, it.lon, cLat, cLon) }.sorted()
        val p90Idx = ((dists.size - 1) * 0.9).toInt()
        val p90Dist = if (dists.isEmpty()) 0.0 else dists[p90Idx]
        val avgAcc  = recentBuf.mapNotNull { it.accuracy?.toDouble() }
            .let { if (it.isEmpty()) 0.0 else it.average() }
        val spanMin = if (recentBuf.size < 2) 0.0
            else (recentBuf.last().ts - recentBuf.first().ts) / 60_000.0
        val radius  = trackingPrefs.stopsRadiusM.toDouble() + avgAcc
        val dwell   = trackingPrefs.stopsDwellMin.toDouble()

        if (recentBuf.size < bufSize) {
            writeTelemetry("buf_filling", p90Dist, radius, spanMin, avgAcc)
            return
        }

        val inside = p90Dist < radius
        val longEnough = spanMin >= dwell

        if (inside && longEnough && activeStop == null) {
            // Transition: MOVING → STOPPED. Emit one Stop row anchored
            // at the centroid + persist activeStopId so a service kill
            // mid-stop can still find the row to patch ended_at on
            // revival (see rehydrateDetectorState).
            val stop = MapsDb.StopRow(
                startedAt = recentBuf.first().ts,
                endedAt   = null,
                lat       = cLat,
                lon       = cLon,
                accuracy  = recentBuf.mapNotNull { it.accuracy }.average().toFloat(),
            )
            activeStopId = db.insertStop(stop)
            activeStop = stop
            trackerPrefs.activeStopId = activeStopId
            writeTelemetry("emit_stop", p90Dist, radius, spanMin, avgAcc)
            // Fire-and-forget reverse-geocode of the new Stop. The
            // enricher self-throttles (1 req/sec) and bails if a
            // worker is already running, so re-entry from rapid
            // Stop emissions is safe.
            StopsEnricher.kickAsync(applicationContext)
        } else if (!inside && activeStop != null) {
            // Transition: STOPPED → MOVING. Patch ended_at on the
            // active Stop row so downstream readers (Daily longest-
            // dwell algorithm + future dwell-time math) see real
            // bounds instead of treating the stop as still-running
            // forever.
            if (activeStopId > 0) db.updateStopEndedAt(activeStopId, row.ts)
            activeStop = null
            activeStopId = -1L
            trackerPrefs.activeStopId = -1L
            writeTelemetry("exit_stop", p90Dist, radius, spanMin, avgAcc)
        } else if (activeStop != null) {
            writeTelemetry("active_stop_held", p90Dist, radius, spanMin, avgAcc)
        } else if (!inside) {
            writeTelemetry("dist_too_far", p90Dist, radius, spanMin, avgAcc)
        } else {
            writeTelemetry("span_too_short", p90Dist, radius, spanMin, avgAcc)
        }
    }

    /** Great-circle distance in metres (Haversine). Cheap; no
     *  geospatial lib needed for the ~50-200m radii we work with. */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    // ── Notification plumbing ──────────────────────────────────────
    private fun ensureChannel() = NavCard.ensureChannel(this)

    /** #1944 the card, with Cloud Nav's navigation lines when the Navigation tab has published them ([NavCardState]). */
    private fun buildNotification(subtitle: String): Notification = NavCard.notification(this, subtitle)

    private var lastSubtitle = "Tracking — passive, waiting for movement"

    companion object {
        private const val CHANNEL_ID = "maps_tracker"
        private const val NOTIF_ID   = 0xA1
        /** #1666 the only start the service accepts: the user's Start on the Maps config page. */
        const val ACTION_USER_START = "com.diegonmarcos.cloudnav.maps.ACTION_USER_START"
        const val SITE = "libs:maps LocationTrackerService (Maps ▸ Start)"
        fun isUserStart(action: String?): Boolean = action == ACTION_USER_START

        @Volatile private var instance: LocationTrackerService? = null

        /** Cloud Nav hooks its wake sources here (WakeSources.arm) so the user's Start arms them even with the engine off. */
        @Volatile var armWake: ((android.content.Context) -> Unit)? = null

        /** #1770 a wake source (Activity Recognition transition, geofence exit, significant motion) tells the running tracker; no-op when it is not running. */
        fun signal(@Suppress("UNUSED_PARAMETER") ctx: android.content.Context, sig: GateSignal) {
            val i = instance ?: return
            i.main.post { i.onSignal(sig) }
        }

        /** Convenience starter — toggles [MapsTrackerPrefs.enabled],
         *  fires startForegroundService so the OS doesn't kill the
         *  start call on Android 8+, AND enqueues the periodic
         *  [TrackerWatchdog]. The watchdog is the survivability layer
         *  for Samsung-class aggressive task killers + memory-pressure
         *  kills that bypass Battery Optimization whitelist. */
        fun startTracking(ctx: android.content.Context) {
            MapsTrackerPrefs(ctx).enabled = true
            val intent = Intent(ctx, LocationTrackerService::class.java).setAction(ACTION_USER_START)
            ContextCompat.startForegroundService(ctx, intent)
            // #1666 no keep-alive: the periodic watchdog that re-spawned the tracker is cancelled, never scheduled.
            TrackerWatchdog.cancel(ctx)
        }
        /** Convenience stopper — flips enabled=false (so a service
         *  revival by START_STICKY immediately self-stops), explicitly
         *  stops the running instance, and cancels the watchdog so a
         *  deliberate Stop doesn't keep the worker firing every 15
         *  min into a disabled service. */
        fun stopTracking(ctx: android.content.Context) {
            MapsTrackerPrefs(ctx).enabled = false
            ctx.stopService(Intent(ctx, LocationTrackerService::class.java))
            TrackerWatchdog.cancel(ctx)
        }
    }
}
