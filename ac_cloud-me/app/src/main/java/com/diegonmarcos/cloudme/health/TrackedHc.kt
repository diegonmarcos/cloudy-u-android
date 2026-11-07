package com.diegonmarcos.cloudme.health

import com.diegonmarcos.cloudme.CloudMeApiData
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/** One Health > Tracked card (data/health/health_tracked.json). */
data class TrackedMetric(
    val id: String, val label: String, val unit: String, val goal: Double, val decimals: Int, val avg: Boolean,
    val entryLabel: String, val entryUnit: String, val entryMinutes: Long,
)

/**
 * The chart ranges of a detail (the D / W / M / Y toggle): Day is 24 hourly buckets, Week 7 and Month 30 daily buckets, Year 12
 * monthly buckets. A Year bucket of a sum metric holds the month's MEAN DAILY value (the month total over the days it covers), so the
 * daily goal line means the same thing on every range.
 */
enum class TrackedRange(val label: String, val short: String, val days: Int) { DAY("Day", "D", 1), WEEK("Week", "W", 7), MONTH("Month", "M", 30), YEAR("Year", "Y", 365) }

/** One bucket of a chart: its start (an hour, or a day at midnight), its label and its value (null = no data). */
data class Bucket(val start: LocalDateTime, val label: String, val value: Double?)

/** Pure rules of Tracked (TrackedTest): parsing, ring progress, formatting, the bucket grid and its summary. */
object Tracked {
    const val ASSET = "health_tracked.json"

    fun parse(json: String): List<TrackedMetric> {
        val a = JSONObject(json).getJSONArray("metrics")
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            TrackedMetric(
                it.getString("id"), it.getString("label"), it.optString("unit"), it.optDouble("goal", 0.0), it.optInt("decimals", 0),
                it.optString("agg", "sum") == "avg", it.optString("entry_label", it.getString("label")), it.optString("entry_unit", it.optString("unit")),
                it.optLong("entry_minutes", 0),
            )
        }
    }

    fun load(ctx: Context): List<TrackedMetric> = parse(CloudMeApiData.text(ctx, ASSET))

    /** The ring: 0..1 toward the goal; a metric with no goal has no ring (null). */
    fun progress(value: Double?, goal: Double): Float? = if (goal <= 0.0) null else ((value ?: 0.0) / goal).coerceIn(0.0, 1.0).toFloat()

    fun format(m: TrackedMetric, v: Double?): String = if (v == null) "--" else "%.${m.decimals}f".format(java.util.Locale.US, v)

    /** Every slot of [range] ending at [today], in order, filled from [values] (keyed by slot start); a missing slot is null. */
    fun grid(range: TrackedRange, today: LocalDate, values: Map<LocalDateTime, Double>): List<Bucket> = when (range) {
        TrackedRange.DAY -> (0 until 24).map { h -> val t = today.atTime(h, 0); Bucket(t, "%02d".format(h), values[t]) }
        TrackedRange.YEAR -> (11 downTo 0).map { back ->
            val m = today.withDayOfMonth(1).minusMonths(back.toLong()).atStartOfDay()
            Bucket(m, m.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US), values[m])
        }
        else -> (range.days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong()).atStartOfDay()
            Bucket(d, if (range == TrackedRange.WEEK) d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US) else "${d.dayOfMonth}", values[d])
        }
    }

    /** The slot a moment falls in: its hour (Day), its day (Week, Month) or its month (Year). */
    fun slot(range: TrackedRange, t: LocalDateTime): LocalDateTime = when (range) {
        TrackedRange.DAY -> t.withMinute(0).withSecond(0).withNano(0)
        TrackedRange.YEAR -> t.toLocalDate().withDayOfMonth(1).atStartOfDay()
        else -> t.toLocalDate().atStartOfDay()
    }

    /** Days a Year bucket covers: the whole month, or up to [today] for the current month. Every other slot covers itself (1). */
    fun daysCovered(range: TrackedRange, start: LocalDateTime, today: LocalDate): Int = when {
        range != TrackedRange.YEAR -> 1
        start.toLocalDate().withDayOfMonth(1) == today.withDayOfMonth(1) -> today.dayOfMonth
        else -> start.toLocalDate().lengthOfMonth()
    }

    /** Total (sum metrics) or mean (avg metrics) over the buckets that hold data; null when none does. */
    fun summary(m: TrackedMetric, buckets: List<Bucket>): Double? {
        val v = buckets.mapNotNull { it.value }
        if (v.isEmpty()) return null
        return if (m.avg) v.average() else v.sum()
    }

    /** The history list: newest first, only slots that hold data. */
    fun history(buckets: List<Bucket>): List<Bucket> = buckets.filter { it.value != null }.reversed()

    /** A manual entry is a positive number below a sane ceiling for its metric (a typo of 80000 kg is refused). */
    fun validEntry(m: TrackedMetric, v: Double?): Boolean = v != null && v > 0.0 && v < when (m.id) {
        "steps" -> 200_000.0; "active" -> 1_440.0; "calories" -> 20_000.0; "distance" -> 500.0
        "heart" -> 260.0; "sleep" -> 24.0; "weight" -> 500.0; "water" -> 20_000.0; else -> Double.MAX_VALUE
    }
}

