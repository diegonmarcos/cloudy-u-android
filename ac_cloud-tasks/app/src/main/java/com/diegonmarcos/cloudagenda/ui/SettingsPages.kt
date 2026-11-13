package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudagenda.agent.AgentStatus
import com.diegonmarcos.cloudagenda.data.AgentConfig
import com.diegonmarcos.cloudagenda.data.JmapAuth
import com.diegonmarcos.cloudagenda.data.MailSourceKind
import com.diegonmarcos.cloudagenda.data.Proposal
import com.diegonmarcos.cloudagenda.data.Snapshot
import com.diegonmarcos.cloudagenda.data.ThemeMode
import com.diegonmarcos.superapp.ai.AiAgent
import com.diegonmarcos.superapp.ai.AiModel
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSegmented
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

object SettingsTags {
    const val APPEARANCE = "settings_appearance"
    const val AGENT = "settings_agent"
    const val INBOX = "settings_inbox"
    const val THEME = "settings_theme"
    const val AGENT_ENABLED = "agent_enabled"
    const val AGENT_STATUS = "agent_status"
    const val SOURCE = "agent_source"
    const val ACCOUNT = "agent_account"
    const val JMAP_URL = "agent_jmap_url"
    const val JMAP_AUTH = "agent_jmap_auth"
    const val JMAP_SECRET = "agent_jmap_secret"
    const val MAILBOXES = "agent_mailboxes"
    const val FREQUENCY = "agent_frequency"
    const val AGENT_PICK = "agent_pick"
    const val MODEL = "agent_model"
    const val MODEL_PICK = "agent_model_pick"
    const val TARGET = "agent_target"
    const val SAVE = "agent_save"
    const val RUN_NOW = "agent_run_now"
    fun extract(id: String) = "agent_extract_$id"
    fun agent(id: String) = "agent_pick_$id"
    fun target(id: String) = "agent_target_$id"
    fun approve(id: String) = "inbox_approve_$id"
    fun reject(id: String) = "inbox_reject_$id"
    fun proposal(id: String) = "inbox_proposal_$id"
    const val INBOX_EMPTY = "inbox_empty"
}

/** Settings > Appearance: Light / Dark / System, applied app-wide through TasksTheme (CloudKitTheme) at once. */
@Composable
fun AppearancePage(mode: ThemeMode, onMode: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(SettingsTags.APPEARANCE), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KitSectionHeader("Theme", "Light, dark, or follow the system. It applies to the whole app straight away.")
        KitSegmented(
            options = ThemeMode.entries.map { it.id to it.label }, selected = mode.id,
            onSelect = { onMode(ThemeMode.of(it)) }, tag = SettingsTags.THEME,
        )
    }
}

/** The part of an [AgentConfig] the user declares; the last-run columns belong to the worker. */
fun AgentConfig.declared(): AgentConfig = copy(lastRunAt = 0, lastStatus = "", lastDetail = "")

/** The banner the Agent page draws for the last run: a word the user can act on, never a dressed-up success. */
fun agentBanner(cfg: AgentConfig): Pair<String, KitState> = when {
    !cfg.enabled -> "The agent is off. Switch it on and save to start." to KitState.IDLE
    cfg.lastStatus == AgentStatus.BACKEND_NOT_DEPLOYED -> "Agent backend not deployed: ${cfg.lastDetail}" to KitState.WARN
    cfg.lastStatus == AgentStatus.MAIL_UNAVAILABLE -> "Mail unavailable: ${cfg.lastDetail}" to KitState.WARN
    cfg.lastStatus == AgentStatus.JMAP_NOT_BUILT -> "JMAP source: ${cfg.lastDetail}" to KitState.WARN
    cfg.lastStatus == AgentStatus.NOT_CONFIGURED -> "Not configured: ${cfg.lastDetail}" to KitState.WARN
    cfg.lastStatus == AgentStatus.ERROR -> "Last run failed: ${cfg.lastDetail}" to KitState.BAD
    cfg.lastStatus == AgentStatus.OK -> "Last run: ${cfg.lastDetail}" to KitState.OK
    else -> "Saved. It has not run yet; Run now tries it once." to KitState.IDLE
}

