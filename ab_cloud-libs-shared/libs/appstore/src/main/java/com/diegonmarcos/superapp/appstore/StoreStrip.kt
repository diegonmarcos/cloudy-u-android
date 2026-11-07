package com.diegonmarcos.superapp.appstore

/**
 * The Cloud tab strip's ORDER as pure data (JVM-tested): the declared groups, then right after Tiny-ML (or where build.json's
 * `ventoy.after_group` puts it) Fleet Data, a "|" separator and Ventoy. A slot's kind is a group index (>= 0) or one of the KIND_* codes.
 */
object StoreStrip {
    const val KIND_VENTOY = -1
    const val KIND_FLEETDATA = -2
    const val KIND_SEPARATOR = -3
    const val SEPARATOR = "|"

    data class Slot(val label: String, val kind: Int)

    /** [ventoyBar] / [tinyMlBar] are strip indexes (-1 = absent); [fleetData] is true only on the provider-hosting Store. */
    fun slots(groups: List<String>, ventoyBar: Int, ventoyLabel: String, tinyMlBar: Int, fleetData: Boolean, fleetDataLabel: String): List<Slot> {
        val out = groups.mapIndexedTo(mutableListOf()) { i, l -> Slot(l, i) }
        val fleetAt = if (ventoyBar >= 0) ventoyBar else if (tinyMlBar >= 0) tinyMlBar + 1 else -1
        var at = ventoyBar
        if (fleetData && tinyMlBar >= 0 && fleetAt >= 0) { out.add(fleetAt, Slot(fleetDataLabel, KIND_FLEETDATA)); if (ventoyBar >= 0) at = ventoyBar + 1 }
        if (ventoyBar >= 0) { out.add(at, Slot(SEPARATOR, KIND_SEPARATOR)); out.add(at + 1, Slot(ventoyLabel, KIND_VENTOY)) }
        return out
    }
}
