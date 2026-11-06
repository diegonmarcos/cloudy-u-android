package com.diegonmarcos.cloudtoolssmallllms.core

import org.json.JSONObject

/**
 * One member of the sensor catalogue (tools/sensors dataset, `catalogue`): [kind] hardware | derived (a virtual sensor computed from others) | feature
 * (a capability, not a SensorManager sensor). It is present on a phone when ANY of its sources answers: a sensor of [type], a sensor whose name or
 * string type contains one of [match], the system [feature], or the [probe] (a manager read). [liveOnly]: the receiver is known but this member is only
 * confirmed by a live reading (a GNSS constellation needs a fix).
 */
data class CatalogueEntry(
    val id: String, val label: String, val kind: String, val type: Int?, val match: List<String>, val feature: String?, val probe: String?,
    val deprecated: Boolean, val liveOnly: Boolean, val note: String?,
)

data class CatalogueCategory(val id: String, val label: String, val entries: List<CatalogueEntry>)

/** What a probe read found: present, and its detail lines (one per camera, microphone, ...). */
data class ProbeResult(val available: Boolean, val lines: List<String> = emptyList())

/** The device as the catalogue asks about it: system features and manager probes (the app adapts PackageManager and the managers; a test fakes it). */
interface DeviceFacts {
    fun feature(name: String): Boolean
    fun probe(id: String): ProbeResult
}

/** One catalogue member resolved against this device: [sensors] are the SensorManager sensors that answer it (wake-up variants included). */
data class ResolvedEntry(val category: String, val entry: CatalogueEntry, val present: Boolean, val sensors: List<SensorRow>, val lines: List<String>)

data class ResolvedCategory(val id: String, val label: String, val entries: List<ResolvedEntry>) {
    val presentCount: Int get() = entries.count { it.present }
}

enum class Presence { ALL, PRESENT, ABSENT }

/** The List-all filters; every field null / ALL means "no filter". [reporting] is a Sensor reporting mode (0 continuous, 1 on change, 2 one-shot, 3 special). */
data class SensorFilter(
    val category: String? = null, val presence: Presence = Presence.ALL, val kind: String? = null, val wake: Boolean? = null,
    val reporting: Int? = null, val vendor: String? = null, val maxPowerMa: Float? = null, val text: String = "",
)

/** Android camera facts the role split needs (Camera2 characteristics, read by the app). */
data class CameraFacts(val front: Boolean, val focalMm: Float, val sensorWidthMm: Float, val sensorHeightMm: Float, val depth: Boolean)

/** wide / ultrawide / tele / front / depth: the roles a set of cameras fills, by 35 mm-equivalent focal length (thresholds are data). */
object CameraRoles {
    private const val FULL_FRAME_DIAGONAL_MM = 43.27f

    fun roles(cams: List<CameraFacts>, ultraMax: Float, wideMax: Float): Set<String> {
        val out = HashSet<String>()
        for (c in cams) {
            if (c.depth) out += "depth"
            if (c.front) { out += "front"; continue }
            val diag = Math.sqrt((c.sensorWidthMm * c.sensorWidthMm + c.sensorHeightMm * c.sensorHeightMm).toDouble()).toFloat()
            if (diag <= 0f || c.focalMm <= 0f) { if (!c.depth) out += "wide"; continue }
            val eq = c.focalMm * FULL_FRAME_DIAGONAL_MM / diag
            out += when { eq <= ultraMax -> "ultrawide"; eq <= wideMax -> "wide"; else -> "tele" }
        }
        return out
    }
}

/**
 * The sensor catalogue as data (a `catalogue` of categories and members, no sensor name in Kotlin) and its resolution against a device:
 * every member present or absent, every SensorManager sensor no member claims shown under [otherId] (so a vendor / OEM sensor, type >= the private
 * base, is never missed), the List-all filters.
 */
