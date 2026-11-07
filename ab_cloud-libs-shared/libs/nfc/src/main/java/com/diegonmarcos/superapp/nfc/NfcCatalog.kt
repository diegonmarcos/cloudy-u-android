package com.diegonmarcos.superapp.nfc

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import org.json.JSONArray
import org.json.JSONObject

data class ClassicKey(val name: String, val bytes: ByteArray)
data class DesfireName(val hwType: Int, val hwMajor: Int?, val name: String)

data class NxpVersion(val version: String, val name: String, val userPages: Int)

/** The declared NFC vocabulary (runtime dataset nfc-catalog, #1264/#1547). Unknown values are spelled raw, never an error. */
class NfcCatalog(
    val icManufacturers: Map<Int, String>,
    val sak: Map<Int, String>,
    val nxpVersions: List<NxpVersion>,
    val iso15693Manufacturers: Map<Int, String>,
    /** SAKs that mean MIFARE Classic (#1322), declared in the catalog. */
    val classicSaks: Set<Int> = emptySet(),
    /** The PUBLIC default sector keys tried on MIFARE Classic, in order (#1322). Nothing else is ever tried. */
    val classicKeys: List<ClassicKey> = emptyList(),
    val classicBudgetMs: Long = 20000,
    val madAids: Map<Int, String> = emptyMap(),
    val desfireSaks: Set<Int> = emptySet(),
    val desfireNames: List<DesfireName> = emptyList(),
) {
    fun isClassicSak(s: Int): Boolean = s in classicSaks
    fun isDesfireSak(s: Int): Boolean = s in desfireSaks
    fun madAid(aid: Int): String = madAids[aid] ?: "application 0x%04X (cluster 0x%02X, code 0x%02X)".format(aid, aid ushr 8, aid and 0xFF)
    /** Name for a DESFire hardware type / major version; an entry without hw_major matches any major. */
    fun desfireName(hwType: Int, hwMajor: Int): String? =
        (desfireNames.firstOrNull { it.hwType == hwType && it.hwMajor == hwMajor } ?: desfireNames.firstOrNull { it.hwType == hwType && it.hwMajor == null })?.name

    fun manufacturer(uid0: Int): String = icManufacturers[uid0] ?: "Unknown (0x%02X)".format(uid0)
    fun iso15693(code: Int): String = iso15693Manufacturers[code] ?: "Unknown (0x%02X)".format(code)
    fun sakName(s: Int): String = sak[s] ?: "SAK 0x%02X".format(s)
    fun version(v: ByteArray): NxpVersion? = Bytes.hex(v).let { h -> nxpVersions.firstOrNull { it.version.equals(h, true) } }

    companion object {
        /** The runtime dataset (shared/nfc-catalog, #1547), never an asset. */
        const val DATASET = "nfc-catalog"

        /** The served catalogue; null until Cloud Store has served it (a loading state, never a crash). */
        fun served(ctx: Context): NfcCatalog? = FleetData.get(ctx.applicationContext, DATASET) { parse(it) }

        fun load(ctx: Context): NfcCatalog = served(ctx) ?: parse("{}")

        fun parse(text: String): NfcCatalog {
            val o = JSONObject(text)
            fun hexMap(key: String): Map<Int, String> {
                val m = o.optJSONObject(key) ?: return emptyMap()
                return m.keys().asSequence().filterNot { it.startsWith("_") }.associate { (it.toIntOrNull(16) ?: error("bad hex key $it in $key")) to m.getString(it) }
            }
            fun strMap(key: String): Map<String, String> {
                val m = o.optJSONObject(key) ?: return emptyMap()
                return m.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { m.getString(it) }
            }
            fun rows(key: String): List<JSONObject> = o.optJSONArray(key).let { a: JSONArray? -> if (a == null) emptyList() else List(a.length()) { a.getJSONObject(it) } }
            val mc = o.optJSONObject("mifare_classic")
            val df = o.optJSONObject("desfire")
            fun hexSet(a: JSONArray?): Set<Int> = if (a == null) emptySet() else List(a.length()) { a.getString(it).toInt(16) }.toSet()
            val keys = mc?.optJSONArray("public_keys")?.let { a -> List(a.length()) { i -> a.getJSONObject(i).let { k -> ClassicKey(k.getString("name"), Bytes.parse(k.getString("hex"))?.takeIf { b -> b.size == 6 } ?: error("bad classic key ${k.getString("name")}")) } } } ?: emptyList()
            val aids = mc?.optJSONObject("mad_aids")?.let { m -> m.keys().asSequence().filterNot { it.startsWith("_") }.associate { it.toInt(16) to m.getString(it) } } ?: emptyMap()
            val names = df?.optJSONArray("versions")?.let { a -> List(a.length()) { i -> a.getJSONObject(i).let { r -> DesfireName(r.getString("hw_type").toInt(16), if (r.has("hw_major")) r.getString("hw_major").toInt(16) else null, r.getString("name")) } } } ?: emptyList()
            return NfcCatalog(
                icManufacturers = hexMap("ic_manufacturers"),
                sak = hexMap("sak"),
                nxpVersions = rows("nxp_versions").map { NxpVersion(it.getString("version").uppercase(), it.getString("name"), it.getInt("user_pages")) },
                iso15693Manufacturers = hexMap("iso15693_manufacturers"),
                classicSaks = hexSet(mc?.optJSONArray("saks")),
                classicKeys = keys,
                classicBudgetMs = mc?.optLong("time_budget_ms", 20000) ?: 20000,
                madAids = aids,
                desfireSaks = hexSet(df?.optJSONArray("saks")),
                desfireNames = names,
            )
        }
    }
}
