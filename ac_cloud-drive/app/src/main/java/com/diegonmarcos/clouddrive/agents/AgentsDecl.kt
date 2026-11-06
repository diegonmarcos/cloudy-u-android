package com.diegonmarcos.clouddrive.agents

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import org.json.JSONObject

/**
 * The declared agents, read at RUNTIME from Cloud Store's provider (libs:fleet-data-client, #1547), never baked into the APK:
 * the dataset `agents` (repo source data/fleet/app/com.diegonmarcos.clouddrive/agents.json). `parse` is pure (JVM-tested by AgentsDeclTest).
 */
object AgentsDecl {
    const val DATASET = "agents"
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

    /** The served agents now, or an empty list while Cloud Store has not served the dataset yet (first run, provider absent); never throws.
     *  The client keeps the last-known-good copy, so once the dataset has arrived it answers even offline. */
    fun load(ctx: Context): List<Agent> =
        runCatching { FleetData.get(ctx.applicationContext, DATASET, own = true) { parse(it) } }.getOrNull().orEmpty()
}
