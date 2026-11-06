package com.diegonmarcos.superapp.ai

import android.content.Context
import com.diegonmarcos.superapp.modelcatalogue.CatalogueFetch
import com.diegonmarcos.superapp.modelcatalogue.CatalogueStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** A price as the snapshot and OpenRouter's list carry it: [unit] tokens ($ per 1M), image, megapixel or second; null = not billed. */
data class AiModelPrice(val unit: String, val input: Double?, val output: Double?)

/** One model of the Model Catalogue: the fields every entry has (assets/ai/models-snapshot.json), whichever tab or search result it is. */
data class AiModelEntry(
    val id: String, val name: String, val provider: String,
    val modalitiesIn: List<String>, val modalitiesOut: List<String>,
    val context: Long, val price: AiModelPrice, val link: String,
    /** Which OpenRouter API serves it: chat | images | videos | embeddings. Only `chat` models can answer the chat. */
    val api: String,
    val note: String = "", val domain: String = "",
) {
    val chatCapable: Boolean get() = api == "chat"
    val outputsImage: Boolean get() = "image" in modalitiesOut
    fun takes(modality: String): Boolean = modality in modalitiesIn
    fun model(): AiModel = AiModel(id, name, context = context)

    /** "text+image->text": the modalities as one searchable word. */
    val modality: String get() = modalitiesIn.joinToString("+") + "->" + modalitiesOut.joinToString("+")
}

/** The tab rows under one header (`Core`), as model-tabs.json::layout declares them. */
data class AiTabBlock(val header: String, val rows: List<List<String>>)

data class AiSearchConfig(val url: String, val ttlMs: Long, val debounceMs: Long, val minChars: Int, val maxResults: Int)

/** The image in, image out routing knobs (model-tabs.json::image_flow). */
class AiImageFlowConfig(
    val imageTab: String, val textTab: String, val modalities: List<String>, val intentText: Regex, val intentEdit: Regex, val question: Regex, val maxAttachBytes: Long,
)

/**
 * The Model Catalogue's tabs as data (assets/ai/model-tabs.json = layout, labels, rules; assets/ai/models-snapshot.json = the baked
 * entries tools/gen-model-snapshot.py wrote from OpenRouter's metadata). Kotlin names no tab and no model: the layout, the labels and
 * the entries are the files'. A tab is shown in the order of [layout]; [entries] is its baked list.
 */
class AiModelTabs(tabs: JSONObject, snapshot: JSONObject) {
    val layout: List<AiTabBlock> = tabs.getJSONArray("layout").objs().map { b ->
        AiTabBlock(b.optString("header"), b.getJSONArray("rows").let { r -> (0 until r.length()).map { i -> r.getJSONArray(i).strings() } })
    }

    /** Every tab id in layout order (rows left to right, top to bottom). */
    val order: List<String> = layout.flatMap { it.rows.flatten() }

    val labels: Map<String, String> = tabs.getJSONObject("tabs").let { t -> t.keys().asSequence().associateWith { t.getJSONObject(it).getString("label") } }

    private val baked: Map<String, List<AiModelEntry>> = snapshot.getJSONObject("tabs").let { t -> t.keys().asSequence().associateWith { k -> t.getJSONArray(k).objs().map { entry(it) } } }

    /** How a provider id prefix is named on a row (model-tabs.json providers.labels): the live search names its results the same way. */
    val providerLabels: Map<String, String> = tabs.getJSONObject("providers").getJSONObject("labels").let { l -> l.keys().asSequence().associateWith { l.getString(it) } }

    /** The date the snapshot was generated. */
    val generated: String = snapshot.optString("generated")

    fun entries(tab: String): List<AiModelEntry> = baked[tab].orEmpty()

    /** Every baked entry once, by id (the lookup the image flow and a pick use). */
    val all: Map<String, AiModelEntry> = baked.values.flatten().associateBy { it.id }

    val search: AiSearchConfig = tabs.getJSONObject("search").let {
        AiSearchConfig(it.getString("url"), it.getLong("ttl_minutes") * 60_000L, it.getLong("debounce_ms"), it.getInt("min_chars"), it.getInt("max_results"))
    }

    val imageFlow: AiImageFlowConfig = tabs.getJSONObject("image_flow").let {
        AiImageFlowConfig(
            it.getString("image_tab"), it.getString("text_tab"), it.getJSONArray("modalities").strings(), Regex(it.getString("intent_text")), Regex(it.getString("intent_edit")), Regex(it.getString("question")),
            it.getLong("max_attach_bytes"),
        )
    }

    private val capabilities: JSONObject = tabs.getJSONObject("capabilities")

