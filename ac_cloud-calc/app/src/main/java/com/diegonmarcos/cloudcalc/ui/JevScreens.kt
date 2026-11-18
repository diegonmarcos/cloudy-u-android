@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudcalc.ui

import com.diegonmarcos.cloudcalc.jev.call
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.Logic
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.decide.JevFlow
import com.diegonmarcos.cloudcalc.decide.JevStore
import com.diegonmarcos.superapp.decisions.Decision
import com.diegonmarcos.superapp.decisions.Decisions
import com.diegonmarcos.cloudcalc.jev.JevConfig
import com.diegonmarcos.cloudcalc.jev.JevRouter
import com.diegonmarcos.cloudcalc.jev.Models
import com.diegonmarcos.superapp.decisions.Option
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Token, binder and network calls block: always off the main thread. */
private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

/**
 * "Asking…" while a call is out. Deliberately text, not an indeterminate progress bar: an endless
 * animation never lets Compose go idle, which hangs every UI test that waits on the screen (#768).
 */
@Composable
private fun Working() = Text(stringResource(R.string.jev_working), style = MaterialTheme.typography.bodySmall)

/** Every option with its probability as a bar, most probable first. */
@Composable
private fun OptionBars(options: List<Option>) {
    options.forEach { o ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            Text(o.label, Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            LinearProgressIndicator(progress = { o.p.toFloat() }, modifier = Modifier.weight(1f).padding(top = 6.dp))
            Text(JevFlow.percent(o.p), Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Model, latency, reported cost and the estimate from the catalogue's price. */
@Composable
private fun DecisionMeta(d: Decision) {
    val ctx = LocalContext.current
    val price = remember(d.model) { JevStore.catalogue(ctx).firstOrNull { it.slug == d.model }?.promptPrice }
    val est = Decisions.estimateCost(d.request, price)
    Text(
        listOfNotNull(
            d.model,
            "${d.latencyMs} ms",
            d.cost?.let { "$" + "%.7f".format(java.util.Locale.ROOT, it) + " " + stringResource(R.string.jev_reported) },
            est?.let { "≈$" + "%.7f".format(java.util.Locale.ROOT, it) + " " + stringResource(R.string.jev_estimated) },
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (d.error.isNotBlank()) Text(d.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

// ── Jev mode ────────────────────────────────────────────────────────────────────────────────

@Composable
fun JevMode(mode: Declarations.Mode) {
    val api = LocalCalcApi.current
    val state = LocalCalcState.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(mode.id) { mutableStateOf("") }
    val attachments = remember { androidx.compose.runtime.mutableStateListOf<com.diegonmarcos.cloudcalc.decide.Attachment>() }
    var busy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<JevFlow.Answer?>(null) }
    var picked by remember { mutableStateOf<JevFlow.Run?>(null) }

    // Text handed over by another screen (a history tap, a test), as ExpressionMode takes it.
    LaunchedEffect(state.pending) {
        val p = state.pending
        if (p != null && p.first == mode.id) {
            text = p.second
            state.pending = null
        }
    }

    fun keep(asked: String, run: JevFlow.Run?) {
        if (run != null && run.error.isBlank() && run.lines.isNotEmpty()) state.remember(Logic.Entry(mode.id, asked, run.result), historyMax())
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().testTag(CalcTags.JEV_INPUT),
            label = { Text(stringResource(R.string.jev_hint)) },
        )
        AttachBar(attachments) { heard -> text = if (text.isBlank()) heard else text.trimEnd() + " " + heard }
        Button(
            enabled = (text.isNotBlank() || attachments.isNotEmpty()) && !busy,
            modifier = Modifier.padding(vertical = CalcMetrics.gap).testTag(CalcTags.JEV_ASK),
            onClick = {
                val asked = text.ifBlank { ctx.getString(R.string.attach_default_request) }
                val sent = attachments.toList()
                scope.launch {
                    busy = true; picked = null
                    answer = io { JevFlow.ask(ctx, api, asked, startTimer = true, attachments = sent) }
                    keep(asked, answer?.run)
                    busy = false
                }
            },
        ) { Text(stringResource(R.string.jev_ask)) }
        if (busy) Working()
        val a = answer ?: return@Column
        val o = a.outcome
        val best = o.options.firstOrNull()
        Text(
            when (o.kind) {
                JevRouter.Kind.ROUTED -> stringResource(R.string.jev_routed, o.tool!!.id, JevFlow.percent(best?.p ?: 0.0))
                JevRouter.Kind.NONE -> stringResource(R.string.jev_none, JevFlow.percent(best?.p ?: 0.0))
                JevRouter.Kind.CANDIDATES -> stringResource(R.string.jev_unsure)
                JevRouter.Kind.FAILED -> stringResource(R.string.jev_failed, o.reason) +
                    if (o.fallback != null) "\n" + stringResource(R.string.jev_fallback) else ""
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(CalcTags.JEV_VERDICT),
        )
        if (o.kind == JevRouter.Kind.CANDIDATES) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
                items(o.candidates, key = { it.id }) { t ->
                    AssistChip(
                        onClick = {
                            val asked = o.request
                            scope.launch {
                                busy = true
                                picked = io { JevFlow.pick(ctx, api, JevStore.config(ctx), null, t, asked, startTimer = true) }
                                keep(asked, picked)
                                busy = false
                            }
                        },
                        label = { Text(t.id + " " + JevFlow.percent(o.options.firstOrNull { it.key == t.id }?.p ?: 0.0)) },
                        modifier = Modifier.testTag(CalcTags.jevCandidate(t.id)),
                    )
                }
            }
        }
        (picked ?: a.run)?.let { RunBlock(it) }
        HorizontalDivider(Modifier.padding(vertical = CalcMetrics.gap))
        Text(stringResource(R.string.jev_probabilities), style = MaterialTheme.typography.labelLarge)
        OptionBars(o.options)
        JevStore.config(ctx).route.extra.forEach { q ->
            o.extras[q.id]?.takeIf { it.isNotEmpty() }?.let {
                Text(q.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = CalcMetrics.gap))
                OptionBars(it)
            }
        }
        Text(stringResource(R.string.jev_token_from, a.tokenSource), style = MaterialTheme.typography.labelSmall)
        DecisionMeta(o.decision)
    }
}

/** What the app computed for a plan, a jump to the tool, and the follow-up question box. */
@Composable
private fun RunBlock(run: JevFlow.Run) {
    val state = LocalCalcState.current
    Column(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.gap).testTag(CalcTags.RESULT)) {
        Text(run.calculation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        run.lines.forEach { (label, value) ->
            Text(if (label.isBlank()) "= $value" else "$label = $value", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (run.error.isNotBlank()) Text(run.error, color = MaterialTheme.colorScheme.error)
        run.slots?.let { d -> if (!d.ok) Text(stringResource(R.string.jev_slots_positional), style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { state.show(run.mode) }) { Text(stringResource(R.string.jev_open, Declarations.mode(run.mode)?.label ?: run.mode)) }
        if (run.error.isBlank()) AskAboutResult(run.mode, run.calculation, run.result)
    }
}

// ── Addendum A: a probabilistic follow-up on any result ─────────────────────────────────────

/**
 * Under any result: the mode's preset questions (build.json::jev.ask) and a free question, each
 * scored by the decision model against the app's own result. The answer, every option's
 * probability, is kept in History with the result.
 */
@Composable
fun AskAboutResult(modeId: String, calculation: String, result: String) {
    if (result.isBlank()) return
    val ctx = LocalContext.current
    val state = LocalCalcState.current
    val scope = rememberCoroutineScope()
    var open by remember(modeId) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.testTag(CalcTags.ASK_TOGGLE)) { Text(stringResource(R.string.jev_ask_about)) }
    if (!open) return
    val presets = remember(modeId) { JevStore.config(ctx).questionsFor(modeId) }
    var context by rememberSaveable(modeId) { mutableStateOf("") }
    var free by rememberSaveable(modeId) { mutableStateOf("") }
    var type by rememberSaveable(modeId) { mutableStateOf(JevConfig.NOUL) }
    var opts by rememberSaveable(modeId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf<Pair<JevConfig.Question, Decision>?>(null) }

    fun run(q: JevConfig.Question) {
        val calc = calculation; val res = result; val ctxText = context
        scope.launch {
            busy = true
            val d = io { JevFlow.askAbout(ctx, q, modeId, calc, res, ctxText) }
            asked = q to d
            if (d.ok) state.remember(Logic.Entry(modeId, calc, res, JevFlow.decisionRecord(q, d)), historyMax())
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().testTag(CalcTags.ASK_BOX)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            items(presets, key = { it.id }) { q ->
                AssistChip(onClick = { run(q) }, enabled = !busy, label = { Text(q.label) }, modifier = Modifier.testTag(CalcTags.askPreset(q.id)))
            }
        }
        OutlinedTextField(context, { context = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.jev_context)) }, singleLine = true)
        OutlinedTextField(free, { free = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.jev_question)) })
        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            listOf(JevConfig.NOUL to R.string.jev_type_noul, JevConfig.CHOICE to R.string.jev_type_choice, JevConfig.SCORE to R.string.jev_type_score).forEach { (t, label) ->
                FilterChip(selected = type == t, onClick = { type = t }, label = { Text(stringResource(label)) })
            }
        }
        if (type != JevConfig.NOUL) {
            OutlinedTextField(opts, { opts = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.jev_options)) })
        }
        Button(enabled = free.isNotBlank() && !busy, onClick = { run(JevRouter.freeQuestion(free.trim(), type, opts)) }) { Text(stringResource(R.string.jev_ask)) }
        if (busy) Working()
        asked?.let { (q, d) ->
            Text(q.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = CalcMetrics.gap))
            OptionBars(d.options(q.id))
            DecisionMeta(d)
        }
    }
}

