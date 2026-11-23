package com.diegonmarcos.cloudcanvas.draw

/** File names of the exports, so a second export never overwrites the first. */
object ExportNames {
    fun png(epochSeconds: Long): String = "cloud-canvas-" + stamp(epochSeconds) + ".png"
    fun wav(epochSeconds: Long): String = "cloud-canvas-mix-" + stamp(epochSeconds) + ".wav"

    /** yyyyMMdd-HHmmss in UTC without java.time formatting state. */
    fun stamp(epochSeconds: Long): String {
        val days = Math.floorDiv(epochSeconds, 86400L)
        val sod = Math.floorMod(epochSeconds, 86400L).toInt()
        // civil from days (Howard Hinnant)
        val z = days + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = (z - era * 146097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        return "%04d%02d%02d-%02d%02d%02d".format(y, m, d, sod / 3600, (sod / 60) % 60, sod % 60)
    }
}
