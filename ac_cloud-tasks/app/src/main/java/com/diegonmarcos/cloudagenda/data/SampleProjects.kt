package com.diegonmarcos.cloudagenda.data

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/** One task of a sample project: [start] is a day offset from the trip's first day, [days] its length. */
data class SampleTask(val name: String, val start: Int, val days: Int, val location: String, val status: TaskStatus, val details: String)

data class SampleList(val name: String, val tasks: List<SampleTask>)

/** A project seeded once as sample data (assets/projects.json): the user can delete it, and it is never given back. */
data class SampleProject(val id: String, val name: String, val startOffset: Int, val lists: List<SampleList>)

/** The project data file: the sample projects and the per-project calendar configs. Pure parsing. */
object SampleProjects {
    const val ASSET = "projects.json"

    fun parse(json: String): List<SampleProject> {
        val arr = JSONObject(json).optJSONArray("samples") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            val lists = o.optJSONArray("lists")
            SampleProject(
                o.getString("id"), o.getString("name"), o.optInt("startOffset", 0),
                (0 until (lists?.length() ?: 0)).mapNotNull { lists?.optJSONObject(it) }.map { l ->
                    val ts = l.optJSONArray("tasks")
                    SampleList(l.getString("name"), (0 until (ts?.length() ?: 0)).mapNotNull { ts?.optJSONObject(it) }.map { t ->
                        SampleTask(t.getString("name"), t.optInt("start"), t.optInt("days", 1), t.optString("location"), TaskStatus.of(t.optString("status")), t.optString("details"))
                    })
                },
            )
        }
    }

    fun parseConfigs(json: String): ProjectConfigs {
        val o = JSONObject(json).optJSONObject("configs") ?: return ProjectConfigs.EMPTY
        return ProjectConfigs(o.keys().asSequence().associateWith { name ->
            val c = o.getJSONObject(name)
            val lanes = c.optJSONArray("lane_order")
            ProjectConfig(c.optString("calendar_mode").ifBlank { null }, (0 until (lanes?.length() ?: 0)).map { lanes!!.getString(it) })
        })
    }

    private fun read(context: Context): String = context.assets.open(ASSET).bufferedReader().use { it.readText() }

    fun load(context: Context): List<SampleProject> = runCatching { parse(read(context)) }.getOrDefault(emptyList())

    fun loadConfigs(context: Context): ProjectConfigs = runCatching { parseConfigs(read(context)) }.getOrDefault(ProjectConfigs.EMPTY)

    /** The first day of a sample's schedule when it is seeded on [today]. */
    fun firstDay(s: SampleProject, today: LocalDate): LocalDate = today.plusDays(s.startOffset.toLong())
}
