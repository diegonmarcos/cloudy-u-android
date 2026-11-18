package com.diegonmarcos.cloudsearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.Chat
import com.diegonmarcos.cloudsearch.core.agents.BudgetLedger
import com.diegonmarcos.cloudsearch.core.agents.Report
import com.diegonmarcos.cloudsearch.core.agents.ReportItem
import com.diegonmarcos.cloudsearch.core.agents.RunRecord
import com.diegonmarcos.cloudsearch.core.agents.Template
import com.diegonmarcos.cloudsearch.data.Account
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * #913 the Agents tab: Agents, Runs, Templates, Settings. DRAFT-ONLY: an agent reads and drafts; every draft
 * waits in a review list where the person copies it (or opens the listing in Cloud Browser) and sends it
 * themselves. Nothing on these pages posts, sends or submits; there is no such button.
 */
object AgentTags {
    const val RUNS = "agents_runs"
    const val MODEL = "agents_model_field"
    const val BUDGET_RUN = "agents_budget_run"
    const val BUDGET_DAY = "agents_budget_day"
    const val APPLY = "agents_apply"
    const val TOKEN = "agents_token_status"
    const val TOKEN_CHECK = "agents_token_check"
    const val TOKEN_SET = "agents_token_set"
    const val STATUS = "agents_status"
    const val LIST = "agents_list"
    const val BACK = "agents_back"
    fun group(key: String) = "agents_group_" + key.replace('/', '_')
    fun row(agent: String) = "agents_row_$agent"
    fun screen(agent: String) = "agents_screen_$agent"
    fun notice(agent: String) = "agents_notice_$agent"
    fun goal(agent: String) = "agents_goal_$agent"
    fun filter(agent: String, id: String) = "agents_filter_${agent}_$id"
    fun source(agent: String, id: String) = "agents_source_${agent}_$id"
    fun result(item: String) = "agents_result_$item"
    fun run(agent: String) = "agents_run_$agent"
    fun copy(item: String) = "agents_copy_$item"
    fun open(item: String) = "agents_open_$item"
    fun sent(item: String) = "agents_sent_$item"
    fun dismiss(item: String) = "agents_dismiss_$item"
    fun template(id: String) = "agents_template_$id"
    fun profile(id: String) = "agents_profile_$id"
    fun report(id: String) = "agents_report_$id"
    fun runRow(id: String) = "agents_run_row_$id"
}

fun stampFull(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.UK).format(Date(ms))

private val listPadding = PaddingValues(start = Metrics.gutter, end = Metrics.gutter, top = Metrics.small, bottom = Metrics.contentBottom)

/** The Agents section: a strip of its four pages over the selected one (Agents opens the list, or the agent tapped). */
@Composable
fun AgentsSection(state: SearchState) {
    Column(Modifier.fillMaxSize()) {
        NavBarTopSecondRow(
            pages = NAV.section("research")?.page("agenthub")?.pages.orEmpty(),
            selectedId = state.agentsPage,
            onSelect = { if (it.id == state.agentsPage) state.agentOpen = null; state.agentsPage = it.id },
            dark = state.dark,
            underTopChrome = false,
            surface = NAV.stripSurface("research", state.dark, "agenthub"),
        )
        Box(Modifier.fillMaxWidth().weight(1f).testTag(Tags.page("agents_" + state.agentsPage))) {
            val svc = state.services.agents
            if (svc == null) Text(stringResource(R.string.agents_none), Modifier.padding(Metrics.gutter), color = LocalGlass.current.text2)
            else when (state.agentsPage) {
                "runs" -> RunsPage(state)
                "templates" -> TemplatesPage(state)
                "settings" -> AgentSettingsPage(state)
                else -> AgentsPage(state)
            }
        }
    }
}

@Composable
private fun Mini(text: String, tag: String, modifier: Modifier = Modifier, style: TextStyle = Type.style(Type.small), color: androidx.compose.ui.graphics.Color = LocalGlass.current.text2, lines: Int = 1) {
    Text(text, modifier.testTag(tag), color = color, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis)
}

// ── Agents ──────────────────────────────────────────────────────────────────────────────────────

/**
 * #913b the agents, grouped: Buy-Side › Real Estate, Things, Services, then Sell-Side (AgentsConfig.groups). One dense
 * row per agent (name, what it reads, its last run); a tap opens the agent's screen: draft-only notice, goal, filters,
 * sources, Run, results, drafts and its runs.
 */
