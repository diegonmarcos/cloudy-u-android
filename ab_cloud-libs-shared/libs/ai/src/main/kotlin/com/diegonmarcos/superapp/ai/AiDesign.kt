package com.diegonmarcos.superapp.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One composer control of the shared row (Task, Agent, Model, Mode, MCPs): [icon] is a Material symbol name. */
data class AiControl(val id: String, val label: String, val icon: String, val row: Int = 1, val dividerBefore: Boolean = false)

/** One Permission Mode: [instruction] is the standing system sentence the mode sends ("" adds nothing). */
data class AiPermissionMode(val id: String, val label: String, val short: String, val icon: String, val instruction: String)

/** One Effort level: [value] is what `reasoning.effort` carries ("" = nothing). */
data class AiEffort(val id: String, val label: String, val value: String)

/** One item of the More sheet; [kind] says what it does (ai-design.json more). */
data class AiMoreItem(val id: String, val label: String, val kind: String, val header: String = "", val plugin: String = "", val prompt: String = "", val text: String = "", val cls: String = "")

/** A headed group of the More sheet. */
data class AiMoreGroup(val id: String, val label: String, val items: List<AiMoreItem>)

/** One optimisation plugin as the sheet shows it: [live] = the gateway lists it now, [on] = the user switched it on. */
data class AiPlugin(val id: String, val label: String, val live: Boolean, val on: Boolean)

/** One line of the Usage & Cost view. */
data class AiUsageLine(val label: String, val value: String)

/** One of the four MCP pickers (ai-design.json mcp.buckets): [control] is its composer control id, [known] the names listed before the gateway does. */
data class AiMcpBucket(val id: String, val control: String, val label: String, val known: List<String>, val exact: Set<String>, val contains: List<String>, val prefix: List<String>, val default: Boolean)

/** One item of a KG-Store tab; [kind] = web (the chat's web search), source (a public source), mcp (an MCP server). */
data class AiKgItem(val id: String, val label: String, val detail: String, val kind: String)

/** One KG-Store tab; [dynamic] = it also lists the other internet MCPs the gateway lists. */
data class AiKgTab(val id: String, val label: String, val items: List<AiKgItem>, val dynamic: Boolean)

/** One MCP server the gateway lists for the session. */
data class AiMcpServer(val name: String, val tools: Int? = null)

/** One task of the shared mapping: the job, and the [agent] and [model] that run it (assets/ai/ai-tasks.json). */
data class AiTask(val id: String, val label: String, val agent: String, val model: String, val icon: String = "", val note: String? = null)

/**
 * The shared AI design (assets/ai/ai-design.json), parsed once: the sizes of the chat and its composer
 * (Cloud Code's AI page, px = dp = sp), the composer's controls, the
 * Permission Modes and the MCP sentence. Cloud Code (HTML) reads the same file through its resolver.
 */
class AiDesign private constructor(private val root: JSONObject) {
    private val metrics = root.getJSONObject("metrics")

    /** A size from `metrics`; throws on a missing key so a typo fails the first test, not the phone. */
    fun metric(key: String): Float = metrics.getDouble(key).toFloat()

    val metricKeys: Set<String> get() = metrics.keys().asSequence().toSet()

    val controls: List<AiControl> = root.getJSONObject("composer").getJSONArray("controls").objs().map {
        AiControl(it.getString("id"), it.getString("label"), it.getString("icon"), it.optInt("row", 1), it.optBoolean("divider_before"))
    }
    val noTask: String = root.getJSONObject("composer").getString("no_task")
    private val composerObj = root.getJSONObject("composer")
    val collapsedByDefault: Boolean = composerObj.getJSONObject("collapse").getString("default") == "collapsed"
    val collapsePrefKey: String = composerObj.getJSONObject("collapse").getString("pref_key")
    val attachKinds: List<Pair<String, String>> = composerObj.getJSONObject("plus").getJSONArray("attach").objs().map { it.getString("id") to it.getString("label") }

    /** The two-row layout (composer.layout): the same controls plus Specialist, Repos, the plugin and MCP pickers, each with its row (0 input row, 1 and 2 toolbar rows). */
    val layout: List<AiControl> = composerObj.optJSONObject("layout")?.getJSONArray("controls")?.objs().orEmpty().map { o ->
        val base = controls.firstOrNull { it.id == o.getString("id") }
        AiControl(o.getString("id"), o.optString("label", base?.label.orEmpty()), o.optString("icon", base?.icon.orEmpty()), o.optInt("row", 1), o.optBoolean("divider_before"))
    }

