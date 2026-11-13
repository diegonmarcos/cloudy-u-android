package com.diegonmarcos.cgomniverse

import org.json.JSONObject

/** One world of data/store.json (generated from data/fwa.md by data/gen-store.py): the Store lists it, Galaxy loads it. */
data class World(
    val id: String,
    val name: String,
    val url: String,
    val type: String,
    val dimension: String,
    val framework: String,
    val stack: List<String>,
    val vr: Boolean,
    val description: String,
    /** The link probe's verdict (data/probe-links.py via gen-store.py) and its date; an unprobed link is UNVERIFIED. */
    val status: LinkStatus = LinkStatus.UNVERIFIED,
    val checked: String = "",
    /** The Wayback Machine copy of [url], generated into store.json (no address is spelled in Kotlin); empty when absent. */
    val wayback: String = "",
    /** A query string Galaxy appends to [url] (data/world-rules.json via gen-store.py), e.g. a lighter render path; empty for most worlds. */
    val launchQuery: String = "",
    /** The query of the CPU presentation (#1657, world-rules.json cpu_query): null = the same as [launchQuery]. */
    val launchQueryCpu: String? = null,
    /** #1701 set for a Type Model entry: the model Galaxy shows in the viewer; null for a web world. */
    val model: ModelRef? = null,
    /** #1694 how long (ms) the render check waits for this world before it calls a flat screen a failure; 0 = [RenderProbe.GRACE_MS] (world-rules.json grace_s). */
    val renderGraceMs: Long = 0L,
)

/**
 * #1701 A 3D model of the Store (data/models.json via gen-store.py): the GLB view the viewer downloads at runtime ([glb], checked
 * against [sha256]; never baked in the APK), the card's [photo], and the facts the card shows. Galaxy opens a world that has one in the
 * model viewer (assets/model) instead of a web page.
 */
data class ModelRef(
    val glb: String,
    val sha256: String,
    val size: Long,
    val photo: String,
    val format: String,
    val software: String = "",
    val units: String = "m",
    val parts: Int = 0,
    val triangles: Int = 0,
)

/** What the link probe found: ONLINE answered, OFFLINE is gone (404/410, timeout, no host), UNVERIFIED the probe could not tell. */
enum class LinkStatus {
    ONLINE, OFFLINE, UNVERIFIED;

    companion object {
        fun parse(s: String?): LinkStatus = when (s) {
            "online" -> ONLINE
            "offline" -> OFFLINE
            else -> UNVERIFIED
        }
    }
}

/**
 * When Galaxy calls a world offline: its main frame timed out (no page within [LOAD_TIMEOUT_MS]), the host never answered
 * (WebView timeout, no such host, connect refused) or the server says it is gone (HTTP 404/410). Any other failure is not
 * "offline" and keeps the generic fallback card. Pure, so the JVM suite holds every rule.
 */
object Offline {
    const val LOAD_TIMEOUT_MS = 15_000L
    /** android.webkit.WebViewClient.ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT (spelled here so the JVM tests need no Android). */
    const val ERROR_HOST_LOOKUP = -2
    const val ERROR_CONNECT = -6
    const val ERROR_TIMEOUT = -8
    val GONE = setOf(404, 410)

    fun isOfflineError(errorCode: Int): Boolean = errorCode == ERROR_TIMEOUT || errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT

    fun isOfflineHttp(status: Int): Boolean = status in GONE

    /** The main frame has not finished after [elapsedMs]: offline once the watchdog's time is up. */
    fun timedOut(elapsedMs: Long, finished: Boolean): Boolean = !finished && elapsedMs >= LOAD_TIMEOUT_MS
}

/** A filter group of the Store: its chips (All aside) in order, and the chips a world counts for in this group. */
enum class FilterGroup(val chips: List<String>) {
    TYPE(listOf(Store.GAMES, Store.WEBSITES, Store.OTHERS, Store.MODELS)),
    DIMENSION(listOf(Store.D3, Store.D2)),
    DEVICE(listOf(Store.FLAT, Store.VR_GLASS));
}

/**
 * The Store's catalogue and its filters. Each group is a multi-select chip row whose chips are complementary: a world passes a
 * group when it counts for at least one selected chip, and passes the filter when it passes every group. "All" is not a chip of
 * its own: it reads selected exactly when every chip of the group is, and tapping it selects them all (or clears them when they
 * already are). Everything is selected by default. Pure, so the JVM suite and its mutants cover it.
 */