@Composable
fun AgentsPage(state: SearchState) {
    val svc = state.services.agents!!
    val open = state.agentOpen?.let { svc.agents.agent(it) }
    if (open != null) { AgentScreen(state, open); return }
    val g = LocalGlass.current
    val m = state.agentsModel
    val runs = remember(m.rev) { svc.runs.all() }
    LazyColumn(Modifier.testTag(AgentTags.LIST), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(Metrics.tiny)) {
        item(key = "draft_only") { Text(stringResource(R.string.agents_draft_only_notice), color = g.text2, style = Type.style(Type.tiny)) }
        for (grp in svc.agents.groups()) {
            item(key = "h_" + grp.key) {
                Text(
                    grp.side.label + (grp.category?.let { " › " + it.label } ?: ""),
                    Modifier.padding(top = Metrics.small).testTag(AgentTags.group(grp.key)),
                    color = g.accent, style = Type.style(Type.label, FontWeight.Bold),
                )
            }
            items(grp.agents, key = { it.id }) { a ->
                val last = runs.firstOrNull { a.owns(it.agentId) }
                val shape = RoundedCornerShape(Metrics.tileRadius)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(g.field).border(Metrics.hairline, g.tileBorder, shape)
                        .clickable { state.agentOpen = a.id }.testTag(AgentTags.row(a.id)).padding(horizontal = Metrics.tilePad, vertical = Metrics.small),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, color = g.text, style = Type.style(Type.body, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.sources.joinToString(" · ") { it.label }, color = g.text2, style = Type.style(Type.tiny), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        if (m.running == a.id) stringResource(R.string.agents_running)
                        else last?.let { stampFull(it.endedAt) + " · " + it.drafts } ?: stringResource(R.string.agents_never_run),
                        color = g.text2, style = Type.style(Type.tiny), maxLines = 1,
                    )
                }
            }
        }
    }
}

