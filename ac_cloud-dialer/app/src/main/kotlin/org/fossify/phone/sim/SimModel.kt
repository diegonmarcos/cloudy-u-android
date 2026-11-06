package org.fossify.phone.sim

/**
 * What Android exposes about one SIM slot or eSIM profile, read by [SimReader] through [SimDetect]. Null = not exposed / no permission.
 * [active] false = an eSIM profile that is installed but not enabled, or a physical slot with no SIM (subscriptionId null).
 */
data class SimCard(
    val slot: Int,
    val subscriptionId: Int?,
    val carrier: String?,
    val displayName: String?,
    val mcc: String?,
    val mnc: String?,
    val country: String?,
    val number: String?,
    val iccid: String?,
    val embedded: Boolean,
    val roaming: Boolean?,
    val defaultData: Boolean,
    val defaultVoice: Boolean,
    val defaultSms: Boolean,
    val networkType: String?,
    val signalLevel: Int?,
    val eid: String? = null,
    val active: Boolean = true,
)

/** The fields Android cannot give, typed by the owner per SIM (schema: assets/sim-fields.json). Never holds PIN or PUK (see [SimManualStore]). */
data class SimManual(val values: Map<String, String> = emptyMap()) {
    operator fun get(id: String): String = values[id].orEmpty()
    val dataAllowance: String get() = get("dataAllowance")
    val renewalDate: String get() = get("renewalDate")
}

data class SimRow(val label: String, val value: String, val secret: Boolean = false)

object SimMask {
    /** Keeps the last [keep] characters, the rest becomes bullets. Short or empty values are fully masked. */
    fun mask(value: String?, keep: Int = 4): String {
        if (value.isNullOrEmpty()) return "-"
        if (value.length <= keep) return "•".repeat(value.length)
        return "•".repeat(value.length - keep) + value.takeLast(keep)
    }

    /** PIN/PUK: never a single digit shown. */
    fun secret(value: String?): String = if (value.isNullOrEmpty()) "-" else "•".repeat(value.length)
}

object SimFields {
    private fun v(s: String?): String = if (s.isNullOrBlank()) "-" else s
    private fun yn(b: Boolean?): String = when (b) { true -> "Yes"; false -> "No"; null -> "-" }
    private fun na(s: String?, needs: String): String = if (s.isNullOrBlank()) "n/a (needs $needs)" else s

    /**
     * The detected rows, in order. ICCID, subscription id and EID show their tail unless [reveal]. Whatever Android hides reads
     * "n/a (needs ...)". [m] only supplies the typed number when the platform hides it.
     */
    fun detected(c: SimCard, m: SimManual, reveal: Boolean = false): List<SimRow> {
        val number = c.number?.takeIf { it.isNotBlank() } ?: m["number"].takeIf { it.isNotBlank() }
        val defaults = listOfNotNull("Data".takeIf { c.defaultData }, "Voice".takeIf { c.defaultVoice }, "SMS".takeIf { c.defaultSms })
        val id: (String?, String) -> String = { s, needs -> if (s.isNullOrBlank()) "n/a (needs $needs)" else if (reveal) s else SimMask.mask(s) }
        val live = if (c.active) "Phone state" else "an active profile"
        return listOf(
            SimRow("Status", if (!c.active && c.subscriptionId == null) "Empty slot" else if (c.active) "Active" else "Inactive"),
            SimRow("Slot", if (c.slot >= 0) (c.slot + 1).toString() else "n/a (needs an active profile in a slot)"),
            SimRow("Type", if (c.embedded) "eSIM" else "Physical SIM"),
            SimRow("Carrier", na(c.carrier, if (c.subscriptionId == null) "a SIM" else "Phone state")),
            SimRow("Display name", v(c.displayName)),
            SimRow("MCC / MNC", if (c.mcc.isNullOrBlank()) "-" else "${c.mcc} / ${v(c.mnc)}"),
            SimRow("Country", v(c.country?.uppercase())),
            SimRow("Number", na(number, "Phone numbers permission or carrier privileges")),
            SimRow("ICCID", id(c.iccid, "carrier privileges"), secret = true),
            SimRow("Subscription id", id(c.subscriptionId?.toString(), "an active profile"), secret = true),
            SimRow("EID", id(c.eid, "an eSIM and carrier privileges"), secret = true),
            SimRow("Roaming", c.roaming?.let { yn(it) } ?: "n/a (needs $live)"),
            SimRow("Defaults", if (defaults.isEmpty()) "-" else defaults.joinToString(", ")),
            SimRow("Network type", na(c.networkType, live)),
            SimRow("Signal", c.signalLevel?.let { "$it/4" } ?: "n/a (needs $live)"),
        )
    }

    /** Stable key for a SIM's typed data: the ICCID when readable, else the subscription id, else the slot. */
    fun key(c: SimCard): String =
        c.iccid?.takeIf { it.isNotBlank() }?.let { "iccid_$it" } ?: c.subscriptionId?.let { "sub_$it" } ?: "slot_${c.slot}"

    /** The row title: type, slot and the name. */
    fun title(c: SimCard): String =
        "${if (c.embedded) "eSIM" else "SIM"}${if (c.slot >= 0) " ${c.slot + 1}" else ""} \u00b7 ${c.displayName ?: c.carrier ?: if (c.subscriptionId == null) "empty" else ""}".trim()
}
