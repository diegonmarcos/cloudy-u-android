package com.diegonmarcos.superapp.jsoncanvas

/** JSON Canvas 1.0 (jsoncanvas.org), the format Obsidian writes. Fields this model does not know are ignored by the parser. */
enum class Side { Top, Right, Bottom, Left;
    companion object { fun of(s: String?): Side? = values().firstOrNull { it.name.equals(s, ignoreCase = true) } }
}

enum class EndShape { None, Arrow }

sealed class CanvasNode {
    abstract val id: String
    abstract val x: Double
    abstract val y: Double
    abstract val width: Double
    abstract val height: Double
    /** "1".."6" preset or "#RRGGBB"; null = none. */
    abstract val color: String?
    val right: Double get() = x + width
    val bottom: Double get() = y + height

    data class Text(override val id: String, override val x: Double, override val y: Double, override val width: Double, override val height: Double, override val color: String?, val text: String) : CanvasNode()
    data class File(override val id: String, override val x: Double, override val y: Double, override val width: Double, override val height: Double, override val color: String?, val file: String, val subpath: String?) : CanvasNode()
    data class Link(override val id: String, override val x: Double, override val y: Double, override val width: Double, override val height: Double, override val color: String?, val url: String) : CanvasNode()
    data class Group(override val id: String, override val x: Double, override val y: Double, override val width: Double, override val height: Double, override val color: String?, val label: String?) : CanvasNode()
}

data class CanvasEdge(
    val id: String, val fromNode: String, val toNode: String,
    val fromSide: Side?, val toSide: Side?,
    /** Spec defaults: fromEnd none, toEnd arrow. */
    val fromEnd: EndShape = EndShape.None, val toEnd: EndShape = EndShape.Arrow,
    val color: String?, val label: String?,
)

data class Canvas(val nodes: List<CanvasNode>, val edges: List<CanvasEdge>) {
    private val byId: Map<String, CanvasNode> by lazy { nodes.associateBy { it.id } }
    fun node(id: String): CanvasNode? = byId[id]
}

/** Why a document is not a canvas: a typed error instead of an exception. */
sealed class CanvasError(val message: String) {
    class Syntax(message: String, val offset: Int) : CanvasError("not valid JSON at offset $offset: $message")
    class NotAnObject : CanvasError("the document is not a JSON object")
    class BadField(val where: String, val field: String, why: String) : CanvasError("$where: field \"$field\" $why")
    class UnknownNodeType(val where: String, val type: String) : CanvasError("$where: unknown node type \"$type\"")
    class DuplicateId(val id: String) : CanvasError("duplicate id \"$id\"")
    class DanglingEdge(val edge: String, val node: String) : CanvasError("edge \"$edge\" points at the missing node \"$node\"")
}

sealed class CanvasResult {
    data class Ok(val canvas: Canvas) : CanvasResult()
    data class Err(val error: CanvasError) : CanvasResult()
}
