package com.diegonmarcos.cloudtoolssmallllms.data

import org.json.JSONArray
import org.json.JSONObject

/** One row of the tools-models dataset: a model, its role, macro task and memory figures (GB, approximate). */
data class ModelRow(
    val role: String,
    val model: String,
    val params: String,
    val diskFp16: Double,
    val ramFp16: Double,
    val ramQ8: Double,
    val ramQ4: Double,
    val tier: String,
    val macro: String,
    val chat: Boolean,
    val task: String?,
    val openrouterId: String?,
) {
    /** Parameters in billions, for sorting ("0.36B" -> 0.36). */
    val paramsB: Double get() = params.removeSuffix("B").toDoubleOrNull() ?: 0.0

    /** The id the gateway is sent: OpenRouter's when the data names it, else the model's own name. */
    val wireId: String get() = openrouterId ?: model
}

/** What a role is for and its icon name (a libs:ui-icons catalog name). */
data class RoleInfo(val icon: String, val purpose: String)

/** A macro task: the group a model belongs to (Retrieval, Gatekeeping, ...). */
data class Macro(val id: String, val label: String, val icon: String, val blurb: String)

/** One step of an app's pipeline: a macro task and the model ids that run it. */
/** [alternatives]: run ONE of [models]; [escalation]: runs only when the earlier step is not enough; [note] describes a step that is no model (a deeplink). */
data class Step(val macro: String, val models: List<String>, val backend: String?, val alternatives: Boolean, val escalation: Boolean, val note: String?)

/** A runtime the Store manages: kind "device" (this phone) or "ollama" (a fleet host at [url]). */
data class Location(val id: String, val label: String, val kind: String, val url: String?)

/**
 * The fleet embeddings + rerank service as the Retrieval steps use it (assets: retrieval_service): the model names it serves,
 * how many passages an embedding step / the reranker keeps, the reranker score a passage needs, batching and timeouts, the
 * role name of the reranker rows (a step whose first model has it reranks, any other retrieval step embeds), and the line
 * a rerank step leaves when nothing is relevant.
 */
data class RetrievalService(
    val embedModel: String, val rerankModel: String, val rerankRole: String, val embedTop: Int, val rerankTop: Int, val minRerank: Double,
    val embedBatch: Int, val probeMs: Int, val readMs: Int, val retryAfterMs: Long, val noneRelevant: String,
)

/** An app category of the Apps list (Search & Knowledge, Documents & Vision, ...). */
data class AppCategory(val id: String, val label: String, val icon: String)

/** A top filter of the Apps page: [cls] is the app class it keeps (null = every app), [glyph] marks that class on a row. */
data class AppFilter(val id: String, val label: String, val blurb: String, val cls: String?, val glyph: String)

/** An application buildable from the models: [pipeline] is its ordered steps. */
data class AppDef(val id: String, val category: String, val cls: String, val name: String, val icon: String, val description: String, val pipeline: List<Step>, val ui: AppUi)

/** An app's own UI: a reusable [template] id and its params (the input hint, the input labels, the output kind). */
data class AppUi(val template: String, val inputHint: String, val inputs: List<String>, val output: String?)

/** A chat task the user picks instead of a model: its macro, the small model it runs on by default and the medium one it escalates to. */
data class ChatTask(val id: String, val label: String, val icon: String, val macro: String, val small: String, val medium: String, val note: String?)

/** A reusable UI template of the Apps section and the flow it draws. */
data class UiTemplate(val id: String, val label: String, val flow: String)

/**
 * THE model data of the app, decoded once from the tools-models dataset: the Models section, the AI chat's model
 * picker, the Apps section and the overhead assumption all read it. No model, role, macro or app is spelled in Kotlin.
 */
