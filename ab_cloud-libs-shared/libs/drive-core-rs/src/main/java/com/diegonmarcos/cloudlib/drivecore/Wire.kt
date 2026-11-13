package com.diegonmarcos.cloudlib.drivecore

/** The text wire shared with the Rust core (wire.rs): `\`, tab, CR and LF escaped in a field; one record per line. */
internal object Wire {
    fun esc(s: String): String {
        if (s.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return s
        val o = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '\\' -> o.append("\\\\")
            '\t' -> o.append("\\t")
            '\n' -> o.append("\\n")
            '\r' -> o.append("\\r")
            else -> o.append(c)
        }
        return o.toString()
    }

    fun unesc(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val o = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { o.append(c); i++; continue }
            when (val n = s[i + 1]) {
                't' -> o.append('\t')
                'n' -> o.append('\n')
                'r' -> o.append('\r')
                '\\' -> o.append('\\')
                else -> { o.append('\\'); o.append(n) }
            }
            i += 2
        }
        return o.toString()
    }

    fun fields(line: String): List<String> = line.split('\t').map(::unesc)

    /** The records of an `OK\n...` answer, or the reason of an `ERR\t...` one. */
    fun body(answer: String?): Result<List<String>> {
        if (answer == null) return Result.failure(IllegalStateException("the native core returned no answer"))
        if (answer.startsWith("OK\n")) return Result.success(answer.substring(3).split('\n').filter { it.isNotEmpty() })
        if (answer.startsWith("ERR\t")) return Result.failure(IllegalStateException(unesc(answer.substring(4))))
        return Result.failure(IllegalStateException("unreadable native answer: ${answer.take(80)}"))
    }
}
