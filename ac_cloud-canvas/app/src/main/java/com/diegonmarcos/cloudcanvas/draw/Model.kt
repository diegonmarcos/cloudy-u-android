package com.diegonmarcos.cloudcanvas.draw

/** The tools of the Draw page. Pure model: no Android type is named in this file, so the JVM suite runs it. */
enum class Tool(val label: String) {
    Brush("Brush"), Pencil("Pencil"), Marker("Marker"), Eraser("Eraser"), Fill("Fill"),
    Line("Line"), Rect("Rect"), Ellipse("Ellipse"), Text("Text"), Eyedropper("Eyedropper");

    val isFreehand: Boolean get() = this == Brush || this == Pencil || this == Marker || this == Eraser
    val isShape: Boolean get() = this == Line || this == Rect || this == Ellipse
}

/** A point of a stroke in document pixels; [p] is the stylus pressure 0..1 (1 for a finger or a mouse). */
data class Pt(val x: Float, val y: Float, val p: Float = 1f)

/** One painted operation of a layer. A layer is the replay of its ops, so undo and the PNG are exact. */
sealed class Op {
    data class Stroke(val tool: Tool, val color: Int, val size: Float, val opacity: Float, val pts: List<Pt>) : Op()
    data class Shape(val kind: Tool, val color: Int, val size: Float, val opacity: Float, val a: Pt, val b: Pt, val filled: Boolean) : Op()
    data class Text(val text: String, val color: Int, val size: Float, val opacity: Float, val at: Pt) : Op()
    data class Fill(val at: Pt, val color: Int, val opacity: Float, val tolerance: Int = 24) : Op()

    /** A picture opened to paint over; [bitmap] is the platform bitmap, opaque to the model. */
    data class Image(val bitmap: Any, val width: Int, val height: Int) : Op()
}

data class Layer(
    val id: Int,
    val name: String,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val ops: List<Op> = emptyList(),
)

/** The whole document, immutable: the history is a stack of these, which shares the unchanged layers and ops. */
data class DocState(
    val width: Int,
    val height: Int,
    val layers: List<Layer>,
    val activeId: Int,
    val nextId: Int,
    val background: Int = 0xFFFFFFFF.toInt(),
) {
    val active: Layer get() = layers.first { it.id == activeId }

    companion object {
        fun blank(width: Int, height: Int): DocState = DocState(width, height, listOf(Layer(1, "Layer 1")), 1, 2)
    }
}

/** Canvas size presets of the Draw page. */
object CanvasPresets {
    data class Preset(val name: String, val width: Int, val height: Int)

    val all: List<Preset> = listOf(
        Preset("Square 1080", 1080, 1080),
        Preset("Portrait 1080 x 1920", 1080, 1920),
        Preset("Landscape 1920 x 1080", 1920, 1080),
        Preset("Photo 4:3 1600 x 1200", 1600, 1200),
        Preset("Sticker 512", 512, 512),
        Preset("Large square 2048", 2048, 2048),
    )
    val default: Preset = all[0]
}

/**
 * The document with its undo/redo history. Every edit but the active-layer pick pushes the previous state on the undo stack and clears
 * the redo stack; [undo] and [redo] move states between the stacks.
 */
class DrawModel(initial: DocState = DocState.blank(CanvasPresets.default.width, CanvasPresets.default.height), private val maxHistory: Int = 100) {
    var state: DocState = initial
        private set
    private val undoStack = ArrayList<DocState>()
    private val redoStack = ArrayList<DocState>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size

    private fun commit(next: DocState) {
        if (next == state) return
        undoStack.add(state)
        if (undoStack.size > maxHistory) undoStack.removeAt(0)
        redoStack.clear()
        state = next
    }

    private fun mapLayer(id: Int, f: (Layer) -> Layer): DocState = state.copy(layers = state.layers.map { if (it.id == id) f(it) else it })

    fun addOp(op: Op) = commit(mapLayer(state.activeId) { it.copy(ops = it.ops + op) })

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.add(state)
        state = undoStack.removeAt(undoStack.size - 1)
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.add(state)
        state = redoStack.removeAt(redoStack.size - 1)
        return true
    }

    /** A new layer on top of the active one's position (the top of the stack), made active; [ops] seeds it (an opened picture). */
    fun addLayer(name: String? = null, ops: List<Op> = emptyList()) {
        val id = state.nextId
        val layer = Layer(id, name ?: "Layer $id", ops = ops)
        commit(state.copy(layers = state.layers + layer, activeId = id, nextId = id + 1))
    }

    /** Removes a layer; the last one cannot be removed. The active layer falls to the one below, else the one above. */
    fun removeLayer(id: Int): Boolean {
        if (state.layers.size <= 1 || state.layers.none { it.id == id }) return false
        val at = state.layers.indexOfFirst { it.id == id }
        val rest = state.layers.filter { it.id != id }
        val active = if (state.activeId != id) state.activeId else rest[(at - 1).coerceAtLeast(0)].id
        commit(state.copy(layers = rest, activeId = active))
        return true
    }

    fun setVisible(id: Int, visible: Boolean) = commit(mapLayer(id) { it.copy(visible = visible) })

    fun setOpacity(id: Int, opacity: Float) = commit(mapLayer(id) { it.copy(opacity = opacity.coerceIn(0f, 1f)) })

    fun rename(id: Int, name: String) = commit(mapLayer(id) { it.copy(name = name) })

    fun clearLayer(id: Int) = commit(mapLayer(id) { it.copy(ops = emptyList()) })

    /** Moves a layer to [toIndex] in the bottom-to-top order (clamped). */
    fun move(id: Int, toIndex: Int) {
        val from = state.layers.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = toIndex.coerceIn(0, state.layers.size - 1)
        if (to == from) return
        val list = state.layers.toMutableList()
        val l = list.removeAt(from)
        list.add(to, l)
        commit(state.copy(layers = list))
    }

    /** Picking the active layer is not an edit: it is not on the undo stack. */
    fun select(id: Int) {
        if (state.layers.any { it.id == id }) state = state.copy(activeId = id)
    }

    /** A new blank document of a preset size; the history starts over. */
    fun newDocument(width: Int, height: Int) {
        state = DocState.blank(width, height)
        undoStack.clear()
        redoStack.clear()
    }
}

/** The recent colours of the picker: most recent first, no duplicates, at most [max]. */
class RecentColors(private val max: Int = 12, initial: List<Int> = emptyList()) {
    var list: List<Int> = initial.distinct().take(max)
        private set

    fun use(color: Int) {
        list = (listOf(color) + list.filter { it != color }).take(max)
    }

    fun encode(): String = list.joinToString(",") { Integer.toHexString(it) }

    companion object {
        fun decode(s: String?, max: Int = 12): RecentColors =
            RecentColors(max, s.orEmpty().split(',').mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() }?.toLongOrNull(16)?.toInt() })
    }
}
