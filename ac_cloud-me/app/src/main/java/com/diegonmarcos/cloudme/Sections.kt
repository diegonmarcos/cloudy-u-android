package com.diegonmarcos.cloudme

import android.content.Context
import android.util.Log
import android.util.Base64
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import org.json.JSONArray
import org.json.JSONObject

/**
 * The whole navigation graph of Cloud Me: libs:bottomnav's [NavDecl] (#868), read from
 * [BuildConfig.UI_BOTTOM_NAV] + [BuildConfig.UI_SECTIONS_B64] + [BuildConfig.UI_DEFAULT_SECTION],
 * which app/build.gradle bakes from build.json::ui.
 *
 * There is deliberately no other list of destinations in this app: the bottom bar, the Settings
 * entries and every tab strip are all built from [all]. A section moves in or out of the bar by
 * adding or removing its id in `ui.bottom_nav`, and a new tab is a new `pages` entry plus the
 * matching page file — no Kotlin edit, no menu resource, nothing to keep in step. There is no drawer.
 *
 * What NavDecl does not carry is Cloud Me's two section extras, `toolbar` and `target`; they are
 * read from the same blob here. Parsing is fail-soft throughout.
 */
/** A tab. [NavPage.pages] is non-empty only for a container tab: Buro > Fin, or Projects > Health
 *  > Workout > Gym three strips deep. Depth is a decision taken in build.json. */
typealias Page = NavPage

/** A [NavSection] plus the two things only Cloud Me reads off it. */
data class Section(
    val nav: NavSection,
    /** Not a bar section: reached from the Settings entries at the top right. */
    val toolbar: Boolean,
    /** Non-blank ⇒ this bar item is a launch target, not a page host: the string goes to
     *  [MainActivity.onTarget] and nothing in this app is shown. No section declares one today. */
    val target: String,
) {
    val id: String get() = nav.id
    val label: String get() = nav.label
    val icon: String get() = nav.icon
    val pages: List<Page> get() = nav.pages

    /** The tabs leading to [id], outermost first; empty when this section declares no such id.
     *  The last entry is the page, the entries before it are the strips drawn above it, and their
     *  ids are the folders the page's content file lives under. */
    fun path(id: String?): List<Page> = nav.path(id)

    /** Resolves a page id against the tab strip AND every strip below it. */
    fun page(id: String?): Page? = nav.page(id)
}

object Sections {

    private val decl: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }
    private val entries: List<Section> by lazy { load() }
    private val index: Map<String, Section> by lazy { entries.associateBy { it.id } }

    fun all(): List<Section> = entries

    fun byId(id: String?): Section? = if (id == null) null else index[id]

    /** The bottom bar, in `ui.bottom_nav` order (the island holds at most eight). */
    fun bottom(): List<Section> = decl.bottomSections().mapNotNull { index[it.id] }

    /** The Settings entries, drawn as visible buttons at the top right (`ui.settings`, section ids, in order). */
    fun settings(): List<Section> = BuildConfig.UI_SETTINGS.split(',').mapNotNull { index[it.trim()] }

    /** Where the app opens: `ui.default_section`, else the first bar section. */
    fun default(): Section? =
        decl.default()?.let { index[it.id] }?.takeIf { !it.toolbar }
            ?: bottom().firstOrNull()
            ?: entries.firstOrNull()

    /**
     * One page's content list, read from assets when the page opens.
     *
     * NOT baked into BuildConfig: the stacks grow with the data — a real
     * profile is tens of kilobytes on its own — and javac caps a String
     * constant at 64KB. The navigation shape is bounded and stays in
     * BuildConfig; the content is a file, read once per page open.
     *
     * A page lives one folder deeper per container tab above it, so the file
     * path IS the tab path: projects/health/workout/gym.json is Projects >
     * Health > Workout > Gym. Missing or malformed yields an empty list and a
     * page that says so, never a crash — the build already refuses a page
     * with no file, so reaching the fallback means the asset was lost after
     * the build, not that JSON went untested.
     */
    fun stack(ctx: Context, sectionId: String, pageId: String): JSONArray {
        val section = byId(sectionId) ?: return JSONArray()
        val chain = section.path(pageId).map { it.id }.ifEmpty { listOf(pageId) }
        val path = (listOf(sectionId) + chain).joinToString("/") + ".json"
        return runCatching {
            JSONArray(CloudMeApiData.text(ctx, path))
        }.getOrElse {
            // Empty-and-quiet is how a wrong path stayed invisible through a
            // whole release: every page rendered its "Nothing here yet" state
            // and nothing said why. Still fail soft, but leave a trace.
            Log.w("Sections", "no page asset at $path", it)
            JSONArray()
        }
    }

    private fun load(): List<Section> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_SECTIONS_B64, Base64.DEFAULT)))
        val extras = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.associateBy { it.optString("id") }
        decl.sections.map {
            Section(it, extras[it.id]?.optBoolean("toolbar", false) ?: false, extras[it.id]?.optString("target").orEmpty())
        }
    }.getOrDefault(emptyList())
}

