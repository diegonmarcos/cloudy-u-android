package com.diegonmarcos.superapp.cad

/** #1542 the Models table of Hardtech-CAD: its filters (derived from the declared data), its sort and its cell texts. Pure Kotlin. */
object ModelTable {
    const val ALL = "all"

    enum class Column { NAME, CATEGORY, EXTENSION, SOFTWARE, SIZE, GEOMETRY, UPDATED }

    data class Filters(val category: String = ALL, val extension: String = ALL, val software: String = ALL)

    data class Sort(val column: Column = Column.NAME, val descending: Boolean = false)

    /** The extension (lower case, no dot) of a path: "boats/iges/x.igs" is "igs". */
    fun extOf(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** The model's own file extension (what the Extension column shows). */
    fun extension(m: Model): String = extOf(m.path).ifBlank { m.format }

    /** Every extension present for a model: its original file, its view and its other derivatives (the Extension filter matches any). */
    fun extensions(m: Model): Set<String> =
        (listOf(extension(m)) + listOfNotNull(m.view?.path?.let { extOf(it) }) + m.derived.map { extOf(it.path) }).filter { it.isNotBlank() }.toSet()

    /** The Model filter's options: "All", then the declared categories in order (a declared one with no model still shows), then any a model names. */
    fun categoryOptions(models: List<Model>, declared: List<ModelCategory>): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        declared.forEach { out[it.id] = it.label }
        models.map { it.category }.filter { it.isNotBlank() && it !in out }.distinct().forEach { out[it] = it }
        return listOf(ALL to "All") + out.map { it.key to it.value }
    }

    /** The Extension filter's options: "All", then each extension present in the data, as ".ext". */
    fun extensionOptions(models: List<Model>): List<Pair<String, String>> =
        listOf(ALL to "All") + models.flatMap { extensions(it) }.distinct().sorted().map { it to ".$it" }

    /** The Software filter's options: "All", then each authoring software the data names. */
    fun softwareOptions(models: List<Model>): List<Pair<String, String>> =
        listOf(ALL to "All") + models.map { it.software }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }.map { it to it }

    fun filter(models: List<Model>, f: Filters): List<Model> = models.filter { m ->
        (f.category == ALL || m.category == f.category) && (f.extension == ALL || f.extension in extensions(m)) && (f.software == ALL || m.software == f.software)
    }

    /** The triangle count shown for a model: its own, else its view's (the mesh the app draws); -1 when not declared. */
    fun triangles(m: Model): Long = if (m.triangles >= 0) m.triangles else m.view?.triangles ?: -1
    fun vertices(m: Model): Long = if (m.vertices >= 0) m.vertices else m.view?.vertices ?: -1

    /** Rows ordered by [s]; numeric columns by value (an undeclared count sorts first ascending), text columns case-insensitively. Ties keep the name order. */
    fun sort(models: List<Model>, s: Sort): List<Model> {
        val cmp: Comparator<Model> = when (s.column) {
            Column.NAME -> compareBy<Model> { it.name.lowercase() }
            Column.CATEGORY -> compareBy<Model> { it.category.lowercase() }
            Column.EXTENSION -> compareBy<Model> { extension(it) }
            Column.SOFTWARE -> compareBy<Model> { it.software.lowercase() }
            Column.SIZE -> compareBy<Model> { it.size }
            Column.GEOMETRY -> compareBy<Model> { triangles(it) }.thenBy { vertices(it) }
            Column.UPDATED -> compareBy<Model> { it.updated }
        }
        val base = models.sortedWith(compareBy<Model> { it.name.lowercase() })
        return base.sortedWith(if (s.descending) cmp.reversed() else cmp)
    }

    /** A header tap: the same column flips its direction, another column starts ascending. */
    /**
     * #1664 the models shown under the filters and the sort, ONE list for both presentations: the table rows and the cards below it are this list,
     * so a filter can never leave a card the table does not show (or the reverse).
     */
    fun shown(models: List<Model>, f: Filters, s: Sort): List<Model> = sort(filter(models, f), s)

    /** #1664 the preview image of [m] (an assets path), or "" when none is declared: the card then shows a placeholder and says why. */
    fun thumb(m: Model): String = m.view?.thumb.orEmpty()

    fun toggle(s: Sort, c: Column): Sort = if (s.column == c) s.copy(descending = !s.descending) else Sort(c, false)

    fun categoryLabel(m: Model, declared: List<ModelCategory>): String = declared.firstOrNull { it.id == m.category }?.label ?: m.category

    /** 1234567 -> "1.2 MB"; the table is dense, so one decimal. */
    fun sizeText(bytes: Long): String = when {
        bytes < 0 -> "-"
        bytes >= 1_000_000_000 -> "%.1f GB".format(java.util.Locale.ROOT, bytes / 1e9)
        bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1e6)
        bytes >= 1_000 -> "%.1f kB".format(java.util.Locale.ROOT, bytes / 1e3)
        else -> "$bytes B"
    }

    fun countText(n: Long): String = when {
        n < 0 -> "-"
        n >= 1_000_000 -> "%.1fM".format(java.util.Locale.ROOT, n / 1e6)
        n >= 1_000 -> "%.1fk".format(java.util.Locale.ROOT, n / 1e3)
        else -> "$n"
    }

    /** "195.7k / 254.1k" (vertices / triangles) or "-". */
    fun geometryText(m: Model): String = if (triangles(m) < 0 && vertices(m) < 0) "-" else countText(vertices(m)) + " / " + countText(triangles(m))
}

