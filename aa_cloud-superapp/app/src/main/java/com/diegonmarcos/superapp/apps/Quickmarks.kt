package com.diegonmarcos.superapp.apps

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * #1889 Quickmarks as the user edits them from the long-press menu. The curated Quickmarks are declared in build.json
 * (phone_app_groups, parsed by [SuitePhoneAppsFragment.parseGroups]) and are never edited; the user's changes live next to them in one JSON
 * file under filesDir/launcher (same place and shape as the #1860 pins): [State.added] (an app the user marked, with the group it was filed
 * under and its label) and [State.removed] (a curated app the user took out; Add undoes it). [apply] lays the user's state over the declared
 * groups for the page; [isQuickmark] is the one predicate the menu label and the star badge both read.
 */
object Quickmarks {
    const val FALLBACK_GROUP = "Others"
    const val ADD = "Quickmark Add"
    const val REMOVE = "Quickmark Remove"

    data class Add(val group: String, val label: String)
    data class State(val added: Map<String, Add> = emptyMap(), val removed: Set<String> = emptySet())

    /** A bumped counter the page and the tiles read, so a toggle shows at once. */
    val changes = mutableIntStateOf(0)

    // ── pure ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The packages the declarations list as Quickmarks: the app tiles of every group and the apps inside its folders (a Cloud app prepended to a folder is not one). */
    internal fun curatedPkgs(groups: List<SuitePhoneAppsFragment.Group>): Set<String> =
        groups.flatMap { g -> g.entries.map { it.pkg } + g.folders.flatMap { f -> f.entries.map { it.pkg } } }.toSet()

    fun isQuickmark(curated: Set<String>, st: State, pkg: String): Boolean = pkg in st.added || (pkg in curated && pkg !in st.removed)

    /** The menu entry's label, decided at runtime. */
    fun label(isQuickmark: Boolean): String = if (isQuickmark) REMOVE else ADD

    /** Flip [pkg]: a Quickmark is removed (a curated one becomes an override), anything else is added under [group] (a curated one that was removed simply returns to its declared place). */
    fun toggle(curated: Set<String>, st: State, pkg: String, group: String, label: String): State =
        if (isQuickmark(curated, st, pkg)) State(st.added - pkg, if (pkg in curated) st.removed + pkg else st.removed)
        else if (pkg in curated) State(st.added - pkg, st.removed - pkg)
        else State(st.added + (pkg to Add(group.ifBlank { FALLBACK_GROUP }, label)), st.removed - pkg)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The Quickmarks group that matches the Phone folder [folderLabel]: a group of that title, else the group that declares a folder of that label, else [FALLBACK_GROUP]. */
    internal fun groupFor(folderLabel: String?, groups: List<SuitePhoneAppsFragment.Group>): String {
        val n = norm(folderLabel.orEmpty())
        if (n.isEmpty()) return FALLBACK_GROUP
        return groups.firstOrNull { norm(it.title) == n }?.title
            ?: groups.firstOrNull { g -> g.folders.any { norm(it.label) == n } }?.title
            ?: FALLBACK_GROUP
    }

    /** The declared [groups] with the user's state laid over them: removed curated apps drop out (of the tiles and the folders), added apps join their group (a missing group is appended). Nothing declared is edited. */
    internal fun apply(groups: List<SuitePhoneAppsFragment.Group>, st: State): List<SuitePhoneAppsFragment.Group> {
        if (st.added.isEmpty() && st.removed.isEmpty()) return groups
        val out = groups.map { g ->
            val present = (g.entries.map { it.pkg } + g.folders.flatMap { f -> f.entries.map { it.pkg } }).toSet()
            val own = st.added.filter { (pkg, a) -> a.group == g.title && pkg !in present }
            g.copy(
                entries = g.entries.filter { it.pkg !in st.removed } + own.map { (pkg, a) -> SuitePhoneAppsFragment.Entry(pkg, a.label) },
                folders = g.folders.map { f -> f.copy(entries = f.entries.filter { it.pkg !in st.removed }) },
            )
        }.toMutableList()
        val known = groups.map { it.title }.toSet()
        val declaredPkgs = curatedPkgs(groups)
        val orphans = st.added.filter { (pkg, a) -> a.group !in known && pkg !in declaredPkgs }
        for ((title, rows) in orphans.entries.groupBy({ it.value.group }, { it.key to it.value })) {
            out += SuitePhoneAppsFragment.Group(title, rows.map { (pkg, a) -> SuitePhoneAppsFragment.Entry(pkg, a.label) }, emptyList())
        }
        return out
    }