    /** Whether [e] has the declared capability (vision, image_out, audio_in, audio_out). */
    fun can(e: AiModelEntry, capability: String): Boolean {
        val c = capabilities.getJSONObject(capability)
        val i = c.optJSONArray("in_has")?.strings().orEmpty()
        val o = c.optJSONArray("out_has")?.strings().orEmpty()
        return i.all { it in e.modalitiesIn } && o.all { it in e.modalitiesOut }
    }

    companion object {
        const val TABS_ASSET = "ai/model-tabs.json"

        fun parse(tabs: String, snapshot: String) = AiModelTabs(JSONObject(tabs), JSONObject(snapshot))

        fun load(ctx: Context): AiModelTabs {
            fun text(p: String) = ctx.assets.open(p).bufferedReader().use { it.readText() }
            val tabs = JSONObject(text(TABS_ASSET))
            return AiModelTabs(tabs, JSONObject(text(tabs.getString("snapshot"))))
        }

        /** One entry of the snapshot (`tabs.<id>[]`). */
        fun entry(o: JSONObject): AiModelEntry {
            val m = o.getJSONObject("modalities")
            val p = o.getJSONObject("price")
            return AiModelEntry(
                o.getString("id"), o.getString("name"), o.getString("provider"), m.getJSONArray("in").strings(), m.getJSONArray("out").strings(),
                o.getLong("context"), AiModelPrice(p.getString("unit"), p.num("in"), p.num("out")), o.getString("link"), o.getString("api"),
                o.optString("note"), o.optString("domain"),
            )
        }

        private fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    }
}

private fun JSONArray.objs(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

/** The live OpenRouter model search: the list parsed to entries, and the filter by name, id, provider and modality. */
object AiModelSearch {
    /** OpenRouter's GET /api/v1/models as entries; a router (negative price) or an entry with no id is skipped. */
    fun parseLive(json: String, labels: Map<String, String> = emptyMap()): List<AiModelEntry> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.mapNotNull { m ->
            val id = m.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val a = m.optJSONObject("architecture")
            val p = m.optJSONObject("pricing")
            fun perMillion(k: String): Double? = p?.optString(k)?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { Math.round(it * 1e12) / 1e6 }
            val pin = perMillion("prompt")
            val pout = perMillion("completion")
            if (pin == null && pout == null) return@mapNotNull null
            val raw = m.optString("name", id)
            val prefix = id.substringBefore('/')
            AiModelEntry(
                id, if (':' in raw) raw.substringAfter(':').trim() else raw, labels[prefix] ?: if (':' in raw) raw.substringBefore(':').trim() else prefix,
                a?.optJSONArray("input_modalities")?.strings() ?: listOf("text"), a?.optJSONArray("output_modalities")?.strings() ?: listOf("text"),
                m.optLong("context_length"), AiModelPrice("tokens", pin, pout), "https://openrouter.ai/$id", "chat",
            )
        }
    }

    /**
     * Every whitespace-separated word of [query] must match: a plain word the name, id, provider or modalities (`image`, `audio`), `in:x` or
     * `out:x` one modality of that side. At most [max] results, the best match first (an id or name that starts with the word, then the rest).
     */
    fun filter(entries: List<AiModelEntry>, query: String, max: Int): List<AiModelEntry> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        fun hit(e: AiModelEntry, w: String): Boolean = when {
            w.startsWith("in:") -> e.modalitiesIn.any { it.contains(w.removePrefix("in:")) }
            w.startsWith("out:") -> e.modalitiesOut.any { it.contains(w.removePrefix("out:")) }
            else -> e.name.lowercase().contains(w) || e.id.lowercase().contains(w) || e.provider.lowercase().contains(w) || e.modality.contains(w)
        }
        fun rank(e: AiModelEntry): Int = if (words.any { e.name.lowercase().startsWith(it) || e.id.substringAfter('/').lowercase().startsWith(it) }) 0 else 1
        return entries.filter { e -> words.all { hit(e, it) } }.sortedWith(compareBy<AiModelEntry>({ rank(it) }, { it.name.lowercase() })).take(max)
    }
}

/**
 * The live list for the search box: fetched on demand only (no background polling), kept [ttlMs] in memory and in [store], so typing
 * again within the TTL asks nothing. A failed fetch answers the stale copy when there is one, else null.
 */