/** One line of the model page's full data: a stable [key] (the app maps it to its label) and the text. */
data class DetailRow(val key: String, val value: String)

object ModelDetail {
    /** Every declared fact of [m], in reading order; a fact the data does not declare is left out (never a made-up value). */
    fun rows(m: Model, declared: List<ModelCategory> = emptyList()): List<DetailRow> = buildList {
        fun row(k: String, v: String) { if (v.isNotBlank()) add(DetailRow(k, v)) }
        row("name", m.name)
        row("category", ModelTable.categoryLabel(m, declared))
        row("extension", ".${ModelTable.extension(m)}" + (m.view?.let { " + ." + ModelTable.extOf(it.path) } ?: "") + m.derived.joinToString("") { " + ." + ModelTable.extOf(it.path) })
        row("software", m.software)
        row("size", ModelTable.sizeText(m.size))
        row("updated", m.updated)
        if (ModelTable.triangles(m) >= 0 || ModelTable.vertices(m) >= 0) row("geometry", ModelTable.vertices(m).toString() + " vertices, " + ModelTable.triangles(m) + " triangles")
        if (m.bboxMin.size == 3 && m.bboxMax.size == 3) {
            val d = m.bboxMax.zip(m.bboxMin) { hi, lo -> hi - lo }
            row("dimensions", d.joinToString(" x ") { "%.2f".format(java.util.Locale.ROOT, it) } + (if (m.units.isNotBlank()) " " + m.units else ""))
            row("bbox", "min " + m.bboxMin.joinToString(", ") { "%.2f".format(java.util.Locale.ROOT, it) } + "; max " + m.bboxMax.joinToString(", ") { "%.2f".format(java.util.Locale.ROOT, it) })
        }
        row("units", m.units)
        row("materials", names(m.materialCount, m.materials))
        row("layers", names(m.layerCount, m.layers))
        if (m.componentCount >= 0) row("components", m.componentCount.toString())
        row("source", m.source)
        row("licence", m.licence)
        row("sha256", m.sha256)
        m.view?.let { row("view_sha256", it.sha256); row("view_tool", it.tool) }
        m.derived.forEach { row("derived_sha256", ModelTable.extOf(it.path) + " " + it.sha256); row("derived_tool", ModelTable.extOf(it.path) + " " + it.tool) }
        row("conversion", m.conversion)
        row("log", m.log.joinToString("\n"))
    }

    private fun names(count: Int, names: List<String>): String = when {
        names.isNotEmpty() -> (if (count >= 0) "$count: " else "") + names.joinToString(", ")
        count >= 0 -> count.toString()
        else -> ""
    }
}
