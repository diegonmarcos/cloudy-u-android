package com.diegonmarcos.superapp.bluetooth

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import org.json.JSONArray
import org.json.JSONObject

data class CatalogService(val uuid: String, val name: String, val alias: String, val category: String)
data class CatalogChar(val uuid: String, val name: String, val alias: String, val decoder: String, val unit: String)
data class CatalogFilter(val id: String, val label: String, val services: List<String>, val namePrefixes: List<String>)
data class CatalogProbe(val label: String, val hex: String, val note: String)

/**
 * The declared Bluetooth vocabulary (assets/bluetooth/gatt-catalog.json): names for services, characteristics and descriptors by
 * UUID, which [GattDecoders] decoder reads a characteristic, the scan filter presets and the Dev tab's test payloads. An UUID the
 * catalog does not hold is "Unknown", never an error.
 */
class GattCatalog(
    val services: List<CatalogService>,
    val characteristics: List<CatalogChar>,
    val descriptors: Map<String, String>,
    val categories: Map<String, String>,
    val filters: List<CatalogFilter>,
    val probes: List<CatalogProbe>,
) {
    private val svcByUuid = services.associateBy { it.uuid }
    private val chrByUuid = characteristics.associateBy { it.uuid }

    fun service(uuid: String): CatalogService? = svcByUuid[Uuids.normalize(uuid)]
    fun characteristic(uuid: String): CatalogChar? = chrByUuid[Uuids.normalize(uuid)]
    fun serviceName(uuid: String): String = service(uuid)?.name ?: "Unknown service"
    fun characteristicName(uuid: String): String = characteristic(uuid)?.name ?: "Unknown characteristic"
    fun descriptorName(uuid: String): String = descriptors[Uuids.normalize(uuid)] ?: "Unknown descriptor"
    fun categoryOf(uuid: String): String = service(uuid)?.category ?: "unknown"
    fun categoryLabel(id: String): String = categories[id] ?: "Other"
    fun decoderFor(charUuid: String): String = characteristic(charUuid)?.decoder.orEmpty()
    fun filter(id: String): CatalogFilter? = filters.firstOrNull { it.id == id }

    /**
     * A Web Bluetooth service name or UUID to the full UUID: a catalog alias (`heart_rate`), a short form (`0x180d`) or a full one.
     * Null when it is none of them (the polyfill answers TypeError).
     */
    fun resolveService(nameOrUuid: String): String? =
        Uuids.normalize(nameOrUuid) ?: services.firstOrNull { it.alias.isNotEmpty() && it.alias == nameOrUuid }?.uuid

    fun resolveCharacteristic(nameOrUuid: String): String? =
        Uuids.normalize(nameOrUuid) ?: characteristics.firstOrNull { it.alias.isNotEmpty() && it.alias == nameOrUuid }?.uuid

    /** What the device looks like, by the first catalog category among [uuids] that is a device family (not core / info / battery). */
    fun deviceCategory(uuids: List<String>): String {
        val cats = uuids.map { categoryOf(it) }
        return FAMILY_ORDER.firstOrNull { it in cats } ?: if (cats.any { it == "battery" || it == "info" }) "info" else "unknown"
    }

    companion object {
        /** The device families, most specific first. */
        val FAMILY_ORDER = listOf("ring", "hrm", "env", "cycling", "health", "uart", "dfu")
        /** The runtime dataset (shared/gatt-catalog, #1547), never an asset. */
        const val DATASET = "gatt-catalog"

        /** The served catalogue; null until Cloud Store has served it (a loading state, never a crash). */
        fun served(ctx: Context): GattCatalog? = FleetData.get(ctx.applicationContext, DATASET) { parse(it) }

        fun load(ctx: Context): GattCatalog = served(ctx) ?: parse("{}")

        fun parse(text: String): GattCatalog {
            val o = JSONObject(text)
            fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
            fun rows(key: String): List<JSONObject> = o.optJSONArray(key).let { a -> if (a == null) emptyList() else List(a.length()) { a.getJSONObject(it) } }
            return GattCatalog(
                services = rows("services").map {
                    CatalogService(Uuids.normalize(it.getString("uuid")) ?: error("bad service uuid ${it.getString("uuid")}"),
                        it.getString("name"), it.optString("alias", ""), it.optString("category", "unknown"))
                },
                characteristics = rows("characteristics").map {
                    CatalogChar(Uuids.normalize(it.getString("uuid")) ?: error("bad characteristic uuid ${it.getString("uuid")}"),
                        it.getString("name"), it.optString("alias", ""), it.optString("decoder", ""), it.optString("unit", ""))
                },
                descriptors = rows("descriptors").associate { (Uuids.normalize(it.getString("uuid")) ?: error("bad descriptor uuid")) to it.getString("name") },
                categories = rows("categories").associate { it.getString("id") to it.getString("label") },
                filters = rows("filters").map {
                    CatalogFilter(it.getString("id"), it.getString("label"),
                        strs(it.optJSONArray("services")).map { s -> Uuids.normalize(s) ?: error("bad filter service $s") },
                        strs(it.optJSONArray("namePrefixes")))
                },
                probes = rows("probes").map { CatalogProbe(it.getString("label"), it.getString("hex"), it.optString("note", "")) },
            )
        }
    }
}
