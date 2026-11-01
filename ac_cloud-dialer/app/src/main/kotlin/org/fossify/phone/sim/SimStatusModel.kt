package org.fossify.phone.sim

/** One live status snapshot of a SIM, read by [SimStatusReader]. Null = not exposed. */
data class SimStatus(
    val slot: Int,
    val label: String,
    val serviceState: Int?,
    val operator: String?,
    val rat: String?,
    val dbm: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val cellId: String?,
    val dataState: Int?,
    val ipv4: List<String>,
    val ipv6: List<String>,
    val dns: List<String>,
    val apn: String?,
    val roaming: Boolean?,
    val smsCapable: Boolean?,
    val callState: Int?,
    val volte: Boolean?,
    val vowifi: Boolean?,
    val monthBytes: Long?,
)

object StatusFormat {
    fun service(s: Int?): String = when (s) {
        0 -> "In service"; 1 -> "Out of service"; 2 -> "Emergency only"; 3 -> "Radio off"; null -> "-"; else -> "Unknown ($s)"
    }

    fun data(s: Int?): String = when (s) {
        0 -> "Disconnected"; 1 -> "Connecting"; 2 -> "Connected"; 3 -> "Suspended"; 4 -> "Disconnecting"; null -> "-"; else -> "Unknown ($s)"
    }

    fun call(s: Int?): String = when (s) { 0 -> "Idle"; 1 -> "Ringing"; 2 -> "Off hook"; null -> "-"; else -> "Unknown ($s)" }

    /** TelephonyManager.NETWORK_TYPE_* to a short RAT name. */
    fun rat(t: Int?): String = when (t) {
        null, 0 -> "-"
        1, 2, 4, 7, 11, 16 -> "2G"
        3, 5, 6, 8, 9, 10, 12, 14, 15, 17 -> "3G"
        13 -> "LTE"
        18 -> "IWLAN"
        19 -> "LTE-CA"
        20 -> "5G NR"
        else -> "Type $t"
    }

    private const val UNAVAILABLE = Int.MAX_VALUE

    fun dbm(v: Int?, unit: String = "dBm"): String = if (v == null || v == UNAVAILABLE) "-" else "$v $unit"

    fun signal(s: SimStatus): String =
        listOfNotNull(
            s.dbm?.takeIf { it != UNAVAILABLE }?.let { "$it dBm" },
            s.rsrp?.takeIf { it != UNAVAILABLE }?.let { "RSRP $it dBm" },
            s.rsrq?.takeIf { it != UNAVAILABLE }?.let { "RSRQ $it dB" },
            s.sinr?.takeIf { it != UNAVAILABLE }?.let { "SINR $it dB" },
        ).joinToString(" · ").ifEmpty { "-" }

    fun bytes(b: Long?): String {
        if (b == null || b < 0) return "-"
        val u = listOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "$b B" else String.format(java.util.Locale.ROOT, "%.1f %s", v, u[i])
    }

    fun list(l: List<String>): String = if (l.isEmpty()) "-" else l.joinToString(", ")
    fun yn(b: Boolean?): String = when (b) { true -> "Yes"; false -> "No"; null -> "-" }

    fun rows(s: SimStatus): List<SimRow> = listOf(
        SimRow("Service", service(s.serviceState)),
        SimRow("Operator", s.operator?.ifBlank { null } ?: "-"),
        SimRow("RAT", s.rat ?: "-"),
        SimRow("Signal", signal(s)),
        SimRow("Cell id", s.cellId ?: "-"),
        SimRow("Data", data(s.dataState)),
        SimRow("IPv4", list(s.ipv4)),
        SimRow("IPv6", list(s.ipv6)),
        SimRow("DNS", list(s.dns)),
        SimRow("APN", s.apn ?: "-"),
        SimRow("Roaming", yn(s.roaming)),
        SimRow("SMS capable", yn(s.smsCapable)),
        SimRow("Call state", call(s.callState)),
        SimRow("VoLTE", yn(s.volte)),
        SimRow("VoWiFi", yn(s.vowifi)),
        SimRow("Data this month", bytes(s.monthBytes)),
    )
}