/**
 * Settings > Agent: the declaration of the mail agent -- what it reads (Cloud Mail on this phone, or a JMAP URL with
 * Bearer / Authelia sign-in), which mailboxes, how often, with which agent and model of the fleet AI gateway, what to
 * extract (events, bookings, invoices, deadlines) and where approved tasks land. Saving stores the declaration whether
 * or not a backend exists; the banner then says honestly what the last run found ("Agent backend not deployed").
 * The agent only PROPOSES: nothing becomes a task until it is approved in Settings > Inbox.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentPage(
    config: AgentConfig,
    snapshot: Snapshot,
    agents: List<AiAgent>,
    models: List<AiModel>,
    mailReason: String?,
    secret: String,
    secretAvailable: Boolean,
    running: Boolean,
    onSave: (AgentConfig, String) -> Unit,
    onRunNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalKitPalette.current
    // The draft restarts when the SAVED declaration changes (first load, or after Save), not when a run only updates the status.
    val declared = config.declared()
    var draft by remember(declared) { mutableStateOf(config) }
    var secretDraft by remember { mutableStateOf(secret) }
    var modelsOpen by remember { mutableStateOf(false) }
    var targetOpen by remember { mutableStateOf(false) }
    val (bannerText, bannerState) = agentBanner(config)
    val dirty = draft.declared() != declared || secretDraft != secret

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(SettingsTags.AGENT), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KitStatusBanner(if (running) "Running..." else bannerText, if (running) KitState.BUSY else bannerState, tag = SettingsTags.AGENT_STATUS)
        KitSwitchRow(
            "Enable the mail agent", "Reads mail on the schedule below and proposes tasks for you to approve. It never creates one by itself.",
            draft.enabled, { draft = draft.copy(enabled = it) }, Modifier.testTag(SettingsTags.AGENT_ENABLED),
        )

        KitCard {
            KitSectionHeader("Mail account", "", eyebrow = true)
            KitSegmented(MailSourceKind.entries.map { it.id to it.label }, draft.source.id, { draft = draft.copy(source = MailSourceKind.of(it)) }, tag = SettingsTags.SOURCE)
            if (draft.source == MailSourceKind.CLOUD_MAIL) {
                Text(mailReason ?: "Cloud Mail is installed and answers. Its accounts and sign-in stay in Cloud Mail; this app never sees them.",
                    color = if (mailReason == null) p.textSecondary else p.warn, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(draft.account, { draft = draft.copy(account = it) }, label = { Text("Cloud Mail account id (blank = all accounts)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsTags.ACCOUNT))
            } else {
                OutlinedTextField(draft.jmapUrl, { draft = draft.copy(jmapUrl = it) }, label = { Text("JMAP URL") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsTags.JMAP_URL))
                KitSegmented(JmapAuth.entries.map { it.id to it.label }, draft.jmapAuth.id, { draft = draft.copy(jmapAuth = JmapAuth.of(it)) }, tag = SettingsTags.JMAP_AUTH)
                if (draft.jmapAuth != JmapAuth.NONE) {
                    // #1693 the bearer / session secret is typed in Cloud Account (App credentials); here it is only taken from there.
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    var credLine by remember { mutableStateOf("") }
                    Text(if (draft.jmapAuth == JmapAuth.BEARER) "Bearer token: entered in Cloud Account (App credentials)." else "Authelia session: entered in Cloud Account (App credentials).",
                        color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(SettingsTags.JMAP_SECRET))
                    Text(if (secretAvailable) "Kept encrypted with the Android Keystore, never in the database." else "The Keystore is unavailable: the secret is not kept.",
                        color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                    if (credLine.isNotBlank()) Text(credLine, color = p.warn, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                val c = withContext(Dispatchers.IO) { com.diegonmarcos.superapp.fleetendpoints.android.AccountClient.credential(ctx, "tasks.jmap_secret", "The JMAP secret") }
                                if (c.present) { secretDraft = c.value; credLine = "Taken from Cloud Account: Save to keep it." } else credLine = c.line
                            }
                        }, modifier = Modifier.testTag(SettingsTags.JMAP_SECRET + ":take")) { Text("Take it from Cloud Account") }
                        TextButton(onClick = { credLine = com.diegonmarcos.superapp.fleetendpoints.android.AccountClient.openCredentials(ctx).orEmpty() },
                            modifier = Modifier.testTag(SettingsTags.JMAP_SECRET + ":set")) { Text("Set it in Cloud Account") }
                    }
                }
                OutlinedTextField(draft.account, { draft = draft.copy(account = it) }, label = { Text("JMAP account id (blank = primary)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsTags.ACCOUNT))
            }
            OutlinedTextField(draft.mailboxes, { draft = draft.copy(mailboxes = it) }, label = { Text("Mailboxes / labels (comma separated, blank = all)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(SettingsTags.MAILBOXES))
        }

        KitCard {
            KitSectionHeader("Frequency", "", eyebrow = true)
            KitSegmented(AgentConfig.FREQUENCIES.map { it.first.toString() to it.second }, draft.frequencyMinutes.toString(),
                { draft = draft.copy(frequencyMinutes = it.toIntOrNull() ?: 0) }, tag = SettingsTags.FREQUENCY)
        }

        KitCard {
            KitSectionHeader("Agent and model", "", eyebrow = true)
            if (agents.isEmpty()) {
                Text("The gateway's agent list is not part of this build; the gateway's default agent answers.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = draft.agent.isBlank(), onClick = { draft = draft.copy(agent = "") }, label = { Text("Default") }, modifier = Modifier.testTag(SettingsTags.agent("")))
                    for (a in agents) FilterChip(selected = draft.agent == a.id, onClick = { draft = draft.copy(agent = a.id) }, label = { Text(a.label) }, modifier = Modifier.testTag(SettingsTags.agent(a.id)))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft.model, { draft = draft.copy(model = it) }, label = { Text("Model (blank = the agent's own)") }, singleLine = true,
                    modifier = Modifier.weight(1f).testTag(SettingsTags.MODEL))
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { modelsOpen = true }, modifier = Modifier.testTag(SettingsTags.MODEL_PICK)) { Text("Pick") }
                    DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                        if (models.isEmpty()) DropdownMenuItem(text = { Text("Model list unavailable (offline?)") }, onClick = { modelsOpen = false }, enabled = false)
                        for (m in models.take(40)) DropdownMenuItem(text = { Text(m.name.ifBlank { m.id }) }, onClick = { draft = draft.copy(model = m.id); modelsOpen = false })
                    }
                }
            }
        }

        KitCard {
            KitSectionHeader("What to extract", "", eyebrow = true)
            KitSwitchRow("Events", "Something happening at a date or time.", draft.extractEvents, { draft = draft.copy(extractEvents = it) }, Modifier.testTag(SettingsTags.extract("events")))
            KitSwitchRow("Bookings", "Reservations, tickets, appointments, trips.", draft.extractBookings, { draft = draft.copy(extractBookings = it) }, Modifier.testTag(SettingsTags.extract("bookings")))
            KitSwitchRow("Invoices", "Bills and payments to make.", draft.extractInvoices, { draft = draft.copy(extractInvoices = it) }, Modifier.testTag(SettingsTags.extract("invoices")))
            KitSwitchRow("Deadlines", "Something to do by a date.", draft.extractDeadlines, { draft = draft.copy(extractDeadlines = it) }, Modifier.testTag(SettingsTags.extract("deadlines")))
        }

        KitCard {
            KitSectionHeader("Target project and list", "", eyebrow = true)
            val target = snapshot.list(draft.targetListId)
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { targetOpen = true }, modifier = Modifier.fillMaxWidth().testTag(SettingsTags.TARGET)) {
                    Text(if (target == null) "Choose a list (first list if none)" else (snapshot.project(target.projectId)?.name.orEmpty() + " › " + target.name))
                }
                DropdownMenu(expanded = targetOpen, onDismissRequest = { targetOpen = false }) {
                    for (l in snapshot.lists) DropdownMenuItem(
                        text = { Text(snapshot.project(l.projectId)?.name.orEmpty() + " › " + l.name) },
                        onClick = { draft = draft.copy(targetListId = l.id); targetOpen = false }, modifier = Modifier.testTag(SettingsTags.target(l.id)),
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onSave(draft, secretDraft) }, enabled = dirty, modifier = Modifier.testTag(SettingsTags.SAVE)) { Text("Save") }
            OutlinedButton(onClick = onRunNow, enabled = !running && !dirty && config.enabled, modifier = Modifier.testTag(SettingsTags.RUN_NOW)) { Text("Run now") }
        }
    }
}

/** Settings > Inbox: what the agent proposed. Approve creates the task (in the target list); Reject only records the decision. */
@Composable
fun InboxPage(proposals: List<Proposal>, onApprove: (Proposal) -> Unit, onReject: (Proposal) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    if (proposals.isEmpty()) {
        KitEmptyState("Nothing to review", "The mail agent only proposes. Items it finds appear here, and nothing becomes a task until you approve it.", modifier.testTag(SettingsTags.INBOX_EMPTY))
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(SettingsTags.INBOX), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (pr in proposals) {
            KitCard(Modifier.testTag(SettingsTags.proposal(pr.id))) {
                Text("${pr.kind.label}  ·  ${(pr.confidence * 100).toInt()}%", color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
                Text(pr.title, color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
                if (pr.details.isNotBlank()) Text(pr.details, color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
                val meta = listOfNotNull(pr.dateTarget?.let { "target $it" }, pr.dateDeadline?.let { "due $it" }, pr.location.ifBlank { null }).joinToString("  ·  ")
                if (meta.isNotEmpty()) Text(meta, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                if (pr.sourceSubject.isNotBlank()) Text("From mail: ${pr.sourceSubject}", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onApprove(pr) }, modifier = Modifier.testTag(SettingsTags.approve(pr.id))) { Text("Approve") }
                    OutlinedButton(onClick = { onReject(pr) }, modifier = Modifier.testTag(SettingsTags.reject(pr.id))) { Text("Reject") }
                }
            }
        }
    }
}
