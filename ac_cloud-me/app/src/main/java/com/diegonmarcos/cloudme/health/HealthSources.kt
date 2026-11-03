package com.diegonmarcos.cloudme.health

/**
 * Which source feeds each Health metric (HealthChartsTest): the Health Connect record Cloud Me reads, the kind of app that writes
 * it and how to connect one. The empty state and the "mock data" banner of every page are written from this, so a page never says
 * "no data" without saying where the data would come from.
 */
object HealthSources {
    data class Source(val id: String, val label: String, val record: String, val writers: String, val how: String)

    private const val HC = "Open Health Connect > App permissions, allow"
    private const val CM = "and allow Cloud Me to read it (the Allow button on this page)."

    val ALL: List<Source> = listOf(
        Source("steps", "Steps", "StepsRecord", "Google Fit, Samsung Health, Fitbit, a watch or the phone's step counter", "$HC the app that counts your steps to write Steps, $CM"),
        Source("active", "Active minutes", "ExerciseSessionRecord", "Google Fit, Samsung Health, Strava, a watch, or an entry on Tracked", "$HC your workout app to write Exercise, $CM"),
        Source("calories", "Calories", "ActiveCaloriesBurnedRecord", "Google Fit, Samsung Health, Fitbit, a watch", "$HC your activity app to write Active calories burned, $CM"),
        Source("distance", "Distance", "DistanceRecord", "Google Fit, Samsung Health, Strava, a watch", "$HC your activity app to write Distance, $CM"),
        Source("heart", "Heart rate", "HeartRateRecord", "a watch or band (Samsung Health, Fitbit, Pixel Watch), a chest strap", "$HC the watch's app to write Heart rate, $CM"),
        Source("sleep", "Sleep", "SleepSessionRecord", "Samsung Health, Fitbit, Sleep as Android, a watch or ring", "$HC your sleep tracker to write Sleep, $CM"),
        Source("weight", "Weight", "WeightRecord", "a smart scale app (Withings, Samsung Health, Renpho), or an entry on Tracked", "$HC the scale's app to write Weight, $CM"),
        Source("water", "Water", "HydrationRecord", "a water tracker, Samsung Health, or an entry on Tracked", "$HC your water tracker to write Hydration, $CM"),
        Source("bodyfat", "Body fat", "BodyFatRecord", "a body-composition scale app (Withings, Samsung Health, Renpho)", "$HC the scale's app to write Body fat, $CM"),
        Source("resting_hr", "Resting heart rate", "RestingHeartRateRecord", "a watch or band (Samsung Health, Fitbit, Pixel Watch)", "$HC the watch's app to write Resting heart rate, $CM"),
        Source("hrv", "Heart rate variability", "HeartRateVariabilityRmssdRecord", "a watch, ring or band that measures HRV (Fitbit, Oura, Samsung Health)", "$HC the device's app to write Heart rate variability, $CM"),
        Source("workouts", "Workouts", "ExerciseSessionRecord", "Google Fit, Samsung Health, Strava, a watch", "$HC your workout app to write Exercise, $CM"),
    )

    private val byId = ALL.associateBy { it.id }

    fun of(id: String): Source? = byId[id]

    /** The metrics each tracker page charts, in the order the page draws them; the plan pages read nothing from Health Connect. */
    fun forPage(page: String): List<Source> = when (page) {
        "tracked" -> listOf("steps", "active", "calories", "distance", "heart", "sleep", "weight", "water")
        "activities" -> listOf("steps", "active", "distance", "calories", "workouts")
        "bodycomp" -> listOf("weight", "bodyfat")
        "hr" -> listOf("resting_hr", "hrv", "heart")
        "sleep" -> listOf("sleep")
        "workout" -> listOf("workouts")
        else -> emptyList()
    }.mapNotNull { byId[it] }
}
