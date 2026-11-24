package com.diegonmarcos.cloudnav.maps.takeout

import java.io.Reader
import java.time.OffsetDateTime

/**
 * #1887 a location history as KML. The Google Timeline converted to KML is a Document of Placemarks: a `<Point>` is a visit named HOME,
 * WORK, UNKNOWN, SEARCHED_ADDRESS, INFERRED_HOME, INFERRED_WORK or ALIASED_LOCATION; a `<LineString>` named Path is a move (with several
 * coordinates, one, or none at all); the times are "Start Time: ... End Time: ..." with their UTC offset in an HTML-escaped
 * `<description>`. A KML whose Placemarks carry a `<TimeSpan>` / `<TimeStamp>` (Cloud Nav's own KMZ export) reads the same way.
 * Streams: one Placemark at a time is held. Coordinates are KML's `lon,lat[,alt]`.
 */
object TakeoutKml {
    private val NAME = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
    private val DESC = Regex("<description>(.*?)</description>", RegexOption.DOT_MATCHES_ALL)
    private val COORDS = Regex("<coordinates>(.*?)</coordinates>", RegexOption.DOT_MATCHES_ALL)
    private val BEGIN = Regex("<begin>(.*?)</begin>", RegexOption.DOT_MATCHES_ALL)
    private val END = Regex("<end>(.*?)</end>", RegexOption.DOT_MATCHES_ALL)
    private val WHEN = Regex("<when>(.*?)</when>", RegexOption.DOT_MATCHES_ALL)
    private val START_T = Regex("Start Time:\\s*([0-9][0-9T:.+\\-Z]*)", RegexOption.IGNORE_CASE)
    private val END_T = Regex("End Time:\\s*([0-9][0-9T:.+\\-Z]*)", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val CDATA = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
    private val NUM_ENT = Regex("&#(x?[0-9a-fA-F]+);")
    private val WS = Regex("\\s+")
    private const val OPEN = "<Placemark"
    private const val CLOSE = "</Placemark>"

    /** The Timeline's visit types; HOME / WORK (and their inferred forms) name the place, the others leave it to the gazetteer. */
    val SEMANTIC = setOf("HOME", "WORK", "UNKNOWN", "SEARCHED_ADDRESS", "INFERRED_HOME", "INFERRED_WORK", "ALIASED_LOCATION")

    /** Parses one KML stream; returns {KML} when it held Placemarks (empty = not a KML location history, nothing emitted). */
    fun parse(reader: Reader, sink: TakeoutSink, onProgress: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }): Set<TakeoutFormat> {
        val buf = StringBuilder()
        val chunk = CharArray(64 * 1024)
        var consumed = 0L
        var any = false
        while (true) {
            val n = reader.read(chunk)
            if (n > 0) { buf.append(chunk, 0, n); consumed += n }
            while (true) {
                val s = buf.indexOf(OPEN)
                if (s < 0) { if (buf.length > 64) buf.delete(0, buf.length - 64); break }
                val e = buf.indexOf(CLOSE, s)
                if (e < 0) { if (s > 0) buf.delete(0, s); break }
                if (cancelled()) throw ImportCancelled()
                placemark(buf.substring(s + OPEN.length, e), sink)
                any = true
                buf.delete(0, e + CLOSE.length)
                onProgress(consumed)
            }
            if (n < 0) break
        }
        return if (any) setOf(TakeoutFormat.KML) else emptySet()
    }

    /** XML text to plain text: CDATA kept, entities decoded (twice for a doubly escaped description), tags dropped. */
    internal fun text(s: String): String {
        var t = CDATA.replace(s) { it.groupValues[1] }
        repeat(2) {
            t = NUM_ENT.replace(t) { m -> val v = m.groupValues[1]; runCatching { String(Character.toChars(if (v.startsWith("x")) v.drop(1).toInt(16) else v.toInt())) }.getOrDefault(m.value) }
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        }
        return TAG.replace(t, " ").replace(WS, " ").trim()
    }

    /** "lon,lat[,alt]" to (lat, lon); null when it is not a coordinate. */
    internal fun point(tok: String): Pair<Double, Double>? {
        val f = tok.split(',')
        if (f.size < 2) return null
        val lon = f[0].trim().toDoubleOrNull() ?: return null; val lat = f[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    private fun offsetMin(t: String?): Long? = t?.let { runCatching { OffsetDateTime.parse(it.trim()).offset.totalSeconds / 60L }.getOrNull() }

    /** HOME / WORK (and INFERRED_*) name the place; the other Timeline types leave it unnamed; any other name (an exported KML) is kept. */
    fun label(name: String?): String? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (n.uppercase()) {
            "HOME", "INFERRED_HOME" -> "Home"
            "WORK", "INFERRED_WORK" -> "Work"
            in SEMANTIC -> null
            else -> n
        }
    }

    internal fun placemark(body: String, sink: TakeoutSink) {
        val name = NAME.find(body)?.groupValues?.get(1)?.let(::text)
        val desc = DESC.find(body)?.groupValues?.get(1)?.let(::text)
        val st = desc?.let { START_T.find(it)?.groupValues?.get(1) } ?: BEGIN.find(body)?.groupValues?.get(1)?.trim() ?: WHEN.find(body)?.groupValues?.get(1)?.trim()
        val et = desc?.let { END_T.find(it)?.groupValues?.get(1) } ?: END.find(body)?.groupValues?.get(1)?.trim() ?: st
        val start = TkVal.ms(st); val end = TkVal.ms(et)
        if (start == null || end == null) { sink.skipped(TakeoutFormat.KML, "placemark without a start or end time"); return }
        val coordText = COORDS.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val pts = if (coordText.isEmpty()) emptyList() else coordText.split(WS).mapNotNull { point(it) }
        val move = body.contains("<LineString") || body.contains("<gx:Track") || (name != null && name.equals("Path", ignoreCase = true) && !body.contains("<Point"))
        val f = TakeoutFormat.KML.sourceTag
        val so = offsetMin(st); val eo = offsetMin(et)
        val raw = JsonOut.write(linkedMapOf<String, Any?>("name" to name, "description" to desc, "coordinates" to coordText.ifEmpty { null }))
        if (!move) {
            val p = pts.firstOrNull()
            sink.emit(TkTable.VISITS, mapOf("key" to "kml:v:$start:$end:${p?.first},${p?.second}", "format" to f, "start_ms" to start, "end_ms" to end,
                "start_offset_min" to so, "end_offset_min" to eo, "lat" to p?.first, "lon" to p?.second, "name" to label(name), "semantic_type" to name, "raw_json" to raw))
            return
        }
        val a = pts.firstOrNull(); val b = pts.lastOrNull()
        val type = name?.removePrefix("Move ")?.trim()?.takeIf { TakeoutDerive.mode(it) != null }
        sink.emit(TkTable.ACTIVITIES, mapOf("key" to "kml:p:$start:$end:${pts.size}:${a?.first},${a?.second}", "format" to f, "start_ms" to start, "end_ms" to end,
            "start_offset_min" to so, "end_offset_min" to eo, "start_lat" to a?.first, "start_lon" to a?.second, "end_lat" to b?.first, "end_lon" to b?.second,
            "activity_type" to type, "path_json" to (if (pts.size >= 2) JsonOut.write(pts.map { mapOf("point" to "${it.first},${it.second}") }) else null), "raw_json" to raw))
    }
}