// ── Configs › Token ─────────────────────────────────────────────────────────────────────────

@Composable
fun JevTokenMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf<JevStore.Token?>(null) }
    var hasManual by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    fun reload() = scope.launch {
        token = io { JevStore.token(ctx) }
        hasManual = io { JevStore.hasOverride(ctx) }
    }
    LaunchedEffect(mode.id) { reload() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        val t = token
        Text(
            if (t == null) stringResource(R.string.loading)
            else stringResource(R.string.jev_token_in_use, stringResource(if (t.present) R.string.jev_token_held else R.string.jev_token_none), t.source),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(CalcTags.JEV_TOKEN),
        )
        Text(stringResource(R.string.jev_token_doc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // #1801 every option kept, the key entry moved: the override is this app's slot in the AI-key owner engine,
        // the fleet key that engine's own; both open its AI keys screen, where the key is typed into the engine's process.
        Row(Modifier.padding(vertical = CalcMetrics.gap), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            Button(onClick = {
                note = ctx.getString(if (JevStore.openKeySettings(ctx, override = true)) R.string.jev_token_opened else R.string.jev_token_no_engine)
            }, modifier = Modifier.testTag(CalcTags.JEV_SET_OVERRIDE)) { Text(stringResource(if (hasManual) R.string.jev_token_change_override else R.string.jev_token_save)) }
            OutlinedButton(onClick = {
                note = ctx.getString(if (JevStore.openKeySettings(ctx, override = false)) R.string.jev_token_opened else R.string.jev_token_no_engine)
            }, modifier = Modifier.testTag(CalcTags.JEV_SET_FLEET)) { Text(stringResource(R.string.jev_token_fleet)) }
        }
        Button(onClick = { scope.launch { note = io { JevFlow.testToken(ctx) }; reload() } }, modifier = Modifier.padding(vertical = CalcMetrics.gap).testTag(CalcTags.JEV_TEST_TOKEN)) {
            Text(stringResource(R.string.jev_token_test))
        }
        if (note.isNotBlank()) Text(note, modifier = Modifier.testTag(CalcTags.JEV_NOTE))
    }
}

// ── Configs › Routing ───────────────────────────────────────────────────────────────────────

@Composable
fun JevRoutingMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    var json by remember(mode.id) { mutableStateOf(JevStore.configJson(ctx)) }
    var edited by remember(mode.id) { mutableStateOf(JevStore.isEdited(ctx)) }
    var note by remember { mutableStateOf("") }
    val cfg = remember(json) { runCatching { JevConfig.parse(json) }.getOrNull() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        Text(stringResource(if (edited) R.string.jev_routing_edited else R.string.jev_routing_default), style = MaterialTheme.typography.titleMedium)
        if (cfg != null) RoutingTable(cfg)
        OutlinedTextField(
            json, { json = it }, Modifier.fillMaxWidth().padding(vertical = CalcMetrics.gap).testTag(CalcTags.JEV_ROUTING_JSON),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), minLines = 8,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            Button(onClick = {
                note = JevStore.saveConfig(ctx, json) ?: ctx.getString(R.string.jev_routing_saved)
                edited = JevStore.isEdited(ctx)
            }, modifier = Modifier.testTag(CalcTags.JEV_ROUTING_SAVE)) { Text(stringResource(R.string.jev_save)) }
            OutlinedButton(onClick = {
                JevStore.resetConfig(ctx); json = JevStore.configJson(ctx); edited = false
                note = ctx.getString(R.string.jev_routing_reset_done)
            }) { Text(stringResource(R.string.jev_routing_reset)) }
        }
        if (note.isNotBlank()) Text(note, modifier = Modifier.testTag(CalcTags.JEV_NOTE))
    }
}

