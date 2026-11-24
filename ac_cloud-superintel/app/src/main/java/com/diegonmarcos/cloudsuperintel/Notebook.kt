package com.diegonmarcos.cloudsuperintel

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/** A user source: [kind] is note | url | doc; [text] is its full text, kept on the phone. */
data class Source(val id: String, val title: String, val kind: String, val text: String, val origin: String = "")

/** One cut of a source: [n] is the source's number in its notebook (1-based, what a citation says). */
data class Passage(val n: Int, val source: Source, val index: Int, val text: String)

/** The notebook's declared knobs (the runtime dataset `notebook`, #1547: served by Cloud Store from data/fleet/app/<pkg>/notebook.json, never an APK asset). */
data class NotebookConfig(
    val chunkChars: Int, val overlapChars: Int, val topK: Int, val minCoverage: Double, val k1: Double, val b: Double,
    val refusal: String, val groundingSystem: String, val groundingUser: String,
    val maxSourceChars: Int, val generatorSystem: String, val generators: List<Generator>,
    val urlMaxChars: Int, val docMime: List<String>,
    val semantic: Semantic? = null,
) {
    data class Generator(val id: String, val label: String, val prompt: String)

    /**
     * The fleet embeddings + rerank service's knobs (retrieval.semantic): the served model names, how many candidates go to
     * the reranker, the floors that keep grounding strict (a passage needs [minRerank] from the reranker, or [minCosine]
     * when only embeddings answered, or the BM25 coverage floor), batching and timeouts. Absent = BM25 only.
     */
    data class Semantic(
        val embedModel: String, val rerankModel: String, val candidates: Int, val minRerank: Double, val minCosine: Double,
        val embedBatch: Int, val probeMs: Int, val readMs: Int, val pdfReadMs: Int, val retryAfterMs: Long,
    )

    companion object {
        /** The app's own served dataset (`app/<pkg>/notebook`). */
        const val DATASET = "notebook"

        /**
         * What the notebook uses while the dataset is loading or not served yet (first run, provider absent): the strict grounding prompt, the
         * refusal line, the retrieval floors and the studio prompts, so it never answers from memory and never crashes. BM25 only: the
         * semantic service knobs come with the served copy. A test holds every field here equal to the served file's.
         */
        val DEFAULT = NotebookConfig(
            chunkChars = 900, overlapChars = 150, topK = 6, minCoverage = 0.5, k1 = 1.5, b = 0.75,
            refusal = "This is not in your sources.",
            groundingSystem = "You answer ONLY from the numbered passages the user supplies. Rules: use no outside knowledge, not even common knowledge; cite the passage number in square brackets, like [2], after every claim; if the passages do not contain the answer, reply with exactly: This is not in your sources.\nDo not guess, do not extend, do not answer from memory.",
            groundingUser = "PASSAGES\n{{passages}}\n\nQUESTION\n{{question}}",
            maxSourceChars = 24000,
            generatorSystem = "You work ONLY from the sources below. Use no outside knowledge. Cite sources as [n] where n is the source number. If the sources are too thin, say This is not in your sources.",
            generators = listOf(
                Generator("summary", "Summary", "Write a concise summary of the sources: the main points as short bullets, each with its [n] citation."),
                Generator("faq", "FAQ", "Write an FAQ of 5 to 8 questions a reader of these sources would ask, each followed by an answer taken only from the sources, with [n] citations."),
                Generator("notes", "Study notes", "Write structured study notes: headings, key terms with definitions, and key facts, each fact with its [n] citation."),
            ),
            urlMaxChars = 200000, docMime = listOf("text/plain", "text/markdown", "text/html", "text/csv", "application/json"),
        )

        /** The served config, now and after every update Cloud Store announces; null while nothing valid is served (the caller shows [DEFAULT]). */
        fun observe(ctx: Context): Flow<NotebookConfig?> = FleetData.observe(ctx, DATASET, own = true) { runCatching { parse(it) }.getOrNull() }

        /** One blocking read of the served config (a few ms), else [DEFAULT]. */
        fun current(ctx: Context): NotebookConfig = FleetData.get(ctx, DATASET, own = true) { runCatching { parse(it) }.getOrNull() } ?: DEFAULT
        fun parse(text: String): NotebookConfig {
            val r = JSONObject(text)
            val ret = r.getJSONObject("retrieval"); val g = r.getJSONObject("grounding"); val gen = r.getJSONObject("generators")
            val refusal = r.getString("refusal")
            val gens = gen.keys().asSequence().filter { it != "max_source_chars" && it != "system" }.map { k ->
                val o = gen.getJSONObject(k); Generator(k, o.getString("label"), o.getString("prompt"))
            }.toList()
            val s = r.getJSONObject("sources")
            val sem = ret.optJSONObject("semantic")?.let { o ->
                Semantic(o.getString("embed_model"), o.getString("rerank_model"), o.getInt("candidates"), o.getDouble("min_rerank"), o.getDouble("min_cosine"),
                    o.getInt("embed_batch"), o.getInt("probe_ms"), o.getInt("read_ms"), o.getInt("pdf_read_ms"), o.getLong("retry_after_ms"))
            }
            return NotebookConfig(
                ret.getInt("chunk_chars"), ret.getInt("overlap_chars"), ret.getInt("top_k"), ret.getDouble("min_coverage"), ret.getDouble("k1"), ret.getDouble("b"),
                refusal, g.getString("system").replace("{{refusal}}", refusal), g.getString("user"),
                gen.getInt("max_source_chars"), gen.getString("system").replace("{{refusal}}", refusal), gens,
                s.getInt("url_max_chars"), s.getJSONArray("doc_mime").let { a -> (0 until a.length()).map { a.getString(it) } },
                sem,
            )
        }
    }
}

