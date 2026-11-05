package com.diegonmarcos.clouddrive.agents

import android.content.Context
import org.json.JSONObject

/** The declared agents (assets/agents.json): pure parse, JVM-tested by AgentsDeclTest. */
object AgentsDecl {
    data class Action(val id: String, val label: String)
    data class Agent(val id: String, val name: String, val summary: String, val outputs: List<String>, val triggers: List<String>, val actions: List<Action>)

    fun parse(text: String): List<Agent> {
        val arr = JSONObject(text).getJSONArray("agents")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun strs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val acts = o.optJSONArray("actions")?.let { a -> (0 until a.length()).map { Action(a.getJSONObject(it).getString("id"), a.getJSONObject(it).getString("label")) } }.orEmpty()
            Agent(o.getString("id"), o.getString("name"), o.optString("summary"), strs("outputs"), strs("triggers"), acts)
        }
    }

    fun load(ctx: Context): List<Agent> = parse(ctx.assets.open("agents.json").bufferedReader().use { it.readText() })
}
