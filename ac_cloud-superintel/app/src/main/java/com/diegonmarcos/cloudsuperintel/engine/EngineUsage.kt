package com.diegonmarcos.cloudsuperintel.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * #1855 P2 the engine's cost metering (IAiEngine.usage): what the backends REPORTED per answer (tokens, and the cost when the
 * backend gives one), summed per UTC day, provider and model, on disk in `engine-usage.json` (no prompt, no answer, no key is stored).
 * [summary] is the JSON the Cost window reads. Nothing is estimated: an answer that reported no usage counts as a request only.
 */
class EngineUsage(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private class Cell(var requests: Long = 0, var prompt: Long = 0, var completion: Long = 0, var cost: Double = 0.0, var costed: Boolean = false)

    private val cells = LinkedHashMap<String, Cell>()

    init { load() }

    private fun day(at: Long) = Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC).toLocalDate().toString()
    private fun key(day: String, provider: String, model: String) = listOf(day, provider, model).joinToString("\t")

    @Synchronized private fun load() {
        val a = runCatching { JSONArray(file.takeIf { it.isFile }?.readText() ?: "[]") }.getOrNull() ?: return
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            cells[key(o.optString("day"), o.optString("provider"), o.optString("model"))] =
                Cell(o.optLong("requests"), o.optLong("prompt"), o.optLong("completion"), o.optDouble("cost", 0.0), o.optBoolean("costed"))
        }
    }

    /** One finished answer. [prompt]/[completion] 0 and [cost] null = it reported nothing. */
    @Synchronized fun record(provider: String, model: String?, prompt: Long, completion: Long, cost: Double?) {
        val c = cells.getOrPut(key(day(clock()), provider, model.orEmpty().ifEmpty { "-" })) { Cell() }
        c.requests++; c.prompt += prompt; c.completion += completion
        if (cost != null) { c.cost += cost; c.costed = true }
        prune()
        save()
    }

    /** Days older than [KEEP_DAYS] are dropped, so the file stays small. */
    private fun prune() {
        val oldest = day(clock() - KEEP_DAYS * 86_400_000L)
        cells.keys.removeAll { it.substringBefore('\t') < oldest }
    }

    private fun save() {
        val a = JSONArray()
        cells.forEach { (k, c) ->
            val f = k.split('\t')
            a.put(JSONObject().put("day", f[0]).put("provider", f[1]).put("model", f[2]).put("requests", c.requests)
                .put("prompt", c.prompt).put("completion", c.completion).put("cost", c.cost).put("costed", c.costed))
        }
        runCatching { file.parentFile?.mkdirs(); file.writeText(a.toString()) }
    }

    private fun total(rows: Collection<Map.Entry<String, Cell>>): JSONObject {
        var r = 0L; var p = 0L; var c = 0L; var cost = 0.0; var costed = false
        rows.forEach { (_, x) -> r += x.requests; p += x.prompt; c += x.completion; cost += x.cost; costed = costed || x.costed }
        return JSONObject().put("requests", r).put("prompt", p).put("completion", c).put("total", p + c).also { if (costed) it.put("cost", cost) }
    }

    /**
     * {"version":1,"today":{..},"month":{..},"all":{..},"days":[{"day":..,totals,"providers":[..]}],"models":[{provider,model,totals}]}
     * where totals are requests, prompt, completion, total tokens and cost (USD, present only when a backend reported one).
     * "{}" when nothing was metered.
     */
    @Synchronized fun summary(): String {
        if (cells.isEmpty()) return "{}"
        val today = day(clock())
        val month = today.substring(0, 7)
        fun merge(o: JSONObject, extra: JSONObject) = extra.also { e -> o.keys().forEach { k -> e.put(k, o.get(k)) } }
        val days = cells.entries.groupBy { it.key.substringBefore('\t') }.toSortedMap(compareByDescending<String> { it }).map { (d, rows) ->
            merge(total(rows), JSONObject().put("day", d)).put("providers", JSONArray(rows.map { it.key.split('\t')[1] }.distinct()))
        }
        val models = cells.entries.groupBy { it.key.split('\t').let { f -> f[1] + "\t" + f[2] } }.map { (pm, rows) ->
            merge(total(rows), JSONObject().put("provider", pm.substringBefore('\t')).put("model", pm.substringAfter('\t')))
        }
        return JSONObject().put("version", 1)
            .put("today", total(cells.entries.filter { it.key.startsWith(today) }))
            .put("month", total(cells.entries.filter { it.key.startsWith(month) }))
            .put("all", total(cells.entries))
            .put("days", JSONArray(days)).put("models", JSONArray(models)).toString()
    }

    companion object { const val KEEP_DAYS = 62L }
}
