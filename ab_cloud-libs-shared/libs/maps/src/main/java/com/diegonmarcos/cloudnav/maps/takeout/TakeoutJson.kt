package com.diegonmarcos.cloudnav.maps.takeout

import java.io.Reader

/**
 * #1853 a streaming JSON pull parser (pure Kotlin, no Android classes) for Takeout exports that run to hundreds of MB.
 * Nothing here ever reads a whole file: [readObject] and [readArray] hand each member or element to a callback, and the
 * callback decides to stream deeper or to materialise just that one record with [readValue]. Memory is one record.
 */
class JsonPull(private val r: Reader) {
    private val buf = CharArray(16 * 1024)
    private var n = 0
    private var p = 0
    private var peeked = -2
    /** Characters consumed so far (the caller turns it into progress). */
    var consumed = 0L
        private set

    private fun read(): Int {
        if (peeked != -2) { val c = peeked; peeked = -2; return c }
        if (p >= n) { n = r.read(buf, 0, buf.size); p = 0; if (n <= 0) { n = 0; return -1 } }
        consumed++
        return buf[p++].code
    }

    private fun peekRaw(): Int { if (peeked == -2) peeked = read(); return peeked }

    /** The next non-blank character, not consumed; -1 at the end. */
    fun peek(): Int {
        while (true) { val c = peekRaw(); if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == 0xFEFF) peeked = -2 else return c }
    }

    private fun expect(ch: Char) { val c = peek(); if (c != ch.code) fail("expected '$ch' but found ${if (c < 0) "end of input" else "'" + c.toChar() + "'"}"); peeked = -2 }
    private fun fail(msg: String): Nothing = throw IllegalStateException("JSON at char $consumed: $msg")

    /** Calls [each] with the name of every member; [each] MUST consume that member's value (readValue / skipValue / a nested read). */
    fun readObject(each: (String) -> Unit) {
        expect('{')
        if (peek() == '}'.code) { peeked = -2; return }
        while (true) {
            val name = readString()
            expect(':')
            each(name)
            when (peek()) { ','.code -> peeked = -2; '}'.code -> { peeked = -2; return }; else -> fail("expected ',' or '}'") }
        }
    }

    /** Calls [each] once per element; [each] MUST consume exactly one value. */
    fun readArray(each: () -> Unit) {
        expect('[')
        if (peek() == ']'.code) { peeked = -2; return }
        while (true) {
            each()
            when (peek()) { ','.code -> peeked = -2; ']'.code -> { peeked = -2; return }; else -> fail("expected ',' or ']'") }
        }
    }

    fun skipValue() {
        when (peek()) {
            '{'.code -> readObject { skipValue() }
            '['.code -> readArray { skipValue() }
            '"'.code -> readString()
            else -> readScalar()
        }
    }

    /** One whole value as a tree: LinkedHashMap, ArrayList, String, Long, Double, Boolean or null. */
    fun readValue(): Any? = when (peek()) {
        '{'.code -> LinkedHashMap<String, Any?>().also { m -> readObject { name -> m[name] = readValue() } }
        '['.code -> ArrayList<Any?>().also { l -> readArray { l.add(readValue()) } }
        '"'.code -> readString()
        else -> readScalar()
    }

    fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = read()
            when {
                c < 0 -> fail("unterminated string")
                c == '"'.code -> return sb.toString()
                c == '\\'.code -> when (val e = read()) {
                    'n'.code -> sb.append('\n'); 't'.code -> sb.append('\t'); 'r'.code -> sb.append('\r'); 'b'.code -> sb.append('\b')
                    'f'.code -> sb.append('\u000C'); '/'.code -> sb.append('/'); '\\'.code -> sb.append('\\'); '"'.code -> sb.append('"')
                    'u'.code -> { var v = 0; repeat(4) { v = v * 16 + Character.digit(read(), 16) }; sb.append(v.toChar()) }
                    else -> fail("bad escape \\${e.toChar()}")
                }
                else -> sb.append(c.toChar())
            }
        }
    }

    private fun readScalar(): Any? {
        val sb = StringBuilder()
        while (true) {
            val c = peekRaw()
            if (c < 0 || c == ','.code || c == '}'.code || c == ']'.code || c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) break
            sb.append(c.toChar()); peeked = -2
        }
        val t = sb.toString()
        return when {
            t == "null" -> null
            t == "true" -> true
            t == "false" -> false
            t.isEmpty() -> fail("empty value")
            else -> t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("bad value '$t'")
        }
    }
}