/** One agent's screen, as the house agent's card was: what it does, what it asks for, where it reads, Run, results, drafts, runs. */
@Composable
private fun AgentScreen(state: SearchState, a: com.diegonmarcos.cloudsearch.core.agents.AgentsConfig.Agent) {
    val svc = state.services.agents!!
    val g = LocalGlass.current
    val m = state.agentsModel
    val scope = rememberCoroutineScope()
    val p = svc.prefs
    var filters by remember(a.id) { mutableStateOf(p.filters(a)) }
    var sourceRev by remember(a.id) { mutableStateOf(0) }
    val last = remember(m.rev, a.id) { svc.runs.all().filter { a.owns(it.agentId) } }
    val report = remember(m.rev, a.id) { svc.reports.newestFirst().firstOrNull { a.owns(it.agentId) } }
    val side = svc.agents.sides.firstOrNull { it.id == a.side }?.label.orEmpty()
    val category = svc.agents.categories.firstOrNull { it.id == a.category }?.label
    LazyColumn(Modifier.testTag(AgentTags.screen(a.id)), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        item(key = "head") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                Chip(stringResource(R.string.agents_all), AgentTags.BACK) { state.agentOpen = null }
                Text(side + (category?.let { " › $it" } ?: ""), Modifier.weight(1f), color = g.text2, style = Type.style(Type.tiny), maxLines = 1)
            }
        }
        item(key = "card") {
            GlassCard {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Text(a.label, Modifier.weight(1f), color = g.text, style = Type.style(Type.cardTitle, FontWeight.Bold))
                    Badge(stringResource(R.string.agents_draft_only), g.accent)
                }
                Text(a.blurb, color = g.text2, style = Type.style(Type.small))
                Text(stringResource(R.string.agents_draft_only_notice), Modifier.testTag(AgentTags.notice(a.id)), color = g.text, style = Type.style(Type.tiny, FontWeight.SemiBold))
                FieldLabel(stringResource(R.string.agents_goal))
                Text(com.diegonmarcos.cloudsearch.core.agents.Plan.goal(a, filters), Modifier.testTag(AgentTags.goal(a.id)), color = g.text, style = Type.style(Type.small))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Chip(if (m.running == a.id) stringResource(R.string.agents_running) else stringResource(R.string.agents_run), AgentTags.run(a.id), accent = true) {
                        if (m.running == null) m.run(a.id, scope)
                    }
                    Mini(
                        m.status.takeIf { m.statusOf == a.id }.orEmpty().ifBlank {
                            last.firstOrNull()?.let { stampFull(it.endedAt) + " · " + it.status + " · " + it.drafts + " draft(s)" } ?: stringResource(R.string.agents_never_run)
                        },
                        AgentTags.STATUS, Modifier.weight(1f),
                    )
                }
            }
        }
        item(key = "filters") {
            GlassCard {
                FieldLabel(stringResource(R.string.agents_filters))
                a.filters.mapNotNull { svc.agents.filter(it) }.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                        pair.forEach { f ->
                            GlassField(
                                filters[f.id].orEmpty(), { v -> filters = filters + (f.id to v); p.setFilter(a.id, f.id, v) },
                                f.label, AgentTags.filter(a.id, f.id), Modifier.weight(1f), icon = null, number = f.number,
                            )
                        }
                        if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item(key = "sources") {
            GlassCard {
                FieldLabel(stringResource(R.string.agents_sources))
                key(sourceRev) {
                    a.sources.forEach { src ->
                        val on = p.sourceOn(a.id, src.id)
                        var subject by remember(a.id, src.id) { mutableStateOf(p.sourceSubject(a.id, src)) }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.small)) {
                            Chip(src.label, AgentTags.source(a.id, src.id), selected = on) { p.setSourceOn(a.id, src.id, !on); sourceRev++ }
                            Text(
                                if (src.mailFrom.isBlank()) stringResource(R.string.agents_source_search_only) else stringResource(R.string.agents_source_alerts, src.mailFrom),
                                Modifier.weight(1f), color = g.text2, style = Type.style(Type.tiny), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            com.diegonmarcos.cloudsearch.core.agents.Plan.searchUrl(src, state.cfg.sources, filters, a.query, state.cfg.city(state.city).label)?.let { url ->
                                Chip(stringResource(R.string.agents_source_open), AgentTags.source(a.id, src.id + "_open"), icon = R.drawable.ph_arrow_square_out) {
                                    svc.browser.open(url, a.group)
                                }
                            }
                        }
                        if (src.mailFrom.isNotBlank() && on) GlassField(
                            subject, { v -> subject = v; p.setSourceSubject(a.id, src.id, v) }, stringResource(R.string.agents_source_subject),
                            AgentTags.source(a.id, src.id + "_subject"), icon = null,
                        )
                    }
                }
            }
        }
        if (report != null && report.items.isNotEmpty()) {
            item(key = "results") {
                GlassCard {
                    FieldLabel(stringResource(R.string.agents_results, report.items.size, stampFull(report.createdAt)))
                    report.items.forEach { it -> ResultRow(it) }
                }
            }
            item(key = "drafts") { FieldLabel(stringResource(R.string.agents_review, report.items.size, stampFull(report.createdAt))) }
            items(report.items, key = { "d_" + it.id }) { DraftRow(state, report, it) }
        }
        if (last.isNotEmpty()) {
            item(key = "runs") { FieldLabel(stringResource(R.string.agents_runs_of, last.size)) }
            items(last.take(5), key = { "r_" + it.id }) { r -> RunRow(a.label, r, false) { state.agentsPage = "runs" } }
        }
    }
}

/** One line of the result table: what was found, where, and whether it is still a draft. */
@Composable
private fun ResultRow(item: ReportItem) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth().testTag(AgentTags.result(item.id)), horizontalArrangement = Arrangement.spacedBy(Metrics.gap), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(item.title, Modifier.weight(1f), color = g.text, style = Type.style(Type.small), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.url.substringAfter("://").substringBefore('/').removePrefix("www."), color = g.text2, style = Type.style(Type.tiny), maxLines = 1)
        Text(
            stringResource(
                when (item.status) { ReportItem.SENT_BY_ME -> R.string.agents_sent_by_me; ReportItem.DISMISSED -> R.string.agents_dismissed; else -> R.string.agents_draft }
            ),
            color = if (item.note.isNotBlank()) g.negative else g.text2, style = Type.style(Type.tiny), maxLines = 1,
        )
    }
}

