package com.diegonmarcos.superapp.configs

import org.json.JSONObject

/**
 * #1550 The ADB Shell terminal as pure rules (no Android, no Compose): the declared snippet palette and destructive-command
 * rules (shell-allowlist.json `terminal`, next to the allowlist that permits each command), ANSI SGR colour spans, the command
 * history with up/down recall, autocomplete, per-command entries (timestamp, duration, exit code, state), output search and
 * the plain-text export. AdbTerminal draws exactly this.
 */
/** #1682 P3 step 4a: owner-side lib (libs:adb-shell-core), public so the SuperApp host and the webserver owner side share it. */
object TerminalModel {

    // ── declared data ──────────────────────────────────────────────────────────────────────────────

    data class Snippet(val id: String, val group: String, val label: String, val cmd: String)
    data class ConfirmRule(val id: String, val re: Regex, val why: String)
    data class Decl(val groups: List<String>, val snippets: List<Snippet>, val confirm: List<ConfirmRule>, val suggest: List<String>)

    /** Parse the `terminal` block of shell-allowlist.json; an absent block is an empty palette (never a crash). */
    fun parse(json: String): Decl {
        val t = runCatching { JSONObject(json).optJSONObject("terminal") }.getOrNull() ?: return Decl(emptyList(), emptyList(), emptyList(), emptyList())
        val groups = t.optJSONArray("groups")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val snippets = t.optJSONArray("snippets")?.let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).let { Snippet(it.getString("id"), it.getString("group"), it.getString("label"), it.getString("cmd")) } }
        }.orEmpty()
        val confirm = t.optJSONArray("confirm")?.let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).let { ConfirmRule(it.getString("id"), Regex(it.getString("re")), it.getString("why")) } }
        }.orEmpty()
        val suggest = t.optJSONArray("suggest")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return Decl(groups, snippets, confirm, suggest)
    }

    /** The first declared rule that makes [cmd] destructive (ask before running), or null. Every `;`/`&&`/`|` segment is checked. */
    fun confirmFor(d: Decl, cmd: String): ConfirmRule? {
        val segs = cmd.split(Regex("\\s*(;|&&|\\|\\||\\|)\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
        return d.confirm.firstOrNull { r -> segs.any { r.re.containsMatchIn(it) } }
    }

    fun byGroup(d: Decl, favourites: Set<String>): List<Pair<String, List<Snippet>>> {
        val fav = d.snippets.filter { it.id in favourites }
        val rest = d.groups.map { g -> g to d.snippets.filter { it.group == g } }.filter { it.second.isNotEmpty() }
        return (if (fav.isNotEmpty()) listOf(FAVOURITES to fav) else emptyList()) + rest
    }

    const val FAVOURITES = "favourites"

    // ── history + autocomplete ─────────────────────────────────────────────────────────────────────

    /** [items] oldest first; [cursor] = -1 when editing a new line, else the index being recalled. */
    data class History(val items: List<String> = emptyList(), val cursor: Int = -1, val draft: String = "") {
        fun push(cmd: String): History {
            val c = cmd.trim(); if (c.isEmpty()) return copy(cursor = -1, draft = "")
            return History((items.filter { it != c } + c).takeLast(MAX_HISTORY), -1, "")
        }
        /** Up: older. Returns the new history and the text to show. */
        fun up(current: String): Pair<History, String> {
            if (items.isEmpty()) return this to current
            val next = if (cursor == -1) items.size - 1 else (cursor - 1).coerceAtLeast(0)
            return copy(cursor = next, draft = if (cursor == -1) current else draft) to items[next]
        }
        /** Down: newer; past the newest returns the draft. */
        fun down(current: String): Pair<History, String> {
            if (cursor == -1) return this to current
            val next = cursor + 1
            return if (next >= items.size) copy(cursor = -1) to draft else copy(cursor = next) to items[next]
        }
    }

    const val MAX_HISTORY = 200

    /** Up to [max] completions for [prefix]: history (newest first), then snippets, then the declared suggestions. */
    fun complete(prefix: String, d: Decl, history: List<String>, max: Int = 8): List<String> {
        val p = prefix.trim(); if (p.isEmpty()) return emptyList()
        return (history.reversed() + d.snippets.map { it.cmd } + d.suggest)
            .filter { it.startsWith(p, ignoreCase = true) && !it.equals(p, ignoreCase = true) }.distinct().take(max)
    }

    // ── entries ────────────────────────────────────────────────────────────────────────────────────

    enum class State(val word: String) { RUNNING("running"), OK("ok"), EXIT("exit"), DENIED("denied"), FAILED("failed"), STOPPED("stopped") }

    data class Entry(val id: Long, val cmd: String, val startedAt: Long, val endedAt: Long? = null, val state: State = State.RUNNING,
                     val exit: Int? = null, val stdout: String = "", val stderr: String = "", val note: String = "") {
        val durationMs: Long? get() = endedAt?.let { it - startedAt }
        val output: String get() = listOf(stdout.trimEnd('\n'), stderr.trimEnd('\n'), note).filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** "1.2 s" / "340 ms" / "running". */
    fun duration(e: Entry): String = e.durationMs?.let { if (it >= 1000) String.format(java.util.Locale.US, "%.1f s", it / 1000.0) else "$it ms" } ?: "running"

    /** The entry's status word with the exit code ("exit 0", "exit 3", "denied", ...). */
    fun status(e: Entry): String = when (e.state) {
        State.OK, State.EXIT -> "exit ${e.exit ?: 0}"
        else -> e.state.word
    }

    fun ok(e: Entry): Boolean? = when (e.state) { State.OK -> true; State.RUNNING -> null; else -> false }

    /** The entries whose command or output contains [q] (case-insensitive); all when blank. */
    fun search(entries: List<Entry>, q: String): List<Entry> =
        if (q.isBlank()) entries else entries.filter { it.cmd.contains(q, true) || it.output.contains(q, true) }

    /** Count of matching lines across [entries]. */
    fun hits(entries: List<Entry>, q: String): Int =
        if (q.isBlank()) 0 else entries.sumOf { e -> (listOf(e.cmd) + e.output.lines()).count { it.contains(q, true) } }

    /** The session as text: one block per command with its time, duration and exit; ANSI stripped. */
    fun export(title: String, entries: List<Entry>, stamp: (Long) -> String): String = buildString {
        append("# ").append(title).append('\n')
        for (e in entries) {
            append("[").append(stamp(e.startedAt)).append("] $ ").append(e.cmd)
                .append("   (").append(status(e)).append(", ").append(duration(e)).append(")\n")
            if (e.output.isNotEmpty()) append(stripAnsi(e.output)).append('\n')
        }
    }

    // ── ANSI ───────────────────────────────────────────────────────────────────────────────────────

    /** One run of text and its SGR style: [fg] index 0..15 (8..15 bright) or null, [bold], [dim], [underline]. */
    data class Span(val text: String, val fg: Int? = null, val bg: Int? = null, val bold: Boolean = false, val dim: Boolean = false, val underline: Boolean = false)

    private val CSI = Regex("\u001B\\[([0-9;]*)([A-Za-z])")

    fun stripAnsi(s: String): String = CSI.replace(s, "")

    /** SGR colour parsing (30-37, 90-97, 40-47, 100-107, 1, 2, 4, 22, 24, 39, 49, 0); other CSI sequences are dropped. */
    fun spans(s: String): List<Span> {
        val out = ArrayList<Span>()
        var fg: Int? = null; var bg: Int? = null; var bold = false; var dim = false; var ul = false
        var at = 0
        for (m in CSI.findAll(s)) {
            if (m.range.first > at) out += Span(s.substring(at, m.range.first), fg, bg, bold, dim, ul)
            at = m.range.last + 1
            if (m.groupValues[2] != "m") continue
            val codes = m.groupValues[1].split(';').mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0) }
            for (c in codes) when (c) {
                0 -> { fg = null; bg = null; bold = false; dim = false; ul = false }
                1 -> bold = true
                2 -> dim = true
                4 -> ul = true
                22 -> { bold = false; dim = false }
                24 -> ul = false
                in 30..37 -> fg = c - 30
                39 -> fg = null
                in 40..47 -> bg = c - 40
                49 -> bg = null
                in 90..97 -> fg = c - 90 + 8
                in 100..107 -> bg = c - 100 + 8
            }
        }
        if (at < s.length) out += Span(s.substring(at), fg, bg, bold, dim, ul)
        return out.filter { it.text.isNotEmpty() }
    }

    /** The 16-colour palette as ARGB, tuned for a dark terminal surface (readable on the light surface too: see [dark]). */
    fun ansiArgb(i: Int, dark: Boolean): Long = (if (dark) DARK else LIGHT)[i.coerceIn(0, 15)]

    private val DARK = longArrayOf(
        0xFF6B7280, 0xFFF46A6A, 0xFF4ADE80, 0xFFFACC15, 0xFF60A5FA, 0xFFC084FC, 0xFF22D3EE, 0xFFE5E7EB,
        0xFF9CA3AF, 0xFFFCA5A5, 0xFF86EFAC, 0xFFFDE68A, 0xFF93C5FD, 0xFFD8B4FE, 0xFF67E8F9, 0xFFFFFFFF,
    )
    private val LIGHT = longArrayOf(
        0xFF374151, 0xFFB91C1C, 0xFF15803D, 0xFFA16207,0xFF1D4ED8, 0xFF7E22CE, 0xFF0E7490, 0xFF111827,
        0xFF4B5563, 0xFFDC2626, 0xFF16A34A, 0xFFCA8A04, 0xFF2563EB, 0xFF9333EA, 0xFF0891B2, 0xFF000000,
    )

    // ── session header ─────────────────────────────────────────────────────────────────────────────

    /** Privilege of the channel that runs the commands: the uid it answered (2000 shell, 0 root) and the route. */
    fun privilege(uid: String?, channel: String): String = when (uid?.trim()) {
        "0" -> "root (uid 0) via $channel"
        "2000" -> "shell (uid 2000) via $channel"
        null, "" -> "broker via $channel"
        else -> "uid ${uid.trim()} via $channel"
    }
}
