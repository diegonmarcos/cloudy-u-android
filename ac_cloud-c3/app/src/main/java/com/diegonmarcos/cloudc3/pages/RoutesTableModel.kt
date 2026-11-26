package com.diegonmarcos.cloudc3.pages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The Containers > Table page's MODEL, pure Kotlin so the JVM suite drives sorting, filtering and the persisted
 * state without a screen. The rows are data/infra_slice.json::routes (derived by data/gen_infra_slice.py from the
 * edge auth tiers); nothing here names a container, a host or a VM.
 */
object RoutesTableModel {

    /** The columns, in the owner's order. [key] is the row field and the column's test tag. #1941 GROUP is shown as Cat1 and CATEGORY as Cat2 (the old Group and Category values, unchanged); Project and Type are new. */
    enum class Column(val key: String) {
        /** The status light (first column): sorted by [ProbeModel.rank], never a text cell. */
        STATUS("status"),
        PROJECT("project"),
        GROUP("group"),
        CATEGORY("category"),
        TYPE("type"),
        CONTAINER("container"),
        PUBLIC_URL("public_url"),
        AUTH_TYPE("auth_type"),
        PRIVATE_URL("private_url"),
        VM_NAME("vm_name"),
        VM_ADDR("vm_addr");

        companion object {
            /** The seven text columns (everything but the light). */
            val DATA: List<Column> = entries.filter { it != STATUS }
        }
    }

    /** The auth tiers a row can be in. [id] is what the generator writes; [label] is what the filter prints. */
    enum class Auth(val id: String, val label: String) {
        BEARER("bearer", "Bearer"),
        WOAUTH("woauth", "WOAuth"),
        MESH("mesh", "Mesh-only"),
        OPEN("open", "Open"),
    }

    data class Row(
        val category: String,
        val container: String,
        val publicUrl: String,
        val authType: String,
        val privateUrl: String,
        val vmName: String,
        val vmAddr: String,
        /** Infra Apps / Cloud Apps / Project Apps (the document's group). */
        val group: String = "",
        /** #1941 the Cloud Me project (Unix_Cloudy, ...) and the Type (App | Db | MCP | API | Repo | Other). */
        val project: String = "",
        val type: String = "",
        /** Every metadata field of the declared container, for the More view. */
        val meta: List<Pair<String, String>> = emptyList(),
    ) {
        /** Identity of a row for the probe cache. */
        val key: String get() = "$container|$publicUrl|$privateUrl"

        fun text(c: Column): String = when (c) {
            Column.STATUS -> ""
            Column.PROJECT -> project
            Column.TYPE -> type
            Column.GROUP -> group
            Column.CATEGORY -> category
            Column.CONTAINER -> container
            Column.PUBLIC_URL -> publicUrl
            Column.AUTH_TYPE -> Auth.entries.firstOrNull { it.id == authType }?.label ?: authType
            Column.PRIVATE_URL -> privateUrl
            Column.VM_NAME -> vmName
            Column.VM_ADDR -> vmAddr
        }
    }

    /**
     * What the user chose. An EMPTY filter set means "no filter on that axis" (every row), so a fresh install shows
     * everything and unticking the last chip does not blank the table.
     */
    data class State(
        val sort: Column = Column.CONTAINER,
        val ascending: Boolean = true,
        val auth: Set<String> = emptySet(),
        val vms: Set<String> = emptySet(),
        /** Free text; blank = no search. */
        val query: String = "",
        /** Exactly this category (set by Open on a category cell); blank = all. */
        val category: String = "",
        /** Light ids (green / yellow / red / grey) to keep; empty = every light. */
        val lights: Set<String> = emptySet(),
        /** #1941 project labels and types to keep; empty = all. */
        val projects: Set<String> = emptySet(),
        val types: Set<String> = emptySet(),
    )

