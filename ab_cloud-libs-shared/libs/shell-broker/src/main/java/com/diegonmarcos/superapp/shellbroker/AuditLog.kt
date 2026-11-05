package com.diegonmarcos.superapp.shellbroker

import org.json.JSONObject

/**
 * #1510 Every broker call is audited: caller, command, exit, duration, source. Never the OUTPUT (it can carry a secret), and the
 * command goes through [redact] first. A bounded in-memory ring plus an optional [Sink] (the SuperApp appends JSON lines to a file).
 */
class AuditLog(private val capacity: Int = 200, private val sink: Sink? = null) {
    data class Entry(val ts: Long, val caller: String, val cmd: String, val outcome: String, val exit: Int, val ms: Long, val source: String)
    fun interface Sink { fun append(line: String) }

    private val ring = ArrayDeque<Entry>()

    fun add(e: Entry) {
        synchronized(ring) { ring.addLast(e); while (ring.size > capacity) ring.removeFirst() }
        runCatching { sink?.append(line(e)) }
    }

    fun entries(): List<Entry> = synchronized(ring) { ring.toList() }

    companion object {
        fun line(e: Entry): String = JSONObject().put("ts", e.ts).put("caller", e.caller).put("cmd", e.cmd)
            .put("outcome", e.outcome).put("exit", e.exit).put("ms", e.ms).put("source", e.source).toString()

        private val KV = Regex("""(?i)\b(token|secret|password|passwd|pwd|key|bearer|authorization|api[_-]?key)(\s*[=:]\s*|\s+)\S+""")
        private val LONG = Regex("""[A-Za-z0-9+/_=-]{32,}""")

        /** A command with every credential-looking value masked, and capped at 300 characters. */
        fun redact(cmd: String): String =
            LONG.replace(KV.replace(cmd.trim()) { "${it.groupValues[1]}=***" }, "***").take(300)
    }
}
