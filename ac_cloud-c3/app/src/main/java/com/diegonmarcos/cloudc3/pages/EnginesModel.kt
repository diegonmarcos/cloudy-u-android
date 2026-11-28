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
    data class Engine(
        val id: String, val dir: String, val family: String?, val kind: String?, val rust: Boolean, val blurb: String, val note: String = "",
        /** Where the source lives: reports (infra-obs_reports), workflow (cloudy-t-infra .github/workflows), ops (1_cicd/src/ops), sec (a container). */
        val origin: String = "reports",
        /** The tag the card carries: rust, gha, shell, or container. Everything that is not Rust names its rewrite (#1978). */
        val tag: String = if (rust) "rust" else "shell",
        /** The cloudy-t-infra workflow whose runs give the card's last run and verdict (read without a token, the repo is public). */
        val workflow: String? = null,
        /** The workflow file the Run button dispatches; only a file in [DISPATCH] is ever sent. */
        val dispatch: String? = null,
    ) {
        val needsRewrite get() = tag == "shell" || tag == "gha"
    }

    const val REWRITE = "not Rust: the Rust rewrite of this check belongs to #1978"
    const val INFRA_REPO = "diegonmarcos/cloudy-t-infra"
    const val INFRA_OWNER = "diegonmarcos"
    const val INFRA_NAME = "cloudy-t-infra"

    /** The workflows the app may dispatch (the same list the card Run buttons use); each is a test with no deploy path and no required input. */
    val DISPATCH = listOf("leak-scan.yml", "lint-secrets-coverage.yml", "vm-pilotd-test.yml")

    /** The (repo, file, ref) a dispatch sends, or null when [file] is not allow-listed (the app never sends it). */
    fun dispatchArgs(file: String?): Triple<String, String, String>? = if (file != null && file in DISPATCH) Triple(INFRA_REPO, file, "main") else null

    private fun gha(id: String, file: String, blurb: String, run: Boolean, note: String = "") =
        Engine(id, ".github/workflows/$file", null, null, false, blurb, note, origin = "workflow", tag = "gha", workflow = file, dispatch = if (run) file else null)

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
        // the sources that are not Rust engines (#1983 owner check): security audits, code-index surfaces, vm-pilotd, CrowdSec; each tagged, none dropped
        gha("leak-scan", "leak-scan.yml", "secret leak scan (gitleaks) of every push to a public repo; a hit turns the run red; its mutation inputs plant and purged must go red", true),
        gha("lint-secrets", "lint-secrets-coverage.yml", "every sops secrets.yaml carries a _credentials block and no uncovered key (wraps audit-credentials-coverage.sh)", true),
        Engine("audit-credentials", "1_cicd/src/ops/audit-credentials-coverage.sh", null, null, false,
            "walks every sops secrets.yaml: top-level keys, the _credentials block, which keys it references, a class per key (hash, token, password, url)",
            "runs inside lint-secrets-coverage.yml (last run and Run are that workflow's)", origin = "ops", tag = "shell", workflow = "lint-secrets-coverage.yml", dispatch = "lint-secrets-coverage.yml"),
        Engine("cgc-surfaces", "1_cicd/src/ops/cloud-cgc-verify-surfaces.sh", null, null, false,
            "acceptance test of the two cloud-cgc MCP surfaces: the public and the private one must answer for their own repos only",
            "no workflow runs it, so there is no GitHub Actions run to read: it is run by hand on a box that can ssh to oci-apps (mesh only surfaces)", origin = "ops", tag = "shell"),
        gha("vm-pilotd", "vm-pilotd-test.yml", "Go host daemon vm-pilotd: vet, tests with parity harnesses against the real shell daemons (each mutation-checked), static cross-compile for both fleet architectures", true),
        Engine("crowdsec", "infra-sec_crowdsec (decisions, alerts)", "cloud_crowdsec", null, false, "CrowdSec decisions and alerts, read only",
            "container crowdsecurity/crowdsec: a DORMANT placeholder (empty acquisition, CAPI off, no bouncers), so it records no decisions or alerts today; no route reads them yet (#1977) and nothing here can change them",
            origin = "sec", tag = "container"),
        gha("lint-agent-credential", "lint-agent-credential.yml", "no agent container declares a GitHub token with more scope than the policy allows (read only probe, never printed)", false),
        gha("lint-pipeline", "lint-pipeline.yml", "build-pipeline hygiene on every PR and push (symlinks and generated files)", false),
        gha("licence-inventory", "licence-inventory-guard.yml", "licenses/inventory.json lists every directory, binary, dependency, flake input, base image and provider with its licence", false),
        gha("readme-check", "readme-check.yml", "README.md equals a fresh render of its template and data", false),
        gha("cloud-run-guard", "cloud-run-guard.yml", "the Cloud Run registry is complete and carries no secret value; main.tf validates (no plan, no apply)", false),
        gha("cloud-run-verify", "cloud-run-verify.yml", "live requests to the Cloud Run services with a minted ID token; nothing secret is printed", false),
    )

    /** One shell health script and the engine that replaces it: shown as a line on that engine's card. */
    data class Replaced(val script: String, val engine: String, val why: String)

    val REPLACED = listOf(
        Replaced("cloud-health-all-vms.sh", "daily", "health of every permanent VM: the daily engine's vms phase (ssh, containers, resources)"),
        Replaced("cloud-health-check-vm.sh", "daily", "one VM's containers and ports: the daily engine's per-VM check"),
        Replaced("cloud-health-full.sh", "daily", "public URLs, DNS, TLS, ssh reachability and APIs in one run: the daily engine's endpoints, dns, certs and vms phases"),
        Replaced("cloud-health-http-public.sh", "url", "every public endpoint from the Caddy catalogue: the url engine's public checks"),
        Replaced("cloud-health-http-private.sh", "url", "every private .app and .db name: the url engine's private checks"),
        Replaced("cloud-health-mail-full.sh", "mail", "PARTIAL: layer 1 (liveness) already is this engine; layers 2 and 3 (Gmail cross-store reconciliation, send and receive round-trip) are not ported yet (#1978)"),
        Replaced("cloud-health-mail-failure-classifier.sh", "mail", "sourced by cloud-health-mail-full.sh: goes with it"),
    )

    /** Scripts of 1_cicd/src/ops that are neither a test nor a report (so need no card), each with its reason. */
    val OPS_NOT_TEST = mapOf(
        "cloud-cgc-db-chunk.sh" to "cgc-db build tooling", "cloud-cgc-db-package.sh" to "cgc-db build tooling", "cloud-cgc-db-pull.sh" to "cgc-db build tooling",
        "cloud-cgc-db-restore-all.sh" to "cgc-db restore tooling", "cloud-cgc-db-restore.sh" to "cgc-db restore tooling", "cloud-cgc-db-seed.sh" to "cgc-db seed tooling",
        "cloud-cgc-db-update.sh" to "cgc-db update tooling", "cloud-cgc-llm-proxy.py" to "LLM proxy, a service", "cloud-git-nuke.sh" to "git history tooling",
        "cloud-git-sync.sh" to "git sync tooling", "overlay-private-secrets.sh" to "secrets overlay tooling",
    )

    /** Ops scripts that are a part of another card's check (a wrapper), each with the card id. */
    val OPS_PART_OF = mapOf("lint-secrets-coverage.sh" to "lint-secrets")

    /** Workflows that run an engine (the engine id), rather than being a test of their own. */
    val WORKFLOW_RUNS_ENGINE = mapOf(
        "health_mail_full.yml" to "mail", "cloud-health-reports.yml" to "daily", "cloud-health-reports-x86-gha.yml" to "daily", "ship-reports.yml" to "daily",
    )

    /** Workflows that are neither a test nor a report (deploys, secret operations, data copies), each with its reason. */
    val WORKFLOW_NOT_TEST = mapOf(
        "ship.yml" to "deploy", "ship-gen-configs.yml" to "deploy", "ship-home-manager.yml" to "deploy", "ship-reconcile.yml" to "deploy", "ship-terraform.yml" to "deploy",
        "ship-wrangler.yml" to "deploy", "ship-cgc-db-seed.yml" to "deploy", "unjam-ship.yml" to "deploy tooling", "cf-zero-trust.yml" to "deploy",
        "cgc-db-index.yml" to "cgc-db build", "cgc-db-propagate.yml" to "cgc-db build", "cgc-db-restore.yml" to "cgc-db restore", "cgc-db.yml" to "cgc-db build",
        "fetch-github-repos.yml" to "data copy", "finterm-db-copy.yml" to "data copy", "gcp-vm-control.yml" to "VM control", "mirror-hermes-image.yml" to "image mirror",
        "sops-copy.yml" to "secret operation", "sops-cover.yml" to "secret operation", "sops-create.yml" to "secret operation", "sops-gen-sshkey.yml" to "secret operation",
        "sops-set.yml" to "secret operation", "sso-oidc-deliver.yml" to "secret operation", "sso-oidc-issue.yml" to "secret operation", "vault-mtls-issue.yml" to "secret operation",
    )

    /**
     * The guard: every report or test source has a card or a declared mapping. [workflows] are the files of cloudy-t-infra/.github/workflows, [ops] the
     * files of 1_cicd/src/ops, [reports] the folders of infra-obs_reports/src/src. Returns what has neither (empty = guarded), and what a card or
     * mapping names that does not exist.
     */
    fun unguarded(workflows: Collection<String>, ops: Collection<String>, reports: Collection<String>): List<String> {
        val wfCards = ENGINES.mapNotNull { if (it.origin == "workflow") it.workflow else null }.toSet()
        val opsCards = ENGINES.filter { it.origin == "ops" }.map { it.dir.substringAfterLast('/') }.toSet()
        val repCards = ENGINES.filter { it.origin == "reports" }.map { it.dir }.toSet()
        val ids = ENGINES.map { it.id }.toSet()
        val out = ArrayList<String>()
        workflows.filter { it !in wfCards && it !in WORKFLOW_RUNS_ENGINE && it !in WORKFLOW_NOT_TEST }.forEach { out += "workflow $it has no card or mapping" }
        ops.filter { it !in opsCards && REPLACED.none { r -> r.script == it } && it !in OPS_PART_OF && it !in OPS_NOT_TEST }.forEach { out += "ops script $it has no card or mapping" }
        reports.filter { it !in repCards && it !in LIBS }.forEach { out += "report engine $it has no card" }
        (wfCards + WORKFLOW_RUNS_ENGINE.keys + WORKFLOW_NOT_TEST.keys).filter { it !in workflows }.forEach { out += "workflow $it is declared but not in the repository" }
        (opsCards + REPLACED.map { it.script } + OPS_PART_OF.keys + OPS_NOT_TEST.keys).filter { it !in ops }.forEach { out += "ops script $it is declared but not in the repository" }
        repCards.filter { it !in reports }.forEach { out += "card $it is declared but not in infra-obs_reports" }
        (REPLACED.map { it.engine } + WORKFLOW_RUNS_ENGINE.values + OPS_PART_OF.values).filter { it !in ids }.forEach { out += "mapping names the missing card $it" }
        return out
    }

    /** The lines an engine's card prints for the shell scripts it replaced. */
    fun replacedLines(id: String): List<String> = REPLACED.filter { it.engine == id }.map { "replaces 1_cicd/src/ops/${it.script}: ${it.why}" }

    /** What the Run button of [e] sends: an allow-listed report kind, an allow-listed workflow, or nothing. */
    fun canRun(e: Engine): Boolean = runBody(e.kind) != null || dispatchArgs(e.dispatch) != null

    /** The kinds the app may ask `/reports/run` for; the same enum the API validates (REPORT_KINDS in c3-infra-api reports.ts). */
    val RUN_KINDS = listOf("all", "daily", "daily-mail", "mail", "url", "sec-network", "sec-data")

    /** The request body for a run, or null when [kind] is not allow-listed (the app never sends it). */
    fun runBody(kind: String?): String? = if (kind != null && kind in RUN_KINDS) "{\"report\":\"$kind\",\"runner\":\"gha\"}" else null

    /** What a Run button says: Run, or that there is no trigger (the card's note line gives the reason). */
    fun runLabel(e: Engine): String = if (canRun(e)) "Run" else "no trigger"

    /** The engines an X-ray (VM, service, full) is built from: shown under the X-ray tabs. */
    val FEEDS = mapOf(
        "full" to listOf("daily", "url", "sec-network", "sec-data", "mail"),
        "vms" to listOf("daily", "sec-network"),
        "services" to listOf("daily", "url", "mail"),
    )

    const val BODY_GAP = "report body route not served yet (#1977): needs the main-server restart (cloud-data is a private repo, no token in the APK, and c3-infra-api has no GET /reports/data/<family> route yet)"
    const val STATUS_GAP = "freshness unknown: GET /reports/status does not list this family yet (#1977)"

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
        /** A workflow card: the last run of its GitHub Actions workflow (its conclusion is the verdict, there is no per-check report). */
        data class Last(val run: Run) : Body()
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

    /** `workflow_runs` of GitHub's `actions/workflows/<file>/runs`, newest first; null when [text] is not that. */
    fun parseWorkflowRuns(text: String): List<Run>? {
        val o = parseObj(text) ?: return null
        val a = o["workflow_runs"] as? JsonArray ?: return null
        return a.mapNotNull { it as? JsonObject }.map { Run("gha", it.s("id"), it.s("status"), it.s("conclusion"), it.s("created_at"), it.s("updated_at"), it.s("event")) }
    }

    /** What reading a workflow's runs gave: [state] is the feed's own (OK, RATE_LIMITED, UNREACHABLE). */
    fun classifyWorkflow(file: String, state: String, text: String?): Body = when {
        text == null && state == "RATE_LIMITED" -> Body.Why("last run unknown: GitHub's anonymous quota (60 per hour) is spent, try again later")
        text == null -> Body.Why("last run unknown: GitHub could not be read from this device")
        else -> parseWorkflowRuns(text)?.let { r -> r.firstOrNull()?.let { Body.Last(it) } ?: Body.Why("no run of $file is listed yet") } ?: Body.Why("the answer is not a runs list")
    }

    /** The verdict word of a workflow run: its conclusion, or its state while it runs. */
    fun runVerdict(r: Run): String = when {
        r.status != "completed" -> "RUNNING"
        r.conclusion == "success" -> "PASS"
        r.conclusion == "failure" || r.conclusion == "timed_out" -> "FAIL"
        r.conclusion.isBlank() -> "UNKNOWN"
        else -> r.conclusion.uppercase()
    }

    /** PASS / WARN / FAIL / RUNNING ... when the card has a verdict, null when it has only a reason. */
    fun verdictWord(b: Body): String? = when (b) {
        is Body.Got -> b.report.verdict
        is Body.Last -> runVerdict(b.run)
        else -> null
    }

    /** True when the card's reason is that this device has no C3 token: the line is then an action (Configs > Profile > Config import). */
    fun isNoToken(b: Body): Boolean = b is Body.Why && b.why.contains(HealthModel.NO_TOKEN_NOTE)

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
        is Body.Last -> "${runVerdict(b.run)} (workflow ${b.run.status}/${b.run.conclusion.ifBlank { "-" }})"
    }

    fun freshText(f: Fresh?): String = when {
        f == null -> STATUS_GAP
        !f.ok && f.lastModified.isBlank() -> "never published or not readable: ${f.error.ifBlank { "no commit for this family" }}"
        else -> "published ${f.lastModified} (${f.ageHours ?: "?"} h ago)" + if (f.stale) " STALE" else ""
    }
}
