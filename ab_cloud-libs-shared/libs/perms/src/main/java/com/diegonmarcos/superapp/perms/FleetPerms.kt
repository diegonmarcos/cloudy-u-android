package com.diegonmarcos.superapp.perms

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import org.json.JSONObject

/**
 * #1546 which fleet app needs which catalogue id: the runtime dataset `fleet-perms` (data/fleet/fleet-perms.json, served by Cloud Store through
 * libs:fleet-data-client, #1547: no baked asset), generated from every app's build.json::perms
 * by 1_cicd/src/scripts/cloud-android-perms-guard.py --write (P8 fails when stale). The Perms page shows "used by fleet
 * apps" per permission, filters by app and groups by app. Pure parse (org.json): JVM-tested.
 */
data class FleetApp(val app: String, val pkg: String, val label: String, val needs: List<String>, val helpers: List<String>)

data class FleetPermsIndex(val apps: List<FleetApp>) {
    private val byId: Map<String, List<String>> by lazy {
        val m = LinkedHashMap<String, MutableList<String>>()
        for (a in apps) for (n in a.needs.distinct()) m.getOrPut(n) { ArrayList() } += a.label
        m
    }

    /** Labels of the fleet apps that declare [id] in their perms.needs, in fleet order. */
    fun appsUsing(id: String): List<String> = byId[id].orEmpty()
    fun labels(): List<String> = apps.map { it.label }

    companion object {
        /** The runtime dataset (shared/fleet-perms), never an asset. */
        const val DATASET = "fleet-perms"
        val EMPTY = FleetPermsIndex(emptyList())

        fun parse(json: String): FleetPermsIndex {
            val a = JSONObject(json).optJSONArray("apps") ?: return EMPTY
            fun strings(x: org.json.JSONArray?) = if (x == null) emptyList() else (0 until x.length()).map { x.getString(it) }
            return FleetPermsIndex((0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                FleetApp(o.getString("app"), o.optString("package"), o.optString("label", o.getString("app")),
                    strings(o.optJSONArray("needs")), strings(o.optJSONArray("helpers")))
            })
        }
    }
}

object FleetPerms {
    @Volatile private var cached: FleetPermsIndex? = null

    /** The served index; [FleetPermsIndex.EMPTY] (not cached, so the next call retries) until Cloud Store has served it. Never throws. */
    fun index(ctx: Context): FleetPermsIndex = cached ?: synchronized(this) {
        cached ?: runCatching { FleetData.get(ctx.applicationContext, FleetPermsIndex.DATASET) { FleetPermsIndex.parse(it) } }
            .getOrNull()?.also { cached = it } ?: FleetPermsIndex.EMPTY
    }

    /** The platform's own protection level of [permission] on this phone ("dangerous", "signature|development", ...). */
    fun liveProtection(ctx: Context, permission: String): String? = if (permission.isEmpty()) null else runCatching {
        val pi = ctx.packageManager.getPermissionInfo(permission, 0)
        val base = if (android.os.Build.VERSION.SDK_INT >= 28) pi.protection else pi.protectionLevel and 0xf
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) pi.protectionFlags else pi.protectionLevel and 0xf.inv()
        protectionWords(base, flags)
    }.getOrNull()

    /** PermissionInfo base level + flags as words (pure). */
    fun protectionWords(base: Int, flags: Int): String {
        val b = when (base) { 0 -> "normal"; 1 -> "dangerous"; 2 -> "signature"; 3 -> "signatureOrSystem"; 4 -> "internal"; else -> "level $base" }
        val f = listOf(0x10 to "privileged", 0x20 to "development", 0x40 to "appop", 0x80 to "pre23", 0x100 to "installer",
            0x400 to "preinstalled", 0x1000 to "instant", 0x4000 to "role")
            .filter { flags and it.first != 0 }.map { it.second }
        return (listOf(b) + f).joinToString("|")
    }
}