/** Tree to JSON text, keys in the order they were read: the raw copy every record keeps. */
object JsonOut {
    fun write(v: Any?): String = StringBuilder().also { put(it, v) }.toString()

    private fun put(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean, is Long, is Int -> sb.append(v.toString())
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else if (v == Math.rint(v) && Math.abs(v) < 1e15) sb.append(v.toLong()).append(".0") else sb.append(v.toString())
            is Map<*, *> -> { sb.append('{'); var first = true; for ((k, x) in v) { if (!first) sb.append(','); first = false; quote(sb, k.toString()); sb.append(':'); put(sb, x) }; sb.append('}') }
            is List<*> -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); put(sb, x) }; sb.append(']') }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) when {
            ch == '"' -> sb.append("\\\""); ch == '\\' -> sb.append("\\\\"); ch == '\n' -> sb.append("\\n"); ch == '\r' -> sb.append("\\r"); ch == '\t' -> sb.append("\\t")
            ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
            else -> sb.append(ch)
        }
        sb.append('"')
    }
}

/** Which field paths the mappers read (typed), so a test can prove every Google field is mapped. [mute] switches named paths off (a mutation). */
class Tracker(val mute: Set<String> = emptySet()) {
    val touched = linkedSetOf<String>()
}

/**
 * One value of a record tree with its path (`visit.topCandidate.placeId`; list elements are `[]`). Typed reads mark the path as
 * mapped; [json] stores a subtree as JSON text and marks every leaf under it.
 */
class Node(val v: Any?, val path: String, private val t: Tracker) {
    private fun child(key: String, value: Any?) = Node(value, if (path.isEmpty()) key else "$path.$key", t)
    private val muted get() = path in t.mute

    val isObject get() = v is Map<*, *>
    val isList get() = v is List<*>
    operator fun get(key: String): Node = child(key, (v as? Map<*, *>)?.get(key))
    fun has(key: String) = (v as? Map<*, *>)?.containsKey(key) == true
    fun keys(): List<String> = (v as? Map<*, *>)?.keys?.map { it.toString() } ?: emptyList()
    fun items(): List<Node> = (v as? List<*>)?.map { Node(it, "$path[]", t) } ?: emptyList()

    private fun hit(): Boolean { if (muted || v == null) return false; t.touched += path; return true }

    fun str(): String? = if (!hit()) null else when (v) { is String -> v; is Map<*, *>, is List<*> -> null; else -> v.toString() }
    fun num(): Double? = if (!hit()) null else when (v) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null }
    fun long(): Long? = if (!hit()) null else when (v) { is Long -> v; is Number -> v.toLong(); is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong(); else -> null }
    /** The scalar as it is (number or text): Google writes some confidences as numbers and some as enum names (HIGH_CONFIDENCE). */
    fun any(): Any? = if (!hit()) null else when (v) { is Map<*, *>, is List<*> -> null; else -> v }
    fun bool(): Boolean? = if (!hit()) null else when (v) { is Boolean -> v; is String -> v.toBooleanStrictOrNull(); else -> null }

    /** The subtree as JSON text (null when absent); every leaf below counts as mapped. */
    fun json(): String? {
        if (v == null || muted) return null
        touchAll(this)
        return JsonOut.write(v)
    }

    private fun touchAll(n: Node) {
        when (val x = n.v) {
            is Map<*, *> -> if (x.isEmpty()) t.touched += n.path else for (k in n.keys()) touchAll(n[k])
            is List<*> -> if (x.isEmpty()) t.touched += n.path else n.items().forEach { touchAll(it) }
            null -> {}
            else -> t.touched += n.path
        }
    }

    /** Every leaf path of the tree (empty containers count as leaves). */
    fun leaves(): List<String> {
        val out = ArrayList<String>()
        fun walk(n: Node) {
            when (val x = n.v) {
                is Map<*, *> -> if (x.isEmpty()) out += n.path else n.keys().forEach { walk(n[it]) }
                is List<*> -> if (x.isEmpty()) out += n.path else n.items().forEach { walk(it) }
                null -> {}
                else -> out += n.path
            }
        }
        walk(this)
        return out
    }
}