/** What a grounded question ended with: [answer] with its [cited] passages, or [grounded] false = the refusal. */
data class Grounded(val answer: String, val cited: List<Passage>, val grounded: Boolean)

/**
 * The fleet's embeddings + rerank service as the notebook sees it. Every call returns null when the service does not answer
 * (off the mesh, down, an unexpected reply), and the notebook then ranks with BM25 alone.
 */
interface Ranker {
    /** One vector per text, in order. */
    fun embed(texts: List<String>): List<DoubleArray>?
    /** One relevance score per document, in order (higher = more relevant). */
    fun rerank(query: String, docs: List<String>): List<Double>?
}

/** Source-grounded retrieval and answer checking, pure (no network, no Android): the model call is injected. */
object Notebook {
    private val word = Regex("[\\p{L}\\p{N}]+")
    private val stop = setOf("the", "a", "an", "of", "and", "or", "to", "in", "on", "is", "are", "was", "were", "it", "this", "that", "for", "with", "as", "by", "at", "be", "what", "which", "who", "how", "do", "does", "did", "i", "you", "my", "me")

    fun tokens(s: String): List<String> = word.findAll(s.lowercase()).map { it.value }.filter { it !in stop }.toList()

    /** Cuts [s] into passages of about [chars] with [overlap], preferring to end on whitespace. */
    fun chunks(s: String, chars: Int, overlap: Int): List<String> {
        val t = s.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return emptyList()
        if (t.length <= chars) return listOf(t)
        val out = ArrayList<String>()
        var start = 0
        while (start < t.length) {
            var end = minOf(start + chars, t.length)
            if (end < t.length) { val sp = t.lastIndexOf(' ', end); if (sp > start + chars / 2) end = sp }
            out.add(t.substring(start, end).trim())
            if (end >= t.length) break
            var next = maxOf(end - overlap, start + 1)
            if (next < t.length && t[next - 1] != ' ') { val sp = t.indexOf(' ', next); next = if (sp < 0) t.length else sp + 1 }
            start = next
        }
        return out
    }

    fun passages(sources: List<Source>, cfg: NotebookConfig): List<Passage> =
        sources.flatMapIndexed { i, s -> chunks(s.text, cfg.chunkChars, cfg.overlapChars).mapIndexed { j, c -> Passage(i + 1, s, j, c) } }