    /** The libs:perms row ids each Attach item needs (`perms`), and the items that cannot run without them (`requires`: Camera). */
    val attachPerms: Map<String, List<String>> = composerObj.getJSONObject("plus").getJSONArray("attach").objs().associate { o ->
        o.getString("id") to (o.optJSONArray("perms")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
    }
    val attachRequires: Set<String> = composerObj.getJSONObject("plus").getJSONArray("attach").objs().filter { it.optBoolean("requires") }.map { it.getString("id") }.toSet()
    val newChatLabel: String = composerObj.getJSONObject("plus").getString("new_chat")
    val defaultEffort: String = root.getJSONObject("effort").getString("default")
    val efforts: List<AiEffort> = root.getJSONObject("effort").getJSONArray("levels").objs().map { AiEffort(it.getString("id"), it.getString("label"), it.getString("value")) }
    val moreGroups: List<AiMoreGroup> = root.getJSONObject("more").getJSONArray("groups").objs().map { g ->
        AiMoreGroup(g.getString("id"), g.getString("label"), g.getJSONArray("items").objs().map {
            AiMoreItem(it.getString("id"), it.getString("label"), it.getString("kind"), it.optString("header"), it.optString("plugin"), it.optString("prompt"), it.optString("text"), it.optString("class"))
        })
    }

    /** The preference key one app keeps its bar's collapsed state under. */
    fun collapseKey(app: String): String = collapsePrefKey + "." + app.ifBlank { "app" }

    fun effort(id: String): AiEffort? = efforts.firstOrNull { it.id == id }

    private val perm = root.getJSONObject("permission")
    val permissionVia: String = perm.getString("via")
    val defaultMode: String = perm.getString("default")
    val modes: List<AiPermissionMode> = perm.getJSONArray("modes").objs().map {
        AiPermissionMode(it.getString("id"), it.getString("label"), it.getString("short"), it.getString("icon"), it.getString("instruction"))
    }
    val mcpBuckets: List<AiMcpBucket> = root.getJSONObject("mcp").optJSONArray("buckets")?.objs().orEmpty().map { b ->
        val m = b.getJSONObject("match")
        AiMcpBucket(
            b.getString("id"), b.getString("control"), b.getString("label"), b.optJSONArray("known")?.strings().orEmpty(),
            m.optJSONArray("exact")?.strings().orEmpty().toSet(), m.optJSONArray("contains")?.strings().orEmpty(), m.optJSONArray("prefix")?.strings().orEmpty(), m.optBoolean("default"),
        )
    }

    /** The bucket a server belongs to: first match in order (exact name, contains, prefix), the default bucket takes the rest. */
    fun mcpBucket(name: String): AiMcpBucket? {
        val n = name.lowercase()
        return mcpBuckets.firstOrNull { b -> n in b.exact || b.contains.any { it in n } || b.prefix.any { n.startsWith(it) } } ?: mcpBuckets.firstOrNull { it.default }
    }
    val kgStoreTabs: List<AiKgTab> = root.optJSONObject("kg_store")?.optJSONArray("tabs")?.objs().orEmpty().map { t ->
        AiKgTab(
            t.getString("id"), t.getString("label"),
            t.getJSONArray("items").objs().map { AiKgItem(it.getString("id"), it.getString("label"), it.optString("detail"), it.getString("kind")) },
            t.optBoolean("dynamic"),
        )
    }
    private val sourcesInstruction: String = root.optJSONObject("kg_store")?.optString("instruction").orEmpty()

    /** The standing sentence for the chosen public [sources] ("" for none). */
    fun sourcesSentence(sources: Collection<String>): String =
        if (sources.isEmpty() || sourcesInstruction.isEmpty()) "" else sourcesInstruction.replace("{sources}", sources.sorted().joinToString(", "))

    private val reposObj = root.optJSONObject("repos")
    private val reposInstruction: String = reposObj?.optString("instruction").orEmpty()

    /** The standing sentence for the exposed [repos] ("" for none). */
    fun reposSentence(repos: Collection<String>): String =
        if (repos.isEmpty() || reposInstruction.isEmpty()) "" else reposInstruction.replace("{repos}", repos.sorted().joinToString(", "))

    /** The composer control that shows the plugins of a More item class ("skill", "performance"). */
    val pluginClassControl: Map<String, String> = mapOf("skill" to "skills", "performance" to "perf")

    val mcpInstruction: String = root.getJSONObject("mcp").getString("instruction")
    val extraAgents: List<AiAgent> = root.getJSONObject("agents_extra").getJSONArray("agents").objs().map {
        AiAgent(it.getString("id"), it.getString("label"), it.getString("sub"), it.getString("mode"))
    }

    fun mode(id: String): AiPermissionMode? = modes.firstOrNull { it.id == id }

    /** The standing sentence for [servers] ("" for none). */
    fun mcpSentence(servers: Collection<String>): String =
        if (servers.isEmpty()) "" else mcpInstruction.replace("{servers}", servers.sorted().joinToString(", "))

    /** [enabled] (an app's declared controls) in the design's order; unknown ids are dropped. */
    fun ordered(enabled: Collection<String>): List<AiControl> = controls.filter { it.id in enabled }

    /** The same in the two-row layout's order (with its rows); the single `mcp` control has no place there. */
    fun orderedLayout(enabled: Collection<String>): List<AiControl> = layout.filter { it.id in enabled }

    companion object {
        const val ASSET = "ai/ai-design.json"
        const val TASKS_ASSET = "ai/ai-tasks.json"

        fun parse(text: String) = AiDesign(JSONObject(text))

        @Volatile private var cached: AiDesign? = null

        /** The design from the lib's merged assets, parsed once per process. */
        fun load(ctx: Context): AiDesign = cached ?: synchronized(this) {
            cached ?: parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() }).also { cached = it }
        }

