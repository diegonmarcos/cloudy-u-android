package com.diegonmarcos.clouddrive.manage

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Local Apps' two groups: Cloud Apps = the declared fleet apps, Phone Apps = everything else. */
enum class AppGroup { CLOUD, PHONE }

/** One group's Total row: [count] apps; each size sums the apps whose size is known ([unknown] = apps without a readable size, never counted as 0). */
data class AppTotals(val count: Int, val unknown: Int, val totalBytes: Long, val appBytes: Long, val dataBytes: Long, val cacheBytes: Long)

/**
 * The small API of the cached Local Apps aggregate: per group count, total, app, data and cache. Overview > Apps reads it with
 * [aggregate] (from the cache file, no scan) or observes [state]; every Local Apps refresh and cache write publishes here, so
 * refreshing Local Apps updates it.
 */
object LocalAppsApi {
    fun group(r: AppRow): AppGroup = if (r.fleetIcon != null) AppGroup.CLOUD else AppGroup.PHONE

    fun totals(rows: List<AppRow>): AppTotals = AppTotals(
        rows.size, rows.count { it.totalBytes == null },
        rows.sumOf { it.totalBytes ?: 0L }, rows.sumOf { it.appBytes ?: 0L }, rows.sumOf { it.dataBytes ?: 0L }, rows.sumOf { it.cacheBytes ?: 0L })

    fun aggregate(rows: List<AppRow>): Map<AppGroup, AppTotals> = AppGroup.entries.associateWith { g -> totals(rows.filter { group(it) == g }) }

    private val _state = MutableStateFlow<Map<AppGroup, AppTotals>?>(null)
    /** The latest aggregate published by a Local Apps read or cache write (null until one happened in this process). */
    val state: StateFlow<Map<AppGroup, AppTotals>?> get() = _state
    fun publish(s: AppSnapshot.Snapshot) { _state.value = aggregate(s.rows) }

    /** The cached aggregate from the cache file, or null when Local Apps has never been read. Cheap: no package scan. */
    fun aggregate(ctx: Context): Map<AppGroup, AppTotals>? =
        _state.value ?: LocalAppsCache(File(ctx.filesDir, "cloud-drive-cache/" + LocalAppsCache.FILE_NAME)).read()?.also { publish(it) }?.let { aggregate(it.rows) }
}
