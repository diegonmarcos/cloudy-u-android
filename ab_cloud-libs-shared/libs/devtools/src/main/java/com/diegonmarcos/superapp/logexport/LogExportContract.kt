package com.diegonmarcos.superapp.logexport

/**
 * #1490 the log-export IPC contract, pure (no Android types) so the JVM tests pin it.
 *
 * `ContentResolver.call(content://<package>.logexport, method, arg)` -> Bundle {ok, mime, payload, error}, the shape of
 * the SuperApp's diagnostics bridge. Methods: [METHODS]. `arg` is a query string `n=<lines>&errors=0|1&crashes=<count>`.
 * Only a caller holding [PERMISSION] is answered.
 */
object LogExportContract {
    const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
    const val AUTHORITY_SUFFIX = "logexport"
    const val MIME_JSON = "application/json"
    const val KEY_OK = "ok"
    const val KEY_MIME = "mime"
    const val KEY_PAYLOAD = "payload"
    const val KEY_ERROR = "error"
    const val META = "meta"
    const val LOGS = "logs"
    const val CRASHES = "crashes"
    const val EXPORT = "export"
    val METHODS = listOf(META, LOGS, CRASHES, EXPORT)

    const val DEFAULT_LINES = 300
    const val MAX_LINES = 2000
    const val DEFAULT_CRASHES = 5
    const val MAX_CRASHES = 20
    /** One crash report is cut here so a runaway trace cannot grow the reply without bound. */
    const val MAX_CRASH_CHARS = 16_000

    /** The crash ring's directory under filesDir: libs:crash's CrashReports.DIR (the guard asserts they are equal; devtools cannot depend on crash). */
    const val CRASH_DIR = "crashes"

    fun authority(pkg: String) = "$pkg.$AUTHORITY_SUFFIX"

    data class Query(val lines: Int, val errorsOnly: Boolean, val crashes: Int)

    data class Crash(val name: String, val text: String)

    /** Parse `n=..&errors=..&crashes=..`; anything missing or malformed falls back to the default, and every number is clamped. */
    fun parse(arg: String?): Query {
        val kv = (arg ?: "").split('&').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }.toMap()
        return Query(
            lines = (kv["n"]?.toIntOrNull() ?: DEFAULT_LINES).coerceIn(1, MAX_LINES),
            errorsOnly = kv["errors"] == "1",
            crashes = (kv["crashes"]?.toIntOrNull() ?: DEFAULT_CRASHES).coerceIn(0, MAX_CRASHES),
        )
    }

    /** null = allowed. A caller without the fleet permission, or an unknown method, is refused with the reason. */
    fun refusal(callerHoldsPermission: Boolean, method: String): String? = when {
        !callerHoldsPermission -> "caller does not hold $PERMISSION"
        method !in METHODS -> "unknown method '$method' (expected ${METHODS.joinToString("|")})"
        else -> null
    }

    fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    fun meta(app: String, pkg: String, version: String): String =
        "{\"app\":${jsonString(app)},\"package\":${jsonString(pkg)},\"version\":${jsonString(version)}," +
            "\"authority\":${jsonString(authority(pkg))},\"methods\":[${METHODS.joinToString(",") { jsonString(it) }}]}"

    fun logsJson(lines: List<String>): String = "[${lines.joinToString(",") { jsonString(it) }}]"

    fun crashesJson(crashes: List<Crash>): String =
        "[${crashes.joinToString(",") { "{\"name\":${jsonString(it.name)},\"text\":${jsonString(it.text.take(MAX_CRASH_CHARS))}}" }}]"

    fun export(app: String, pkg: String, version: String, timeMs: Long, lines: List<String>, crashes: List<Crash>): String =
        "{\"app\":${jsonString(app)},\"package\":${jsonString(pkg)},\"version\":${jsonString(version)},\"time_ms\":$timeMs," +
            "\"logcat\":${logsJson(lines)},\"crashes\":${crashesJson(crashes)}}"
}