/** One draft in a review list: what it answers, the message, and the person's four buttons. No send. */
@Composable
fun DraftRow(state: SearchState, report: Report, item: ReportItem) {
    val g = LocalGlass.current
    val svc = state.services.agents!!
    val m = state.agentsModel
    val clip = LocalClipboardManager.current
    var copied by remember(item.id, report.id) { mutableStateOf(false) }
    var open by remember(item.id, report.id) { mutableStateOf(false) }
    val group = svc.agents.agent(report.agentId)?.group.orEmpty()
    val shape = RoundedCornerShape(Metrics.tileRadius)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(g.field).border(Metrics.hairline, g.tileBorder, shape).padding(Metrics.tilePad),
        verticalArrangement = Arrangement.spacedBy(Metrics.tiny),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(item.title, Modifier.weight(1f), color = g.text, style = Type.style(Type.body, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.status != ReportItem.DRAFT) Badge(stringResource(if (item.status == ReportItem.SENT_BY_ME) R.string.agents_sent_by_me else R.string.agents_dismissed), g.text2)
        }
        Mini(item.url, AgentTags.report(item.id + "_url"))
        if (item.note.isNotBlank()) Mini(item.note, AgentTags.report(item.id + "_note"), color = g.negative)
        Text(
            item.body, Modifier.clickable { open = !open }.testTag(AgentTags.report(item.id)), color = g.text,
            style = Type.style(Type.small), maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
        )
        ChipRow {
            Chip(if (copied) stringResource(R.string.agents_copied) else stringResource(R.string.agents_copy), AgentTags.copy(item.id), icon = R.drawable.ph_arrow_up) {
                clip.setText(AnnotatedString(item.body)); copied = true
            }
            Chip(stringResource(R.string.agents_open_listing), AgentTags.open(item.id), icon = R.drawable.ph_arrow_square_out) {
                svc.browser.open(item.url, group)
            }
            if (item.status == ReportItem.DRAFT) {
                Chip(stringResource(R.string.agents_mark_sent), AgentTags.sent(item.id)) { svc.reports.setItemStatus(report.id, item.id, ReportItem.SENT_BY_ME); m.rev++ }
                Chip(stringResource(R.string.agents_dismiss), AgentTags.dismiss(item.id)) { svc.reports.setItemStatus(report.id, item.id, ReportItem.DISMISSED); m.rev++ }
            }
        }
    }
}

// ── Runs ────────────────────────────────────────────────────────────────────────────────────────

@Composable
fun RunsPage(state: SearchState) {
    val svc = state.services.agents!!
    val g = LocalGlass.current
    val m = state.agentsModel
    val runs = remember(m.rev) { svc.runs.all() }
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.testTag(AgentTags.RUNS), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
        if (runs.isEmpty()) item { Text(stringResource(R.string.agents_no_runs), color = g.text2, style = Type.style(Type.small)) }
        items(runs, key = { it.id }) { r -> RunRow(svc.agents.agent(r.agentId)?.label ?: r.agentId, r, open == r.id) { open = if (open == r.id) null else r.id } }
    }
}

