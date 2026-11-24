package com.diegonmarcos.cloudnav.maps.takeout

import java.io.Reader
import java.time.Instant
import java.time.OffsetDateTime

/** The three export families the importer reads. */
enum class TakeoutFormat(val label: String, val sourceTag: String, val rank: Int) {
    /** Legacy Takeout Records.json: raw positions. */
    RECORDS("Records.json", "takeout-records", 3),
    /** Legacy Takeout Semantic Location History/<year>/<YEAR_MONTH>.json: placeVisit and activitySegment. */
    SEMANTIC("Semantic Location History", "takeout-semantic", 2),
    /** The on-device Timeline export (Timeline.json, location-history.json): semanticSegments, rawSignals, userLocationProfile. */
    TIMELINE("Timeline export", "timeline-export", 1),
    /** #1887 a location history as KML (the Timeline converted to KML: Point visits, Path moves; Cloud Nav's own KMZ export): [TakeoutKml]. */
    KML("Timeline KML", "timeline-kml", 4),
}

/** Table names the parsers emit rows for; each maps 1:1 to a maps.db table (TakeoutSchema). */
object TkTable {
    const val FIXES = "fixes"
    const val VISITS = "imp_visits"
    const val CANDIDATES = "imp_candidates"
    const val ACTIVITIES = "imp_activities"
    const val SIGNALS = "imp_signals"
    const val PROFILE = "imp_profile"
}

/** Where parsed rows go: the database writer in the app, an in-memory list in a JVM test. Rows are column-name to value. */
interface TakeoutSink {
    fun emit(table: String, row: Map<String, Any?>)
    /** A record that could not be used (no time, no position): counted, never silently lost. */
    fun skipped(format: TakeoutFormat, why: String) {}
}

/** Timestamps and coordinates in every spelling the exports use. */
object TkVal {
    private val DIGITS = Regex("-?\\d+")
    private val NUM = Regex("-?\\d+(?:\\.\\d+)?")

    /** ISO-8601 with offset or Z, epoch milliseconds ("1551434400123", 13 digits) or epoch seconds; null when unreadable. */
    fun ms(s: String?): Long? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (DIGITS.matches(t)) { val v = t.toLong(); return if (t.trimStart('-').length >= 12) v else v * 1000 }
        return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(t, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]XX")).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(t).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    /** "52.5°, 13.4°", "geo:52.5,13.4" or "52.5,13.4" to (lat, lon). */
    fun latLng(s: String?): Pair<Double, Double>? {
        val m = NUM.findAll(s ?: return null).map { it.value.toDouble() }.toList()
        if (m.size < 2 || m[0] !in -90.0..90.0 || m[1] !in -180.0..180.0) return null
        return m[0] to m[1]
    }

    fun e7(v: Long?): Double? = v?.let { it / 1e7 }
}

/**
 * #1853 the three parsers. They stream: the top-level array or object is walked with [JsonPull] and only one record at a time is
 * materialised as a [Node] tree. Every Google field is read through the Node accessors (typed column or a JSON-text column) and the
 * whole record is also kept as `raw_json`, so a field Google adds later is not lost either. [FieldSpec] lists the paths mapped.
 */
