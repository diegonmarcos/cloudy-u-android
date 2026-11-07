/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.help.data

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import org.json.JSONObject

/**
 * Loads the changelog from English-only markdown entries of the runtime dataset `changelog`
 * (#1547: served by Cloud Store through libs:fleet-data-client, repo
 * data/fleet/app/com.diegonmarcos.mediacenter/changelog.json; never baked into the APK):
 * `{schemaVersion, releases:[{versionCode, text}]}`. The integer version code
 * defines ordering; a small YAML-ish front-matter block in `text` carries the display
 * version, date and optional linked tip ids:
 *
 * ```
 * ---
 * version: 5.1.0
 * date: 2026-07-13
 * tips: tip_a, tip_b
 * ---
 * ## What's new in 5.1.0
 * ...markdown body...
 * ```
 *
 * A non-empty result is cached; while the dataset has not been served (first run, provider absent)
 * the list is empty, never cached, and the next call retries. Never throws.
 */
object ChangelogRepository {

    private const val DATASET = "changelog"

    @Volatile
    private var cache: List<ReleaseNotes>? = null

    /** All releases, newest (highest version code) first. */
    fun getAll(context: Context): List<ReleaseNotes> {
        cache?.let { return it }
        val releases = runCatching {
            FleetData.get(context.applicationContext, DATASET, own = true) { raw ->
                val arr = JSONObject(raw).getJSONArray("releases")
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    runCatching { parse(o.getInt("versionCode"), o.getString("text")) }.getOrNull()
                }
            }
        }.getOrNull().orEmpty().sortedByDescending { it.versionCode }
        if (releases.isNotEmpty()) cache = releases
        return releases
    }

    fun getCurrent(context: Context): ReleaseNotes? = getAll(context).firstOrNull()

    private fun parse(versionCode: Int, raw: String): ReleaseNotes {
        var version = ""
        var date = ""
        var tips = emptyList<String>()
        var body = raw.trim()

        if (body.startsWith("---")) {
            val end = body.indexOf("\n---", 3)
            if (end > 0) {
                val front = body.substring(3, end).trim()
                body = body.substring(end + 4).trim()
                front.lineSequence().forEach { line ->
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        when (key) {
                            "version" -> version = value
                            "date" -> date = value
                            "tips" -> tips = value.split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                        }
                    }
                }
            }
        }

        return ReleaseNotes(
            versionName = version.ifBlank { versionCode.toString() },
            versionCode = versionCode,
            releaseDate = date,
            markdown = body,
            tipIds = tips
        )
    }
}