class SensorTree(
    val categories: List<CatalogueCategory>, val otherId: String, val otherLabel: String, val vendorBase: Int, private val derivedTypes: Set<Int>,
    val cameraUltraMax: Float, val cameraWideMax: Float,
) {
    /** The `kind` of a raw sensor row: derived when its type is a composite one, else hardware. */
    fun kindOf(r: SensorRow): String = if (r.raw.type in derivedTypes) "derived" else "hardware"

    /** Sensors that report an OEM / device-private type (>= the private base): all of them, claimed by a member or not. */
    fun vendorSensors(rows: List<SensorRow>): List<SensorRow> = rows.filter { it.raw.type >= vendorBase }

    private fun answers(e: CatalogueEntry, r: SensorRow): Boolean =
        (e.type != null && r.raw.type == e.type) || e.match.any { m -> r.raw.name.contains(m, true) || r.raw.stringType.contains(m, true) }

    fun resolve(rows: List<SensorRow>, facts: DeviceFacts): List<ResolvedCategory> {
        val claimed = HashSet<Int>()
        val out = categories.map { c ->
            ResolvedCategory(c.id, c.label, c.entries.map { e ->
                val sensors = rows.filter { answers(e, it) }
                sensors.forEach { claimed += it.raw.id }
                val viaFeature = e.feature?.let { facts.feature(it) } == true
                val probe = e.probe?.let { facts.probe(it) }
                ResolvedEntry(c.id, e, sensors.isNotEmpty() || viaFeature || probe?.available == true, sensors, probe?.lines.orEmpty())
            })
        }
        val other = rows.filter { it.raw.id !in claimed }.map { r ->
            val e = CatalogueEntry("sensor_${r.raw.id}", r.raw.name, kindOf(r), r.raw.type, emptyList(), null, null, false, false, null)
            ResolvedEntry(otherId, e, true, listOf(r), emptyList())
        }
        return out + ResolvedCategory(otherId, otherLabel, other)
    }

    /** The List-all rows: every member of every category (present and absent) that passes [f]; sensor-level filters need a sensor that passes. */
    fun filter(resolved: List<ResolvedCategory>, f: SensorFilter): List<ResolvedEntry> {
        val sensorLevel = f.wake != null || f.reporting != null || f.vendor != null || f.maxPowerMa != null
        fun sensorOk(s: SensorRow) =
            (f.wake == null || s.raw.wakeUp == f.wake) && (f.reporting == null || s.raw.reportingMode == f.reporting) &&
                (f.vendor == null || s.raw.vendor.equals(f.vendor, true)) && (f.maxPowerMa == null || s.raw.powerMa <= f.maxPowerMa)
        return resolved.filter { f.category == null || it.id == f.category }.flatMap { it.entries }.filter { e ->
            (f.presence == Presence.ALL || (f.presence == Presence.PRESENT) == e.present) &&
                (f.kind == null || e.entry.kind == f.kind) &&
                (!sensorLevel || e.sensors.any(::sensorOk)) &&
                (f.text.isBlank() || e.entry.label.contains(f.text, true) || e.sensors.any { it.raw.name.contains(f.text, true) || it.typeConst.contains(f.text, true) })
        }
    }

    /** Every vendor name among the sensors the phone reports (the Vendor filter's choices). */
    fun vendors(rows: List<SensorRow>): List<String> = rows.map { it.raw.vendor }.filter { it.isNotBlank() }.distinct().sorted()

    companion object {
        fun parse(root: JSONObject, vendorBase: Int): SensorTree {
            val cat = root.getJSONObject("catalogue")
            val cats = SensorCatalogue.objs(cat.getJSONArray("categories")).map { c ->
                CatalogueCategory(c.getString("id"), c.getString("label"), SensorCatalogue.objs(c.getJSONArray("entries")).map { e ->
                    val type = if (e.has("type")) e.getInt("type") else null
                    val match = SensorCatalogue.strs(e.optJSONArray("match"))
                    val feature = e.optString("feature").ifBlank { null }
                    val probe = e.optString("probe").ifBlank { null }
                    require(type != null || match.isNotEmpty() || feature != null || probe != null) { "catalogue entry ${e.getString("id")} has no way to be found" }
                    val kind = e.getString("kind")
                    require(kind in setOf("hardware", "derived", "feature")) { "catalogue entry ${e.getString("id")} has kind $kind" }
                    CatalogueEntry(e.getString("id"), e.getString("label"), kind, type, match, feature, probe, e.optBoolean("deprecated"), e.optBoolean("live_only"), e.optString("note").ifBlank { null })
                })
            }
            val ids = cats.flatMap { c -> c.entries.map { it.id } }
            require(ids.toSet().size == ids.size) { "duplicate catalogue entry id" }
            val other = cat.getJSONObject("other")
            val derived = (0 until root.getJSONArray("types").length()).map { root.getJSONArray("types").getJSONObject(it) }.filter { it.optBoolean("derived") }.map { it.getInt("id") }.toSet()
            val roles = cat.getJSONObject("camera_roles")
            return SensorTree(cats, other.getString("id"), other.getString("label"), vendorBase, derived, roles.getDouble("ultrawide_max_mm").toFloat(), roles.getDouble("wide_max_mm").toFloat())
        }
    }
}
