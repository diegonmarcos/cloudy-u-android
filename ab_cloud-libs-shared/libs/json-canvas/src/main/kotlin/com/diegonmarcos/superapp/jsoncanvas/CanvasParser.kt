package com.diegonmarcos.superapp.jsoncanvas

/** Parses a JSON Canvas 1.0 document. Never throws: malformed input is a [CanvasResult.Err]. Unknown fields are ignored; missing `nodes`/`edges` mean empty. */
object CanvasParser {
    private class Fail(val e: CanvasError) : Exception()

    fun parse(text: String): CanvasResult = try {
        val root = try { JsonMini.parse(text) } catch (x: JsonSyntaxException) { throw Fail(CanvasError.Syntax(x.message.orEmpty(), x.offset)) }
        @Suppress("UNCHECKED_CAST")
        val o = root as? Map<String, Any?> ?: throw Fail(CanvasError.NotAnObject())
        val nodes = list(o, "nodes", "canvas").mapIndexed { i, n -> node(n, "nodes[$i]") }
        val ids = HashSet<String>()
        for (n in nodes) if (!ids.add(n.id)) throw Fail(CanvasError.DuplicateId(n.id))
        val edges = list(o, "edges", "canvas").mapIndexed { i, e -> edge(e, "edges[$i]") }
        val eids = HashSet<String>()
        for (e in edges) {
            if (!eids.add(e.id)) throw Fail(CanvasError.DuplicateId(e.id))
            if (e.fromNode !in ids) throw Fail(CanvasError.DanglingEdge(e.id, e.fromNode))
            if (e.toNode !in ids) throw Fail(CanvasError.DanglingEdge(e.id, e.toNode))
        }
        CanvasResult.Ok(Canvas(nodes, edges))
    } catch (f: Fail) {
        CanvasResult.Err(f.e)
    } catch (t: Throwable) {
        CanvasResult.Err(CanvasError.BadField("canvas", "?", "could not be read: ${t.javaClass.simpleName}"))
    }

    private fun list(o: Map<String, Any?>, k: String, where: String): List<Map<String, Any?>> {
        val v = o[k] ?: return emptyList()
        val l = v as? List<*> ?: throw Fail(CanvasError.BadField(where, k, "must be an array"))
        return l.mapIndexed { i, e ->
            @Suppress("UNCHECKED_CAST")
            e as? Map<String, Any?> ?: throw Fail(CanvasError.BadField("$where.$k[$i]", k, "must be an object"))
        }
    }

    private fun str(o: Map<String, Any?>, k: String, where: String, required: Boolean): String? {
        val v = o[k]
        if (v == null) { if (required) throw Fail(CanvasError.BadField(where, k, "is missing")); return null }
        return v as? String ?: throw Fail(CanvasError.BadField(where, k, "must be a string"))
    }

    private fun num(o: Map<String, Any?>, k: String, where: String): Double {
        val v = o[k] ?: throw Fail(CanvasError.BadField(where, k, "is missing"))
        val d = v as? Double ?: throw Fail(CanvasError.BadField(where, k, "must be a number"))
        if (!d.isFinite()) throw Fail(CanvasError.BadField(where, k, "must be finite"))
        return d
    }

    private fun color(o: Map<String, Any?>, where: String): String? {
        val c = str(o, "color", where, false) ?: return null
        if (!CanvasColors.valid(c)) throw Fail(CanvasError.BadField(where, "color", "must be \"1\"..\"6\" or #hex, got \"$c\""))
        return c
    }

    private fun node(o: Map<String, Any?>, where: String): CanvasNode {
        val id = str(o, "id", where, true)!!
        val type = str(o, "type", where, true)!!
        val x = num(o, "x", where); val y = num(o, "y", where)
        val w = num(o, "width", where); val h = num(o, "height", where)
        if (w < 0 || h < 0) throw Fail(CanvasError.BadField(where, "width/height", "must not be negative"))
        val c = color(o, where)
        return when (type) {
            "text" -> CanvasNode.Text(id, x, y, w, h, c, str(o, "text", where, true)!!)
            "file" -> CanvasNode.File(id, x, y, w, h, c, str(o, "file", where, true)!!, str(o, "subpath", where, false))
            "link" -> CanvasNode.Link(id, x, y, w, h, c, str(o, "url", where, true)!!)
            "group" -> CanvasNode.Group(id, x, y, w, h, c, str(o, "label", where, false))
            else -> throw Fail(CanvasError.UnknownNodeType(where, type))
        }
    }

    private fun side(o: Map<String, Any?>, k: String, where: String): Side? {
        val s = str(o, k, where, false) ?: return null
        return Side.of(s) ?: throw Fail(CanvasError.BadField(where, k, "must be top|right|bottom|left, got \"$s\""))
    }

    private fun end(o: Map<String, Any?>, k: String, where: String, default: EndShape): EndShape =
        when (val s = str(o, k, where, false)) {
            null -> default
            "none" -> EndShape.None
            "arrow" -> EndShape.Arrow
            else -> throw Fail(CanvasError.BadField(where, k, "must be none|arrow, got \"$s\""))
        }

    private fun edge(o: Map<String, Any?>, where: String): CanvasEdge = CanvasEdge(
        id = str(o, "id", where, true)!!,
        fromNode = str(o, "fromNode", where, true)!!, toNode = str(o, "toNode", where, true)!!,
        fromSide = side(o, "fromSide", where), toSide = side(o, "toSide", where),
        fromEnd = end(o, "fromEnd", where, EndShape.None), toEnd = end(o, "toEnd", where, EndShape.Arrow),
        color = color(o, where), label = str(o, "label", where, false),
    )
}
