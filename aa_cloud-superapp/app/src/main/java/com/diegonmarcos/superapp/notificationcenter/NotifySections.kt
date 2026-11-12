package com.diegonmarcos.superapp.notificationcenter

import android.content.Context
import org.json.JSONObject

/**
 * The redesigned Notify page / Config badge sections (owner 2026-11-08). The ORDER and the Cloudy / Phone split are
 * data in `build.json::ui.notification_center` (`page_order`, `app_sections`); this file only parses them and holds
 * the per-app switches the two app sections draw. Nothing names a section.
 */
object NotifySections {

    data class Sub(val id: String, val label: String)

    /**
     * An app section: no producer, its rows are apps. [kind] is "push" or "keepalive". [filter] (#1603) is "cloudy" or
     * "phone" for a section that renders ONE AppSplit side as a flat list (the Cloud Publisher / Phone Publisher push
     * categories); blank keeps [subs], the two labelled sub-groups (the keep-alive block below the six categories).
     */
    data class AppSection(val id: String, val label: String, val kind: String, val subs: List<Sub>, val filter: String = "")

    fun pageOrder(json: String): List<String> {
        val a = runCatching { JSONObject(json) }.getOrNull()?.optJSONArray("page_order") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    fun appSections(json: String): List<AppSection> {
        val a = runCatching { JSONObject(json) }.getOrNull()?.optJSONArray("app_sections") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id"); if (id.isBlank()) return@mapNotNull null
            val subs = o.optJSONArray("subgroups")?.let { s ->
                (0 until s.length()).mapNotNull { j -> s.optJSONObject(j)?.let { Sub(it.optString("id"), it.optString("label")) } }
            }.orEmpty()
            AppSection(id, o.optString("label", id), o.optString("kind"), subs, o.optString("filter"))
        }
    }

    /**
     * Top-to-bottom ids of the page: the shade groups in [groupOrder] (the owner's order), with each app section
     * placed right after the entry that precedes it in [pageOrder] (first entry: at the top).
     */
    fun layout(pageOrder: List<String>, groupOrder: List<String>, appIds: List<String>): List<String> {
        val out = groupOrder.toMutableList()
        for (a in pageOrder.filter { it in appIds }) {
            val i = pageOrder.indexOf(a)
            val prev = pageOrder.take(i).lastOrNull { it in out }
            out.add(if (prev == null) 0 else out.indexOf(prev) + 1, a)
        }
        for (a in appIds) if (a !in out) out += a
        return out
    }

    /** #1672 a page block that is not a shade group: the two notification feeds, Archived and Configs. */
    data class Block(val id: String, val label: String, val split: String = "")

    fun pageBlocks(json: String): List<Block> {
        val a = runCatching { JSONObject(json) }.getOrNull()?.optJSONArray("page_blocks") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id"); if (id.isBlank()) return@mapNotNull null
            Block(id, o.optString("label", id), o.optString("split"))
        }
    }

    /**
     * #1672 the badge sections in PAGE order: the group ids [pageOrder] names, in that order, then any group it does not
     * name in [groupOrder] (the owner's shade order), so a group added later is still drawn, last.
     */
    fun pageGroupOrder(pageOrder: List<String>, groupOrder: List<String>): List<String> =
        pageOrder.filter { it in groupOrder } + groupOrder.filter { it !in pageOrder }

    /** The declared label of page block [id], or [fallback]. */
    fun blockLabel(id: String, fallback: String): String = pageBlocks.firstOrNull { it.id == id }?.label ?: fallback

    val pageOrder: List<String> by lazy { pageOrder(BadgeServices.declaredJson) }
    val pageBlocks: List<Block> by lazy { pageBlocks(BadgeServices.declaredJson) }
    val appSections: List<AppSection> by lazy { appSections(BadgeServices.declaredJson) }
}

/** Cloudy = the fleet's own apps (com.diegonmarcos.* / cloud-*); Phone = every other app. */
object AppSplit {
    const val CLOUDY = "cloudy"
    const val PHONE = "phone"

    fun of(pkg: String): String =
        if (pkg.startsWith("com.diegonmarcos.") || pkg.contains("cloud-")) CLOUDY else PHONE

    /** [pkgs] split into (cloudy, phone), each sorted by label then package, duplicates dropped. */
    fun split(pkgs: Map<String, String>): Pair<List<Pair<String, String>>, List<Pair<String, String>>> {
        val sorted = pkgs.entries.sortedWith(compareBy({ it.value.lowercase() }, { it.key })).map { it.key to it.value }
        return sorted.filter { of(it.first) == CLOUDY } to sorted.filter { of(it.first) == PHONE }
    }
}

/** Per-app switches of the two app sections, in SharedPreferences. Default: push allowed, keep-alive off. */
object AppSwitches {
    private const val PREFS = "notify_app_switches"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pushAllowed(ctx: Context, pkg: String) = p(ctx).getBoolean("push.$pkg", true)
    fun setPushAllowed(ctx: Context, pkg: String, on: Boolean) { p(ctx).edit().putBoolean("push.$pkg", on).apply() }
    fun keepAlive(ctx: Context, pkg: String) = p(ctx).getBoolean("keep.$pkg", false)
    fun setKeepAlive(ctx: Context, pkg: String, on: Boolean) { p(ctx).edit().putBoolean("keep.$pkg", on).apply() }

    /** package -> label of every app the sections list: the phone-notification history plus, for Cloudy, installed fleet apps. */
    fun knownApps(ctx: Context): Map<String, String> {
        val m = linkedMapOf<String, String>()
        runCatching { PhoneNotificationStore.apps(ctx).forEach { (k, v) -> m.putIfAbsent(k, v) } }
        runCatching {
            val pm = ctx.packageManager
            for (ai in pm.getInstalledApplications(0))
                if (AppSplit.of(ai.packageName) == AppSplit.CLOUDY && ai.packageName != ctx.packageName)
                    m.putIfAbsent(ai.packageName, pm.getApplicationLabel(ai).toString())
        }
        return m
    }
}