/** The declaration as a table: thresholds, fallback, every tool and question. */
@Composable
private fun RoutingTable(cfg: JevConfig) {
    val r = cfg.route
    val small = MaterialTheme.typography.bodySmall
    Text(stringResource(R.string.jev_routing_summary, JevFlow.percent(r.threshold), r.candidates.toString(), JevFlow.percent(r.candidateMinP), r.onError + (if (r.onError == "expression") " → " + r.fallbackMode else ""), JevFlow.percent(r.slotThreshold)), style = small)
    Text(stringResource(R.string.jev_routing_models, cfg.model(JevFlow.ROUTE_USE), cfg.model(JevFlow.SCORE_USE)), style = small)
    r.tools.forEach { t ->
        Text(t.id + " → " + t.action + (if (t.mode.isNotBlank()) " " + t.mode else "") + (if (t.form.isNotBlank()) "/" + t.form else ""), style = small, fontWeight = FontWeight.Bold)
        Text(t.criterion, style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("none: " + r.noneCriterion, style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
    (r.extra + cfg.ask.values.flatten()).distinctBy { it.id }.forEach { q ->
        Text(q.id + " (" + q.type + "): " + q.instructions, style = small)
    }
}

// ── Configs › Models ────────────────────────────────────────────────────────────────────────

@Composable
fun JevModelsMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf(JevStore.catalogue(ctx)) }
    var note by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    fun refresh(force: Boolean) = scope.launch {
        note = io { JevStore.refreshCatalogue(ctx, force) }.orEmpty()
        models = JevStore.catalogue(ctx); tick++
    }
    LaunchedEffect(mode.id) { refresh(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        // Every use build.json::jev.uses declares (#772 added sound), never a list spelled here.
        val cfg = remember { JevStore.config(ctx) }
        cfg.uses.keys.sorted().forEach { use ->
            val choice = remember(tick, use) { JevStore.modelChoice(ctx, use) }
            Text(cfg.useLabel(use), style = MaterialTheme.typography.labelLarge)
            ModelPicker(choice, listOf(JevConfig.CHEAPEST) + Models.cheapestFirst(models).map { it.slug }, use) { slug ->
                JevStore.setModelChoice(ctx, use, slug); tick++
            }
            Text(stringResource(R.string.jev_runs_on, remember(tick, use) { JevStore.model(ctx, use) }), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider(Modifier.padding(vertical = CalcMetrics.gap))
        OutlinedButton(onClick = { refresh(true) }) { Text(stringResource(R.string.jev_refresh)) }
        val at = JevStore.catalogueAt(ctx)
        Text(
            (if (at > 0) stringResource(R.string.jev_catalogue_at, java.time.Instant.ofEpochMilli(at).toString().take(16), models.size.toString()) else "") +
                (if (note.isNotBlank()) "\n" + note else ""),
            style = MaterialTheme.typography.bodySmall,
        )
        Models.cheapestFirst(models).forEach { m ->
            Column(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.small).testTag(CalcTags.jevModel(m.slug))) {
                Text(m.slug, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    listOf(m.provider, Models.perMillion(m), "ctx ${m.context}", m.inputs.joinToString("+").ifBlank { "text" } + "→decisions")
                        .plus(if (m.free) listOf(stringResource(R.string.jev_free)) else emptyList()).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ModelPicker(current: String, slugs: List<String>, use: String, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag(CalcTags.jevPicker(use))) { Text(current) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.jev_declared_default)) }, onClick = { onPick(null); open = false })
        slugs.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { onPick(s); open = false }) }
    }
}

// ── Configs › Test ──────────────────────────────────────────────────────────────────────────

@Composable
fun JevTestMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by rememberSaveable(mode.id) { mutableStateOf("") }
    val models = remember { JevStore.catalogue(ctx) }
    var selected by remember { mutableStateOf(setOf(JevStore.model(ctx, JevFlow.ROUTE_USE))) }
    var image by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(listOf<Decision>()) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) dataUrl(ctx, uri).let { (url, why) -> image = url; note = why }
    }
    // An image rides along only when EVERY chosen model's catalogue entry takes images.
    val imageOk = selected.isNotEmpty() && selected.all { s -> models.firstOrNull { it.slug == s }?.images == true }
    val cfg = remember { JevStore.config(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().testTag(CalcTags.JEV_TEST_INPUT), label = { Text(stringResource(R.string.jev_prompt)) })
        Text(stringResource(R.string.jev_models_to_compare), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = CalcMetrics.gap))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            items((selected.toList() + Models.cheapestFirst(models).map { it.slug }).distinct(), key = { it }) { s ->
                FilterChip(selected = s in selected, onClick = { selected = if (s in selected) selected - s else selected + s }, label = { Text(s) })
            }
        }
        if (imageOk) Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            OutlinedButton(onClick = { pickImage.launch("image/*") }) { Text(stringResource(R.string.jev_attach_image)) }
            if (image != null) TextButton(onClick = { image = null }) { Text(stringResource(R.string.jev_remove_image)) }
        }
        Button(
            enabled = prompt.isNotBlank() && selected.isNotEmpty() && !busy,
            modifier = Modifier.padding(vertical = CalcMetrics.gap).testTag(CalcTags.JEV_TEST_RUN),
            onClick = {
                val p = prompt; val img = image.takeIf { imageOk }; val chosen = selected.toList()
                scope.launch {
                    busy = true
                    results = io {
                        val tok: String? = null // #1801 the AI-key owner engine adds the key (JevStore.http)
                        chosen.map { m -> Decisions.call(cfg, JevStore.http, tok, m, JevRouter.state(p, img), JevRouter.questions(cfg)) }
                    }
                    busy = false
                }
            },
        ) { Text(stringResource(R.string.jev_run)) }
        if (busy) Working()
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
        if (results.isEmpty()) return@Column
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gutter)) {
            results.forEach { d ->
                Column(Modifier.width(240.dp).testTag(CalcTags.jevTestColumn(d.model))) {
                    Text(d.model, fontWeight = FontWeight.Bold)
                    DecisionMeta(d)
                    OptionBars(d.options(JevRouter.ROUTE))
                    cfg.route.extra.forEach { q -> Text(q.label, style = MaterialTheme.typography.labelSmall); OptionBars(d.options(q.id)) }
                }
            }
        }
        Text(stringResource(R.string.jev_raw_request), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = CalcMetrics.gap))
        SelectionContainer { Text(results.first().shownRequest(cfg.endpoint), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
        Text(stringResource(R.string.jev_raw_responses), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = CalcMetrics.gap))
        results.forEach { d ->
            SelectionContainer { Text(d.model + "\n" + (d.answers?.toString(2) ?: d.error), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
        }
    }
}

/** MAX_IMAGE_BYTES bounds what one Decisions request may carry; ponytail: no downscaling, a big photo is refused. */
private const val MAX_IMAGE_BYTES = 1_000_000

/** The picked image as a data: URL, or null with the reason. */
private fun dataUrl(ctx: Context, uri: Uri): Pair<String?, String> = runCatching {
    val mime = ctx.contentResolver.getType(uri) ?: "image/png"
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null to "unreadable image"
    if (bytes.size > MAX_IMAGE_BYTES) return null to "image is ${bytes.size / 1000} kB; at most ${MAX_IMAGE_BYTES / 1000} kB is sent"
    "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) to ""
}.getOrElse { null to (it.message ?: "unreadable image") }