/** Each Tracked id's Health Connect record, aggregate and writer. The ids are data/health/health_tracked.json's. */
object TrackedHc {
    val IDS = listOf("steps", "active", "calories", "distance", "heart", "sleep", "weight", "water")

    fun type(id: String): KClass<out Record> = when (id) {
        "steps" -> StepsRecord::class
        "active" -> ExerciseSessionRecord::class
        "calories" -> ActiveCaloriesBurnedRecord::class
        "distance" -> DistanceRecord::class
        "heart" -> HeartRateRecord::class
        "sleep" -> SleepSessionRecord::class
        "weight" -> WeightRecord::class
        "water" -> HydrationRecord::class
        else -> error("unknown Tracked metric $id")
    }

    private fun metric(id: String): AggregateMetric<*> = when (id) {
        "steps" -> StepsRecord.COUNT_TOTAL
        "active" -> ExerciseSessionRecord.EXERCISE_DURATION_TOTAL
        "calories" -> ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
        "distance" -> DistanceRecord.DISTANCE_TOTAL
        "heart" -> HeartRateRecord.BPM_AVG
        "sleep" -> SleepSessionRecord.SLEEP_DURATION_TOTAL
        "weight" -> WeightRecord.WEIGHT_AVG
        "water" -> HydrationRecord.VOLUME_TOTAL
        else -> error("unknown Tracked metric $id")
    }

