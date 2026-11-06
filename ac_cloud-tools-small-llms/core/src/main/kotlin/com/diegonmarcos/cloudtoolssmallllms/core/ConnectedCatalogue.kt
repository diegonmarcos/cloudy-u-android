package com.diegonmarcos.cloudtoolssmallllms.core

import org.json.JSONObject

/** One standard characteristic to read on a standard GATT profile: [parser] is a GattParse parser id, [mode] "notify" or "read". */
data class GattRead(val char: String, val parser: String, val mode: String)

/**
 * One declared external sensor class (assets/sensors.json::connected.profiles): how it connects ([connection]), the GATT service
 * [uuid] (normalized 128-bit) and / or the USB class, a vendor [manufacturerId] with its [manufacturerPrefix] bytes (iBeacon),
 * what it is typically used for and the standard characteristics to read.
 */
data class ConnectedProfile(
    val id: String, val label: String, val category: String, val connection: String, val uuid: String?, val usbClass: Int?,
    val manufacturerId: Int?, val manufacturerPrefix: ByteArray, val typicalUse: String, val reads: List<GattRead>,
) {
    val ble: Boolean get() = uuid != null || manufacturerId != null
    val usb: Boolean get() = usbClass != null
}

/** The Connected page's declaration: the profiles and the scan timeout. A profile with no valid UUID and no valid USB class is an error. */
class ConnectedCatalogue(val scanTimeoutMs: Long, val profiles: List<ConnectedProfile>) {
    /** The profiles a BLE advertisement matches: by an advertised service UUID, or by manufacturer data (id and prefix). */
    fun bleMatches(serviceUuids: Collection<String>, manufacturerData: Map<Int, ByteArray>): List<ConnectedProfile> {
        val have = serviceUuids.mapNotNull { Uuid.normalize(it) }.toSet()
        return profiles.filter { p ->
            (p.uuid != null && p.uuid in have) ||
                (p.manufacturerId != null && manufacturerData[p.manufacturerId]?.let { d -> d.size >= p.manufacturerPrefix.size && p.manufacturerPrefix.indices.all { d[it] == p.manufacturerPrefix[it] } } == true)
        }
    }

    /** The profiles a USB device matches by any of its device or interface classes. */
    fun usbMatches(classes: Collection<Int>): List<ConnectedProfile> = profiles.filter { it.usbClass != null && it.usbClass in classes }

    companion object {
        fun parse(o: JSONObject): ConnectedCatalogue {
            val ps = SensorCatalogue.objs(o.getJSONArray("profiles")).map { p ->
                val id = p.getString("id")
                val uuid = if (p.has("uuid")) requireNotNull(Uuid.normalize(p.getString("uuid"))) { "profile $id has an invalid UUID" } else null
                val cls = if (p.has("usb_class")) p.getInt("usb_class").also { require(it in 0..255) { "profile $id has an invalid USB class" } } else null
                val mfr = if (p.has("manufacturer_id")) p.getInt("manufacturer_id") else null
                require(uuid != null || cls != null) { "profile $id declares neither a GATT service UUID nor a USB class" }
                val prefix = p.optString("manufacturer_prefix").let { h -> ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
                val reads = p.optJSONArray("reads")?.let { a -> SensorCatalogue.objs(a).map { GattRead(requireNotNull(Uuid.normalize(it.getString("char"))), it.getString("parser"), it.getString("mode")) } }.orEmpty()
                ConnectedProfile(id, p.getString("label"), p.getString("category"), p.getString("connection"), uuid, cls, mfr, prefix, p.getString("typical_use"), reads)
            }
            require(ps.map { it.id }.toSet().size == ps.size) { "duplicate connected profile id" }
            return ConnectedCatalogue(o.getLong("scan_timeout_ms"), ps)
        }
    }
}

/** Bluetooth UUIDs: 16-bit ("180D", "0x180D"), 32-bit and 128-bit forms, normalized to the lower-case 128-bit string. */
object Uuid {
    private const val BASE = "-0000-1000-8000-00805f9b34fb"
    private val hex = Regex("^[0-9a-fA-F]+$")
    private val full = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun normalize(s: String): String? {
        val t = s.trim().removePrefix("0x").removePrefix("0X")
        return when {
            full.matches(t) -> t.lowercase()
            hex.matches(t) && t.length == 4 -> "0000${t.lowercase()}$BASE"
            hex.matches(t) && t.length == 8 -> "${t.lowercase()}$BASE"
            else -> null
        }
    }

    /** "0x180D" for a Bluetooth-SIG 16-bit UUID, else the full string. */
    fun short(full: String): String = if (full.startsWith("0000") && full.endsWith(BASE)) "0x" + full.substring(4, 8).uppercase() else full
}
