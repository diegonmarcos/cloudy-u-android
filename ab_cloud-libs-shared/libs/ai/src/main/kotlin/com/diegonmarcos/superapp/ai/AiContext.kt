package com.diegonmarcos.superapp.ai

import java.util.Locale

/** Context-window usage, from what the backend REPORTED (no estimate): the last answer's prompt + completion is what the next request starts from. */
object AiContext {
    /** Tokens the open session occupies: the last answer's reported prompt plus completion; 0 when no answer has reported any. */
    fun used(session: AiSession): Long = session.messages.lastOrNull { it.role == "assistant" && it.prompt + it.completion > 0 }?.let { it.prompt + it.completion } ?: 0

    /** Percent of [limit] used, one decimal; null when the limit is unknown. */
    fun percent(used: Long, limit: Long): String? = if (limit <= 0) null else String.format(Locale.ROOT, "%.1f", used * 100.0 / limit)

    /** "Context 1,234 / 128,000 tokens (1.0%)", the same without a limit when the model list reports none, null before any reported usage. */
    fun line(session: AiSession, limit: Long, s: AiStrings): String? {
        val u = used(session)
        if (u <= 0) return null
        val pct = percent(u, limit) ?: return s.contextUsed.format(n(u))
        return s.contextLine.format(n(u), n(limit), pct)
    }

    /** One answer's reported tokens ("812 in · 140 out"); "" when none were reported. */
    fun answer(m: AiMsg, fmt: String): String = if (m.prompt + m.completion <= 0) "" else fmt.format(n(m.prompt), n(m.completion))

    /**
     * The Context Breakdown: what the open session holds, from what is REPORTED or counted (no token estimate): the window, the reported
     * use and its share, the reported tokens in and out over all answers, the messages per role, the system prompt, the files.
     */
    fun breakdown(session: AiSession, limit: Long, systemPrompt: String, pending: List<AiAttachment> = emptyList()): List<AiUsageLine> {
        val answers = session.messages.filter { it.role == "assistant" }
        val used = used(session)
        val files = files(session, pending)
        return listOfNotNull(
            if (limit > 0) AiUsageLine("Window", n(limit) + " tokens") else null,
            AiUsageLine("In context (reported)", if (used > 0) n(used) + " tokens" + (percent(used, limit)?.let { " ($it%)" } ?: "") else "nothing reported yet"),
            AiUsageLine("Prompt tokens, all answers", n(answers.sumOf { it.prompt })),
            AiUsageLine("Completion tokens, all answers", n(answers.sumOf { it.completion })),
            AiUsageLine("Messages", "${session.messages.count { it.role == "user" }} you · ${answers.size} assistant"),
            AiUsageLine("Characters", "${n(session.messages.filter { it.role == "user" }.sumOf { it.content.length.toLong() })} you · ${n(answers.sumOf { it.content.length.toLong() })} assistant"),
            AiUsageLine("System prompt", if (systemPrompt.isBlank()) "none" else n(systemPrompt.length.toLong()) + " characters"),
            AiUsageLine("Files", if (files.isEmpty()) "none" else "${files.size} · ${n(files.sumOf { it.bytes })} bytes"),
        )
    }

    /** Every file of the chat: those sent with its messages, then those waiting on the next one. */
    fun files(session: AiSession, pending: List<AiAttachment> = emptyList()): List<AiAttachment> =
        (session.messages.flatMap { it.attachments } + pending).distinctBy { it.id }

    private fun n(v: Long) = String.format(Locale.ROOT, "%,d", v)
}
