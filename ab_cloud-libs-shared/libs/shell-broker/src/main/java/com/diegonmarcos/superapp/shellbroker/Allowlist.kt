package com.diegonmarcos.superapp.shellbroker

import com.diegonmarcos.superapp.shellclient.ShellWire
import org.json.JSONObject

/**
 * #1510 The per-caller command allowlist, declared in DATA (assets/shell-allowlist.json), keyed by caller package plus a command
 * pattern. Default DENY: a caller with no entry, or a command no rule of that caller matches, is refused.
 *
 * A command is also refused when it is empty, over [MAX_CMD], multi-line, or carries a shell metacharacter the matching rule did
 * not declare in its `meta`: the broker runs `sh -c`, so `dumpsys battery; rm -rf x` must not pass a `^dumpsys .*$` rule.
 * Two declared, per-caller escapes exist for the SuperApp's own features (data, never a client choice):
 *  - `exact`: literal command lines (the X-Ray probes are fixed scripts with pipes and loops), matched as a whole;
 *  - `segments`: `;` and newline separate commands, and EACH segment must match a rule (the Background policy chains appops/am).
 */
class Allowlist(private val callers: Map<String, Entry>, private val aliases: Map<String, String> = emptyMap()) {

    /** [maxBytes] only applies to a `stdin` rule: the most input one call may stream (never above [ShellWire.STDIN_MAX_BYTES]). */
    data class Rule(val id: String, val pattern: Regex, val meta: String = "", val maxBytes: Long = 0L)
    /** [stdin] = the caller's #1512 stdin rules (`pm install -S <size>`); empty = this caller may stream nothing. */
    data class Entry(val rules: List<Rule>, val exact: Map<String, Rule> = emptyMap(), val segments: Boolean = false,
                     val stdin: List<Rule> = emptyList(),
                     /** #1682 the output door's cap for this caller (0 = no door); [audit] = may read the audit ring and summaries. */
                     val outMaxBytes: Int = 0, val audit: Boolean = false)

    sealed class Decision {
        data class Allow(val rule: Rule) : Decision()
        data class Deny(val reason: String) : Decision()
    }

    /** The allowlist key of a binder-resolved package (a package may be an alias, e.g. the Webserver is "mesh"). */
    fun callerKey(pkg: String): String = aliases[pkg] ?: pkg

    fun callers(): Set<String> = callers.keys

    /** #1682 The most stdout one `exec_out` of [pkg] may return: its `outMaxBytes`, never above [ShellWire.OUT_MAX_BYTES]; 0 = no door. */
    fun outCap(pkg: String): Int = (callers[callerKey(pkg)]?.outMaxBytes ?: 0).coerceIn(0, ShellWire.OUT_MAX_BYTES)

    /** #1682 May [pkg] read the audit ring and the caller summaries? Default no. */
    fun mayAudit(pkg: String): Boolean = callers[callerKey(pkg)]?.audit == true

    /** One short line of what [key] may do (the ADB Shell page's Callers rows): rule count, exact lines, stdin, chaining. */
    fun summary(key: String): String {
        val e = callers[key] ?: return "no entry"
        return buildList {
            add("${e.rules.size} rules")
            if (e.exact.isNotEmpty()) add("${e.exact.size} exact")
            if (e.stdin.isNotEmpty()) add("stdin")
            if (e.segments) add("chains")
        }.joinToString(", ")
    }

    /**
     * #1512 The stdin door: may [pkg] run [cmd] with [size] bytes on its stdin? Default deny: only a command matching one of the
     * caller's `stdin` rules, with a size in 1..min(rule.maxBytes, [ShellWire.STDIN_MAX_BYTES]), and a `-S <n>` in the command
     * (pm reads exactly n bytes) that equals [size]. A trailing ` 2>&1` is allowed and ignored; the broker adds it anyway.
     */
    fun decideStdin(pkg: String, cmd: String, size: Long): Decision {
        val key = callerKey(pkg)
        val e = callers[key] ?: return Decision.Deny("no allowlist entry for $key")
        if (e.stdin.isEmpty()) return Decision.Deny("$key may not stream input")
        if (cmd.isBlank()) return Decision.Deny("empty command")
        if (cmd.length > MAX_CMD) return Decision.Deny("command over $MAX_CMD characters")
        if (cmd.any { it == '\u0000' || it == '\r' || it == '\n' }) return Decision.Deny("control character in command")
        ShellWire.stdinProblem(size)?.let { return Decision.Deny(it) }
        val c = cmd.trim()
        val d = one(key, e.stdin, c)
        if (d !is Decision.Allow) return d
        if (d.rule.maxBytes <= 0L || size > minOf(d.rule.maxBytes, ShellWire.STDIN_MAX_BYTES))
            return Decision.Deny("input of $size bytes is over the ${d.rule.id} limit of ${d.rule.maxBytes}")
        val declared = DECLARED.find(c.removeSuffix(MERGE))?.groupValues?.get(1)?.toLongOrNull()
        if (declared != null && declared != size) return Decision.Deny("-S $declared does not match the $size byte input")
        return d
    }