    /** The aggregate in the card's unit (km, kcal, min, h, kg, mL). */
    private fun value(id: String, r: AggregationResult): Double? = when (id) {
        "steps" -> r[StepsRecord.COUNT_TOTAL]?.toDouble()
        "active" -> r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes()?.toDouble()
        "calories" -> r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
        "distance" -> r[DistanceRecord.DISTANCE_TOTAL]?.inKilometers
        "heart" -> r[HeartRateRecord.BPM_AVG]?.toDouble()
        "sleep" -> r[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()?.div(60.0)
        "weight" -> r[WeightRecord.WEIGHT_AVG]?.inKilograms
        "water" -> r[HydrationRecord.VOLUME_TOTAL]?.inMilliliters
        else -> null
    }

    fun readPerms(ids: Collection<String> = IDS): Set<String> = ids.map { HealthPermission.getReadPermission(type(it)) }.toSet()
    fun writePerm(id: String): String = HealthPermission.getWritePermission(type(id))

    /**
     * Today's card value: the day's total for sum metrics, the day's mean heart rate, the last 24 hours of sleep (last
     * night spans midnight), and the latest weight of the last 30 days.
     */
    suspend fun today(c: HealthConnectClient, id: String, zone: ZoneId, now: Instant = Instant.now()): Double? = when (id) {
        "weight" -> c.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(30)), now), ascendingOrder = false, pageSize = 1))
            .records.firstOrNull()?.weight?.inKilograms
        "sleep" -> value(id, c.aggregate(AggregateRequest(setOf(metric(id)), TimeRangeFilter.between(now.minus(Duration.ofHours(24)), now))))
        else -> value(id, c.aggregate(AggregateRequest(setOf(metric(id)), TimeRangeFilter.between(LocalDate.now(zone).atStartOfDay(zone).toInstant(), now))))
    }

    /** The chart's slots: hourly buckets of today, daily buckets of the last 7 / 30 days, or monthly buckets of the last 12 months, de-duplicated across apps by Health Connect. */
    suspend fun series(c: HealthConnectClient, id: String, range: TrackedRange, zone: ZoneId, avg: Boolean = id == "heart" || id == "weight"): List<Bucket> {
        val today = LocalDate.now(zone)
        val values = HashMap<LocalDateTime, Double>()
        when (range) {
            TrackedRange.DAY -> {
                val from = today.atStartOfDay(zone).toInstant()
                c.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(metric(id)), TimeRangeFilter.between(from, from.plus(Duration.ofDays(1))), Duration.ofHours(1)))
                    .forEach { g -> value(id, g.result)?.let { values[LocalDateTime.ofInstant(g.startTime, zone)] = it } }
            }
            TrackedRange.YEAR -> {
                val from = today.withDayOfMonth(1).minusMonths(11).atStartOfDay()
                c.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(metric(id)), TimeRangeFilter.between(from, today.plusDays(1).atStartOfDay()), Period.ofMonths(1)))
                    .forEach { g -> value(id, g.result)?.let { v -> values[g.startTime] = if (avg) v else v / Tracked.daysCovered(range, g.startTime, today) } }
            }
            else -> {
                val from = today.minusDays(range.days - 1L).atStartOfDay()
                c.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(metric(id)), TimeRangeFilter.between(from, today.plusDays(1).atStartOfDay()), Period.ofDays(1)))
                    .forEach { g -> value(id, g.result)?.let { values[g.startTime] = it } }
            }
        }
        return Tracked.grid(range, today, values)
    }

    /** The manual entry as the record Health Connect stores, ending at [end]. */
    fun record(m: TrackedMetric, v: Double, end: Instant, zone: ZoneId): Record {
        val off = zone.rules.getOffset(end)
        fun from(min: Long) = end.minus(Duration.ofMinutes(min.coerceAtLeast(1)))
        return when (m.id) {
            "steps" -> StepsRecord(startTime = from(m.entryMinutes), startZoneOffset = off, endTime = end, endZoneOffset = off, count = v.toLong())
            "active" -> ExerciseSessionRecord(startTime = from(v.toLong()), startZoneOffset = off, endTime = end, endZoneOffset = off,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, title = "Cloud Me entry")
            "calories" -> ActiveCaloriesBurnedRecord(startTime = from(m.entryMinutes), startZoneOffset = off, endTime = end, endZoneOffset = off, energy = Energy.kilocalories(v))
            "distance" -> DistanceRecord(startTime = from(m.entryMinutes), startZoneOffset = off, endTime = end, endZoneOffset = off, distance = Length.kilometers(v))
            "heart" -> from(m.entryMinutes).let { s ->
                HeartRateRecord(startTime = s, startZoneOffset = off, endTime = end, endZoneOffset = off, samples = listOf(HeartRateRecord.Sample(s, v.toLong())))
            }
            "sleep" -> SleepSessionRecord(startTime = from((v * 60).toLong()), startZoneOffset = off, endTime = end, endZoneOffset = off, title = "Cloud Me entry")
            "weight" -> WeightRecord(time = end, zoneOffset = off, weight = Mass.kilograms(v))
            "water" -> HydrationRecord(startTime = from(m.entryMinutes), startZoneOffset = off, endTime = end, endZoneOffset = off, volume = Volume.milliliters(v))
            else -> error("unknown Tracked metric ${m.id}")
        }
    }

    suspend fun add(c: HealthConnectClient, m: TrackedMetric, v: Double, zone: ZoneId) {
        c.insertRecords(listOf(record(m, v, Instant.now(), zone)))
    }
}
