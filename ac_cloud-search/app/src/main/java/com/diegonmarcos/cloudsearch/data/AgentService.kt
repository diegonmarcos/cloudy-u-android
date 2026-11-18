package com.diegonmarcos.cloudsearch.data

import android.content.Context
import com.diegonmarcos.cloudsearch.BuildConfig
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.agents.AgentsConfig
import com.diegonmarcos.cloudsearch.core.agents.BudgetLedger
import com.diegonmarcos.cloudsearch.core.agents.Caps
import com.diegonmarcos.cloudsearch.core.agents.DraftRunner
import com.diegonmarcos.cloudsearch.core.agents.Llm
import com.diegonmarcos.cloudsearch.core.agents.MailSource
import com.diegonmarcos.cloudsearch.core.agents.OpenRouterLlm
import com.diegonmarcos.cloudsearch.core.agents.PageSource
import com.diegonmarcos.cloudsearch.core.agents.Pricing
import com.diegonmarcos.cloudsearch.core.agents.Reports
import java.io.File
import java.util.UUID

/**
 * #913 the Agents tab's one set of collaborators, shared by the screens and the debug API. [run] is the only
 * thing that makes an agent do work, and all the work is reading: the owner's mail and the listing pages through
 * the two read-only doors, one model call per listing inside the budget, and a report written to the local store.
 * Nothing is sent, posted or submitted anywhere; the drafts wait for the person.
 */
class AgentService(
    ctx: Context, private val http: Http, private val cfg: SearchConfig, val agents: AgentsConfig, val models: ModelCatalog,
    mailPackage: String, browserPackage: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokenOf: () -> String? = { null },
    mailOverride: MailSource? = null, pagesOverride: PageSource? = null, llmOverride: Llm? = null,
) {
    val app: Context = ctx.applicationContext
    val prefs = AgentPrefs(app.getSharedPreferences(Services.PREFS, Context.MODE_PRIVATE), agents, cfg.ai.defaultModel)
    val runs = RunStore(JsonFile(File(app.filesDir, "agents/runs.json")))
    val reports = ReportStore(JsonFile(File(app.filesDir, "agents/reports.json")))
    val browser = BrowserDoor(app, agents.browser, browserPackage)
    val mail: MailSource = mailOverride ?: MailDoor(app, agents.mail, mailPackage)
    private val pages: PageSource = pagesOverride ?: browser
    private val llm: Llm = llmOverride ?: OpenRouterLlm(cfg.ai, http) { tokenOf() }

    /** What the next call to [model] would be priced at: the catalogue's price, else the declared deliberately high one. */
    fun pricing(model: String): Pricing =
        models.raw()?.let { Pricing.fromCatalog(it, model) } ?: agents.defaults.unknownPricePerToken.let { Pricing(it, it) }

    fun run(agentId: String): DraftRunner.Outcome {
        val agent = agents.agent(agentId) ?: error("no agent $agentId")
        val now = clock()
        val model = prefs.model
        val ledger = BudgetLedger(Caps(prefs.budgetRunUsd, prefs.budgetDayUsd), prefs.spentToday(now)) { prefs.addSpend(now, it) }
        val d = agents.defaults
        val settings = DraftRunner.Settings(d.maxMails, prefs.maxListings, prefs.lookbackDays, d.pageChars, d.personalMaxWords, d.maxTokens)
        val runner = DraftRunner(mail, pages, llm, ledger, clock) { UUID.randomUUID().toString() }
        val out = runner.run(
            DraftRunner.Input(
                agent, prefs.templateBody(agent.template), prefs.profile(), settings, prefs.seen(agent.id), model, pricing(model),
                filters = prefs.filters(agent), sources = prefs.sources(agent),
            ),
        )
        prefs.addSeen(agent.id, out.handled)
        runs.add(out.record)
        reports.publish(Reports.digest(agent, out))
        return out
    }

    companion object {
        fun from(s: Services): AgentService? {
            val a = s.cfg.agents ?: return null
            return AgentService(
                s.app, s.http, s.cfg, a, s.models, BuildConfig.MAIL_PACKAGE, BuildConfig.BROWSER_PACKAGE,
                // #1801 the run's completions go through the AI-key owner engine: no key is read here.
                llmOverride = com.diegonmarcos.cloudsearch.core.agents.RelayLlm { body ->
                    Account.relay(s.app, body).let { r -> com.diegonmarcos.cloudsearch.core.agents.RelayLlm.Relayed(r.code, r.body, if (r.ok) null else r.why) }
                },
            )
        }
    }
}