    fun decide(pkg: String, cmd: String): Decision {
        val key = callerKey(pkg)
        val e = callers[key] ?: return Decision.Deny("no allowlist entry for $key")
        if (cmd.isBlank()) return Decision.Deny("empty command")
        if (cmd.length > MAX_CMD) return Decision.Deny("command over $MAX_CMD characters")
        if (cmd.any { it == '\u0000' || it == '\r' }) return Decision.Deny("control character in command")
        val c = cmd.trim()
        e.exact[c]?.let { return Decision.Allow(it) }
        if (!e.segments) {
            if (c.contains('\n')) return Decision.Deny("multi-line command")
            return one(key, e.rules, c)
        }
        var last: Decision.Allow? = null
        for (part in c.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            when (val d = one(key, e.rules, part)) {
                is Decision.Deny -> return d
                is Decision.Allow -> last = d
            }
        }
        return last ?: Decision.Deny("empty command")
    }

    private fun one(key: String, rules: List<Rule>, cmd: String): Decision {
        // The one redirect a client may spell: stderr into stdout (adb `exec:` carries stdout only, and pm reports failures on stderr).
        val c = if (cmd.endsWith(MERGE)) cmd.removeSuffix(MERGE).trimEnd() else cmd
        var matched = false
        for (r in rules) {
            if (!r.pattern.matches(c)) continue
            matched = true
            if (c.none { it in META && it !in r.meta }) return Decision.Allow(r)
        }
        val meta = c.firstOrNull { it in META }
        return Decision.Deny(if (matched && meta != null) "shell metacharacter '$meta' not declared" else "no rule of $key matches")
    }

    companion object {
        const val MAX_CMD = 2048
        /** The only redirect a command may end with; the broker appends it itself to a stdin call. */
        const val MERGE = " 2>&1"
        private val DECLARED = Regex("""(?:^| )-S (\d+)(?: |$)""")
        /** Characters that chain, redirect, substitute or comment out the rest of a line in `sh -c`. */
        const val META = ";&|`$<>(){}\\#"

        fun parse(json: String): Allowlist {
            val o = JSONObject(json)
            val cs = o.getJSONObject("callers")
            val callers = LinkedHashMap<String, Entry>()
            for (k in cs.keys()) {
                val c = cs.getJSONObject(k)
                val arr = c.getJSONArray("allow")
                val rules = (0 until arr.length()).map { i ->
                    val r = arr.getJSONObject(i)
                    Rule(r.getString("id"), Regex(r.getString("re")), r.optString("meta", ""))
                }
                val ex = LinkedHashMap<String, Rule>()
                c.optJSONArray("exact")?.let { a -> for (i in 0 until a.length()) ex[a.getString(i).trim()] = Rule("exact-${i + 1}", Regex("^$")) }
                val sin = c.optJSONArray("stdin")?.let { a ->
                    (0 until a.length()).map { i ->
                        val r = a.getJSONObject(i)
                        Rule(r.getString("id"), Regex(r.getString("re")), r.optString("meta", ""), r.getLong("maxBytes"))
                    }
                } ?: emptyList()
                callers[k] = Entry(rules, ex, c.optBoolean("segments", false), sin, c.optInt("outMaxBytes", 0), c.optBoolean("audit", false))
            }
            val al = LinkedHashMap<String, String>()
            o.optJSONObject("aliases")?.let { a -> for (k in a.keys()) al[k] = a.getString(k) }
            return Allowlist(callers, al)
        }
    }
}