class ModelsData(json: String) {
    private val root = JSONObject(json)
    val overheadPct: Int = root.getInt("overhead_pct")
    val overheadNote: String = root.getString("overhead_note")
    val phoneBudgetQ4: Double = root.getDouble("phone_budget_gb_q4")
    val phoneEachQ4: Double = root.getDouble("phone_each_gb_q4")
    val rows: List<ModelRow> = list(root.getJSONArray("models")) {
        ModelRow(
            it.getString("role"), it.getString("model"), it.getString("params"), it.getDouble("disk_gb_fp16"),
            it.getDouble("ram_gb_fp16"), it.getDouble("ram_gb_q8"), it.getDouble("ram_gb_q4"), it.getString("tier"),
            it.getString("macro"), it.getBoolean("chat"), it.optString("task").ifBlank { null }, it.optString("openrouter_id").ifBlank { null },
        )
    }
    val macros: List<Macro> = list(root.getJSONArray("macros")) { Macro(it.getString("id"), it.getString("label"), it.getString("icon"), it.getString("blurb")) }
    val locations: List<Location> = list(root.getJSONArray("locations")) { Location(it.getString("id"), it.getString("label"), it.getString("kind"), it.optString("url").ifBlank { null }) }
    val retrievalService: RetrievalService? = root.optJSONObject("retrieval_service")?.let {
        RetrievalService(it.getString("embed_model"), it.getString("rerank_model"), it.getString("rerank_role"), it.getInt("embed_top"), it.getInt("rerank_top"),
            it.getDouble("min_rerank"), it.getInt("embed_batch"), it.getInt("probe_ms"), it.getInt("read_ms"), it.getLong("retry_after_ms"), it.getString("none_relevant"))
    }
    val quantTags: Map<Int, String> = root.getJSONObject("quant_tags").let { o -> o.keys().asSequence().associate { it.toInt() to o.getString(it) } }
    val appCategories: List<AppCategory> = list(root.getJSONArray("app_categories")) { AppCategory(it.getString("id"), it.getString("label"), it.getString("icon")) }
    val appFilters: List<AppFilter> = list(root.getJSONArray("app_filters")) { AppFilter(it.getString("id"), it.getString("label"), it.getString("blurb"), it.optString("class").ifBlank { null }, it.getString("glyph")) }
    val templates: List<UiTemplate> = list(root.getJSONArray("app_ui_templates")) { UiTemplate(it.getString("id"), it.getString("label"), it.getString("flow")) }
    val chatTasks: List<ChatTask> = list(root.getJSONArray("chat_tasks")) { ChatTask(it.getString("id"), it.getString("label"), it.getString("icon"), it.getString("macro"), it.getString("small"), it.getString("medium"), it.optString("note").ifBlank { null }) }
    val chatDefaultTask: String = root.getString("chat_default_task")
    val gateModel: String = root.getJSONObject("chat_gate").getString("model")
    val judgeModel: String = root.getJSONObject("chat_escalation").getString("small_judge")
    val judgeMinScore: Int = root.getJSONObject("chat_escalation").getInt("min_score")
    private val prompts: JSONObject = root.getJSONObject("step_prompts")
    val apps: List<AppDef> = list(root.getJSONArray("apps")) { a ->
        AppDef(a.getString("id"), a.getString("category"), a.getString("class"), a.getString("name"), a.getString("icon"), a.getString("description"),
            list(a.getJSONArray("pipeline")) { s -> Step(s.getString("macro"), strings(s.getJSONArray("models")), s.optString("backend").ifBlank { null }, s.optBoolean("alternatives"), s.optBoolean("escalation"), s.optString("note").ifBlank { null }) }, a.getJSONObject("ui").let { u -> val pr = u.getJSONObject("params"); AppUi(u.getString("template"), pr.optString("input_hint"), pr.optJSONArray("inputs")?.let { strings(it) } ?: emptyList(), pr.optString("output").ifBlank { null }) })
    }

    /** The apps a top filter keeps: those of its class, every app for the filter that has none. */
    fun appsIn(filter: String): List<AppDef> = appFilters.firstOrNull { it.id == filter }?.cls?.let { c -> apps.filter { it.cls == c } } ?: apps

    /** The glyph that marks [a]'s class on a row. */
    fun classGlyph(a: AppDef): String = appFilters.firstOrNull { it.cls == a.cls }?.glyph.orEmpty()

    /** The rows of a Models page: "small" / "medium" without retrieval models, "retrieval" = every retrieval model. */
    fun page(id: String): List<ModelRow> = if (id == "retrieval") rows.filter { it.macro == "retrieval" } else rows.filter { it.tier == id && it.macro != "retrieval" }

    /** The backend a step runs on: its own declaration, else local when the whole app is phone-capable, else OpenRouter. */
    fun backend(a: AppDef, s: Step): String = s.backend ?: if (phoneCapable(a)) "local" else "openrouter"

    /** The chat picker's models, in macro order: only rows with chat = true. */
    fun chatRows(): List<ModelRow> = macros.flatMap { m -> rows.filter { it.chat && it.macro == m.id } }

    /** The instruction a step of [macro] runs its model with. */
    fun stepPrompt(macro: String): String = prompts.optString(macro)

    fun macro(id: String): Macro? = macros.firstOrNull { it.id == id }
    fun row(model: String): ModelRow? = rows.firstOrNull { it.model == model }
    fun role(name: String): RoleInfo = root.getJSONObject("roles").optJSONObject(name)?.let { RoleInfo(it.getString("icon"), it.getString("purpose")) } ?: RoleInfo("memory", "")
    fun guidance(tier: String): String = root.getJSONObject("quant_guidance").optString(tier)

    /** The models a step needs in memory: all of them, or only the largest of its alternatives. */
    private fun stepModels(s: Step): List<ModelRow> {
        val rs = s.models.mapNotNull { row(it) }
        return if (s.alternatives) listOfNotNull(rs.maxByOrNull { it.ramQ4 }) else rs
    }

    /** The distinct models of an app's pipeline (the largest alternative of a step). */
    fun appModels(a: AppDef): List<ModelRow> = a.pipeline.flatMap { stepModels(it) }.distinctBy { it.model }

    /** Total 4-bit RAM of an app's distinct models. */
    fun appRamQ4(a: AppDef): Double = appModels(a).sumOf { it.ramQ4 }

    /** Total 16-bit disk of an app's distinct models. */
    fun appDiskFp16(a: AppDef): Double = appModels(a).sumOf { it.diskFp16 }

    /** True when the pipeline fits the phone at 4-bit: the total within the budget and no model above the per-model limit. */
    fun phoneCapable(a: AppDef): Boolean = appRamQ4(a) <= phoneBudgetQ4 && a.pipeline.flatMap { it.models }.mapNotNull { row(it) }.all { it.ramQ4 <= phoneEachQ4 }

    private fun <T> list(a: JSONArray, f: (JSONObject) -> T): List<T> = List(a.length()) { f(a.getJSONObject(it)) }
    private fun strings(a: JSONArray): List<String> = List(a.length()) { a.getString(it) }

    companion object {
        /** The runtime dataset (app/<package>/tools-models); never an asset. */
        const val DATASET = "tools-models"
    }
}