    /** Rows from the slice's `routes` array; a missing or malformed array is an empty table, never a crash. */
    fun parse(routes: Any?): List<Row> = (routes as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        Row(s("category"), s("container"), s("public_url"), s("auth_type"), s("private_url"), s("vm_name"), s("vm_addr"))
            .takeIf { it.container.isNotBlank() }
    }

    /** Filter, then sort. Blank cells sort LAST in both directions, so a column's gaps never lead the table. */
    fun view(rows: List<Row>, state: State, light: (Row) -> ProbeModel.Light = { ProbeModel.Light.GREY }): List<Row> {
        val kept = rows.filter { r ->
            (state.auth.isEmpty() || r.authType in state.auth) && (state.vms.isEmpty() || r.vmName in state.vms) &&
                (state.category.isEmpty() || r.category == state.category) && matches(r, state.query) &&
                (state.lights.isEmpty() || light(r).id in state.lights) &&
                (state.projects.isEmpty() || r.project in state.projects) && (state.types.isEmpty() || r.type in state.types)
        }
        val byText = Comparator<Row> { a, b ->
            if (state.sort == Column.STATUS) {
                val c = ProbeModel.rank(light(a)).compareTo(ProbeModel.rank(light(b)))
                return@Comparator if (state.ascending) c else -c
            }
            val x = a.text(state.sort)
            val y = b.text(state.sort)
            when {
                x.isBlank() && y.isBlank() -> 0
                x.isBlank() -> 1
                y.isBlank() -> -1
                else -> naturalCompare(x, y).let { if (state.ascending) it else -it }
            }
        }
        return kept.sortedWith(byText.thenBy(String.CASE_INSENSITIVE_ORDER) { it.container })
    }

