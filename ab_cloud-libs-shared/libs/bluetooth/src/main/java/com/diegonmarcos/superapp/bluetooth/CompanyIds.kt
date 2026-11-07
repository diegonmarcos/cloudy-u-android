package com.diegonmarcos.superapp.bluetooth

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import org.json.JSONObject

/**
 * The declared vendor vocabulary (assets/bluetooth/company-ids.json, #1264): SIG company identifiers, Apple continuity message
 * types and well-known service-data UUIDs. Unknown ids are spelled in hex, never an error.
 */
class CompanyIds(val companies: Map<Int, String>, val appleTypes: Map<Int, String>, val serviceData: Map<String, String>) {
    fun name(id: Int): String = companies[id] ?: "0x%04X".format(id)
    fun known(id: Int): Boolean = id in companies

    /** What an Apple manufacturer payload is, by its continuity type byte; null for another company or an unknown type. */
    fun appleType(company: Int, data: ByteArray): String? =
        if (company == AdvParser.APPLE && data.isNotEmpty()) appleTypes[data[0].toInt() and 0xFF] else null

    fun serviceDataName(uuid: String): String? = Uuids.assigned(uuid)?.let { serviceData[Uuids.short(uuid).lowercase()] }

    companion object {
        /** The runtime dataset (shared/company-ids, #1547), never an asset. */
        const val DATASET = "company-ids"

        /** The served table; null until Cloud Store has served it (a loading state, never a crash). */
        fun served(ctx: Context): CompanyIds? = FleetData.get(ctx.applicationContext, DATASET) { parse(it) }

        fun load(ctx: Context): CompanyIds = served(ctx) ?: parse("{}")

        fun parse(text: String): CompanyIds {
            val o = JSONObject(text)
            fun hexMap(key: String): Map<Int, String> {
                val m = o.optJSONObject(key) ?: return emptyMap()
                return m.keys().asSequence().associate { (it.toIntOrNull(16) ?: error("bad hex id $it in $key")) to m.getString(it) }
            }
            val sd = o.optJSONObject("service_data")
            return CompanyIds(hexMap("companies"), hexMap("apple_continuity"),
                sd?.keys()?.asSequence()?.associate { it.lowercase() to sd.getString(it) }.orEmpty())
        }
    }
}