object TakeoutParsers {
    /** Parses one JSON stream; returns the formats found in it (empty = not a location export, nothing emitted). */
    fun parse(reader: Reader, sink: TakeoutSink, t: Tracker = Tracker(), onProgress: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }): Set<TakeoutFormat> {
        val p = JsonPull(reader)
        val found = linkedSetOf<TakeoutFormat>()
        fun each(f: TakeoutFormat, body: (Node) -> Unit) {
            found += f
            p.readArray {
                if (cancelled()) throw ImportCancelled()
                body(Node(p.readValue(), "", t)); onProgress(p.consumed)
            }
        }
        when (p.peek()) {
            '['.code -> each(TakeoutFormat.TIMELINE) { segment(it, sink) }
            '{'.code -> p.readObject { name ->
                when (name) {
                    "locations" -> each(TakeoutFormat.RECORDS) { record(it, sink) }
                    "timelineObjects" -> each(TakeoutFormat.SEMANTIC) { timelineObject(it, sink) }
                    "semanticSegments" -> each(TakeoutFormat.TIMELINE) { segment(it, sink) }
                    "rawSignals" -> each(TakeoutFormat.TIMELINE) { rawSignal(Node(it.v, "rawSignals[]", t), sink) }
                    "userLocationProfile" -> { found += TakeoutFormat.TIMELINE; profile(Node(p.readValue(), "userLocationProfile", t), sink) }
                    else -> p.skipValue()
                }
            }
            else -> {}
        }
        return found
    }

    /** The record as raw JSON, only when it holds a field no mapper knows (the million-row Records and rawSignals are fully typed otherwise: no second copy). */
    private fun rawIfUnknown(n: Node, known: Set<String>, outer: Node? = null): String? =
        if (n.keys().all { it in known } && (outer == null || outer.keys().size <= 1)) null else JsonOut.write((outer ?: n).v)

    private val RECORD_KEYS = setOf("timestamp", "timestampMs", "latitudeE7", "longitudeE7", "accuracy", "altitude", "verticalAccuracy", "velocity", "heading", "source", "deviceTag",
        "platformType", "formFactor", "serverTimestamp", "deviceTimestamp", "batteryCharging", "osLevel", "placeId", "activity", "locationMetadata", "inferredLocation")
    private val POSITION_KEYS = setOf("LatLng", "latLng", "accuracyMeters", "altitudeMeters", "source", "speedMetersPerSecond", "timestamp")

    // ---------------------------------------------------------------- legacy Records.json

    internal fun record(n: Node, sink: TakeoutSink) {
        val lat = TkVal.e7(n["latitudeE7"].long()); val lon = TkVal.e7(n["longitudeE7"].long())
        val ts = TkVal.ms(n["timestamp"].str()) ?: TkVal.ms(n["timestampMs"].str())
        val acc = n["accuracy"].num(); val alt = n["altitude"].num(); val vacc = n["verticalAccuracy"].num()
        val vel = n["velocity"].num(); val head = n["heading"].num(); val src = n["source"].str(); val tag = n["deviceTag"].long()
        val extra = linkedMapOf<String, Any?>()
        for (k in listOf("platformType", "formFactor", "serverTimestamp", "deviceTimestamp", "osLevel", "placeId")) n[k].str()?.let { extra[k] = it }
        n["batteryCharging"].bool()?.let { extra["batteryCharging"] = it }
        for (k in listOf("locationMetadata", "inferredLocation")) n[k].json()?.let { extra[k] = it }
        val act = n["activity"].json()
        if (lat == null || lon == null || ts == null) { sink.skipped(TakeoutFormat.RECORDS, "position without time or coordinates"); return }
        sink.emit(TkTable.FIXES, mapOf("ikey" to "rec:$ts:${n["latitudeE7"].v}:${n["longitudeE7"].v}:${tag ?: ""}", "ts" to ts, "lat" to lat, "lon" to lon, "accuracy" to acc,
            "speed" to vel, "altitude" to alt, "vertical_acc" to vacc, "heading" to head, "provider" to TakeoutFormat.RECORDS.sourceTag, "g_source" to src,
            "device_tag" to tag, "activity_json" to act, "extra_json" to extra.takeIf { it.isNotEmpty() }?.let { JsonOut.write(it) },
            "source" to TakeoutFormat.RECORDS.sourceTag, "raw_json" to rawIfUnknown(n, RECORD_KEYS)))
    }

    // ---------------------------------------------------------------- legacy Semantic Location History

    private fun timelineObject(n: Node, sink: TakeoutSink) {
        val v = n["placeVisit"]; val a = n["activitySegment"]
        when {
            v.isObject -> visit(v, null, TakeoutFormat.SEMANTIC, sink)
            a.isObject -> activity(a, sink)
            else -> sink.skipped(TakeoutFormat.SEMANTIC, "timeline object that is neither placeVisit nor activitySegment")
        }
    }

    private fun place(n: Node): Map<String, Any?> = mapOf("lat" to TkVal.e7(n["latitudeE7"].long()), "lon" to TkVal.e7(n["longitudeE7"].long()), "place_id" to n["placeId"].str(),
        "address" to n["address"].str(), "name" to n["name"].str(), "semantic_type" to n["semanticType"].str(), "device_tag" to n["sourceInfo"]["deviceTag"].long(),
        "location_confidence" to n["locationConfidence"].any(), "calibrated_probability" to n["calibratedProbability"].num())

    /** A placeVisit (legacy, nested under `placeVisit`) or a child visit (same shape, directly an array element). */
    private fun visit(n: Node, parent: String?, f: TakeoutFormat, sink: TakeoutSink) {
        val loc = place(n["location"])
        val start = TkVal.ms(n["duration"]["startTimestamp"].str()) ?: TkVal.ms(n["duration"]["startTimestampMs"].str())
        val end = TkVal.ms(n["duration"]["endTimestamp"].str()) ?: TkVal.ms(n["duration"]["endTimestampMs"].str())
        val key = "v:$start:$end:${loc["place_id"] ?: "${loc["lat"]},${loc["lon"]}"}"
        if (start == null || end == null) { sink.skipped(f, "visit without a duration"); return }
        val ccLat = TkVal.e7(n["centerLatE7"].long()); val ccLon = TkVal.e7(n["centerLngE7"].long())
        val row = linkedMapOf<String, Any?>("key" to key, "format" to f.sourceTag, "parent_key" to parent, "start_ms" to start, "end_ms" to end,
            "place_confidence" to n["placeConfidence"].any(), "visit_confidence" to n["visitConfidence"].any(), "visit_type" to n["placeVisitType"].str(),
            "importance" to n["placeVisitImportance"].str(), "edit_status" to n["editConfirmationStatus"].str(), "center_lat" to ccLat, "center_lon" to ccLon,
            "last_edited_ms" to TkVal.ms(n["lastEditedTimestamp"].str()), "simplified_path_json" to n["simplifiedRawPath"].json())
        row += loc
        n["locationConfidence"].any()?.let { row["location_confidence"] = it }   // the visit-level one wins over the location's
        n["otherCandidateLocations"].items().forEachIndexed { i, c ->
            sink.emit(TkTable.CANDIDATES, place(c) + mapOf("visit_key" to key, "rank" to i.toLong(), "raw_json" to JsonOut.write(c.v)))
        }
        row["raw_json"] = JsonOut.write(n.v)
        sink.emit(TkTable.VISITS, row)
        n["childVisits"].items().forEach { visit(it, key, f, sink) }
    }

    private fun activity(n: Node, sink: TakeoutSink) {
        val s = n["startLocation"]; val e = n["endLocation"]; val d = n["duration"]
        val start = TkVal.ms(d["startTimestamp"].str()) ?: TkVal.ms(d["startTimestampMs"].str())
        val end = TkVal.ms(d["endTimestamp"].str()) ?: TkVal.ms(d["endTimestampMs"].str())
        val type = n["activityType"].str()
        val sl = place(s); val el = place(e)
        val probs = n["activities"].let { a -> a.items().forEach { it["activityType"].str(); it["probability"].num() }; a.json() }
        val row = linkedMapOf<String, Any?>("key" to "a:$start:$end:${type ?: ""}", "format" to TakeoutFormat.SEMANTIC.sourceTag, "start_ms" to start, "end_ms" to end,
            "start_lat" to sl["lat"], "start_lon" to sl["lon"], "start_place_id" to sl["place_id"], "start_address" to sl["address"], "start_name" to sl["name"], "start_device_tag" to sl["device_tag"],
            "end_lat" to el["lat"], "end_lon" to el["lon"], "end_place_id" to el["place_id"], "end_address" to el["address"], "end_name" to el["name"], "end_device_tag" to el["device_tag"],
            "distance_m" to n["distance"].num(), "activity_type" to type, "confidence" to n["confidence"].any(), "probabilities_json" to probs,
            "waypoints_json" to n["waypointPath"].json(), "simplified_path_json" to n["simplifiedRawPath"].json(), "transit_json" to n["transitPath"].json(),
            "parking_json" to n["parkingEvent"].json(), "edit_status" to n["editConfirmationStatus"].str(), "last_edited_ms" to TkVal.ms(n["lastEditedTimestamp"].str()),
            "raw_json" to JsonOut.write(n.v))
        if (start == null || end == null) { sink.skipped(TakeoutFormat.SEMANTIC, "activity without a duration"); return }
        sink.emit(TkTable.ACTIVITIES, row)
    }

    // ---------------------------------------------------------------- on-device Timeline export

    private fun ll(n: Node): Pair<Double, Double>? = TkVal.latLng(if (n.isObject) n["latLng"].str() else n.str())

    private fun segment(n: Node, sink: TakeoutSink) {
        val start = TkVal.ms(n["startTime"].str()); val end = TkVal.ms(n["endTime"].str())
        val so = n["startTimeTimezoneUtcOffsetMinutes"].long(); val eo = n["endTimeTimezoneUtcOffsetMinutes"].long()
        if (start == null || end == null) { sink.skipped(TakeoutFormat.TIMELINE, "segment without startTime or endTime"); return }
        val f = TakeoutFormat.TIMELINE.sourceTag
        val v = n["visit"]; val a = n["activity"]; val path = n["timelinePath"]
        val memory = n["timelineMemory"].json(); val pathJson = path.json()
        if (v.isObject) {
            val c = v["topCandidate"]; val pl = ll(c["placeLocation"])
            val placeId = c["placeId"].str()
            sink.emit(TkTable.VISITS, mapOf("key" to "v:$start:$end:${placeId ?: "${pl?.first},${pl?.second}"}", "format" to f, "start_ms" to start, "end_ms" to end,
                "start_offset_min" to so, "end_offset_min" to eo, "lat" to pl?.first, "lon" to pl?.second, "place_id" to placeId, "semantic_type" to c["semanticType"].str(),
                "location_confidence" to c["probability"].any(), "probability" to v["probability"].num(), "hierarchy_level" to v["hierarchyLevel"].long(),
                "timeless" to v["isTimelessVisit"].bool()?.let { if (it) 1L else 0L }, "extra_json" to memory, "simplified_path_json" to pathJson, "raw_json" to JsonOut.write(n.v)))
        }
        if (a.isObject) {
            val s = ll(a["start"]); val e = ll(a["end"]); val c = a["topCandidate"]; val type = c["type"].str()
            sink.emit(TkTable.ACTIVITIES, mapOf("key" to "a:$start:$end:${type ?: ""}", "format" to f, "start_ms" to start, "end_ms" to end, "start_offset_min" to so, "end_offset_min" to eo,
                "start_lat" to s?.first, "start_lon" to s?.second, "end_lat" to e?.first, "end_lon" to e?.second, "distance_m" to a["distanceMeters"].num(),
                "activity_type" to type, "confidence" to c["probability"].any(), "probabilities_json" to a["probability"].num()?.let { JsonOut.write(mapOf("probability" to it)) },
                "path_json" to pathJson, "memory_json" to memory, "raw_json" to JsonOut.write(n.v)))
        } else if (path.isList && !v.isObject) {
            sink.emit(TkTable.ACTIVITIES, mapOf("key" to "p:$start:$end", "format" to f, "start_ms" to start, "end_ms" to end, "start_offset_min" to so, "end_offset_min" to eo,
                "path_json" to pathJson, "memory_json" to memory, "raw_json" to JsonOut.write(n.v)))
        } else if (!v.isObject) sink.skipped(TakeoutFormat.TIMELINE, "segment with neither visit, activity nor timelinePath")
    }

    private fun rawSignal(n: Node, sink: TakeoutSink) {
        val pos = n["position"]; val act = n["activityRecord"]; val wifi = n["wifiScan"]
        if (pos.isObject) {
            val p = TkVal.latLng(pos["LatLng"].str() ?: pos["latLng"].str()); val ts = TkVal.ms(pos["timestamp"].str())
            val acc = pos["accuracyMeters"].num(); val alt = pos["altitudeMeters"].num(); val src = pos["source"].str(); val spd = pos["speedMetersPerSecond"].num()
            if (p == null || ts == null) { sink.skipped(TakeoutFormat.TIMELINE, "raw position without time or coordinates"); return }
            sink.emit(TkTable.FIXES, mapOf("ikey" to "sig:$ts:${"%.6f".format(java.util.Locale.ROOT, p.first)}:${"%.6f".format(java.util.Locale.ROOT, p.second)}", "ts" to ts, "lat" to p.first, "lon" to p.second, "accuracy" to acc, "speed" to spd,
                "altitude" to alt, "provider" to TakeoutFormat.TIMELINE.sourceTag, "g_source" to src, "source" to TakeoutFormat.TIMELINE.sourceTag, "raw_json" to rawIfUnknown(pos, POSITION_KEYS, n)))
        } else if (act.isObject) {
            val ts = TkVal.ms(act["timestamp"].str()); val j = act["probableActivities"].json()
            if (ts == null) { sink.skipped(TakeoutFormat.TIMELINE, "activityRecord without time"); return }
            sink.emit(TkTable.SIGNALS, mapOf("key" to "act:$ts", "ts" to ts, "kind" to "activityRecord", "json" to j, "raw_json" to rawIfUnknown(act, setOf("probableActivities", "timestamp"), n)))
        } else if (wifi.isObject) {
            val ts = TkVal.ms(wifi["deliveryTime"].str()); val j = wifi["devicesRecords"].json()
            if (ts == null) { sink.skipped(TakeoutFormat.TIMELINE, "wifiScan without time"); return }
            sink.emit(TkTable.SIGNALS, mapOf("key" to "wifi:$ts", "ts" to ts, "kind" to "wifiScan", "json" to j, "raw_json" to rawIfUnknown(wifi, setOf("deliveryTime", "devicesRecords"), n)))
        } else {
            val ts = n.keys().firstNotNullOfOrNull { k -> TkVal.ms((n[k]["timestamp"].v as? String)) }
            sink.emit(TkTable.SIGNALS, mapOf("key" to "sig:${JsonOut.write(n.v).hashCode()}", "ts" to (ts ?: 0L), "kind" to (n.keys().firstOrNull() ?: "unknown"), "json" to n.json(), "raw_json" to JsonOut.write(n.v)))
        }
    }

    private fun profile(n: Node, sink: TakeoutSink) {
        for (k in n.keys()) sink.emit(TkTable.PROFILE, mapOf("k" to k, "json" to n[k].json()))
    }
}

/** Thrown by the parsers and the importer when the owner cancels; rows already written stay (a re-import resumes, keys are stable). */
class ImportCancelled : RuntimeException("import cancelled")