    /**
     * The search predicate: every whitespace-separated word of [query] must appear (case-insensitive) in at least
     * one of the row's seven shown cells, so "bearer oci" finds Bearer rows on an oci VM. Blank matches everything.
     */
    fun matches(row: Row, query: String): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val cells = Column.DATA.map { row.text(it).lowercase() }
        return words.all { w -> cells.any { w in it } }
    }

    /** What Open does with a cell. */
    sealed interface OpenTarget {
        /** A URL for the browser / C3's existing open pattern. */
        data class Url(val url: String) : OpenTarget
        /** The container's detail sheet in C3. */
        data class Container(val name: String) : OpenTarget
        /** The VM's place in C3 (the Infra page's VMs). */
        data class Vm(val name: String) : OpenTarget
        /** Filter the table to this category. */
        data class Category(val name: String) : OpenTarget
    }

    /**
     * The value-to-target mapping for the long-press menu; null = nothing to open (Open and Copy & Open are
     * disabled). Public URLs are https; private URLs and IP:PORT are http on the mesh (private names without a dot
     * or a port, e.g. a local tool, have no address to open); Auth Type opens nothing.
     */
    fun openTarget(column: Column, value: String): OpenTarget? {
        val v = value.trim()
        if (v.isEmpty()) return null
        return when (column) {
            Column.PUBLIC_URL -> OpenTarget.Url(if (v.contains("://")) v else "https://$v")
            Column.PRIVATE_URL -> if (v.contains("://")) OpenTarget.Url(v) else if ('.' in v || ':' in v) OpenTarget.Url("http://$v") else null
            Column.VM_ADDR -> OpenTarget.Url(if (v.contains("://")) v else "http://$v")
            Column.CONTAINER -> OpenTarget.Container(v)
            Column.VM_NAME -> OpenTarget.Vm(v)
            Column.CATEGORY -> OpenTarget.Category(v)
            Column.GROUP, Column.PROJECT, Column.TYPE -> null
            Column.AUTH_TYPE, Column.STATUS -> null
        }
    }

    /** Tap on a header: the sorted column flips direction, any other column starts ascending. */
    fun toggleSort(state: State, column: Column): State =
        if (state.sort == column) state.copy(ascending = !state.ascending) else state.copy(sort = column, ascending = true)

    fun toggleAuth(state: State, id: String): State = state.copy(auth = state.auth.toggled(id))
    fun toggleLight(state: State, id: String): State = state.copy(lights = state.lights.toggled(id))
    fun toggleVm(state: State, vm: String): State = state.copy(vms = state.vms.toggled(vm))
    fun toggleProject(state: State, project: String): State = state.copy(projects = state.projects.toggled(project))
    fun toggleType(state: State, type: String): State = state.copy(types = state.types.toggled(type))

    /** The projects the rows name, in the rows' order, for the Project chips. */
    fun projectOptions(rows: List<Row>): List<String> = rows.map { it.project }.filter { it.isNotBlank() }.distinct()

    /** The types the rows carry, for the Type chips: the declared order ([order], data/container_projects.json) first. */
    fun typeOptions(rows: List<Row>, order: List<String>): List<String> {
        val have = rows.map { it.type }.filter { it.isNotBlank() }.toSet()
        return order.filter { it in have } + (have - order.toSet()).sorted()
    }

    /** Auth types worth a chip: the owner's three always, Open only when a row is in it. In tier order. */
    fun authOptions(rows: List<Row>): List<Auth> =
        Auth.entries.filter { it != Auth.OPEN || rows.any { r -> r.authType == it.id } }

    /** The VMs the rows name, sorted, for the VM chips. */
    fun vmOptions(rows: List<Row>): List<String> =
        rows.map { it.vmName }.filter { it.isNotBlank() }.distinct().sortedWith(::naturalCompare)

    /** "12 of 104 rows", or "104 rows" when nothing is filtered out. */
    fun countLine(shown: Int, total: Int): String = if (shown == total) "$total rows" else "$shown of $total rows"

    // ── persistence: one line of text, tolerant of anything ────────────────────────────────

    fun encode(s: State): String =
        listOf(s.sort.key, if (s.ascending) "asc" else "desc", s.auth.sorted().joinToString(","), s.vms.sorted().joinToString("|"),
            java.net.URLEncoder.encode(s.query, "UTF-8"), java.net.URLEncoder.encode(s.category, "UTF-8"), s.lights.sorted().joinToString(","),
            s.projects.sorted().joinToString("|") { java.net.URLEncoder.encode(it, "UTF-8") }, s.types.sorted().joinToString(",")).joinToString(";")

    /** Anything unreadable is the default state: stale or hand-edited preferences cannot break the page. */
    fun decode(text: String?): State {
        val p = text?.split(';') ?: return State()
        if (p.size !in 4..9) return State()
        val col = Column.entries.firstOrNull { it.key == p[0] } ?: return State()
        val asc = when (p[1]) { "asc" -> true; "desc" -> false; else -> return State() }
        return State(col, asc, p[2].split(',').filter { it.isNotBlank() }.toSet(), p[3].split('|').filter { it.isNotBlank() }.toSet(),
            p.getOrNull(4)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty(),
            p.getOrNull(5)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty(),
            p.getOrNull(6)?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty(),
            p.getOrNull(7)?.split('|')?.filter { it.isNotBlank() }?.mapNotNull { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.toSet().orEmpty(),
            p.getOrNull(8)?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty())
    }

    private fun Set<String>.toggled(x: String): Set<String> = if (x in this) this - x else this + x

    /** Case-insensitive, digit runs compared as numbers: "oci-A1-f_0" < "oci-A1-f_10", "10.0.0.6:80" < "10.0.0.6:8010". */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var ie = i; while (ie < a.length && a[ie].isDigit()) ie++
                var je = j; while (je < b.length && b[je].isDigit()) je++
                val x = a.substring(i, ie).trimStart('0')
                val y = b.substring(j, je).trimStart('0')
                if (x.length != y.length) return x.length.compareTo(y.length)
                val c = x.compareTo(y)
                if (c != 0) return c
                i = ie; j = je
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