    /** BM25 over [all], best first; only passages holding at least [NotebookConfig.minCoverage] of the question's terms are evidence. */
    fun retrieve(question: String, all: List<Passage>, cfg: NotebookConfig): List<Pair<Passage, Double>> {
        val q = tokens(question).distinct()
        if (q.isEmpty() || all.isEmpty()) return emptyList()
        val docs = all.map { tokens(it.text) }
        val avg = docs.map { it.size }.average().coerceAtLeast(1.0)
        val df = q.associateWith { t -> docs.count { t in it } }
        val n = all.size.toDouble()
        return all.indices.map { i ->
            val d = docs[i]
            val tf = d.groupingBy { it }.eachCount()
            val score = q.sumOf { t ->
                val f = (tf[t] ?: 0).toDouble()
                if (f == 0.0) 0.0 else ln(1 + (n - df.getValue(t) + 0.5) / (df.getValue(t) + 0.5)) * f * (cfg.k1 + 1) / (f + cfg.k1 * (1 - cfg.b + cfg.b * d.size / avg))
            }
            Triple(all[i], score, q.count { t -> t in tf }.toDouble() / q.size)
        }.filter { it.third >= cfg.minCoverage && it.second > 0 }.map { it.first to it.second }.sortedByDescending { it.second }.take(cfg.topK)
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }

    /**
     * Retrieval with the fleet service when it answers: dense candidates (embeddings) plus the BM25 hits, re-scored by the
     * reranker. Grounding stays strict: a passage is evidence only with [NotebookConfig.Semantic.minRerank] from the reranker
     * (or, when only embeddings answered, [NotebookConfig.Semantic.minCosine], or the BM25 coverage floor), so an out-of-scope
     * question still finds nothing and the model is not called. No service, no declaration or any failure = [retrieve] (BM25).
     */
    fun retrieveHybrid(question: String, all: List<Passage>, cfg: NotebookConfig, ranker: Ranker?): List<Pair<Passage, Double>> {
        val sem = cfg.semantic
        if (ranker == null || sem == null || question.isBlank() || all.isEmpty()) return retrieve(question, all, cfg)
        val vecs = runCatching { ranker.embed(listOf(question) + all.map { it.text }) }.getOrNull()
        if (vecs == null || vecs.size != all.size + 1) return retrieve(question, all, cfg)
        val cos = all.indices.map { cosine(vecs[0], vecs[it + 1]) }
        val dense = all.indices.sortedByDescending { cos[it] }.take(sem.candidates)
        val lexical = retrieve(question, all, cfg.copy(topK = sem.candidates)).map { hit -> all.indexOfFirst { it === hit.first } }.filter { it >= 0 }
        val cands = (dense + lexical).distinct()
        val rr = runCatching { ranker.rerank(question, cands.map { all[it].text }) }.getOrNull()
        val scored: List<Pair<Int, Double>> =
            if (rr != null && rr.size == cands.size) cands.zip(rr).filter { it.second >= sem.minRerank }
            else cands.filter { cos[it] >= sem.minCosine || it in lexical }.map { it to cos[it] }
        return scored.sortedByDescending { it.second }.take(cfg.topK).map { all[it.first] to it.second }
    }

    /** The wire messages (system, user) for a question over [found]; passages are numbered by SOURCE, so a citation names the source. */
    fun prompt(question: String, found: List<Passage>, cfg: NotebookConfig): Pair<String, String> {
        val p = found.joinToString("\n\n") { "[${it.n}] (${it.source.title}) ${it.text}" }
        return cfg.groundingSystem to cfg.groundingUser.replace("{{passages}}", p).replace("{{question}}", question)
    }

    private val cite = Regex("\\[(\\d+)]")

    /**
     * Holds the model to its sources: the refusal line passes as a refusal; an answer must cite at least one source
     * that was actually supplied, and every citation must be one of them, else it is replaced by the refusal.
     */
    fun check(reply: String, found: List<Passage>, cfg: NotebookConfig): Grounded {
        val text = reply.trim()
        val refuse = Grounded(cfg.refusal, emptyList(), false)
        if (text.isEmpty() || text.contains(cfg.refusal.trimEnd('.'), ignoreCase = true)) return refuse
        val ns = cite.findAll(text).map { it.groupValues[1].toInt() }.toList()
        val allowed = found.map { it.n }.toSet()
        if (ns.isEmpty() || ns.any { it !in allowed }) return refuse
        val cited = ns.distinct().mapNotNull { n -> found.firstOrNull { it.n == n } }
        return Grounded(text, cited, true)
    }

    /**
     * One grounded question: retrieval first; with no passage above the floor the model is not called at all.
     * [ask] sends (system, user) to the model and returns its text.
     */
    fun answer(question: String, sources: List<Source>, cfg: NotebookConfig, ask: (String, String) -> String): Grounded =
        answerWith(question, sources, cfg, null, ask)

