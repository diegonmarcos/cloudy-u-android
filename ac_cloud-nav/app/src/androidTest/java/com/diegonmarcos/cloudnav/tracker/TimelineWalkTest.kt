package com.diegonmarcos.cloudnav.tracker

import android.Manifest
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diegonmarcos.cloudnav.maps.LocationTrackerService
import com.diegonmarcos.cloudnav.maps.MapsDb
import com.diegonmarcos.cloudnav.maps.TimelineSync
import com.diegonmarcos.cloudnav.maps.TlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * #1851 the emulator walk. The workflow `test-cloud-nav-walk` boots an emulator, starts this test with `walk=1`, and in parallel injects
 * a walk with `adb emu geo fix` (home, walk, cafe, tram, office, walk home, home; ~65 min wall clock, one fix every 20 s). This test starts
 * the tracker the way the user's Start does, waits for the injector's marker file, rebuilds the store and asserts what the Day page and
 * Tracker > Timeline would show: several stays and moves, nonzero km, and the two lists agree. Without `walk=1` (the normal suite) it is skipped.
 */
@RunWith(AndroidJUnit4::class)
class TimelineWalkTest {
    private val marker = File("/data/local/tmp/walk-done")

    @Test fun `a walk injected with adb emu geo fix shows several stays and moves with km, and the Timeline matches the Day`() {
        val inst = InstrumentationRegistry.getInstrumentation()
        Assume.assumeTrue("only in the walk workflow (walk=1)", InstrumentationRegistry.getArguments().getString("walk") == "1")
        val ctx: Context = inst.targetContext
        for (p in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION))
            runCatching { inst.uiAutomation.grantRuntimePermission(ctx.packageName, p) }
        val db = MapsDb.get(ctx)
        db.clearAll()
        TimelineState0.reset(ctx)

        // the GPS provider only runs while somebody asks for it; the tracker's passive listener then sees the emulator's geo fixes
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val keepGps = object : LocationListener { override fun onLocationChanged(l: android.location.Location) {} }
        inst.runOnMainSync {
            @Suppress("MissingPermission") lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 0f, keepGps, Looper.getMainLooper())
        }
        LocationTrackerService.startTracking(ctx)
        runCatching { File("/data/local/tmp/walk-start").apply { writeText("1"); setReadable(true, false) } }   // the injector waits for this

        val deadline = System.currentTimeMillis() + 110 * 60_000L
        while (!marker.exists() && System.currentTimeMillis() < deadline) Thread.sleep(10_000L)
        inst.runOnMainSync { lm.removeUpdates(keepGps) }
        LocationTrackerService.stopTracking(ctx)
        assertTrue("the injector never finished (no ${marker.path})", marker.exists())

        val now = System.currentTimeMillis()
        val fixes = db.fixesBetween(0L, now)
        assertTrue("the geo fixes reached the one store: ${fixes.size}", fixes.size >= 20)
        TimelineSync.run(ctx, now, 0L)
        val zone = ZoneId.systemDefault()
        val segs = db.segmentsBetween(0L, now)
        val stays = segs.count { it.kind != TlKind.MOVE }; val moves = segs.filter { it.kind == TlKind.MOVE }
        assertTrue("several stays: $stays (${segs.map { it.kind }})", stays >= 3)
        assertTrue("several moves: ${moves.size}", moves.size >= 2)
        assertTrue("nonzero km: ${moves.sumOf { it.distanceM }}", moves.sumOf { it.distanceM } > 3_000.0)

        // the Day page's list and Tracker > Timeline's rows for the day of the first fix
        val date = Instant.ofEpochMilli(fixes.first().ts).atZone(zone).toLocalDate()
        val a = DayTimeline.dayStart(date, zone); val b = minOf(DayTimeline.dayStart(date.plusDays(1), zone), now)
        val day = DayTimeline.build(TimelineSync.view(ctx, a - 12 * 3_600_000L, b), a, b)
        val rows = TrackerRows.timeline(TrackerDebugApiProvider.segments(ctx, date.toString(), now, zone))
        assertEquals("Timeline and Day show the same stays", day.count { it is DaySeg.Stay }, rows.count { it.type == "STAY" })
        assertEquals("Timeline and Day show the same moves", day.count { it is DaySeg.Move }, rows.count { it.type == "MOVE" })
        assertTrue("the Day page shows km", DayDetails.bar(day, StepsTotal(null, "walk test")).distanceM > 0.0)
        assertTrue("the Day page shows more than one stay", day.count { it is DaySeg.Stay && !it.missing } >= 2)
    }

    /** The walk starts from an empty store: forget the migration markers too. */
    private object TimelineState0 {
        fun reset(ctx: Context) { ctx.getSharedPreferences("timeline_state", Context.MODE_PRIVATE).edit().clear().apply() }
    }
}
