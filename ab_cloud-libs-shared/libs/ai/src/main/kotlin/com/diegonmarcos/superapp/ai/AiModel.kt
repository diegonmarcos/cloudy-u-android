package com.diegonmarcos.superapp.ai

/** One chat message: role user | assistant. */
data class AiMsg(
    val role: String, val content: String,
    /** An assistant answer's "agent · model" line (Cloud Code shows it under each answer); "" = none. */
    val meta: String = "",
    /** Tokens the backend REPORTED for this answer (prompt incl. history, completion); 0 = none reported. */
    val prompt: Long = 0, val completion: Long = 0,
    /** Files attached to a user message, or the images an assistant answer returned (shown inline, saveable, shareable). */
    val attachments: List<AiAttachment> = emptyList(),
)

/** One chat session as the host stores it. */
data class AiSession(val id: String, val title: String, val model: String, val updated: Long, val messages: List<AiMsg>)

/** One model of the host's catalogue: [nativeWeb] = it searches the web by itself; [free] = no cost. */
data class AiModel(
    val id: String, val name: String, val nativeWeb: Boolean = false, val free: Boolean = false,
    /** Data hints for the Model picker's tabs (ai-design.json model_tabs): the catalogue section id (A0 = Code) and the tier a data row declares (small / medium). */
    val section: String = "", val tier: String = "",
    /** The model's context window in tokens as the live list reports it (`context_length`); 0 = not reported. */
    val context: Long = 0,
)

/**
 * One agent of the Agent picker: [sub] says what it can do (from the shared descriptor), [mode] is its
 * X-Agent-Mode value at the gateway, [modelFor] turns the user's model pick into what the agent is sent
 * (null = a fixed-model agent: the gateway runs its own). An "" id = no agent (the model directly).
 */
class AiAgent(
    val id: String,
    val label: String,
    val sub: String,
    val mode: String = id,
    val modelFor: (String) -> String? = { it },
)

/** One declared search engine: its box on the page (Cloud Search, Cloud Browser). [icon]/[accent] are names the theme resolves. */
data class AiEngine(val id: String, val label: String, val icon: String, val accent: String)

/** What one send ended with: the session as stored, the reason there is no answer, the answer's sources. */
data class AiOutcome(val session: AiSession, val error: String?, val citations: List<String>)

/** A tool call waiting for the user: [sentence] names the exact action; nothing runs until Allow. */
data class AiConsentRequest(val id: String, val tool: String, val sentence: String)

/** What a running send reports back to the page: the answer as it streams in, and a question for the user. */
interface AiSink {
    /** A piece of the answer, as it arrives (any thread). */
    fun delta(text: String) {}

    /** A tool wants to act: block until the user decides; true = allow. The default denies. */
    fun consent(request: AiConsentRequest): Boolean = false

    /** The sink that listens to nothing (a send with no page: a debug route, a test). */
    object None : AiSink
}
