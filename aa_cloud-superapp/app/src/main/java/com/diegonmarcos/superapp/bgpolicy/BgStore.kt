package com.diegonmarcos.superapp.bgpolicy

import org.json.JSONObject

/** A string key-value seam: SharedPreferences in the app, a map in tests. */
interface BgKv {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * #1506 The persisted policy: the chosen intent per package (restrict / allow) and the prior state captured the first time an
 * app was restricted, so Restore (and Super Saving Off) put back what was there rather than a guess. Written on user action only.
 */
class BgStore(private val kv: BgKv) {
    private fun obj(key: String) = runCatching { JSONObject(kv.get(key) ?: "{}") }.getOrDefault(JSONObject())

    fun choices(): Map<String, BgChoice> = obj(K_CHOICE).let { o -> o.keys().asSequence().associateWith { BgChoice.valueOf(o.getString(it)) } }
    fun choice(pkg: String): BgChoice? = choices()[pkg]

    fun setChoice(pkg: String, c: BgChoice?) {
        val o = obj(K_CHOICE)
        if (c == null) o.remove(pkg) else o.put(pkg, c.name)
        kv.put(K_CHOICE, o.toString())
    }

    fun prior(pkg: String): BgPrior? = obj(K_PRIOR).optJSONObject(pkg)?.let {
        BgPrior(it.optString("any", "default"), it.optString("in", "default"), it.optInt("bk", Bucket.ACTIVE), it.optBoolean("idle", false))
    }

    fun priors(): Map<String, BgPrior> = obj(K_PRIOR).keys().asSequence().mapNotNull { k -> prior(k)?.let { k to it } }.toMap()

    /** Keeps the FIRST capture: a second Restrict must not overwrite the state that Restore is meant to bring back. */
    fun capture(pkg: String, p: BgPrior) {
        val o = obj(K_PRIOR)
        if (o.has(pkg)) return
        o.put(pkg, JSONObject().put("any", p.runAny).put("in", p.runIn).put("bk", p.bucket).put("idle", p.idleWhitelisted))
        kv.put(K_PRIOR, o.toString())
    }

    fun forgetPrior(pkg: String) {
        val o = obj(K_PRIOR); o.remove(pkg); kv.put(K_PRIOR, o.toString())
    }

    private companion object { const val K_CHOICE = "choice"; const val K_PRIOR = "prior" }
}
