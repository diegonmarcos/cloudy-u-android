package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import android.location.Geocoder
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.diegonmarcos.cloudnav.maps.MapsDb
import com.diegonmarcos.cloudnav.maps.TimelineSync
import com.diegonmarcos.cloudnav.maps.TimelineViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period as JPeriod
import java.time.ZoneId
import java.util.Locale

/**
 * Loads the Steps page. Health Connect (StepsRecord) is the source of truth when it is installed and
 * the read permission is granted; otherwise the page says so and shows what the tracker engine
 * itself counted on this device (labelled as such).
 */
class StepsRepository(private val ctx: Context) {
    companion object {
        val READ_STEPS: Set<String> = setOf(HealthPermission.getReadPermission(StepsRecord::class))
        /** Optional: sleep sessions sharpen the sleep rule; without this grant the night window alone decides. */
        val READ_SLEEP: String = HealthPermission.getReadPermission(SleepSessionRecord::class)
        private const val HISTORY_DAYS = 400L
    }

    private val names = HashMap<String, String>()

    suspend fun load(zone: ZoneId = ZoneId.systemDefault()): StepsUi = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dayStart = LocalDate.now(zone).minusDays(StepsModel.CARD_DAYS - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
        runCatching { ingestHealth(zone) }   // #1851 the store first: Explored > Day reads the same totals
        val fixes = fixes(dayStart, now)
        val stepM = EnginePrefs(ctx).stepLengthM
        val label: (PlaceFix) -> String = { name(it) }

        val hcStatus = HealthConnectClient.getSdkStatus(ctx)
        if (hcStatus == HealthConnectClient.SDK_AVAILABLE) {
            val client = HealthConnectClient.getOrCreate(ctx)
            val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
            val perm = StepsDiagnostics.permState(hcStatus, granted, READ_STEPS)
            if (perm == PermState.GRANTED) {
                val hc = runCatching {
                    val daily = hcDaily(client, zone).toMutableMap()
                    val today = hcToday(client, zone, now)
                    // The aggregate over the local day is the de-duplicated truth for today; the per-period group can lag it.
                    daily[Instant.ofEpochMilli(now).atZone(zone).toLocalDate()] = today.steps
                    Triple(daily, hcSlices(client, dayStart, now), today)
                }
                hc.getOrNull()?.let { (daily, slices, today) ->
                    val diag = StepsDiag(
                        StepsDiagnostics.statusLine("Health Connect", perm, today.lastDataMs, today.origins, zone),
                        StepsDiagnostics.hint(perm, today.steps, today.origins))
                    return@withContext StepsModel.build(StepsSource.HEALTH_CONNECT, SourceStatus.READY, daily, slices, fixes, zone, now, stepM, diag, label)
                }
                // Granted but the read threw: say so, never relabel it as "not allowed".
                return@withContext device(SourceStatus.HEALTH_CONNECT_ERROR, zone, now, dayStart, fixes, stepM, label, perm,
                    "Health Connect read failed: ${hc.exceptionOrNull()?.javaClass?.simpleName}")
            }
            return@withContext device(SourceStatus.NEEDS_PERMISSION, zone, now, dayStart, fixes, stepM, label, perm)
        }
        val perm = StepsDiagnostics.permState(hcStatus, emptySet(), READ_STEPS)
        val st = if (perm == PermState.UPDATE_REQUIRED) SourceStatus.HEALTH_CONNECT_UPDATE else SourceStatus.HEALTH_CONNECT_MISSING
        device(st, zone, now, dayStart, fixes, stepM, label, perm)
    }

