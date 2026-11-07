package com.diegonmarcos.superapp.wifi

/**
 * The vendor of a MAC address from the IEEE MA-L registry (pack `wifi-oui`, data/fleet/packs/wifi-oui.tsv, #1547: `<6 hex>\t<organization>`, sorted) (#1263).
 * A locally administered BSSID (bit 0x02 of the first octet) is usually a virtual BSSID the AP derived from its own OUI, so a miss is
 * retried with that bit cleared and reported as `<vendor> (virtual)`. Binary search over a sorted int array: ~40k entries, no map.
 */
class OuiTable private constructor(private val keys: IntArray, private val names: Array<String>) {
    val size: Int get() = keys.size

    fun lookup(mac: String): String? {
        val p = prefix(mac) ?: return null
        exact(p)?.let { return it }
        if (p and 0x020000 != 0) exact(p and 0x020000.inv())?.let { return "$it (virtual)" }
        return null
    }

    /** The label the UI shows: the vendor, `Private (randomized)` for an unknown local MAC, else `Unknown`. */
    fun label(mac: String): String = lookup(mac) ?: if (isLocallyAdministered(mac)) "Private (randomized)" else "Unknown"

    private fun exact(p: Int): String? { val i = keys.binarySearch(p); return if (i >= 0) names[i] else null }

    companion object {
        val EMPTY = OuiTable(IntArray(0), emptyArray())

        fun parse(text: String): OuiTable {
            val pairs = ArrayList<Pair<Int, String>>(45000)
            text.lineSequence().forEach { line ->
                if (line.length < 8 || line[0] == '#') return@forEach
                val tab = line.indexOf('\t'); if (tab != 6) return@forEach
                val k = line.substring(0, 6).toIntOrNull(16) ?: return@forEach
                pairs += k to line.substring(7).trim()
            }
            pairs.sortBy { it.first }
            return OuiTable(IntArray(pairs.size) { pairs[it].first }, Array(pairs.size) { pairs[it].second })
        }

        /** The 24-bit OUI of `aa:bb:cc:..`, `AA-BB-CC-..` or `aabbcc..`; null when it is not a MAC. */
        fun prefix(mac: String): Int? {
            val hex = mac.filter { it.isLetterOrDigit() }
            if (hex.length < 6) return null
            return hex.substring(0, 6).toIntOrNull(16)
        }

        fun isLocallyAdministered(mac: String): Boolean = prefix(mac)?.let { it and 0x020000 != 0 } ?: false
        fun isMulticast(mac: String): Boolean = prefix(mac)?.let { it and 0x010000 != 0 } ?: false
    }
}