class AiModelListCache(
    private val url: String, private val ttlMs: Long, private val fetch: CatalogueFetch, private val store: CatalogueStore?,
    private val labels: Map<String, String> = emptyMap(), private val clock: () -> Long = System::currentTimeMillis,
) {
    private var memory: Pair<Long, List<AiModelEntry>>? = null

    @Synchronized fun get(): List<AiModelEntry>? {
        val now = clock()
        memory?.let { (at, list) -> if (now - at in 0 until ttlMs) return list }
        store?.get(KEY)?.let { hit ->
            if (now - hit.at in 0 until ttlMs) runCatching { AiModelSearch.parseLive(hit.body, labels) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { memory = hit.at to it; return it }
        }
        val body = runCatching { fetch.get(url) }.getOrNull()
        val fresh = body?.let { runCatching { AiModelSearch.parseLive(it, labels) }.getOrNull() }?.takeIf { it.isNotEmpty() }
        if (fresh != null) { memory = now to fresh; store?.put(KEY, body, now); return fresh }
        return memory?.second ?: store?.get(KEY)?.let { runCatching { AiModelSearch.parseLive(it.body, labels) }.getOrNull() }?.takeIf { it.isNotEmpty() }
    }

    companion object { const val KEY = "ai-model-list|v1" }
}

/** Models the user picked from the live search (not in the snapshot): remembered so the image flow knows their modalities. */
object AiKnownModels {
    private val extra = ConcurrentHashMap<String, AiModelEntry>()
    fun remember(e: AiModelEntry) { extra[e.id] = e }
    fun lookup(id: String): AiModelEntry? = extra[id]
    fun clear() = extra.clear()
}

/**
 * Image in, image out (and text to image, and image to text): which model answers a message. A picked model whose output includes an
 * image is used as picked; when the picked model draws none and the prompt asks for a picture, the Image tab's first chat-capable entry
 * (one that takes the attached image, when there is one) is chosen; an attached image with no such ask is a vision question for a model
 * that reads images. Every knob is model-tabs.json::image_flow; every model is the snapshot's.
 */
class AiImageFlow(private val tabs: AiModelTabs) {
    enum class Kind { TEXT, VISION, IMAGE }

    /** [model] null = keep the picked model. */
    data class Plan(val kind: Kind, val model: String?)

    private fun lookup(id: String): AiModelEntry? = tabs.all[id] ?: AiKnownModels.lookup(id)

    fun wantsImage(text: String, hasImage: Boolean): Boolean {
        val cfg = tabs.imageFlow
        if (cfg.question.containsMatchIn(text)) return false
        return if (hasImage) cfg.intentEdit.containsMatchIn(text) else cfg.intentText.containsMatchIn(text)
    }

    fun plan(text: String, hasImage: Boolean, picked: String): Plan {
        val pm = lookup(picked)
        if (pm != null && pm.chatCapable && pm.outputsImage) return Plan(Kind.IMAGE, picked)
        if (wantsImage(text, hasImage)) {
            val pool = tabs.entries(tabs.imageFlow.imageTab).filter { it.chatCapable && tabs.can(it, "image_out") }
            val pick = (if (hasImage) pool.firstOrNull { it.takes("image") } else pool.firstOrNull()) ?: return Plan(Kind.TEXT, null)
            return Plan(Kind.IMAGE, pick.id)
        }
        if (hasImage) {
            if (pm != null && pm.chatCapable && pm.takes("image")) return Plan(Kind.VISION, picked)
            val pick = tabs.entries(tabs.imageFlow.textTab).firstOrNull { it.chatCapable && tabs.can(it, "vision") } ?: return Plan(Kind.TEXT, null)
            return Plan(Kind.VISION, pick.id)
        }
        return Plan(Kind.TEXT, null)
    }
}

/** The words a model row says: its context window, its price and its modalities. */
object AiModelText {
    fun context(n: Long): String = when {
        n <= 0 -> "-"
        n >= 1_000_000 -> trim(n / 1_000_000.0) + "M"
        else -> (n / 1000).toString() + "K"
    }

    private fun trim(v: Double): String = String.format(java.util.Locale.ROOT, "%.1f", v).removeSuffix(".0")

    fun price(p: AiModelPrice): String {
        val per = when (p.unit) { "image" -> "/img"; "megapixel" -> "/MP"; "second" -> "/s"; else -> "/1M" }
        return listOfNotNull(p.input?.let { "in " + com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue.usd(it) }, p.output?.let { "out " + com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue.usd(it) }).joinToString(" · ") + per
    }

    /** "1M ctx · in $2.00 · out $10.00/1M · text+image->text". */
    fun spec(e: AiModelEntry): String = listOf(context(e.context).takeIf { e.context > 0 }?.plus(" ctx"), price(e.price), e.modality).filterNotNull().joinToString(" · ")
}
