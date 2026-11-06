package com.diegonmarcos.cloudtoolssmallllms.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** One sensor exactly as the platform reports it (android.hardware.Sensor), without the Android type so the model runs on a plain JVM. */
data class SensorRaw(
    val id: Int, val name: String, val vendor: String, val type: Int, val stringType: String, val version: Int,
    val powerMa: Float, val resolution: Float, val maxRange: Float, val minDelayUs: Int, val maxDelayUs: Int,
    val reportingMode: Int, val wakeUp: Boolean, val fifoMax: Int, val dynamic: Boolean,
)

/** Every sensor the phone has: SensorManager.getSensorList(TYPE_ALL) plus the dynamic ones (the app adapts SensorManager to this). */
fun interface SensorSource { fun sensors(): List<SensorRaw> }

/** One row of the Inbuilt page: the sensor with its type constant name, readable type name and reporting-mode name resolved from the data. */
data class SensorRow(val raw: SensorRaw, val typeConst: String, val typeLabel: String, val mode: String)

/** A non-SensorManager hardware item of the Inbuilt page (assets/sensors.json::hardware); [probe] picks how the app reads it. */
data class HardwareItem(
    val id: String, val label: String, val probe: String, val feature: String?, val sensorType: Int?,
    val match: List<String>, val forecastS: List<Int>,
)

/** The unit and label of a decoded GATT value key (assets/sensors.json::gatt_keys). */
data class GattKey(val label: String, val unit: String)

/**
 * assets/sensors.json read once: the sensor type table (constant name + readable name per Sensor.TYPE_*), the reporting modes, the
 * fallback names for vendor (OEM) and unknown types, the live-graph limits, the hardware list and the GATT value keys. No sensor,
 * hardware or type name is spelled in Kotlin.
 */
class SensorCatalogue private constructor(
    private val types: Map<Int, Pair<String, String>>,
    private val modes: Map<Int, String>,
    private val vendorBase: Int,
    private val vendor: Pair<String, String>,
    private val unknown: Pair<String, String>,
    private val modeFallback: String,
    val maxSeries: Int,
    val history: Int,
    val hardware: List<HardwareItem>,
    val gattKeys: Map<String, GattKey>,
    val connected: ConnectedCatalogue,
    val tree: SensorTree,
    val gnss: GnssDecl,
) {
    /** The constant name and readable name of a sensor type; an OEM type (>= the private base) or an unknown one falls back to the data's templates. */
    fun typeOf(type: Int, stringType: String = ""): Pair<String, String> {
        types[type]?.let { return it }
        val tpl = if (type >= vendorBase) vendor else unknown
        fun fill(s: String) = s.replace("{n}", type.toString()).replace("{offset}", (type - vendorBase).toString()).replace("{string_type}", stringType)
        return fill(tpl.first) to fill(tpl.second)
    }

    fun modeOf(mode: Int): String = modes[mode] ?: modeFallback.replace("{n}", mode.toString())

    /** Every sensor of the [source] once (by id), ordered by type then name; nothing is filtered out (wake-up, dynamic, uncalibrated, vendor). */
    fun rows(source: SensorSource): List<SensorRow> =
        source.sensors().distinctBy { it.id }.sortedWith(compareBy({ it.type }, { it.name }))
            .map { s -> typeOf(s.type, s.stringType).let { SensorRow(s, it.first, it.second, modeOf(s.reportingMode)) } }

    companion object {
        fun parse(json: String): SensorCatalogue {
            val o = JSONObject(json)
            val types = objs(o.getJSONArray("types")).associate { it.getInt("id") to (it.getString("const") to it.getString("label")) }
            val modes = objs(o.getJSONArray("reporting_modes")).associate { it.getInt("id") to it.getString("label") }
            val fb = o.getJSONObject("fallback")
            fun pair(k: String) = fb.getJSONObject(k).let { it.getString("const") to it.getString("label") }
            val live = o.getJSONObject("live")
            val hw = objs(o.getJSONArray("hardware")).map {
                HardwareItem(
                    it.getString("id"), it.getString("label"), it.getString("probe"), it.optString("feature").ifBlank { null },
                    if (it.has("sensor_type")) it.getInt("sensor_type") else null,
                    strs(it.optJSONArray("match")), (0 until (it.optJSONArray("forecast_s")?.length() ?: 0)).map { i -> it.getJSONArray("forecast_s").getInt(i) },
                )
            }
            val keys = o.getJSONObject("gatt_keys").let { k -> k.keys().asSequence().associateWith { id -> k.getJSONObject(id).let { GattKey(it.getString("label"), it.optString("unit")) } } }
            val g = o.getJSONObject("gnss")
            val gnss = GnssDecl(
                objs(g.getJSONArray("constellations")).associate { it.getInt("id") to it.getString("label") },
                objs(g.getJSONArray("bands")).map { Triple(it.getDouble("low_mhz"), it.getDouble("high_mhz"), it.getString("label")) },
            )
            return SensorCatalogue(types, modes, fb.getInt("vendor_base"), pair("vendor"), pair("unknown"), fb.getString("mode"),
                live.getInt("max_series"), live.getInt("history"), hw, keys, ConnectedCatalogue.parse(o.getJSONObject("connected")),
                SensorTree.parse(o, fb.getInt("vendor_base")), gnss)
        }

        internal fun objs(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
        internal fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    }
}

/** The GNSS names the live status shows (assets: tools/sensors::gnss): constellation id -> label, and the carrier bands as [lowMhz, highMhz, label]. */
data class GnssDecl(val constellations: Map<Int, String>, val bands: List<Triple<Double, Double, String>>) {
    fun band(hz: Float): String = bands.firstOrNull { hz / 1e6 in it.first..it.second }?.third.orEmpty()
}

/** A sensor number without trailing zeros ("0.0012", "1000", "-"), for the rows and the live values. */
object Num {
    fun short(f: Float): String {
        if (f.isNaN() || f.isInfinite()) return "-"
        return BigDecimal.valueOf(Math.round(f * 10000.0) / 10000.0).stripTrailingZeros().toPlainString()
    }
}
