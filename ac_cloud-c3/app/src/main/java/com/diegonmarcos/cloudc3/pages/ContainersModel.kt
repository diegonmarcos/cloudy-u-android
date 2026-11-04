package com.diegonmarcos.cloudc3.pages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudc3.BuildConfig
import com.diegonmarcos.cloudc3.Declarations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * #1148 THE ONE model of the Containers page. Topology (the dashboards) and Table both read the SAME declarative
 * document, containers-declared.json: derived by the configs-sync engine from every service build.json, committed
 * to the data repo and served by c3-infra-api at /configs/containers. Nothing here names a container, a group or a
 * category; the document carries them, in order. A copy is baked into the app ([ContainersSource.bundled]) so the
 * page still draws with the API unreachable.
 */
object ContainersModel {

    /** One container with every metadata field the document carries. */
    data class Entry(
        val name: String, val service: String, val role: String, val group: String, val category: String,
        val description: String, val image: String, val host: String, val dns: String, val port: Int?,
        val isPublic: Boolean, val protocol: String, val dbEngine: String, val legacyCategory: String, val folder: String,
        val publicUrl: String, val auth: String, val authType: String,
        /** Extra labelled facts after the document's own (a Repos entity: project, visibility, default branch, Pages URL, GitHub URL). */
        val extra: List<Pair<String, String>> = emptyList(),
    ) {
        /** Every field, label first: what the More view prints. A new field is added HERE and in [parse], nowhere else. */
        fun fields(): List<Pair<String, String>> = listOf(
            "name" to name, "service" to service, "role" to role, "group" to group, "category" to category,
            "description" to description, "image" to image, "host" to host, "dns" to dns, "port" to (port?.toString() ?: ""),
            "public" to isPublic.toString(), "protocol" to protocol, "db_engine" to dbEngine, "legacy_category" to legacyCategory,
            "folder" to folder, "public_url" to publicUrl, "auth" to auth, "auth_type" to authType,
        ) + extra
    }

    data class Category(val label: String, val names: List<String>)
    data class Group(val id: String, val label: String, val categories: List<Category>)

    data class Catalog(val groups: List<Group>, val entries: List<Entry>) {
        private val byName: Map<String, Entry> by lazy { entries.associateBy { it.name } }
        fun entry(name: String): Entry? = byName[name]
        val names: List<String> get() = entries.map { it.name }
    }

    val EMPTY = Catalog(emptyList(), emptyList())

    /** Parse the document; null when it is not a usable containers-declared.json (so a bad live copy never replaces a good one). */
    fun parse(text: String): Catalog? {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val cs = root["containers"] as? JsonArray ?: return null
        val gs = root["groups"] as? JsonArray ?: return null
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val entries = cs.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o.s("name").isBlank()) return@mapNotNull null
            Entry(
                o.s("name"), o.s("service"), o.s("role"), o.s("group"), o.s("category"), o.s("description"), o.s("image"),
                o.s("host"), o.s("dns"), (o["port"] as? JsonPrimitive)?.intOrNull, o.s("public") == "true", o.s("protocol"),
                o.s("db_engine"), o.s("legacy_category"), o.s("dir"), o.s("public_url"), o.s("auth"), o.s("auth_type"),
            )
        }
        val groups = gs.mapNotNull { g ->
            val o = g as? JsonObject ?: return@mapNotNull null
            Group(o.s("id"), o.s("label"), (o["categories"] as? JsonArray).orEmpty().mapNotNull { c ->
                val co = c as? JsonObject ?: return@mapNotNull null
                Category(co.s("label"), (co["containers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            })
        }
        return if (entries.isEmpty() || groups.isEmpty()) null else Catalog(groups, entries)
    }

    /** The Infra Apps categories in the owner's order; the document must list them exactly so. */
    val INFRA_ORDER = listOf(
        "Net-Routing & Security", "Net-Mesh", "Dev-Gits", "Dev-Builders", "Ops-Infra", "Ops-API-MCP",
        "Ops-Observability", "Ops-Backup", "Ops-dBs-infra", "Ops-dBs-cloud", "Ops-dBs-projects",
    )
    const val INFRA = "Infra Apps"
    const val CLOUD = "Cloud Apps"
    const val PROJECT = "Project Apps"

    /** What is wrong with [c], empty when it is sound: a container with no group/category, one named twice, one in two places, the Infra order. */
    fun problems(c: Catalog): List<String> {
        val out = mutableListOf<String>()
        c.entries.forEach {
            if (it.group.isBlank()) out += "${it.name}: no group"
            if (it.category.isBlank()) out += "${it.name}: no category"
        }
        c.names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { out += "$it: listed twice" }
        val placed = c.groups.flatMap { g -> g.categories.flatMap { cat -> cat.names.map { it to "${g.label}/${cat.label}" } } }
        placed.groupBy({ it.first }, { it.second }).filter { it.value.size > 1 }.forEach { (n, w) -> out += "$n: in ${w.joinToString(" and ")}" }
        (c.names.toSet() - placed.map { it.first }.toSet()).forEach { out += "$it: in no group" }
        c.entries.forEach { e ->
            val g = c.groups.firstOrNull { it.label == e.group }
            if (g == null || g.categories.none { it.label == e.category && e.name in it.names }) out += "${e.name}: declared ${e.group}/${e.category} but not placed there"
        }
        val infra = c.groups.firstOrNull { it.label == INFRA }?.categories?.map { it.label }
        if (infra != INFRA_ORDER) out += "Infra Apps categories are $infra, not $INFRA_ORDER"
        return out
    }

    /** The Table's rows: one per container, straight from the document. */
    fun rows(c: Catalog): List<RoutesTableModel.Row> = c.entries.map { e ->
        RoutesTableModel.Row(
            category = e.category, container = e.name, publicUrl = e.publicUrl, authType = e.authType,
            privateUrl = if (e.dns.isBlank()) "" else e.dns + (e.port?.let { ":$it" } ?: ""),
            vmName = e.host, vmAddr = "", group = e.group, meta = e.fields(),
        )
    }
}

/**
 * Where the document comes from: the copy baked into the app, replaced by the live one from c3-infra-api once it has
 * answered with a sound document. [version] ticks on a replacement so a screen showing the old one re-reads.
 */
object ContainersSource {
    @Volatile private var live: ContainersModel.Catalog? = null

    var version by mutableStateOf(0)
        private set

    val bundled: ContainersModel.Catalog by lazy {
        ContainersModel.parse(Declarations.decodeChunks(BuildConfig.CONTAINERS_DECLARED_B64)) ?: ContainersModel.EMPTY
    }

    fun current(): ContainersModel.Catalog = live ?: bundled

    /** Ask the API; null on success (or no change), else why the bundled/previous copy stays. */
    suspend fun refresh(src: FleetLive): String? {
        return when (val r = src.get("/configs/containers")) {
            is FleetLive.Result.Failed -> r.why
            is FleetLive.Result.Body -> {
                val parsed = ContainersModel.parse(r.text) ?: return "the API answered something that is not containers-declared.json"
                val bad = ContainersModel.problems(parsed)
                if (bad.isNotEmpty()) return "the served document is unsound: ${bad.first()}"
                if (parsed != live) { live = parsed; version += 1 }
                null
            }
        }
    }
}
