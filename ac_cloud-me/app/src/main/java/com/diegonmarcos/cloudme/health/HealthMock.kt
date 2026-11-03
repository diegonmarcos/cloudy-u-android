package com.diegonmarcos.cloudme.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sin
import kotlin.random.Random

/**
 * Seeded stand-in data for a Health page when Health Connect holds nothing (an emulator, no permission, no writer app): the same
 * pattern as Inventory's LedgerSeed. Every screen that draws it shows the "mock data" badge. Deterministic per metric and day, so a
 * screenshot is stable; never written anywhere and never mixed with real readings.
 */
object HealthMock {
    const val BADGE = "mock data"

    private fun rnd(id: String, day: LocalDate) = Random(id.hashCode() * 31L + day.toEpochDay())

    /** A plausible daily total (sum metrics) or level (avg metrics) for [id] on [day]. */
    fun daily(id: String, day: LocalDate): Double {
        val r = rnd(id, day)
        val weekend = day.dayOfWeek.value >= 6
        val t = day.toEpochDay().toDouble()
        return when (id) {
            "steps" -> (if (weekend) 9500.0 else 7200.0) + r.nextDouble(-2500.0, 3500.0)
            "active" -> (if (weekend) 45.0 else 28.0) + r.nextDouble(-15.0, 25.0)
            "calories" -> (if (weekend) 520.0 else 410.0) + r.nextDouble(-140.0, 180.0)
            "distance" -> daily("steps", day) * 0.00072
            "heart" -> 72.0 + r.nextDouble(-6.0, 8.0)
            "sleep" -> 7.1 + r.nextDouble(-1.2, 1.1)
            "weight" -> 76.0 - (t % 365) / 365.0 * 1.8 + 0.4 * sin(t / 9.0) + r.nextDouble(-0.25, 0.25)
            "water" -> 1800.0 + r.nextDouble(-600.0, 700.0)
            "bodyfat" -> 19.0 - (t % 365) / 365.0 * 1.4 + r.nextDouble(-0.3, 0.3)
            "resting_hr" -> 56.0 + 2 * sin(t / 11.0) + r.nextDouble(-2.0, 2.0)
            "hrv" -> 58.0 + 8 * sin(t / 13.0) + r.nextDouble(-7.0, 7.0)
            else -> 0.0
        }.coerceAtLeast(0.0)
    }

    private val SPREAD = listOf(7, 8, 9, 10, 12, 13, 15, 17, 18, 19, 20)

    /**
     * Readings over the last [days] days ending [today]: sum metrics spread over waking hours (today only up to [nowHour]), avg
     * metrics one per waking hour (heart) or one each morning (weight, body fat, resting HR, HRV), sleep one per morning.
     */
    fun readings(id: String, today: LocalDate, zone: ZoneId, days: Int = 366, nowHour: Int = java.time.LocalTime.now(zone).hour): List<Reading> {
        val out = ArrayList<Reading>()
        for (back in days - 1 downTo 0) {
            val d = today.minusDays(back.toLong())
            val total = daily(id, d)
            val hours = SPREAD.filter { back > 0 || it <= nowHour }
            fun at(h: Int) = d.atTime(h, 15).atZone(zone).toInstant()
            when (id) {
                "steps", "active", "calories", "distance", "water" -> {
                    if (hours.isEmpty()) continue
                    val share = if (back == 0) hours.size.toDouble() / SPREAD.size else 1.0
                    val w = hours.map { 1.0 + rnd(id + it, d).nextDouble(0.0, 1.5) }
                    hours.forEachIndexed { i, h -> out += Reading(at(h), total * share * w[i] / w.sum()) }
                }
                "heart" -> hours.forEach { h -> out += Reading(at(h), total + rnd("h$h", d).nextDouble(-8.0, 22.0)) }
                else -> if (back > 0 || nowHour >= 7) out += Reading(at(7), total)
            }
        }
        return out
    }

    /** [m]'s mock series on [range], through the same aggregation as real readings. */
    fun series(m: TrackedMetric, range: TrackedRange, today: LocalDate, zone: ZoneId): List<Bucket> =
        HealthAgg.bucketize(readings(m.id, today, zone, if (range == TrackedRange.YEAR) 366 else range.days), range, today, zone, m.avg)

    /** Nights with stages, oldest first, dated by the morning. */
    fun nights(today: LocalDate, n: Int): List<SleepNight> = (n downTo 1).map { back ->
        val d = today.minusDays(back - 1L)
        val total = daily("sleep", d) * 60
        val r = rnd("stages", d)
        val deep = total * r.nextDouble(0.14, 0.22); val rem = total * r.nextDouble(0.18, 0.25)
        val awake = r.nextDouble(12.0, 38.0)
        SleepNight(d, total, mapOf("deep" to deep, "rem" to rem, "light" to total - deep - rem, "awake" to awake))
    }

    private val TYPES = listOf(
        Triple("Morning run", ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, 32L),
        Triple("Strength", ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, 50L),
        Triple("Cycling", ExerciseSessionRecord.EXERCISE_TYPE_BIKING, 55L),
        Triple("Walk", ExerciseSessionRecord.EXERCISE_TYPE_WALKING, 40L),
        Triple("Yoga", ExerciseSessionRecord.EXERCISE_TYPE_YOGA, 30L),
    )

    /** Sessions over the last [days] days, newest first: about four a week. */
    fun workouts(today: LocalDate, zone: ZoneId, days: Int): List<Workout> = (0 until days).mapNotNull { back ->
        val d = today.minusDays(back.toLong())
        val r = rnd("workout", d)
        if (r.nextDouble() > 0.58) return@mapNotNull null
        val (title, type, min) = TYPES[r.nextInt(TYPES.size)]
        val start = d.atTime(if (back == 0) 7 else 18, 0).atZone(zone).toInstant()
        Workout(title, start, start.plusSeconds((min + r.nextLong(-8, 15)) * 60), type)
    }

    /** One heart-rate sample a minute over a week of mostly resting, some active and a few hard minutes. */
    fun hrSamples(today: LocalDate): List<Double> {
        val r = rnd("zones", today)
        return List(7 * 16 * 60) {
            when (r.nextInt(100)) {
                in 0..61 -> 60.0 + r.nextDouble(0.0, 30.0)
                in 62..79 -> 96.0 + r.nextDouble(0.0, 18.0)
                in 80..90 -> 114.0 + r.nextDouble(0.0, 19.0)
                in 91..97 -> 133.0 + r.nextDouble(0.0, 19.0)
                else -> 152.0 + r.nextDouble(0.0, 22.0)
            }
        }
    }
}
