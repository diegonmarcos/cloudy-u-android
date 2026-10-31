package com.diegonmarcos.superapp.battery

/**
 * What only the fleet's privileged shell channel can read, for the battery
 * stats page and the per-cycle tracker: the gauge's full / design capacity and cycle count from sysfs
 * (SELinux keeps an app out), level / status / plug from `dumpsys battery`, and the per-app power since
 * charge from `dumpsys batterystats --charged` (BatteryStatsManager is system-only). One shell
 * round trip, [COMMAND]; [parse] is pure. Without the channel every field
 * stays null and the page prints "—".
 */
object BatteryPrivileged {

    private const val SPLIT = "--batterystats--"

    /** sysfs first (key=value lines), `dumpsys battery` ("key: value"), then the power-use block of batterystats. */
    const val COMMAND =
        "P=/sys/class/power_supply/battery; for f in charge_full charge_full_design cycle_count; do " +
            "echo \"\$f=\$(cat \$P/\$f 2>/dev/null)\"; done; dumpsys battery 2>/dev/null; echo $SPLIT; " +
            "dumpsys batterystats --charged 2>/dev/null | sed -n '/Estimated power use/,\$p' | head -200"

    data class App(val uid: Int, val mAh: Double)

    data class Facts(
        val fullMah: Int? = null,
        val designMah: Int? = null,
        val cycles: Int? = null,
        /** `dumpsys battery`: level 0..100, BatteryManager status code, any of AC/USB/Wireless powered. */
        val levelPct: Int? = null,
        val status: Int? = null,
        val plugged: Boolean? = null,
        /** batterystats' own "Capacity:" (the design capacity the framework uses). */
        val statsCapacityMah: Int? = null,
        /** Heaviest first; empty when the dump had no per-uid block. */
        val apps: List<App> = emptyList(),
    )

    private val UID = Regex("""^\s*uid\s+(u(\d+)a(\d+)|\d+):\s*([\d.]+)""", RegexOption.IGNORE_CASE)
    private val LEVEL = Regex("""(?m)^\s*level:\s*(\d+)""")
    private val STATUS = Regex("""(?m)^\s*status:\s*(\d+)""")
    private val POWERED = Regex("""(?m)^\s*(AC|USB|Wireless|Dock) powered:\s*(true|false)""")
    private val CAPACITY = Regex("""Capacity:\s*(\d+)""")

    fun parse(out: String?): Facts? {
        if (out.isNullOrBlank()) return null
        val sys = out.substringBefore(SPLIT)
        val stats = out.substringAfter(SPLIT, "")
        val kv = sys.lineSequence().mapNotNull { l ->
            val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
        }.toMap()
        val apps = stats.lineSequence().mapNotNull { line ->
            val m = UID.find(line) ?: return@mapNotNull null
            val token = m.groupValues[1]
            val uid = if (token.startsWith("u", ignoreCase = true))
                (m.groupValues[2].toIntOrNull() ?: 0) * 100_000 + 10_000 + (m.groupValues[3].toIntOrNull() ?: 0)
            else token.toIntOrNull() ?: return@mapNotNull null
            val mah = m.groupValues[4].toDoubleOrNull() ?: return@mapNotNull null
            App(uid, mah)
        }.groupBy { it.uid }.map { (uid, l) -> App(uid, l.sumOf { it.mAh }) }.sortedByDescending { it.mAh }
        return Facts(
            fullMah = mah(kv["charge_full"]), designMah = mah(kv["charge_full_design"]),
            cycles = kv["cycle_count"]?.toIntOrNull()?.takeIf { it >= 0 },
            levelPct = LEVEL.find(sys)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 },
            status = STATUS.find(sys)?.groupValues?.get(1)?.toIntOrNull(),
            plugged = POWERED.findAll(sys).toList().takeIf { it.isNotEmpty() }?.any { it.groupValues[2] == "true" },
            statsCapacityMah = CAPACITY.find(stats)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 300..30_000 },
            apps = apps,
        )
    }

    /** sysfs charge nodes are µAh on most kernels and mAh on a few: told apart by magnitude. */
    fun mah(raw: String?): Int? {
        val v = raw?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val m = if (v > 100_000L) (v / 1000L).toInt() else v.toInt()
        return m.takeIf { it in 300..30_000 }
    }
}
