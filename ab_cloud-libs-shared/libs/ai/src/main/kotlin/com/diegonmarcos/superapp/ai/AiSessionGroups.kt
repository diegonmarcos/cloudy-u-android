package com.diegonmarcos.superapp.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** One named group of chat sessions; [collapsed] is its header's state in the all-chats drawer. */
data class AiSessionGroup(val id: String, val name: String, val collapsed: Boolean = false)

/**
 * The chat sessions organised into named groups: the groups in order, each collapsible, and which group a session belongs to
 * (none = ungrouped). Kept through [save] (a host passes where: [prefs] or memory) and read back at construction, so groups and
 * membership survive restarts. Membership of a deleted group falls back to ungrouped.
 */
class AiSessionGroups(private val load: () -> String = { "" }, private val save: (String) -> Unit = {}) {
    private class Snapshot(val groups: List<AiSessionGroup>, val members: Map<String, String>)

    private var state by mutableStateOf(parse(load()))

    val groups: List<AiSessionGroup> get() = state.groups

    /** The group [sessionId] is in, or null when ungrouped. */
    fun groupOf(sessionId: String): String? = state.members[sessionId]?.takeIf { g -> state.groups.any { it.id == g } }

    fun add(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val id = "g" + (state.groups.mapNotNull { it.id.drop(1).toIntOrNull() }.maxOrNull() ?: 0).plus(1)
        put(Snapshot(state.groups + AiSessionGroup(id, n), state.members))
        return id
    }

    fun toggle(groupId: String) = put(Snapshot(state.groups.map { if (it.id == groupId) it.copy(collapsed = !it.collapsed) else it }, state.members))

    fun rename(groupId: String, name: String) {
        if (name.isBlank()) return
        put(Snapshot(state.groups.map { if (it.id == groupId) it.copy(name = name.trim()) else it }, state.members))
    }

    fun remove(groupId: String) = put(Snapshot(state.groups.filterNot { it.id == groupId }, state.members.filterValues { it != groupId }))

    /** Moves a session into [groupId] (null = ungrouped). */
    fun assign(sessionId: String, groupId: String?) =
        put(Snapshot(state.groups, if (groupId == null) state.members - sessionId else state.members + (sessionId to groupId)))

    private fun put(s: Snapshot) { state = s; save(encode(s)) }

    private fun encode(s: Snapshot): String = JSONObject()
        .put("groups", JSONArray(s.groups.map { JSONObject().put("id", it.id).put("name", it.name).put("collapsed", it.collapsed) }))
        .put("members", JSONObject(s.members)).toString()

    private fun parse(text: String): Snapshot = runCatching {
        val o = JSONObject(text)
        val ga = o.optJSONArray("groups") ?: JSONArray()
        val mo = o.optJSONObject("members") ?: JSONObject()
        Snapshot(
            (0 until ga.length()).map { ga.getJSONObject(it) }.map { AiSessionGroup(it.getString("id"), it.getString("name"), it.optBoolean("collapsed")) },
            mo.keys().asSequence().associateWith { mo.getString(it) },
        )
    }.getOrDefault(Snapshot(emptyList(), emptyMap()))

    companion object {
        private const val FILE = "ai_session_groups"

        /** Groups kept in the app's SharedPreferences under [app]. */
        fun prefs(ctx: Context, app: String): AiSessionGroups {
            val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val key = "groups." + app.ifBlank { "app" }
            return AiSessionGroups({ p.getString(key, "").orEmpty() }, { p.edit().putString(key, it).apply() })
        }
    }
}
