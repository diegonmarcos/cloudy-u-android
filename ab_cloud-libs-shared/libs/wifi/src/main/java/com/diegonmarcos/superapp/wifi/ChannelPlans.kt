package com.diegonmarcos.superapp.wifi

import org.json.JSONObject

data class PlanChannel(val ch: Int, val dfs: Boolean, val note: String)
data class ChannelPlan(val id: String, val label: String, val countries: List<String>, val bands: Map<Band, List<PlanChannel>>) {
    fun channels(band: Band): List<PlanChannel> = bands[band].orEmpty()
}

/** The regulatory channel plans (the served shared dataset `channel-plans`, #1547; data/fleet/channel-plans.json) (#1263): which 20 MHz channels a domain allows, per band, with DFS. */
class ChannelPlans(val plans: List<ChannelPlan>, val defaultId: String) {
    fun byId(id: String?): ChannelPlan = plans.firstOrNull { it.id.equals(id, true) } ?: plans.first { it.id == defaultId }

    /** The plan of an ISO-3166 country (`ES` -> EU); the default plan when the country is unknown or blank. */
    fun forCountry(cc: String?): ChannelPlan {
        val c = cc?.trim()?.uppercase().orEmpty()
        return plans.firstOrNull { c.isNotEmpty() && c in it.countries } ?: byId(defaultId)
    }

    companion object {
        /** The shared dataset: the regulatory plans per domain. */
        const val DATASET = "channel-plans"

        /**
         * What the analyzer uses while the dataset loads or is not served yet: the WORLD safe common set (2.4 GHz 1-11, 5 GHz 36-48 and DFS 52-64),
         * built from ranges. A test holds it equal to the served file's WORLD plan.
         */
        val DEFAULT = ChannelPlans(
            listOf(ChannelPlan("WORLD", "World (safe common set)", emptyList(), mapOf(
                Band.of("2.4") to (1..11).map { PlanChannel(it, false, "") },
                Band.of("5") to (36..64 step 4).map { PlanChannel(it, it >= 52, "") },
                Band.of("6") to emptyList(),
            ))),
            "WORLD",
        )

        fun parse(text: String): ChannelPlans {
            val o = JSONObject(text)
            val arr = o.getJSONArray("plans")
            val plans = (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                val bo = p.getJSONObject("bands")
                val bands = bo.keys().asSequence().associate { k ->
                    val chs = bo.getJSONArray(k)
                    Band.of(k) to (0 until chs.length()).map { j ->
                        val c = chs.getJSONObject(j); PlanChannel(c.getInt("ch"), c.optBoolean("dfs"), c.optString("note"))
                    }
                }
                val cs = p.getJSONArray("countries")
                ChannelPlan(p.getString("id"), p.getString("label"), (0 until cs.length()).map { cs.getString(it) }, bands)
            }
            return ChannelPlans(plans, o.optString("default", "WORLD"))
        }
    }
}
