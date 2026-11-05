package com.diegonmarcos.superapp.headless

import org.json.JSONArray
import org.json.JSONObject

/** #1498 the audit trail: every agent command is kept in a capped ring and shown in Domains > History tagged "agent". */
object HeadlessAudit {
    const val TAG = "agent"
    const val CAP = 500
    data class Entry(val ts: Long, val agent: String, val cmd: String, val url: String, val ok: Boolean)

    fun add(list: List<Entry>, e: Entry): List<Entry> = (list + e).takeLast(CAP)
    fun title(e: Entry): String = "[$TAG${if (e.agent.isBlank()) "" else ":" + e.agent}] ${e.cmd}${if (e.ok) "" else " (failed)"}"
    fun toJson(list: List<Entry>): String = JSONArray().also { a -> list.forEach { a.put(JSONObject().put("ts", it.ts).put("agent", it.agent).put("cmd", it.cmd).put("url", it.url).put("ok", it.ok)) } }.toString()
    fun fromJson(s: String?): List<Entry> = runCatching {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Entry(o.getLong("ts"), o.optString("agent"), o.optString("cmd"), o.optString("url"), o.optBoolean("ok", true)) } }
    }.getOrDefault(emptyList())
}