@Composable
private fun RunRow(label: String, r: RunRecord, expanded: Boolean, toggle: () -> Unit) {
    val g = LocalGlass.current
    GlassCard(Modifier.testTag(AgentTags.runRow(r.id)), onClick = toggle) {
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, color = g.text, style = Type.style(Type.body, FontWeight.Bold))
            Badge(r.status, if (r.status == RunRecord.OK) g.positive else if (r.status == RunRecord.FAILED) g.negative else g.accent)
            Text(stampFull(r.startedAt), Modifier.weight(1f), color = g.text2, style = Type.style(Type.tiny), maxLines = 1)
            Text(BudgetLedger.usd(r.costUsd), color = g.text2, style = Type.style(Type.tiny))
        }
        Text(r.summary, color = g.text2, style = Type.style(Type.small))
        Text(stringResource(R.string.agents_run_counts, r.mails, r.links, r.pages, r.drafts, r.llmCalls), color = g.text2, style = Type.style(Type.tiny))
        if (expanded) {
            Hairline(Modifier.padding(vertical = Metrics.tiny))
            r.audit.forEach { e ->
                Text(
                    "${e.kind}  ${e.subject}" + if (e.detail.isNotBlank()) "  ·  ${e.detail}" else "",
                    color = g.text2, style = Type.style(Type.tiny), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Templates ───────────────────────────────────────────────────────────────────────────────────

/** A multi-line glass text box (GlassField is one line). */
@Composable
fun NoteField(value: String, onValue: (String) -> Unit, tag: String, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(Metrics.inputRadius)
    BasicTextField(
        value = value, onValueChange = onValue,
        textStyle = TextStyle(color = g.text, fontSize = Type.input),
        cursorBrush = SolidColor(g.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = modifier.fillMaxWidth().testTag(tag).clip(shape).background(g.input).border(Metrics.hairline, g.glassBorder, shape)
            .padding(horizontal = Metrics.inputPadH, vertical = Metrics.inputPadV),
    )
}

@Composable
fun TemplatesPage(state: SearchState) {
    val svc = state.services.agents!!
    val g = LocalGlass.current
    val profileState = remember { mutableStateOf(svc.prefs.profile()) }
    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        item {
            GlassCard {
                FieldLabel(stringResource(R.string.agents_vars_title))
                Text(stringResource(R.string.agents_vars_blurb), color = g.text2, style = Type.style(Type.tiny))
                svc.agents.profileFields.forEach { f ->
                    FieldLabel(f.label + if (f.hint.isNotBlank()) " · " + f.hint else "")
                    GlassField(
                        profileState.value[f.id].orEmpty(), { v -> profileState.value = profileState.value + (f.id to v); svc.prefs.setProfile(f.id, v) },
                        f.label, AgentTags.profile(f.id), icon = null, bordered = true,
                    )
                }
            }
        }
        items(svc.agents.templates, key = { it.id }) { t ->
            var body by remember(t.id) { mutableStateOf(svc.prefs.templateBody(t.id)) }
            var custom by remember(t.id) { mutableStateOf(svc.prefs.isCustomised(t.id)) }
            val owner = svc.agents.agent(t.agent)
            val sample = owner?.let { svc.prefs.filters(it) }.orEmpty() + svc.prefs.profile() + mapOf(
                "listing_title" to stringResource(R.string.agents_sample_title), "listing_url" to "https://example.org/…",
                "listing_id" to "12345678", "personal" to stringResource(R.string.agents_sample_personal),
            )
            val preview = Template.render(body, sample)
            GlassCard {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Text(t.label, Modifier.weight(1f), color = g.text, style = Type.style(Type.cardTitle, FontWeight.Bold))
                    owner?.let { Text(it.label, color = g.text2, style = Type.style(Type.tiny), maxLines = 1) }
                    if (custom) Badge(stringResource(R.string.agents_yours), g.accent)
                }
                NoteField(body, { body = it; svc.prefs.setTemplate(t.id, it); custom = true }, AgentTags.template(t.id))
                Text(
                    stringResource(R.string.agents_vars_available, (svc.agents.profileFields.map { it.id } + (owner?.filters ?: emptyList()) + svc.agents.builtinVars).joinToString { "{{$it}}" }),
                    color = g.text2, style = Type.style(Type.tiny),
                )
                if (preview.missing.isNotEmpty()) Text(stringResource(R.string.agents_vars_missing, preview.missing.joinToString()), color = g.negative, style = Type.style(Type.tiny))
                FieldLabel(stringResource(R.string.agents_preview))
                Text(preview.text, Modifier.testTag(AgentTags.template(t.id + "_preview")), color = g.text, style = Type.style(Type.small))
                if (custom) Chip(stringResource(R.string.agents_reset), AgentTags.template(t.id + "_reset")) {
                    svc.prefs.resetTemplate(t.id); body = svc.prefs.templateBody(t.id); custom = false
                }
            }
        }
    }
}

// ── Settings ────────────────────────────────────────────────────────────────────────────────────

@Composable
fun AgentSettingsPage(state: SearchState) {
    val svc = state.services.agents!!
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = svc.prefs
    var model by remember { mutableStateOf(p.model) }
    var run by remember { mutableStateOf(plain(p.budgetRunUsd)) }
    var day by remember { mutableStateOf(plain(p.budgetDayUsd)) }
    var maxListings by remember { mutableStateOf(p.maxListings.toString()) }
    var lookback by remember { mutableStateOf(p.lookbackDays.toString()) }
    var catalogue by remember { mutableStateOf<List<Chat.Model>>(emptyList()) }
    var token by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { catalogue = withContext(Dispatchers.IO) { svc.models.models() } }
    val price = svc.pricing(model)
    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        item {
            GlassCard {
                FieldLabel(stringResource(R.string.agents_model))
                GlassField(model, { model = it }, svc.agents.defaults.model, AgentTags.MODEL, icon = null)
                val matches = catalogue.filter { model.isNotBlank() && it.id.contains(model.trim(), ignoreCase = true) && it.id != model.trim() }.take(8)
                if (matches.isNotEmpty()) ChipRow { matches.forEach { m -> Chip(m.id, AgentTags.template("pick_" + m.id)) { model = m.id } } }
                Text(
                    stringResource(R.string.agents_price, plain(price.promptPerToken * 1_000_000), plain(price.completionPerToken * 1_000_000)),
                    color = g.text2, style = Type.style(Type.tiny),
                )
            }
        }
        item {
            GlassCard {
                FieldLabel(stringResource(R.string.agents_budget))
                Text(stringResource(R.string.agents_budget_blurb), color = g.text2, style = Type.style(Type.tiny))
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Column(Modifier.weight(1f)) { FieldLabel(stringResource(R.string.agents_per_run)); GlassField(run, { run = it }, "0.05", AgentTags.BUDGET_RUN, icon = null, number = true) }
                    Column(Modifier.weight(1f)) { FieldLabel(stringResource(R.string.agents_per_day)); GlassField(day, { day = it }, "0.50", AgentTags.BUDGET_DAY, icon = null, number = true) }
                }
                Text(stringResource(R.string.agents_spent_today, BudgetLedger.usd(p.spentToday(System.currentTimeMillis()))), color = g.text2, style = Type.style(Type.tiny))
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Column(Modifier.weight(1f)) { FieldLabel(stringResource(R.string.agents_max_listings)); GlassField(maxListings, { maxListings = it }, "8", AgentTags.template("max_listings"), icon = null, number = true) }
                    Column(Modifier.weight(1f)) { FieldLabel(stringResource(R.string.agents_lookback)); GlassField(lookback, { lookback = it }, "14", AgentTags.template("lookback"), icon = null, number = true) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Chip(stringResource(R.string.agents_apply), AgentTags.APPLY, accent = true) {
                        val r = num(run); val d = num(day)
                        saved = when {
                            model.isBlank() -> ctx.getString(R.string.agents_bad_model)
                            r == null || d == null || r < 0 || d < 0 -> ctx.getString(R.string.agents_bad_budget)
                            r > d -> ctx.getString(R.string.agents_run_above_day)
                            else -> {
                                p.model = model.trim(); p.budgetRunUsd = r; p.budgetDayUsd = d
                                p.maxListings = (maxListings.toIntOrNull() ?: p.maxListings).coerceIn(1, 50)
                                p.lookbackDays = (lookback.toIntOrNull() ?: p.lookbackDays).coerceIn(1, 90)
                                ctx.getString(R.string.agents_saved)
                            }
                        }
                    }
                    Text(saved, color = g.text2, style = Type.style(Type.tiny))
                }
            }
        }
        item {
            GlassCard {
                FieldLabel(stringResource(R.string.token_title))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Chip(stringResource(R.string.token_check), AgentTags.TOKEN_CHECK, icon = R.drawable.ph_key) {
                        token = ctx.getString(R.string.token_checking)
                        scope.launch {
                            val t = withContext(Dispatchers.IO) { Account.token(ctx, state.cfg.ai.accountProvider) }
                            token = if (t.present) ctx.getString(R.string.token_present) else ctx.getString(R.string.token_missing, t.why)
                        }
                    }
                    // #1801 the key is entered in the fleet's AI-key owner engine, not here: this opens its AI keys screen.
                    Chip(stringResource(R.string.token_set), AgentTags.TOKEN_SET, icon = R.drawable.ph_key) {
                        if (!Account.openSettings(ctx, state.cfg.ai.accountProvider)) token = ctx.getString(R.string.token_no_engine)
                    }
                    Text(token, Modifier.testTag(AgentTags.TOKEN), color = g.text, style = Type.style(Type.tiny))
                }
                Text(stringResource(R.string.agents_draft_only_blurb), color = g.text2, style = Type.style(Type.tiny))
            }
        }
    }
}

/** The Agents tab's state: which agent is running, the last status line, and a counter the lists re-read on. */
class AgentsModel(private val state: SearchState) {
    var running by mutableStateOf<String?>(null)
    var status by mutableStateOf("")
    /** The agent [status] is about. */
    var statusOf by mutableStateOf<String?>(null)
    var rev by mutableStateOf(0)

    fun run(agentId: String, scope: CoroutineScope) {
        val svc = state.services.agents ?: return
        running = agentId; status = ""; statusOf = agentId
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { svc.run(agentId) } }
            running = null
            status = r.fold({ it.record.summary }, { "failed: " + (it.message ?: it.javaClass.simpleName) })
            rev++
        }
    }
}