    fun serialize(st: State): String = JSONObject()
        .put("added", JSONArray(st.added.map { (pkg, a) -> JSONObject().put("pkg", pkg).put("group", a.group).put("label", a.label) }))
        .put("removed", JSONArray(st.removed.toList())).toString()

    fun parse(json: String?): State {
        val o = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
        val added = LinkedHashMap<String, Add>()
        o.optJSONArray("added")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                val pkg = r.optString("pkg")
                if (pkg.isNotBlank()) added[pkg] = Add(r.optString("group").ifBlank { FALLBACK_GROUP }, r.optString("label").ifBlank { pkg })
            }
        }
        val removed = LinkedHashSet<String>()
        o.optJSONArray("removed")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { removed += it } }
        return State(added, removed)
    }

    // ── files ────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun file(ctx: Context) = File(File(ctx.filesDir, "launcher").apply { mkdirs() }, "quickmarks.json")

    @Synchronized fun load(ctx: Context): State = runCatching { parse(file(ctx).takeIf { it.isFile }?.readText()) }.getOrDefault(State())

    @Synchronized fun save(ctx: Context, st: State): Boolean = runCatching {
        val f = file(ctx)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(serialize(st))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        changes.intValue++
        true
    }.getOrDefault(false)

    private val declared: Set<String> by lazy { curatedPkgs(SuitePhoneAppsFragment.parseGroups()) }

    /** Is [pkg] a Quickmark now (curated or the user's)? The one predicate the menu label and the star read. */
    fun isQuickmark(ctx: Context, pkg: String): Boolean = isQuickmark(declared, load(ctx), pkg)

    /** Flip [pkg] for the long-press menu: filed under the group of the Phone folder it is in now. Returns the new state of [pkg] (true = a Quickmark). Blocking: call off the main thread. */
    fun toggle(ctx: Context, pkg: String, label: String): Boolean {
        val st = load(ctx)
        val groups = SuitePhoneAppsFragment.parseGroups()
        val group = groupFor(folderLabelOf(ctx, pkg), groups)
        val next = toggle(declared, st, pkg, group, label)
        save(ctx, next)
        return isQuickmark(declared, next, pkg)
    }

    /** The shown label of the Phone folder [pkg] is filed in (the user's assignment honoured), null when it is in none. */
    private fun folderLabelOf(ctx: Context, pkg: String): String? = runCatching {
        val data = allApps(ctx)
        val id = data.grouped.entries.firstOrNull { e -> e.value.any { it.packageName == pkg } }?.key ?: return null
        val f = PhoneAppsFragment.cachedFolders().firstOrNull { it.id == id } ?: return null
        PhoneTaxonomyLayout.displayLabel(f.label, PhoneSections.loadFromBuildConfig().map { it.prefix })
    }.getOrNull()

    /** True when [pkg] is a Quickmark; reads [changes] so a toggle recomposes the tile. */
    @Composable
    fun starred(pkg: String): Boolean {
        val ctx = LocalContext.current
        changes.intValue
        return !PinnedKey.isPin(pkg) && isQuickmark(ctx, pkg)
    }

    /** The small star at the top right of an icon of edge [size]; drawn only for a Quickmark. */
    @Composable
    fun StarBadge(pkg: String, size: Dp, content: @Composable () -> Unit) {
        Box(Modifier.size(size)) {
            content()
            if (starred(pkg)) Text("☆", color = androidx.compose.ui.res.colorResource(com.diegonmarcos.superapp.R.color.star_silver), fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Thin,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).testTag("quickmark:star:$pkg"))
        }
    }

    private object PinnedKey { fun isPin(pkg: String) = com.diegonmarcos.superapp.launcher.pins.PinnedFolders.isAppKey(pkg) }
}