    /** [answer] with the fleet embeddings + rerank service ([ranker]) when it is given and answers; BM25 otherwise. */
    fun answerWith(question: String, sources: List<Source>, cfg: NotebookConfig, ranker: Ranker?, ask: (String, String) -> String): Grounded {
        val found = retrieveHybrid(question, passages(sources, cfg), cfg, ranker).map { it.first }
        if (found.isEmpty()) return Grounded(cfg.refusal, emptyList(), false)
        val (sys, user) = prompt(question, found, cfg)
        return check(ask(sys, user), found, cfg)
    }

    /** The studio generators' user message: the numbered sources, cut to the declared size, then the generator's prompt. */
    fun generatorPrompt(g: NotebookConfig.Generator, sources: List<Source>, cfg: NotebookConfig): Pair<String, String> {
        val body = StringBuilder()
        sources.forEachIndexed { i, s ->
            val room = cfg.maxSourceChars - body.length
            if (room > 0) body.append("SOURCE [${i + 1}] ${s.title}\n").append(s.text.take(room)).append("\n\n")
        }
        return cfg.generatorSystem to "$body${g.prompt}"
    }

    /** Plain text of an HTML page: script and style dropped, tags dropped, entities for the common few decoded. */
    fun htmlText(html: String): String = html
        .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?s)<!--.*?-->"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()

    fun title(html: String, fallback: String): String =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { htmlText(it) }?.takeIf { it.isNotBlank() } ?: fallback
}

/** A notebook: its name and its sources, and the questions asked of it (the chat, kept so it survives a restart). */
data class NotebookDoc(val id: String, val name: String, val sources: List<Source> = emptyList(), val chat: List<ChatTurn> = emptyList(), val outputs: List<ChatTurn> = emptyList())

/** One turn: [grounded] false on an assistant turn = the not-in-your-sources refusal. [cited] are source numbers. */
data class ChatTurn(val role: String, val text: String, val grounded: Boolean = true, val cited: List<Int> = emptyList(), val title: String = "", val passages: List<String> = emptyList()) {
    val fromUser: Boolean get() = role == "user"
}

/** The notebooks in one JSON file (filesDir), read and written whole. */
class NotebookStore(private val file: java.io.File) {
    @Synchronized fun all(): List<NotebookDoc> = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    @Synchronized fun put(d: NotebookDoc) = write(listOf(d) + all().filterNot { it.id == d.id })
    @Synchronized fun delete(id: String) = write(all().filterNot { it.id == id })
    private fun write(l: List<NotebookDoc>) { file.parentFile?.mkdirs(); file.writeText(toJson(l)) }

    companion object {
        fun toJson(l: List<NotebookDoc>): String = JSONArray(l.map { d ->
            JSONObject().put("id", d.id).put("name", d.name)
                .put("sources", JSONArray(d.sources.map { JSONObject().put("id", it.id).put("title", it.title).put("kind", it.kind).put("text", it.text).put("origin", it.origin) }))
                .put("chat", turns(d.chat)).put("outputs", turns(d.outputs))
        }).toString()

        private fun turns(l: List<ChatTurn>) = JSONArray(l.map {
            JSONObject().put("role", it.role).put("text", it.text).put("grounded", it.grounded).put("cited", JSONArray(it.cited)).put("title", it.title).put("passages", JSONArray(it.passages))
        })

        private fun readTurns(a: JSONArray?): List<ChatTurn> = a?.let { x ->
            (0 until x.length()).map { x.getJSONObject(it) }.map { o ->
                ChatTurn(o.getString("role"), o.getString("text"), o.optBoolean("grounded", true),
                    o.optJSONArray("cited")?.let { c -> (0 until c.length()).map { c.getInt(it) } } ?: emptyList(), o.optString("title"),
                    o.optJSONArray("passages")?.let { c -> (0 until c.length()).map { c.getString(it) } } ?: emptyList())
            }
        } ?: emptyList()

        fun parse(text: String): List<NotebookDoc> {
            val a = JSONArray(text)
            return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val s = o.getJSONArray("sources")
                NotebookDoc(o.getString("id"), o.getString("name"),
                    (0 until s.length()).map { s.getJSONObject(it) }.map { Source(it.getString("id"), it.getString("title"), it.getString("kind"), it.getString("text"), it.optString("origin")) },
                    readTurns(o.optJSONArray("chat")), readTurns(o.optJSONArray("outputs")))
            }
        }
    }
}
