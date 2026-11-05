package com.diegonmarcos.superapp.shellbroker

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

    data class Rule(val id: String, val pattern: Regex, val meta: String = "")
    data class Entry(val rules: List<Rule>, val exact: Map<String, Rule> = emptyMap(), val segments: Boolean = false)

    sealed class Decision {
        data class Allow(val rule: Rule) : Decision()
        data class Deny(val reason: String) : Decision()
    }

    /** The allowlist key of a binder-resolved package (a package may be an alias, e.g. the Webserver is "mesh"). */
    fun callerKey(pkg: String): String = aliases[pkg] ?: pkg

    fun callers(): Set<String> = callers.keys

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
            return one(key, e, c)
        }
        var last: Decision.Allow? = null
        for (part in c.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            when (val d = one(key, e, part)) {
                is Decision.Deny -> return d
                is Decision.Allow -> last = d
            }
        }
        return last ?: Decision.Deny("empty command")
    }

    private fun one(key: String, e: Entry, c: String): Decision {
        var matched = false
        for (r in e.rules) {
            if (!r.pattern.matches(c)) continue
            matched = true
            if (c.none { it in META && it !in r.meta }) return Decision.Allow(r)
        }
        val meta = c.firstOrNull { it in META }
        return Decision.Deny(if (matched && meta != null) "shell metacharacter '$meta' not declared" else "no rule of $key matches")
    }

    companion object {
        const val MAX_CMD = 2048
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
                callers[k] = Entry(rules, ex, c.optBoolean("segments", false))
            }
            val al = LinkedHashMap<String, String>()
            o.optJSONObject("aliases")?.let { a -> for (k in a.keys()) al[k] = a.getString(k) }
            return Allowlist(callers, al)
        }
    }
}
