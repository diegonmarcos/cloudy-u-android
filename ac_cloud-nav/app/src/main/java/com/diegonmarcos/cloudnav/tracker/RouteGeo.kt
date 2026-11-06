package com.diegonmarcos.cloudnav.tracker

import org.json.JSONArray
import org.json.JSONObject

/** One move drawn on the map: its path (from, the engine's measured fixes during it, to) in its mode's colour. */
data class RouteLine(val mode: Mode, val points: List<LatLon>)

/** An icon on the map: [icon] is a [RouteGeo] image name (`mode_car`, `stay_sleep`, `poi_food`...). */
/** [seg] = index of the day segment the pin stands for (-1 = none); a tap on the pin scrolls the list to that row. */
data class RouteMarker(val at: LatLon, val icon: String, val label: String, val seg: Int = -1)

data class RouteModel(val lines: List<RouteLine>, val markers: List<RouteMarker>, val current: LatLon?) {
    val points: List<LatLon> get() = lines.flatMap { it.points } + markers.map { it.at } + listOfNotNull(current)
    val isEmpty: Boolean get() = points.isEmpty()
}

/** Pure: the day's segments (and the engine's fixes) to what the map draws. The Android layer only renders [RouteModel]. */
object RouteGeo {
    /** Mode colours, one source for the list chips and the map polyline (ARGB). */
    fun modeArgb(m: Mode): Long = when (m) {
        Mode.WALK -> 0xFF4ADE80; Mode.RUN -> 0xFFFB923C; Mode.BIKE -> 0xFF2DD4BF
        Mode.CAR -> 0xFF60A5FA; Mode.TRAIN -> 0xFFA78BFA; Mode.PLANE -> 0xFFF472B6; Mode.UNKNOWN -> 0xFF9AA3B2
    }
    fun hex(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)
    fun modeHex(m: Mode): String = hex(modeArgb(m))

    fun modeIcon(m: Mode) = "mode_" + m.name.lowercase()
    fun stayIcon(s: DaySeg.Stay) = if (s.kind == StayKind.SLEEP) "stay_sleep" else "poi_" + (s.place?.poi ?: PoiCategory.OTHER).name.lowercase()
    /** All image names the map may ask for (the renderer registers each once). */
    val ALL_ICONS: List<String> = Mode.values().map { modeIcon(it) } + "stay_sleep" + PoiCategory.values().map { "poi_" + it.name.lowercase() }

    private fun Place.at() = if (located) LatLon(lat!!, lon!!) else null

    /** [live] marks today: the newest known point is the current position. */
    fun build(day: List<DaySeg>, fixes: List<PlaceFix> = emptyList(), live: Boolean = false): RouteModel {
        val lines = ArrayList<RouteLine>(); val markers = ArrayList<RouteMarker>()
        for ((si, s) in day.withIndex()) when (s) {
            is DaySeg.Stay -> s.place?.at()?.let { markers += RouteMarker(it, stayIcon(s), s.place?.label ?: "", si) }
            is DaySeg.Move -> {
                val path = ArrayList<LatLon>()
                s.from?.at()?.let { path += it }
                fixes.filter { !it.predicted && it.ts > s.startMs && it.ts < s.endMs }.sortedBy { it.ts }.forEach { path += LatLon(it.lat, it.lon) }
                s.to?.at()?.let { path += it }
                if (path.size >= 2) {
                    lines += RouteLine(s.mode, path)
                    markers += RouteMarker(path[path.size / 2], modeIcon(s.mode), s.mode.name.lowercase(), si)
                }
            }
        }
        val cur = if (live) (fixes.lastOrNull { !it.predicted }?.let { LatLon(it.lat, it.lon) } ?: day.lastOrNull { it is DaySeg.Stay }?.let { (it as DaySeg.Stay).place?.at() }) else null
        return RouteModel(lines, markers, cur)
    }

    private fun feature(geometry: JSONObject, props: JSONObject) = JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", props)
    private fun coord(p: LatLon) = JSONArray().put(p.lon).put(p.lat)

    fun linesJson(m: RouteModel): String {
        val f = JSONArray()
        for (l in m.lines) f.put(feature(JSONObject().put("type", "LineString").put("coordinates", JSONArray().also { a -> l.points.forEach { a.put(coord(it)) } }),
            JSONObject().put("mode", l.mode.name.lowercase()).put("color", modeHex(l.mode))))
        return JSONObject().put("type", "FeatureCollection").put("features", f).toString()
    }

    fun markersJson(m: RouteModel): String {
        val f = JSONArray()
        for (k in m.markers) f.put(feature(JSONObject().put("type", "Point").put("coordinates", coord(k.at)), JSONObject().put("icon", k.icon).put("label", k.label).put("seg", k.seg)))
        return JSONObject().put("type", "FeatureCollection").put("features", f).toString()
    }

    fun currentJson(m: RouteModel): String {
        val f = JSONArray()
        m.current?.let { f.put(feature(JSONObject().put("type", "Point").put("coordinates", coord(it)), JSONObject())) }
        return JSONObject().put("type", "FeatureCollection").put("features", f).toString()
    }
}