/**
 * `extapp:<id>` → the package to launch, from build.json::ui.external_apps.
 *
 * Cloud Me links out far more than it stores, so this table decides whether a
 * tile does anything. An id missing here, or an app that is not installed,
 * makes the tile a no-op rather than a crash — which is also the honest
 * behaviour when the constellation member simply is not on this phone.
 */
object ExternalApps {

    /** One `ui.external_apps` row: [activity] (optional) is the explicit launcher activity, [label] the display name
     *  used in the "not installed" message; both are declared in build.json, none is named in Kotlin. */
    data class Entry(val id: String, val pkg: String, val activity: String = "", val label: String = "")

    /** What a target resolves to: open [component] (explicit when declared, else the package's launch intent),
     *  the app is declared but not installed, or the id is not declared at all (a silent no-op, by design). */
    sealed class Resolution {
        data class Open(val pkg: String, val activity: String) : Resolution()
        data class Missing(val label: String) : Resolution()
        object Unknown : Resolution()
    }

    fun parse(arr: JSONArray): List<Entry> = (0 until arr.length()).mapNotNull { i ->
        val o: JSONObject = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id"); val pkg = o.optString("package")
        if (id.isBlank() || pkg.isBlank()) null else Entry(id, pkg, o.optString("activity"), o.optString("label"))
    }

    private val entries: List<Entry> by lazy {
        runCatching { parse(JSONArray(String(Base64.decode(BuildConfig.UI_EXTERNAL_APPS_B64, Base64.DEFAULT)))) }.getOrDefault(emptyList())
    }

    /** Target grammar is `extapp:<id>` or `extapp:<id>/<hint>`; the hint after
     *  the slash is a destination inside the other app that no launch intent
     *  can currently express, so it is parsed off and ignored rather than
     *  turned into a package lookup that would always miss. */
    fun entryFor(all: List<Entry>, target: String): Entry? =
        all.firstOrNull { it.id == target.removePrefix("extapp:").substringBefore('/') }

    fun packageFor(target: String): String? = entryFor(entries, target)?.pkg

    fun labelFor(target: String): String = entryFor(entries, target)?.let { it.label.ifBlank { it.id } }.orEmpty()

    /** Pure resolution (unit-tested): [installed] answers whether a package is on the phone. */
    fun resolve(all: List<Entry>, target: String, installed: (String) -> Boolean): Resolution {
        val e = entryFor(all, target) ?: return Resolution.Unknown
        return if (installed(e.pkg)) Resolution.Open(e.pkg, e.activity) else Resolution.Missing(e.label.ifBlank { e.id })
    }

    fun launch(ctx: Context, target: String): Boolean {
        val installed = { pkg: String -> ctx.packageManager.getLaunchIntentForPackage(pkg) != null }
        when (val r = resolve(entries, target, installed)) {
            is Resolution.Open -> {
                val intent = if (r.activity.isNotBlank())
                    android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                        .setClassName(r.pkg, r.activity).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                else ctx.packageManager.getLaunchIntentForPackage(r.pkg) ?: return false
                return runCatching { ctx.startActivity(intent) }.isSuccess
            }
            is Resolution.Missing -> {
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.external_app_missing, r.label), android.widget.Toast.LENGTH_LONG).show()
                return false
            }
            Resolution.Unknown -> return false
        }
    }
}
