package com.diegonmarcos.superapp.jsoncanvas

/** A small strict JSON reader (RFC 8259) so the canvas parser is pure Kotlin and runs on any JVM. Values: Map, List, String, Double, Boolean, null. */
class JsonSyntaxException(message: String, val offset: Int) : Exception(message)

object JsonMini {
    fun parse(text: String): Any? {
        val p = Reader(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i < text.length) throw JsonSyntaxException("unexpected trailing data", p.i)
        return v
    }

    private class Reader(val s: String) {
        var i = 0
        fun err(m: String): Nothing = throw JsonSyntaxException(m, i)
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }
        fun value(depth: Int): Any? {
            if (depth > 64) err("nesting too deep")
            if (i >= s.length) err("unexpected end of input")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else err("unexpected character '$c'")
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) err("bad literal"); i += w.length; return v }
        fun num(): Double {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(st, i).toDoubleOrNull() ?: run { i = st; err("bad number") }
        }
        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) err("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        if (i >= s.length) err("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000c'); 'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) err("bad unicode escape")
                                b.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: err("bad unicode escape")); i += 4
                            }
                            else -> err("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> err("control character in string")
                    else -> b.append(c)
                }
            }
        }
        fun arr(depth: Int): List<Any?> {
            i++; val out = ArrayList<Any?>(); ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws(); out.add(value(depth + 1)); ws()
                if (i >= s.length) err("unterminated array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return out }
                err("expected , or ]")
            }
        }
        fun obj(depth: Int): Map<String, Any?> {
            i++; val out = LinkedHashMap<String, Any?>(); ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') err("expected a string key")
                val k = str(); ws()
                if (i >= s.length || s[i] != ':') err("expected :")
                i++; ws(); out[k] = value(depth + 1); ws()
                if (i >= s.length) err("unterminated object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return out }
                err("expected , or }")
            }
        }
    }
}
