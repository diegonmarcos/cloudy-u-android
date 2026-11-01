package org.fossify.phone.sim

/** What Android exposes about one SIM slot (physical or eSIM), read by [SimReader]. Null = not exposed / no permission. */
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
)

/** The fields Android cannot give, typed by the owner per SIM (keyed by ICCID or slot). PIN/PUK live only in [SimManualStore]. */
data class SimManual(
    val provider: String = "",
    val plan: String = "",
    val monthlyPrice: String = "",
    val currency: String = "",
    val dataAllowance: String = "",
    val renewalDate: String = "",
    val number: String = "",
    val pin: String = "",
    val puk: String = "",
    val apnNotes: String = "",
    val support: String = "",
)

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
    const val MANUAL_KEYS = "provider,plan,monthlyPrice,currency,dataAllowance,renewalDate,number,pin,puk,apnNotes,support"
    val SECRET_KEYS: Set<String> = setOf("pin", "puk")

    private fun v(s: String?): String = if (s.isNullOrBlank()) "-" else s
    private fun yn(b: Boolean?): String = when (b) { true -> "Yes"; false -> "No"; null -> "-" }

    /** The card rows, in order. ICCID, subscription id and EID are masked unless [reveal]; PIN/PUK always masked here. */
    fun rows(c: SimCard, m: SimManual, reveal: Boolean = false): List<SimRow> {
        val number = c.number?.takeIf { it.isNotBlank() } ?: m.number.takeIf { it.isNotBlank() }
        val defaults = listOfNotNull("Data".takeIf { c.defaultData }, "Voice".takeIf { c.defaultVoice }, "SMS".takeIf { c.defaultSms })
        val id: (String?) -> String = { if (reveal) v(it) else SimMask.mask(it) }
        return listOf(
            SimRow("Slot", (c.slot + 1).toString()),
            SimRow("Type", if (c.embedded) "eSIM" else "Physical SIM"),
            SimRow("Carrier", v(c.carrier)),
            SimRow("Display name", v(c.displayName)),
            SimRow("MCC / MNC", if (c.mcc.isNullOrBlank()) "-" else "${c.mcc} / ${v(c.mnc)}"),
            SimRow("Country", v(c.country?.uppercase())),
            SimRow("Number", v(number)),
            SimRow("ICCID", id(c.iccid), secret = true),
            SimRow("Subscription id", id(c.subscriptionId?.toString()), secret = true),
            SimRow("EID", id(c.eid), secret = true),
            SimRow("Roaming", yn(c.roaming)),
            SimRow("Defaults", if (defaults.isEmpty()) "-" else defaults.joinToString(", ")),
            SimRow("Network type", v(c.networkType)),
            SimRow("Signal", c.signalLevel?.let { "$it/4" } ?: "-"),
            SimRow("Provider", v(m.provider)),
            SimRow("Plan", v(m.plan)),
            SimRow("Monthly price", if (m.monthlyPrice.isBlank()) "-" else "${m.monthlyPrice} ${m.currency}".trim()),
            SimRow("Data allowance", v(m.dataAllowance)),
            SimRow("Renewal date", v(m.renewalDate)),
            SimRow("PIN", SimMask.secret(m.pin), secret = true),
            SimRow("PUK", SimMask.secret(m.puk), secret = true),
            SimRow("APN notes", v(m.apnNotes)),
            SimRow("Support contact", v(m.support)),
        )
    }

    /** Stable key for a SIM's manual data: the ICCID when readable, else the slot. */
    fun key(c: SimCard): String = c.iccid?.takeIf { it.isNotBlank() }?.let { "iccid_$it" } ?: "slot_${c.slot}"
}
