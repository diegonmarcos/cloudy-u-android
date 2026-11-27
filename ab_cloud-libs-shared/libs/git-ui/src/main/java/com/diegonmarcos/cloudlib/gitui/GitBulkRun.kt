package com.diegonmarcos.cloudlib.gitui

/**
 * #1934 THE BULK RUN, pure: which repositories a scope holds, which controls cannot run for it (each with its own reason key, drawn greyed with a reason line),
 * and the per-repository progress rows and the summary of a run over the scoped set. The host feeds real job state in; nothing here touches git.
 */
object BulkScopes {
    const val ALL = "all"; const val SELECTED = "selected"; const val CLONED = "cloned"

    /** One row of the repository list: its name and whether it has a local clone. */
    data class Entry(val name: String, val cloned: Boolean)

    /** The repositories [scope] holds, in list order. */
    fun targets(scope: String, entries: List<Entry>, picked: Set<String>): List<Entry> = when (scope) {
        SELECTED -> entries.filter { it.name in picked }
        CLONED -> entries.filter { it.cloned }
        else -> entries
    }

    private fun isCloneControl(c: SheetControl) = c.action.startsWith("clone_all_") || c.action == "run_clone_selected" || c.action == "run_clone"

    /** Control id -> reason KEY (of the declared reasons) for every action / run control that cannot run over [scoped]; [busy] = a bulk run is in progress. */
    fun unavailable(sheet: GitSheet, scope: String, scoped: List<Entry>, busy: Boolean): Map<String, String> {
        val cloned = scoped.count { it.cloned }; val notCloned = scoped.size - cloned
        val out = LinkedHashMap<String, String>()
        sheet.controls(true).filter { it.kind == "action" || it.kind == "run" }.forEach { c ->
            val global = sheet.bulkActions[c.action]?.startsWith("global:") == true || c.action in setOf("refresh_list", "scan_inventory", "cancel_checks", "open_logs", "legend", "open_report", "show_archived", "open_advanced")
            val why = when {
                busy && c.kind == "run" -> "run_busy"
                global -> null
                scoped.isEmpty() -> if (scope == SELECTED) "no_selection" else "no_cloned"
                isCloneControl(c) -> if (notCloned == 0) "all_cloned" else null
                cloned == 0 -> "no_cloned"
                else -> null
            }
            if (why != null) out[c.id] = why
        }
        return out
    }
    /** [unavailable] with the reason text of the declaration. */
    fun unavailableText(sheet: GitSheet, scope: String, scoped: List<Entry>, busy: Boolean): Map<String, String> =
        unavailable(sheet, scope, scoped, busy).mapValues { (_, k) -> sheet.reasons[k].orEmpty().ifBlank { k } }
}

object BulkProgress {
    enum class State(val word: String) { QUEUED("queued"), RUNNING("running"), OK("ok"), FAILED("failed"), SKIPPED("skipped") }
    data class Row(val name: String, val state: State, val detail: String) { fun line(): String = state.word + if (detail.isBlank()) "" else ": $detail" }
    data class Summary(val total: Int, val ok: Int, val failed: Int, val skipped: Int, val running: Int, val queued: Int) { val done: Boolean get() = running == 0 && queued == 0 }

    /**
     * One row per target: the job's outcome when it has one (ok / failed with the error / skipped), RUNNING when its operation is in flight, else QUEUED.
     * [notes] are targets the run could not even start (a not-cloned repository: FAILED with the reason). [ended]: a row the job never reached becomes SKIPPED ("cancelled" when [cancelled], else "not run").
     */
    fun rows(targets: List<String>, running: Set<String>, ok: Map<String, String>, failed: Map<String, String>, skipped: Map<String, String>,
             notes: Map<String, String> = emptyMap(), ended: Boolean = false, cancelled: Boolean = false): List<Row> = targets.map { n ->
        when {
            n in notes -> Row(n, State.FAILED, notes.getValue(n))
            n in failed -> Row(n, State.FAILED, failed.getValue(n))
            n in skipped -> Row(n, State.SKIPPED, skipped.getValue(n))
            n in ok -> Row(n, State.OK, ok.getValue(n))
            n in running -> Row(n, State.RUNNING, "")
            ended -> Row(n, State.SKIPPED, if (cancelled) "cancelled" else "not run")
            else -> Row(n, State.QUEUED, "")
        }
    }

    fun summary(rows: List<Row>): Summary = Summary(rows.size, rows.count { it.state == State.OK }, rows.count { it.state == State.FAILED }, rows.count { it.state == State.SKIPPED },
        rows.count { it.state == State.RUNNING }, rows.count { it.state == State.QUEUED })

    /** The summary line: "3 repos: 2 ok, 1 failed, 0 skipped". */
    fun summaryLine(s: Summary): String = "${s.total} repos: ${s.ok} ok, ${s.failed} failed, ${s.skipped} skipped"
}
