package com.diegonmarcos.cloudc3.pages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * #1983 Ops > Health > Tests lists EVERY full-test engine of cloudy-u-containers infra-obs_reports/src/src (all Rust, owner rule: apps only trigger
 * them and render their reports; NO check logic here). Each engine is a card: last run, verdict, duration, pass/warn/fail, drill-down per phase and
 * per check with the check's own evidence fields, history, and a Run button that goes through the API's allow-listed `POST /reports/run`.
 *
 * Read path (no token in the APK): the device's own Authelia bearer against c3-infra-api. `GET /reports/status` (freshness per family) and
 * `GET /reports/runs` (history) are served; the report BODY lives in cloud-data, a PRIVATE repo, so it needs a `GET /reports/data/<family>` proxy
 * on the API, which is not served yet (#1977): the card then prints that reason instead of numbers.
 */
object EnginesModel {

    /** One engine: [dir] is its folder under infra-obs_reports/src/src, [family] the published `<family>.json`, [kind] the `/reports/run` kind (null = none). */
    data class Engine(val id: String, val dir: String, val family: String?, val kind: String?, val rust: Boolean, val blurb: String, val note: String = "")

    /** Folders under infra-obs_reports/src/src that are libraries, not engines (a new folder that is in neither list fails the guard). */
    val LIBS = listOf("reports-common")

    val ENGINES = listOf(
        Engine("mail", "cloud-mail-health-full", "cloud_mail_full", "mail", true, "extensive mail full test: 9 phases (path, KPIs, preflight, containers, network, DNS/auth, internals, config drift, e2e delivery)",
            "workflow health_mail_full.yml still runs the shell script (parity with the Rust engine pending, #1983); Run dispatches the Rust engine through cloud-health-reports"),
        Engine("daily", "cloud-health-full-daily", "cloud_health_daily", "daily", true, "daily full health: VMs, endpoints, certificates, DNS, drift, firewalls, issues"),
        Engine("url", "cloud-url-health-report", "cloud_url_health", "url", true, "public and private URL health, email end to end"),
        Engine("sec-network", "cloud-sec-network-report", "cloud_sec_network", "sec-network", true, "network security: port scan, firewall, TLS, DNS, WireGuard"),
        Engine("sec-data", "cloud-sec-data-report", "cloud_sec_data", "sec-data", true, "data security: repo scan, YARA, SIEM, threat intel, journal"),
        Engine("analytics", "cloud-analytics-daily", null, null, false, "analytics daily (Matomo, Umami)",
            "shell scripts, not a Rust engine: it publishes .md/.html only and /reports/run has no analytics kind (rewrite in Rust is #1978)"),
    )

    /** The kinds the app may ask `/reports/run` for; the same enum the API validates (REPORT_KINDS in c3-infra-api reports.ts). */
    val RUN_KINDS = listOf("all", "daily", "daily-mail", "mail", "url", "sec-network", "sec-data")

    /** The request body for a run, or null when [kind] is not allow-listed (the app never sends it). */
    fun runBody(kind: String?): String? = if (kind != null && kind in RUN_KINDS) "{\"report\":\"$kind\",\"runner\":\"gha\"}" else null

    /** What a Run button says: the engine's trigger, or why there is none. */
    fun runLabel(e: Engine): String = if (runBody(e.kind) != null) "Run" else "Run: no trigger (${e.note.ifBlank { "no /reports/run kind" }})"

    /** The engines an X-ray (VM, service, full) is built from: shown under the X-ray tabs. */
    val FEEDS = mapOf(
        "full" to listOf("daily", "url", "sec-network", "sec-data", "mail"),
        "vms" to listOf("daily", "sec-network"),
        "services" to listOf("daily", "url", "mail"),
    )

    const val BODY_GAP = "report body not served: cloud-data is a private repo (no token in the APK) and c3-infra-api has no GET /reports/data/<family> route yet (#1977)"
    const val STATUS_GAP = "freshness not served: GET /reports/status does not list this family"

    // ── what was read ───────────────────────────────────────────────────────

    data class Fresh(val family: String, val ok: Boolean, val lastModified: String, val ageHours: Int?, val stale: Boolean, val error: String)
    data class Run(val runner: String, val id: String, val status: String, val conclusion: String, val createdAt: String, val updatedAt: String, val event: String) {
        val seconds: Long? get() = runCatching { (java.time.Instant.parse(updatedAt).toEpochMilli() - java.time.Instant.parse(createdAt).toEpochMilli()) / 1000 }.getOrNull()
    }

    /** One check of a phase: [status] is pass | warn | fail | info; [fields] are the check's own evidence fields, every one of them. */
    data class Chk(val name: String, val status: String, val fields: List<Pair<String, String>>)
    data class Phase(val id: String, val checks: List<Chk>) {
        val pass get() = checks.count { it.status == "pass" }
        val warn get() = checks.count { it.status == "warn" }
        val fail get() = checks.count { it.status == "fail" }
    }
    data class Parsed(val generated: String, val durationMs: Long?, val pass: Int, val warn: Int, val fail: Int, val phases: List<Phase>, val extra: List<Pair<String, String>>) {
        val verdict get() = if (fail > 0) "FAIL" else if (warn > 0) "WARN" else "PASS"
    }

    /** What reading one engine's body gave: a parsed report, or the reason there is none. */
    sealed class Body {
        object Idle : Body()
        data class Why(val why: String) : Body()
        data class Got(val report: Parsed) : Body()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun parseObj(t: String) = runCatching { json.parseToJsonElement(t) as? JsonObject }.getOrNull()
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.o(k: String) = this[k] as? JsonObject

    fun parseStatus(body: String): Map<String, Fresh>? {
        val o = parseObj(body) ?: return null
        return (o["families"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.associate { f ->
            val ok = f.s("ok") == "true"
            f.s("family") to Fresh(f.s("family"), ok, f.s("lastModified"), f.l("ageHours")?.toInt(), f.s("stale") == "true", f.s("error").ifBlank { if (ok) "" else "HTTP ${f.s("status")}" })
        }
    }

    fun parseRuns(body: String): List<Run>? {
        val o = parseObj(body) ?: return null
        return (o["runs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
            Run(it.s("runner"), it.s("id"), it.s("status"), it.s("conclusion"), it.s("createdAt"), it.s("updatedAt"), it.s("event"))
        }
    }

    private fun prim(v: kotlinx.serialization.json.JsonElement): String = when (v) {
        is JsonNull -> "null"
        is JsonPrimitive -> v.content
        is JsonArray -> v.joinToString(", ", "[", "]") { prim(it) }
        is JsonObject -> v.entries.joinToString(", ", "{", "}") { "${it.key}=${prim(it.value)}" }
    }

    private val NAME_KEYS = listOf("name", "domain", "vm_alias", "vm", "url", "message", "id", "ip")
    private val DAILY_PHASES = listOf("top_issues", "vms", "dns", "drift", "certs", "firewalls")

    private fun chk(o: JsonObject): Chk {
        val name = NAME_KEYS.firstNotNullOfOrNull { k -> o.s(k).takeIf { it.isNotBlank() } } ?: "(unnamed)"
        val passed = (o["passed"] as? JsonPrimitive)?.contentOrNull
        val ok = (o["ok"] as? JsonPrimitive)?.contentOrNull
        val sev = o.s("severity").lowercase()
        val status = when {
            passed != null -> if (passed == "true") "pass" else if (sev == "critical") "fail" else "warn"
            ok != null -> if (ok == "true") "pass" else "fail"
            sev == "crit" || sev == "critical" -> "fail"
            sev == "warn" || sev == "warning" -> "warn"
            else -> "info"
        }
        return Chk(name, status, o.entries.map { it.key to prim(it.value) })
    }

    /** null when [text] is not a JSON object. Counts come from the engine's own summary when it has one, else from the checks. */
    fun parse(e: Engine, text: String): Parsed? {
        val o = parseObj(text) ?: return null
        val phases = o.entries.mapNotNull { (k, v) ->
            val a = (v as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return@mapNotNull null
            if (a.isEmpty()) return@mapNotNull null
            if (e.id == "daily") { if (k !in DAILY_PHASES) null else Phase(k, a.map(::chk)) }
            else Phase(k, a.map(::chk))
        }.toMutableList()
        if (e.id == "daily") (o.o("exec_summary")?.get("top_issues") as? JsonArray)?.mapNotNull { it as? JsonObject }?.takeIf { it.isNotEmpty() }?.let { phases.add(0, Phase("top_issues", it.map(::chk))) }
        val sum = o.o("summary") ?: o.o("exec_summary")
        val all = phases.flatMap { it.checks }
        val (p, w, f) = when {
            sum != null && sum["passed"] != null -> Triple(sum.l("passed")?.toInt() ?: 0, ((sum.l("failed") ?: 0) - (sum.l("critical") ?: 0)).coerceAtLeast(0).toInt(), (sum.l("critical") ?: 0).toInt())
            sum != null && sum["public_ok"] != null -> Triple(((sum.l("public_ok") ?: 0) + (sum.l("private_ok") ?: 0)).toInt(), 0, (((sum.l("public_total") ?: 0) - (sum.l("public_ok") ?: 0)) + ((sum.l("private_total") ?: 0) - (sum.l("private_ok") ?: 0))).toInt())
            sum != null && sum["ok"] != null -> Triple(sum.l("ok")?.toInt() ?: 0, sum.l("warnings")?.toInt() ?: 0, sum.l("critical")?.toInt() ?: 0)
            else -> Triple(all.count { it.status == "pass" }, all.count { it.status == "warn" }, all.count { it.status == "fail" })
        }
        val generated = o.s("generated").ifBlank { listOf(o.s("date"), o.s("time")).filter { it.isNotBlank() }.joinToString(" ") }
        val dur = o.l("duration_ms") ?: o.l("generation_duration_ms") ?: sum?.l("total_ms")
        val extra = buildList<Pair<String, String>> {
            sum?.entries?.filter { it.value !is JsonArray }?.let { es -> if (es.isNotEmpty()) add("summary" to es.joinToString("  ") { "${it.key} ${prim(it.value)}" }) }
            o.o("timers")?.let { t -> add("timers_ms" to t.entries.sortedBy { it.key }.joinToString("  ") { "${it.key} ${prim(it.value)}" }) }
            o.o("email")?.let { add("email" to prim(it)) }
        }
        return Parsed(generated, dur, p, w, f, phases, extra)
    }

    fun classify(e: Engine, r: FleetLive.Result): Body = when (r) {
        is FleetLive.Result.Failed -> when {
            r.why.startsWith("NOT_FOUND") -> Body.Why(BODY_GAP)
            r.why.startsWith("NO_TOKEN") -> Body.Why("device: ${HealthModel.NO_TOKEN_NOTE}")
            else -> Body.Why("unreachable from here: ${r.why}")
        }
        is FleetLive.Result.Body -> parse(e, r.text)?.let { Body.Got(it) } ?: Body.Why("the answer is not a JSON report")
    }

    // ── views ───────────────────────────────────────────────────────────────

    fun durationText(ms: Long?): String = when {
        ms == null -> "duration n/a"
        ms < 1000 -> "$ms ms"
        else -> String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
    }

    fun runSeconds(r: Run): String = r.seconds?.let { "${it}s" } ?: "n/a"

    /** The card's one verdict line source: the parsed verdict, or the reason there is none. */
    fun verdictText(b: Body): String = when (b) {
        Body.Idle -> "not read yet"
        is Body.Why -> "no verdict: ${b.why}"
        is Body.Got -> b.report.verdict
    }

    fun freshText(f: Fresh?): String = when {
        f == null -> STATUS_GAP
        !f.ok && f.lastModified.isBlank() -> "never published or not readable: ${f.error.ifBlank { "no commit for this family" }}"
        else -> "published ${f.lastModified} (${f.ageHours ?: "?"} h ago)" + if (f.stale) " STALE" else ""
    }
}