        /** The shared task mapping (assets/ai/ai-tasks.json). */
        fun parseTasks(text: String): List<AiTask> = JSONObject(text).getJSONArray("tasks").objs().map {
            AiTask(it.getString("id"), it.getString("label"), it.getString("agent"), it.getString("model"), it.optString("icon"), it.optString("note").ifBlank { null })
        }

        fun loadTasks(ctx: Context): List<AiTask> = runCatching {
            parseTasks(ctx.assets.open(TASKS_ASSET).bufferedReader().use { it.readText() })
        }.getOrDefault(emptyList())

        private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    }
}

/**
 * An app's composer declaration (assets/ai/composer.json): WHICH of the shared controls it enables, the task it opens on
 * and whether a task is always set (Cloud Tools: a task, never a model, picks the model).
 */
data class AiComposerDecl(val app: String, val controls: List<String>, val defaultTask: String = "", val taskRequired: Boolean = false, val repos: List<String> = emptyList()) {
    companion object {
        const val ASSET = "ai/composer.json"

        /** Throws on an id the design does not have, so a typo in an app's file fails its test. */
        fun parse(text: String, design: AiDesign): AiComposerDecl {
            val o = JSONObject(text)
            val ids = o.getJSONArray("controls").let { a -> (0 until a.length()).map { a.getString(it) } }
            val known = design.controls.map { it.id } + design.layout.map { it.id }
            require(ids.all { it in known }) { "composer.json names a control the design lacks: ${ids.filter { it !in known }}" }
            require(ids.toSet().size == ids.size) { "composer.json lists a control twice" }
            return AiComposerDecl(o.getString("app"), ids, o.optString("default_task"), o.optBoolean("task_required"),
                o.optJSONArray("repos")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
        }

        fun load(ctx: Context): AiComposerDecl =
            parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() }, AiDesign.load(ctx))
    }
}

/** Pure text the More sheet shows. */
object AiExport {
    /** The session as Markdown: its title, then each message under its role. */
    fun markdown(s: AiSession): String = buildString {
        append("# ").append(s.title.ifBlank { "Untitled" }).append("\n\n")
        for (m in s.messages) {
            append("**").append(if (m.role == "user") "You" else "Assistant").append("**")
            if (m.meta.isNotBlank()) append(" (").append(m.meta).append(")")
            append("\n\n").append(m.content).append("\n\n")
        }
    }.trimEnd() + "\n"
}
