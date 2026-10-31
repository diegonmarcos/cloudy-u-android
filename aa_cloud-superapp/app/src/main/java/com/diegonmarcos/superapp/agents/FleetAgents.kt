package com.diegonmarcos.superapp.agents

import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.launcher.Sections
import org.json.JSONObject

/**
 * Configs ▸ Fleet ▸ Agents — the agent registry as this app reads it.
 *
 * Every app declares its agents in its own build.json::fleet_agents; the CI script
 * 1_cicd/src/scripts/cloud-android-fleet-agents.py consolidates them into
 * data/fleet-agents.json and app/build.gradle bakes that file, deflated, as
 * [BuildConfig.FLEET_AGENTS_B64]. Nothing here names an agent: the rows, their kinds, triggers,
 * open targets and status channels are all the declaration's.
 */
object FleetAgents {

    /** The declaring app's name for this app: its rows are the SuperApp agents tab. */
    const val SELF_APP = "cloud-superapp"

    /** The two scopes Configs ▸ Observability ▸ X-Ray lists under its AGENTS group. */
    const val SCOPE_SUPERAPP = "superapp"
    const val SCOPE_FLEET = "fleet"

    /** Where an agent's live status is read from (`status` of a row). */
    data class StatusSource(
        val source: String,
        val unique: String = "",
        val tag: String = "",
        val serviceClass: String = "",
        val authoritySuffix: String = "",
        val path: String = "",
        val fleet: String = "",
        val packageName: String = "",
        val reason: String = "",
    )

    data class Agent(
        val id: String,
        val name: String,
        val app: String,
        val appLabel: String,
        val packageName: String,
        val kind: String,
        val trigger: String,
        val summary: String,
        val open: String,
        val status: StatusSource,
        val declaredIn: String,
        val service: String = "",
        val usedBy: List<String> = emptyList(),
    ) {
        val isSelf: Boolean get() = app == SELF_APP
    }

    /** Parses the consolidated document. Throws on a document that is not one (the page says so). */
    fun parse(json: String): List<Agent> {
        val arr = JSONObject(json).getJSONArray("agents")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val s = o.optJSONObject("status") ?: JSONObject()
            Agent(
                id = o.getString("id"),
                name = o.optString("name", o.getString("id")),
                app = o.optString("app"),
                appLabel = o.optString("app_label", o.optString("app")),
                packageName = o.optString("package"),
                kind = o.optString("kind"),
                trigger = o.optString("trigger"),
                summary = o.optString("summary"),
                open = o.optString("open"),
                status = StatusSource(
                    source = s.optString("source", "none"),
                    unique = s.optString("unique"),
                    tag = s.optString("tag"),
                    serviceClass = s.optString("class"),
                    authoritySuffix = s.optString("authority_suffix"),
                    path = s.optString("path"),
                    fleet = s.optString("fleet"),
                    packageName = s.optString("package"),
                    reason = s.optString("reason"),
                ),
                declaredIn = o.optString("declared_in"),
                service = o.optString("service"),
                usedBy = o.optJSONArray("used_by")?.let { u -> (0 until u.length()).map { u.optString(it) } }.orEmpty(),
            )
        }
    }

    @Volatile private var cached: List<Agent>? = null

    /** Every declared agent, from the baked blob. Throws when the blob does not parse. */
    fun all(): List<Agent> = cached ?: parse(Sections.unbake(BuildConfig.FLEET_AGENTS_B64)).also { cached = it }

    /** The rows a tab shows: this app's own, or the whole fleet's. */
    fun forScope(all: List<Agent>, scope: String): List<Agent> =
        if (scope == SCOPE_SUPERAPP) all.filter { it.isSelf } else all
}