object Store {
    const val GAMES = "Games"
    const val WEBSITES = "Websites"
    const val OTHERS = "Others"
    const val MODELS = "Models"
    const val D3 = "3D"
    const val D2 = "2D"
    const val FLAT = "Flat Screen"
    const val VR_GLASS = "VR Glass"

    /** The address Galaxy loads for [w]: its url plus its declared launch query (joined with `?` or `&`, before any `#`); [cpu] = the CPU presentation's query. */
    fun launchUrl(w: World, cpu: Boolean = false): String {
        val query = if (cpu) w.launchQueryCpu ?: w.launchQuery else w.launchQuery
        if (query.isEmpty()) return w.url
        val hash = w.url.indexOf('#')
        val base = if (hash >= 0) w.url.substring(0, hash) else w.url
        val frag = if (hash >= 0) w.url.substring(hash) else ""
        return base + (if ('?' in base) "&" else "?") + query + frag
    }

    fun parse(json: String): List<World> {
        val arr = runCatching { JSONObject(json).optJSONArray("entries") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id"); val url = o.optString("url")
            if (id.isEmpty() || url.isEmpty()) return@mapNotNull null
            val st = o.optJSONArray("stack")
            val m = o.optJSONObject("model")?.let { mo ->
                val glb = mo.optString("glb"); val sha = mo.optString("sha256")
                if (glb.isEmpty() || sha.isEmpty()) null
                else ModelRef(glb, sha, mo.optLong("size"), mo.optString("photo"), mo.optString("format"), mo.optString("software"),
                    mo.optString("units", "m"), mo.optInt("parts"), mo.optInt("triangles"))
            }
            World(
                id = id, name = o.optString("name"), url = url, type = o.optString("type"), dimension = o.optString("dimension"),
                framework = o.optString("framework"),
                stack = if (st == null) emptyList() else (0 until st.length()).map { st.optString(it) },
                vr = o.optBoolean("vr", false), description = o.optString("description"),
                status = LinkStatus.parse(o.optString("status")), checked = o.optString("checked"), wayback = o.optString("wayback"),
                launchQuery = o.optString("launch_query"),
                launchQueryCpu = if (o.has("launch_query_cpu")) o.optString("launch_query_cpu") else null,
                model = m,
                renderGraceMs = o.optLong("render_grace_s", 0L) * 1000L,
            )
        }
    }

    /** The chips of [group] that [w] counts for: 2D/3D and 3D/Video are both 2D and 3D; VR Yes is VR Glass and Flat Screen, VR No Flat Screen only. */
    fun tags(w: World, group: FilterGroup): Set<String> = when (group) {
        FilterGroup.TYPE -> when (w.type) {
            "Game" -> setOf(GAMES)
            "Website" -> setOf(WEBSITES)
            "Others" -> setOf(OTHERS)
            "Model" -> setOf(MODELS)
            else -> emptySet()
        }
        FilterGroup.DIMENSION -> when (w.dimension) {
            "3D" -> setOf(D3)
            "2D" -> setOf(D2)
            "2D/3D", "3D/Video" -> setOf(D2, D3)
            else -> emptySet()
        }
        FilterGroup.DEVICE -> if (w.vr) setOf(FLAT, VR_GLASS) else setOf(FLAT)
    }

    /** The selection everything starts with: every chip of every group. */
    fun allSelected(): Map<FilterGroup, Set<String>> = FilterGroup.entries.associateWith { it.chips.toSet() }

    fun isAll(sel: Map<FilterGroup, Set<String>>, group: FilterGroup): Boolean = sel[group].orEmpty().containsAll(group.chips)

    /** Tapping one chip toggles it alone; the other chips of the group keep their state. */
    fun toggle(sel: Map<FilterGroup, Set<String>>, group: FilterGroup, chip: String): Map<FilterGroup, Set<String>> {
        if (chip !in group.chips) return sel
        val cur = sel[group].orEmpty()
        return sel + (group to (if (chip in cur) cur - chip else cur + chip))
    }

    /** Tapping All selects every chip of the group, or clears the group when every chip already is. */
    fun toggleAll(sel: Map<FilterGroup, Set<String>>, group: FilterGroup): Map<FilterGroup, Set<String>> =
        sel + (group to (if (isAll(sel, group)) emptySet() else group.chips.toSet()))

    fun matches(w: World, sel: Map<FilterGroup, Set<String>>): Boolean =
        FilterGroup.entries.all { g -> tags(w, g).any { it in sel[g].orEmpty() } }

    fun filter(worlds: List<World>, sel: Map<FilterGroup, Set<String>>): List<World> = worlds.filter { matches(it, sel) }
}
