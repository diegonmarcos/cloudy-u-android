package com.diegonmarcos.cloudnav.tracker

import org.json.JSONObject

/**
 * Tracker pages (#1156): pure mapping from the tracker provider's contract-v1 JSON (`tracker/segments`, `tracker/daily`,
 * `tracker/steps`, the SAME objects the fleet door serves, see [TrackerDebugApiProvider]) to the rows the pages draw. Nothing is
 * recomputed or copied here.
 */
data class TimelineRow(
    val type: String,        // STAY | MOVE
    val startMs: Long, val endMs: Long,
    val title: String,       // place name (STAY) or "from > to" (MOVE)
    val address: String,
    val lat: Double?, val lon: Double?,
    val mode: String,        // walk|car|... for MOVE, sleep|idle for STAY
    val distanceM: Long, val steps: Long,
    /** #1862 the city of the stay (a sleep's city sits under the strip); for a move, where it ended. */
    val city: String = "",
    /** #1862 the place has its own name (a named stay), not only a street or city. */
    val named: Boolean = false,
)

data class StepsRow(val date: String, val steps: Long)
data class SleepRow(val date: String, val hours: Double, val city: String)

object TrackerRows {
    private fun JSONObject.str(k: String) = optString(k, "").takeIf { it != "null" } ?: ""
    private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

    private fun placeTitle(p: JSONObject?): String = p?.let { it.str("name").ifBlank { it.str("street") }.ifBlank { it.str("city") } } ?: ""

    /** Every STAY / MOVE of `tracker/segments`, in order. */
    fun timeline(segments: JSONObject): List<TimelineRow> {
        val arr = segments.optJSONArray("segments") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { s ->
            if (s.optString("type") == "stay") {
                val loc = s.optJSONObject("location")
                TimelineRow("STAY", s.getLong("start"), s.getLong("end"),
                    placeTitle(loc).ifBlank { "Unnamed place" }, loc?.str("address") ?: "",
                    loc?.dbl("lat"), loc?.dbl("lon"), s.str("kind"), 0, 0, loc?.str("city") ?: "", (loc?.str("name") ?: "").isNotBlank())
            } else {
                val from = s.optJSONObject("from"); val to = s.optJSONObject("to")
                val title = listOf(placeTitle(from), placeTitle(to)).filter { it.isNotBlank() }.joinToString(" > ")
                TimelineRow("MOVE", s.getLong("start"), s.getLong("end"), title.ifBlank { "Move" }, to?.str("address") ?: "",
                    from?.dbl("lat"), from?.dbl("lon"), s.str("mode"), s.optLong("distance_m"), s.optLong("steps"), (to ?: from)?.str("city") ?: "")
            }
        }
    }

    /** `tracker/steps`: steps per day, newest first. */
    fun steps(j: JSONObject): List<StepsRow> {
        val arr = j.optJSONArray("days") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { StepsRow(it.getString("date"), it.optLong("steps")) }.sortedByDescending { it.date }
    }

    /** `tracker/daily`: sleep per day, newest first. */
    fun sleep(j: JSONObject): List<SleepRow> {
        val arr = j.optJSONArray("days") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { SleepRow(it.getString("date"), it.optDouble("sleep_h", 0.0), it.str("sleep_city")) }.sortedByDescending { it.date }
    }

    /** Bar heights 0..1 of [values] against their own maximum (all zero when there is no data). */
    fun bars(values: List<Double>): List<Float> {
        val max = values.maxOrNull() ?: return emptyList()
        return values.map { if (max <= 0.0) 0f else (it / max).toFloat().coerceIn(0f, 1f) }
    }
}
