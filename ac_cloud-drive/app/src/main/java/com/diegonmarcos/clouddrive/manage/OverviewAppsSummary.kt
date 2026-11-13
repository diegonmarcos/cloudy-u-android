package com.diegonmarcos.clouddrive.manage

/** One row of the Overview > APPS table. A size column is null when no app of the group reported it ("not available"). */
data class AppsSumRow(val id: String, val total: Long?, val app: Long?, val data: Long?, val cache: Long?, val amount: Int)

/**
 * Overview > APPS: "Cloud Apps" (fleet apps: a package of the fleet registry, or a declared drive-apps tile) against "Phone Apps"
 * (every other installed app), summed from the rows of the Local Apps cached list; nothing is re-read here.
 */
object OverviewAppsSummary {
    const val CLOUD = "cloud"
    const val PHONE = "phone"

    fun summarize(rows: List<AppRow>, fleetPackages: Set<String>): List<AppsSumRow> {
        val (cloud, phone) = rows.partition { it.pkg in fleetPackages || it.fleetIcon != null }
        return listOf(row(CLOUD, cloud), row(PHONE, phone))
    }

    private fun sum(v: List<Long?>): Long? = v.filterNotNull().let { if (it.isEmpty()) null else it.sum() }

    private fun row(id: String, g: List<AppRow>): AppsSumRow {
        val app = sum(g.map { it.appBytes }); val data = sum(g.map { it.dataBytes }); val cache = sum(g.map { it.cacheBytes })
        val total = if (app == null && data == null && cache == null) null else (app ?: 0L) + (data ?: 0L) + (cache ?: 0L)
        return AppsSumRow(id, total, app, data, cache, g.size)
    }
}