    /**
     * #1851 Health Connect into the one store (maps.db): the day totals of the last [days] days (the de-duplicated aggregate, today from
     * the local-day aggregate) and the sleep sessions when the sleep read grant is there. Both the Steps page and Explored > Day read the store,
     * so a day shows the same steps in both. False = nothing read (not installed, not granted, or the read threw): the store keeps what it had.
     */
    suspend fun ingestHealth(zone: ZoneId = ZoneId.systemDefault(), days: Long = 31): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) return@runCatching false
            val client = HealthConnectClient.getOrCreate(ctx)
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(READ_STEPS)) return@runCatching false
            val now = System.currentTimeMillis(); val store = MapsDb.get(ctx)
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val daily = hcDaily(client, zone).toMutableMap()
            daily[today] = hcToday(client, zone, now).steps
            for ((d, n) in daily) if (!d.isBefore(today.minusDays(days))) store.putDailySteps(d.toString(), n, "hc", now)
            if (READ_SLEEP in granted) {
                val from = today.minusDays(days).atStartOfDay(zone).toInstant()
                client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, Instant.ofEpochMilli(now)), pageSize = 1000))
                    .records.forEach { store.putSleepSession(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), "hc") }
            }
            true
        }.getOrDefault(false)
    }

    /** Today's steps from Health Connect's de-duplicating aggregate over the local day, plus who wrote them and when. */
    private class HcToday(val steps: Long, val origins: Set<String>, val lastDataMs: Long?)

    private suspend fun hcToday(c: HealthConnectClient, zone: ZoneId, now: Long): HcToday {
        val (from, to) = StepsDiagnostics.todayRange(Instant.ofEpochMilli(now), zone)
        val range = TimeRangeFilter.between(from, to)
        val agg = c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range))
        val last = c.readRecords(ReadRecordsRequest(StepsRecord::class, range, ascendingOrder = false, pageSize = 1))
            .records.firstOrNull()?.endTime?.toEpochMilli()
        return HcToday(agg[StepsRecord.COUNT_TOTAL] ?: 0L, agg.dataOrigins.map { it.packageName }.toSet(), last)
    }

    private fun device(st: SourceStatus, zone: ZoneId, now: Long, dayStart: Long, fixes: List<PlaceFix>, stepM: Double, label: (PlaceFix) -> String,
                       perm: PermState = PermState.UNAVAILABLE, extra: String? = null): StepsUi {
        // #1114: the steps are a view over THE timeline store, not the engine's samples directly
        val all = TimelineViews.stepSlices(TimelineSync.view(ctx, now - HISTORY_DAYS * 86_400_000L, now)).map { StepSlice(it.startMs, it.endMs, it.count) }
        val prefs = EnginePrefs(ctx)
        val recog = android.os.Build.VERSION.SDK_INT < 29 ||
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val engine = when {
            !recog -> "physical-activity permission (ACTIVITY_RECOGNITION) is not granted, so the step counter cannot be read (Configs > Tracker)"
            !prefs.config.enabled -> "tracker engine is off (Configs > Tracker), so nothing is sampled"
            else -> "tracker engine samples every 15 min, last sample " + (prefs.lastSampleMs.takeIf { it > 0 }?.let { StepsFormat.time(it, zone) } ?: "never")
        }
        val last = all.maxOfOrNull { it.endMs }
        val diag = StepsDiag(
            StepsDiagnostics.statusLine("this phone's step counter", perm, last, emptySet(), zone) + " | " + engine,
            listOfNotNull(StepsDiagnostics.hint(perm, 0, emptySet()), extra).joinToString(" ").ifBlank { null })
        return StepsModel.build(StepsSource.DEVICE, st, StepStats.perDay(all, zone), all.filter { it.startMs >= dayStart }, fixes, zone, now, stepM, diag, label)
    }

    private suspend fun hcDaily(c: HealthConnectClient, zone: ZoneId): Map<LocalDate, Long> {
        val end = LocalDateTime.now(zone).plusDays(1).toLocalDate().atStartOfDay()
        val res = c.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(
            setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(end.minusDays(HISTORY_DAYS), end), JPeriod.ofDays(1)))
        return res.associate { it.startTime.toLocalDate() to (it.result[StepsRecord.COUNT_TOTAL] ?: 0L) }
    }

    private suspend fun hcSlices(c: HealthConnectClient, fromMs: Long, toMs: Long): List<StepSlice> {
        val out = mutableListOf<StepSlice>()
        var token: String? = null
        do {
            val r = c.readRecords(ReadRecordsRequest(StepsRecord::class,
                TimeRangeFilter.between(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs)), pageSize = 5000, pageToken = token))
            r.records.mapTo(out) { StepSlice(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.count) }
            token = r.pageToken
        } while (token != null && out.size < 100_000)
        return out
    }

    /** #1851 every fix the one store holds (the tracker's, the engine's, migrated history), oldest first; the pages read nothing else. */
    internal fun fixes(from: Long, to: Long): List<PlaceFix> = runCatching {
        MapsDb.get(ctx).fixesBetween(from, to).map {
            val src = if (it.provider == "legacy-points") "gps" else it.provider.removePrefix("engine-")
            PlaceFix(it.ts, it.lat, it.lon, it.provider == com.diegonmarcos.cloudnav.maps.TimelineEngine.PREDICTED, it.accuracyM ?: 0.0, src)
        }
    }.getOrDefault(emptyList())

    /** The stops the tracker's stop-detector recorded (what Cloud Nav's Stops and Daily pages list), oldest first. */
    internal fun stops(from: Long, to: Long): List<StopFix> = runCatching {
        MapsDb.get(ctx).stopsBetween(from, to).sortedBy { it.startedAt }.map { s ->
            StopFix(s.startedAt, s.endedAt, s.lat, s.lon,
                s.placeName?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(s.neighborhood, s.city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null })
        }
    }.getOrDefault(emptyList())

    /** Geocoder place name when the platform has one, else the coordinates. Cached on a ~100 m grid. */
    @Suppress("DEPRECATION")
    internal fun name(f: PlaceFix): String {
        val key = "%.3f,%.3f".format(Locale.US, f.lat, f.lon)
        return names.getOrPut(key) {
            val coords = "%.4f, %.4f".format(Locale.US, f.lat, f.lon)
            if (!Geocoder.isPresent()) return@getOrPut coords
            runCatching {
                Geocoder(ctx, Locale.getDefault()).getFromLocation(f.lat, f.lon, 1)?.firstOrNull()?.let { a ->
                    listOfNotNull(a.thoroughfare ?: a.featureName, a.locality ?: a.subAdminArea).distinct().joinToString(", ").ifBlank { null }
                }
            }.getOrNull() ?: coords
        }
    }
}
