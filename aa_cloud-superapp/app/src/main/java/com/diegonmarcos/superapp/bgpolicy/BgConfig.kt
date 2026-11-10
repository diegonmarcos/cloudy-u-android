package com.diegonmarcos.superapp.bgpolicy

import org.json.JSONObject

/**
 * #1506 `assets/bg-policy.json`, parsed: who is fleet, who is essential (never restricted), what the bulk action touches and how
 * many drainers Battery Hunger ranks. Names are DATA; this holds no package id.
 */
data class BgConfig(
    val fleetPrefixes: List<String>,
    val essentialRoles: List<String>,
    val essentialPackages: Set<String>,
    val essentialPrefixes: List<String>,
    val bulkIncludesSystem: Boolean,
    val hungerTop: Int,
) {
    fun isFleet(pkg: String) = fleetPrefixes.any { pkg.startsWith(it) }

    /** Fixed part of the allowlist (declared names and prefixes); the role part (dialer, SMS, launcher, keyboard) is resolved on the device. */
    fun isFixedEssential(pkg: String) = pkg in essentialPackages || essentialPrefixes.any { pkg.startsWith(it) }

    companion object {
        fun parse(json: String): BgConfig {
            val o = JSONObject(json)
            val e = o.getJSONObject("essential")
            fun list(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
            return BgConfig(
                fleetPrefixes = list(o.getJSONArray("fleet_prefixes")),
                essentialRoles = list(e.getJSONArray("roles")),
                essentialPackages = list(e.getJSONArray("packages")).toSet(),
                essentialPrefixes = list(e.optJSONArray("prefixes")),
                bulkIncludesSystem = o.optJSONObject("bulk")?.optBoolean("include_system", false) ?: false,
                hungerTop = o.optJSONObject("hunger")?.optInt("top", 25) ?: 25,
            )
        }
    }
}

/**
 * The allowlist the engine consults. [roles] are the on-device answers for the declared roles (self, dialer, sms, launcher,
 * keyboard, systemui), resolved once per screen by the Android layer. A package is essential when the declaration names it, a
 * declared prefix covers it, or a role resolved to it.
 */
class BgEssential(
    private val cfg: BgConfig,
    private val roles: Set<String>,
    private val selfPkg: String? = null,
    private val selfProtected: Boolean = true,
) {
    // #1602 The SuperApp's own "essential app" flag. ON (default) keeps this package out of every restrict / force-stop /
    // "restrict all non-essential" pass, so Samsung Super Saving and battery policy never hibernate it. OFF drops the self
    // protection even when the `self` role and the fixed essential list still name it, so the OS is free to hibernate the app.
    fun isEssential(pkg: String): Boolean {
        if (!selfProtected && selfPkg != null && pkg == selfPkg) return false
        return pkg in roles || cfg.isFixedEssential(pkg)
    }
}
