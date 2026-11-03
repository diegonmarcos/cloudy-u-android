package com.diegonmarcos.cloudme.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.diegonmarcos.superapp.health.HealthConnectGateway
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/** A timestamped reading from Health Connect. */
data class Reading(val at: Instant, val value: Double)
data class DayValue(val day: LocalDate, val value: Double)
data class SleepNight(val day: LocalDate, val totalMin: Double, val stageMin: Map<String, Double>)
data class Workout(val title: String, val start: Instant, val end: Instant, val type: Int)

/** Pure aggregation of Health Connect readings (HealthSeriesTest). */
object HealthSeries {
    fun dailyMean(r: List<Reading>, zone: ZoneId): List<DayValue> =
        r.groupBy { it.at.atZone(zone).toLocalDate() }.map { (d, v) -> DayValue(d, v.map { it.value }.average()) }.sortedBy { it.day }

    fun dailySum(r: List<Reading>, zone: ZoneId): List<DayValue> =
        r.groupBy { it.at.atZone(zone).toLocalDate() }.map { (d, v) -> DayValue(d, v.sumOf { it.value }) }.sortedBy { it.day }

    /** Sleep stage code (SleepSessionRecord.STAGE_TYPE_*) to the declared stage key; null for stages that are not asleep/awake buckets. */
    fun stageKey(stage: Int): String? = when (stage) {
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> "light"
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
        else -> null
    }

    /** One night per session, dated by the day the session ENDS (the morning you woke). Without stages the whole session counts as unclassified sleep. */
    fun night(start: Instant, end: Instant, stages: List<Triple<Instant, Instant, Int>>, zone: ZoneId): SleepNight {
        val byStage = mutableMapOf<String, Double>()
        for ((s, e, code) in stages) {
            val k = stageKey(code) ?: continue
            byStage[k] = (byStage[k] ?: 0.0) + Duration.between(s, e).toMinutes()
        }
        val asleep = Duration.between(start, end).toMinutes().toDouble() - (byStage["awake"] ?: 0.0)
        return SleepNight(end.atZone(zone).toLocalDate(), asleep.coerceAtLeast(0.0), byStage)
    }

    /** Sleep debt in hours against [targetH] over the nights given (never negative per night: oversleeping does not pay it back). */
    fun sleepDebtH(nights: List<SleepNight>, targetH: Double): Double = nights.sumOf { (targetH - it.totalMin / 60.0).coerceAtLeast(0.0) }
}

sealed interface HcState<out T> {
    data object Loading : HcState<Nothing>
    data class Unavailable(val why: String) : HcState<Nothing>
    data class NeedPermission(val missing: Set<String>) : HcState<Nothing>
    data class Ready<T>(val data: T) : HcState<T>
}

/** Live Health Connect reads for the trackers. Every function returns what is really there; an empty list is an empty list. */
object HealthHc {
    fun perm(type: KClass<out Record>): String = HealthPermission.getReadPermission(type)

    val BODY = setOf(perm(WeightRecord::class), perm(BodyFatRecord::class))
    val HEIGHT = setOf(perm(HeightRecord::class))
    val HEART = setOf(perm(RestingHeartRateRecord::class), perm(HeartRateVariabilityRmssdRecord::class))
    val SLEEP = setOf(perm(SleepSessionRecord::class))
    val ACTIVITY = setOf(perm(StepsRecord::class), perm(ExerciseSessionRecord::class))
    val HEART_ZONES = HEART + perm(HeartRateRecord::class)

    /** Runs [read] once Health Connect is usable and every permission in [needed] is granted; otherwise says exactly what is missing. */
    suspend fun <T> guarded(ctx: Context, needed: Set<String>, read: suspend (HealthConnectClient) -> T): HcState<T> {
        if (!HealthConnectGateway.isProviderInstalled(ctx)) return HcState.Unavailable("Health Connect is not available on this phone.")
        val client = HealthConnectGateway.client(ctx) ?: return HcState.Unavailable("Health Connect could not be opened.")
        val missing = needed - HealthConnectGateway.grantedPermissions(ctx)
        if (missing.isNotEmpty()) return HcState.NeedPermission(missing)
        return runCatching { HcState.Ready(read(client)) }.getOrElse { HcState.Unavailable("Health Connect read failed: ${it.javaClass.simpleName}") }
    }

    private suspend fun <R : Record> records(c: HealthConnectClient, type: KClass<R>, days: Long): List<R> {
        val range = TimeRangeFilter.between(Instant.now().minus(Duration.ofDays(days)), Instant.now())
        return c.readRecords(ReadRecordsRequest(type, range)).records
    }

    suspend fun weight(c: HealthConnectClient, days: Long): List<Reading> =
        records(c, WeightRecord::class, days).map { Reading(it.time, it.weight.inKilograms) }.sortedBy { it.at }

    suspend fun bodyFat(c: HealthConnectClient, days: Long): List<Reading> =
        records(c, BodyFatRecord::class, days).map { Reading(it.time, it.percentage.value) }.sortedBy { it.at }

    suspend fun height(c: HealthConnectClient): Reading? =
        records(c, HeightRecord::class, 3650).maxByOrNull { it.time }?.let { Reading(it.time, it.height.inMeters * 100.0) }

    suspend fun restingHr(c: HealthConnectClient, days: Long): List<Reading> =
        records(c, RestingHeartRateRecord::class, days).map { Reading(it.time, it.beatsPerMinute.toDouble()) }.sortedBy { it.at }

    suspend fun hrv(c: HealthConnectClient, days: Long): List<Reading> =
        records(c, HeartRateVariabilityRmssdRecord::class, days).map { Reading(it.time, it.heartRateVariabilityMillis) }.sortedBy { it.at }

    /** Every heart-rate sample of the last [days] days, in bpm (the zones card). */
    suspend fun heartSamples(c: HealthConnectClient, days: Long): List<Double> =
        records(c, HeartRateRecord::class, days).flatMap { r -> r.samples.map { it.beatsPerMinute.toDouble() } }

    suspend fun steps(c: HealthConnectClient, days: Long): List<Reading> =
        records(c, StepsRecord::class, days).map { Reading(it.startTime, it.count.toDouble()) }

    suspend fun workouts(c: HealthConnectClient, days: Long): List<Workout> =
        records(c, ExerciseSessionRecord::class, days).map {
            Workout(it.title?.takeIf { t -> t.isNotBlank() } ?: "Exercise", it.startTime, it.endTime, it.exerciseType)
        }.sortedByDescending { it.start }

    suspend fun sleep(c: HealthConnectClient, days: Long, zone: ZoneId): List<SleepNight> =
        records(c, SleepSessionRecord::class, days).map { s ->
            HealthSeries.night(s.startTime, s.endTime, s.stages.map { Triple(it.startTime, it.endTime, it.stage) }, zone)
        }.groupBy { it.day }.map { (d, v) ->
            SleepNight(d, v.sumOf { it.totalMin }, v.flatMap { it.stageMin.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, x) -> x.sum() })
        }.sortedBy { it.day }
}
