package com.diegonmarcos.cloudaccount.hub

import com.diegonmarcos.superapp.profile.ActivityFeed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudaccount.autofill.AutofillImportBody
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitFingerprint
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitStatTile
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatePill
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// LocalDeviceAuth lives with RevealGate in libs:account-ui (hub/Reveal.kt, #1439).

/** Reads a picked file's text; MainActivity's document picker. */
typealias PickText = ((String) -> Unit) -> Unit

object HubTags {
    const val PAGE_CONNECTIONS = "hub:page:connections"
    const val PAGE_IMPORT = "hub:page:import"
    const val PAGE_PROVIDERS = "hub:page:providers"
    const val PAGE_APPS = "hub:page:apps"
    const val CONNECTIONS = "hub:connections"
    const val APPS_LOADING = "hub:apps:loading"
    const val APPS_NO_PLAN = "hub:apps:no-plan"
    const val SETUP_ALL = "hub:setup-all"
    const val TOKENS_IMPORT = "hub:tokens:import"
    const val TOKENS_IMPORT_ALL = "hub:tokens:import-all"
    fun cell(p: String, m: AuthMethod) = "hub:cell:$p:${m.name}"
    fun secret(path: String) = "hub:secret:$path"
    fun reveal(path: String) = "hub:reveal:$path"
    fun provider(id: String) = "hub:provider:$id"
    fun app(id: String) = "hub:app:$id"
}

private fun stateOf(s: CellStatus) = when (s) {
    CellStatus.CONNECTED -> KitState.OK; CellStatus.EXPIRED -> KitState.BAD; CellStatus.MISSING -> KitState.WARN; CellStatus.NOT_USED -> KitState.IDLE
}

private fun stateOf(r: SetupResult?) = when { r == null -> KitState.IDLE; r.ok -> KitState.OK; else -> KitState.BAD }

@Composable
private fun Scroll(tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = KitDensity.large, vertical = KitDensity.medium)
        .testTag(tag), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) { content() }
}

@Composable
private fun Caption(text: String) = Text(text, color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption)

/** Every hub page opens the same way: one scroll, [KitDensity.large] gutters, a title with one sentence under it. */
@Composable
private fun PageFrame(tag: String, title: String, subtitle: String, content: @Composable () -> Unit) = Scroll(tag) {
    KitSectionHeader(title, subtitle)
    content()
}

/** A list with nothing in it yet says so in one place (ui-kit's empty state), never as a blank card. */
@Composable
private fun Empty(title: String, caption: String, tag: String) = KitCard(Modifier.testTag(tag)) { KitEmptyState(title, caption) }

// ── one secret: masked, reveal / copy behind device auth, edit / rotate / delete ─────────────────────────────

@Composable
fun SecretLine(e: SecretEntry, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val gate = RevealGate(LocalDeviceAuth.current)
    var shown by remember(e.path) { mutableStateOf<String?>(null) }
    var note by remember(e.path) { mutableStateOf("") }
    var editing by remember(e.path) { mutableStateOf(false) }
    var confirmDelete by remember(e.path) { mutableStateOf(false) }
    val fp = remember(e.path, onChanged) { Providers.mask(HubAndroid.value(ctx, e.path).orEmpty()) }
    val exp = e.expiresAt?.let { (if (e.expired) "expired " else "expires ") + it.toString().take(10) }.orEmpty()
    KitListRow(e.path, Modifier.testTag(HubTags.secret(e.path)), tag = e.path,
        secondary = listOf(e.method.label, exp, note).filter { it.isNotBlank() }.joinToString(" · "),
        pill = (if (e.expired) "expired" else "stored") to (if (e.expired) KitState.BAD else KitState.OK),
        onClick = {
            if (shown != null) shown = null
            else gate.reveal("Show ${e.name}", { HubAndroid.value(ctx, e.path) }, { shown = it; ActivityFeed.record(ctx, "secret", "revealed ${e.path}") }, { note = "not shown: device auth refused" })
        })
    val v = shown
    if (v != null) Text(v, color = LocalKitPalette.current.textPrimary, fontSize = KitDensity.body, modifier = Modifier.testTag(HubTags.reveal(e.path)))
    else KitFingerprint(fp, tag = "fp:${e.path}")
    KitActionBar(listOf(
        KitAction(if (v == null) "Reveal" else "Hide", "hub:secret:reveal:${e.path}") {
            if (v != null) shown = null
            else gate.reveal("Show ${e.name}", { HubAndroid.value(ctx, e.path) }, { shown = it; ActivityFeed.record(ctx, "secret", "revealed ${e.path}") }, { note = "not shown: device auth refused" })
        },
        KitAction("Copy", "hub:secret:copy:${e.path}") {
            gate.reveal("Copy ${e.name}", { HubAndroid.value(ctx, e.path) },
                { SecretClipboard.copy(ctx, it); ActivityFeed.record(ctx, "secret", "copied ${e.path}"); note = "copied · clears in ${SecretClipboard.CLEAR_AFTER_MS / 1000}s" },
                { note = "not copied: device auth refused" })
        },
        KitAction("Edit / rotate", "hub:secret:edit:${e.path}") { editing = true },
        KitAction("Delete", "hub:secret:delete:${e.path}") { confirmDelete = true },
    ), filledFirst = false)
    if (editing) {
        var text by remember { mutableStateOf("") }
        OutlinedTextField(text, { text = it }, label = { Text("New value for ${e.name}", fontSize = KitDensity.caption) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("hub:secret:edit:field:${e.path}"))
        KitActionBar(listOf(
            KitAction("Save", "hub:secret:edit:save:${e.path}", enabled = text.isNotBlank()) {
                HubAndroid.put(ctx)(e.path, text.trim()); editing = false; shown = null; note = "rotated"; ActivityFeed.record(ctx, "secret", "rotated ${e.path}"); onChanged()
            },
            KitAction("Cancel", "hub:secret:edit:cancel:${e.path}") { editing = false },
        ))
    }
    if (confirmDelete) KitConfirmDialog("Delete ${e.name}?", "${e.path} is removed from this phone's account. Apps that use it stop working until you set it again.",
        "Delete", "Keep", onConfirm = { confirmDelete = false; HubAndroid.remove(ctx, e.path); ActivityFeed.record(ctx, "secret", "deleted ${e.path}"); onChanged() }, onDismiss = { confirmDelete = false })
}

// ── a provider: its secrets + its own Setup (connect, test, re-auth) ─────────────────────────────────────────

@Composable
fun ProviderCard(p: Provider, entries: List<SecretEntry>, go: (String, String) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember(p.id) { mutableStateOf<SetupResult?>(null) }
    var busy by remember(p.id) { mutableStateOf(false) }
    var connecting by remember(p.id) { mutableStateOf(false) }
    // Status first, details on demand: the card opens on a tap, or by itself when something needs doing.
    val expired = entries.count { it.expired }
    val attention = entries.isEmpty() || expired > 0
    var open by remember(p.id) { mutableStateOf(attention) }
    val pill = when { entries.isEmpty() -> "missing" to KitState.WARN; expired > 0 -> "expired" to KitState.BAD; else -> "connected" to KitState.OK }
    val next = when { entries.isEmpty() -> "next: connect or import a token"; expired > 0 -> "next: re-auth $expired expired"; else -> "${entries.size} stored" }
    KitCard(Modifier.testTag(HubTags.provider(p.id))) {
        KitListRow(p.label, tag = "provider:${p.id}", leading = p.label.take(1).uppercase(),
            secondary = next + " · " + p.methods.joinToString { it.label }, pill = pill, onClick = { open = !open })
        if (!open) return@KitCard
        if (entries.isEmpty()) Caption("Nothing stored for ${p.label} yet: Connect pastes a token, Re-auth signs in.")
        for (e in entries) SecretLine(e, onChanged)
        KitSectionHeader("Setup", "", eyebrow = true)
        result?.let { r -> for (s in r.steps) KitListRow(s.kind.label, tag = "setup:${p.id}:${s.kind.name}", secondary = s.line,
            pill = (if (s.ok) "ok" else "failed") to (if (s.ok) KitState.OK else KitState.BAD)) }
            ?: Caption(if (busy) "Testing ${p.label}…" else "Connect, test the stored credential, or re-auth.")
        KitActionBar(listOf(
            KitAction("Connect", "hub:provider:connect:${p.id}") { connecting = !connecting },
            KitAction(if (busy) "Testing…" else "Test", "hub:provider:test:${p.id}", enabled = !busy && entries.isNotEmpty()) {
                busy = true
                scope.launch { result = withContext(Dispatchers.IO) { HubAndroid.providerTarget(ctx, p).run() }; busy = false }
            },
            KitAction("Re-auth", "hub:provider:reauth:${p.id}") {
                if (AuthMethod.WEB_OAUTH in p.methods && p.id in setOf("github", "gitea", "cloud-authelia")) go("home", "connect") else connecting = true
            },
        ), filledFirst = entries.isEmpty())
        if (connecting) ConnectToken(p) { connecting = false; onChanged() }
    }
}

/** Connect by pasting one token: stored at the path TokenImport would give it, so every page agrees. */
@Composable
private fun ConnectToken(p: Provider, done: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("api_token") }
    var value by remember { mutableStateOf("") }
    OutlinedTextField(name, { name = it }, label = { Text("Name", fontSize = KitDensity.caption) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("hub:connect:name:${p.id}"))
    OutlinedTextField(value, { value = it }, label = { Text("Token / key for ${p.label}", fontSize = KitDensity.caption) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag("hub:connect:value:${p.id}"))
    KitActionBar(listOf(KitAction("Save", "hub:connect:save:${p.id}", enabled = value.isNotBlank() && name.isNotBlank()) {
        TokenImport.importOne(TokenImport.Row(p.id, Providers.methodOf(name.lowercase()) ?: AuthMethod.API_TOKEN, "${p.id}_$name", value.trim()), HubAndroid.put(ctx))
        done()
    }))
}

// ── Secrets ▸ providers: THE token list ──────────────────────────────────────────────────────────────────────

@Composable
fun SecretsProvidersPage(go: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val groups = remember(tick) { Providers.secrets(HubAndroid.connections(ctx)).filter { (p, s) -> p.core || s.isNotEmpty() } }
    val stored = groups.values.sumOf { it.size }
    PageFrame(HubTags.PAGE_PROVIDERS, "Providers",
        "Every SSH key, token, password and login this account keeps, by provider. Masked; reveal and copy ask for your fingerprint or screen lock; a copy clears itself after ${SecretClipboard.CLEAR_AFTER_MS / 1000} s.") {
        if (stored == 0) KitStatusBanner("No credential stored yet: connect a provider below, or paste tokens on Home ▸ Import.", KitState.WARN, tag = "providers:empty",
            action = KitAction("Import tokens", "hub:providers:import") { go("home", "import") })
        for ((p, entries) in groups) ProviderCard(p, entries, go) { tick++ }
    }
}

// ── Profiles ▸ apps ──────────────────────────────────────────────────────────────────────────────────────────

@Composable
fun AppProfilesPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    // The plan reads every app's captured settings: off the main thread, with the page saying so meanwhile.
    var plan by remember { mutableStateOf<com.diegonmarcos.superapp.profile.SetupPlan.Plan?>(null) }
    var rows by remember { mutableStateOf<List<AppProfiles.Row>?>(null) }
    LaunchedEffect(tick) {
        rows = null
        val p = withContext(Dispatchers.IO) { HubAndroid.plan(ctx) }
        rows = withContext(Dispatchers.IO) { HubAndroid.appRows(ctx, p) }
        plan = p
    }
    val results = remember { mutableStateMapOf<String, SetupResult>() }
    var busy by remember { mutableStateOf(false) }
    Scroll(HubTags.PAGE_APPS) {
        val list = rows
        val o = list?.firstOrNull { it.id == open }
        if (o == null) {
            KitSectionHeader("App profiles", "Each fleet app's account and configuration as Cloud Account holds it. Tap an app for its profile and its own Setup.")
            when {
                list == null -> KitStatusBanner("Reading the app profiles…", KitState.BUSY, tag = HubTags.APPS_LOADING)
                list.isEmpty() -> Empty("No fleet app declared", "The fleet manifest lists no app for this phone; nothing to profile yet.", "hub:apps:empty")
                else -> {
                    if (plan == null) KitStatusBanner("The setup plan could not be read: states are shown, pushed counts are not.", KitState.WARN, tag = HubTags.APPS_NO_PLAN)
                    val signedIn = list.count { it.state == "signed in" || it.state == "configured" }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                        KitStatTile("$signedIn", "set up", if (signedIn > 0) KitState.OK else KitState.IDLE, Modifier.weight(1f), tag = "apps:ready")
                        KitStatTile("${list.size - signedIn}", "to set up", if (list.size - signedIn > 0) KitState.WARN else KitState.OK, Modifier.weight(1f), tag = "apps:todo")
                    }
                    KitCard {
                        for (r in list) KitListRow(r.label, Modifier.testTag(HubTags.app(r.id)), tag = r.id, leading = r.label.take(1).uppercase(),
                            secondary = listOfNotNull(r.endpoint, "${r.captured} setting(s)", r.sync.takeIf { it.isNotEmpty() }?.joinToString()).joinToString(" · "),
                            pill = r.state to when (r.state) { "signed in", "configured" -> KitState.OK; "signed out" -> KitState.WARN; else -> KitState.IDLE },
                            onClick = { open = r.id })
                    }
                }
            }
        } else {
            TextButton(onClick = { open = null }, modifier = Modifier.testTag("hub:app:back")) { Text("‹ All apps") }
            KitSectionHeader(o.label, o.pkg)
            KitCard {
                KitListRow("State", secondary = o.state, tag = "app:${o.id}:state",
                    pill = o.state to when (o.state) { "signed in", "configured" -> KitState.OK; "signed out" -> KitState.WARN; else -> KitState.IDLE })
                KitListRow("Server / endpoint", secondary = o.endpoint ?: "none captured", tag = "app:${o.id}:endpoint")
                KitListRow("Sync", secondary = o.sync.joinToString().ifEmpty { "no sync settings captured" }, tag = "app:${o.id}:sync")
                KitListRow("Stores it owns", secondary = o.stores.joinToString().ifEmpty { "none captured yet (Configs ▸ Setup ▸ Push captures them)" }, tag = "app:${o.id}:stores")
                KitListRow("Settings captured", secondary = "${o.captured} · ${o.planned} would be pushed", tag = "app:${o.id}:counts")
            }
            KitSectionHeader("Setup", "", eyebrow = true)
            KitCard {
                results[o.id]?.let { r -> for (s in r.steps) KitListRow(s.kind.label, tag = "setup:${o.id}:${s.kind.name}", secondary = s.line,
                    pill = (if (s.ok) "ok" else "failed") to (if (s.ok) KitState.OK else KitState.BAD)) }
                    ?: Caption("Login, endpoint and test: pushes this app's settings through its setup contract and reads them back.")
                KitActionBar(listOf(
                    KitAction(if (busy) "Running…" else "Set up ${o.label}", "hub:app:setup:${o.id}", enabled = !busy) {
                        busy = true
                        scope.launch {
                            val app = AppProfiles.App(o.id, o.label, o.pkg)
                            results[o.id] = withContext(Dispatchers.IO) { HubAndroid.appTarget(ctx, app, plan).run() }
                            busy = false; tick++
                        }
                    },
                    KitAction("Open app", "hub:app:open:${o.id}", enabled = o.installed) {
                        ctx.packageManager.getLaunchIntentForPackage(o.pkg)?.let { ctx.startActivity(it) }
                    },
                ))
            }
        }
    }
}

// ── Home ▸ connections: the status of every provider, and Setup all ──────────────────────────────────────────

@Composable
fun ConnectionsPage(go: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val blob = remember(tick) { HubAndroid.connections(ctx) }
    val groups = remember(tick) { Providers.secrets(blob) }
    val matrix = remember(tick) { Providers.matrix(blob) }
    val summary = remember(tick) { runCatching { AccountVault(ctx).summary() }.getOrNull() }
    PageFrame(HubTags.PAGE_CONNECTIONS, "Connections", "Which providers this account can reach. Each row leads with its worst state and the next thing to do; tap it for every sign-in way (Web OAuth, API token, SSH) and its own action.") {
        val creds = groups.values.sumOf { it.size }
        val expired = groups.values.sumOf { l -> l.count { it.expired } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
            KitStatTile("$creds", "credentials", if (creds > 0) KitState.OK else KitState.WARN, Modifier.weight(1f), tag = "home:creds") { go("secrets", "providers") }
            KitStatTile("$expired", "expired", if (expired > 0) KitState.BAD else KitState.OK, Modifier.weight(1f), tag = "home:expired") { go("secrets", "providers") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
            KitStatTile("${summary?.optInt("connections") ?: 0}", "connection values", KitState.IDLE, Modifier.weight(1f), tag = "home:connections") { go("secrets", "bundle") }
            KitStatTile("${summary?.optInt("configs") ?: 0}", "app settings", KitState.IDLE, Modifier.weight(1f), tag = "home:configs") { go("profiles", "apps") }
        }
        val needing = matrix.count { it.worst != CellStatus.CONNECTED }
        when {
            creds == 0 -> KitStatusBanner("Nothing connected yet: sign in on Connect, or paste tokens on Import.", KitState.WARN, tag = "home:empty",
                action = KitAction("Connect", "hub:connections:connect") { go("home", "connect") })
            expired > 0 -> KitStatusBanner("$expired credential(s) expired: re-auth them under Secrets ▸ Providers.", KitState.BAD, tag = "home:expired-banner",
                action = KitAction("Re-auth", "hub:connections:reauth") { go("secrets", "providers") })
            needing > 0 -> KitStatusBanner("$needing provider(s) have a sign-in way still to set up; the rest are connected.", KitState.WARN, tag = "home:todo")
            else -> KitStatusBanner("Every provider is connected.", KitState.OK, tag = "home:ok")
        }

        KitSectionHeader("Connections", "", eyebrow = true)
        if (matrix.isEmpty()) Empty("No provider yet", "Rows appear as soon as a credential is stored.", "hub:connections:empty")
        else ConnectionsList(matrix, go) { tick++ }

        KitSectionHeader("Setup all", "", eyebrow = true)
        SetupAllCard { tick++ }
    }
}

// ── Home ▸ import: THE importer (personal data, then tokens) ─────────────────────────────────────────────────

@Composable
fun ImportPage(pickText: PickText) {
    var tick by remember { mutableStateOf(0) }
    PageFrame(HubTags.PAGE_IMPORT, "Import", "Paste or pick a file; nothing is saved until you review it. Personal data lands under Profiles ▸ Autofill, tokens under Secrets ▸ Providers.") {
        KitSectionHeader("Personal data", "", eyebrow = true)
        KitCard { AutofillImportBody(pickText) }
        KitSectionHeader("Tokens", "", eyebrow = true)
        KitCard { TokenImporter(pickText) { tick++ } }
        if (tick > 0) KitStatusBanner("Imported: the Connections table and Secrets ▸ Providers show them now.", KitState.OK, tag = "import:done")
    }
}

/**
 * One row per provider: its worst state as the pill and the next action as the line; a tap opens the three
 * sign-in ways (Web OAuth, API token, SSH) as rows of their own, each with the one thing to do. A list, never
 * a wide grid: it reads on a 360 dp phone without scrolling sideways.
 */
@Composable
private fun ConnectionsList(rows: List<ConnectionRow>, go: (String, String) -> Unit, changed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tested = remember { mutableStateMapOf<String, SetupResult>() }
    var open by remember { mutableStateOf<String?>(null) }
    KitCard(Modifier.testTag(HubTags.CONNECTIONS)) {
        for (r in rows) {
            val worst = r.worst
            val next = when (worst) {
                CellStatus.CONNECTED -> "every way connected · tap to test"
                CellStatus.EXPIRED -> "next: re-auth " + r.cells.filter { it.value.status == CellStatus.EXPIRED }.keys.joinToString { it.label }
                CellStatus.MISSING -> "next: set up " + r.cells.filter { it.value.status == CellStatus.MISSING }.keys.joinToString { it.label }
                CellStatus.NOT_USED -> "not used"
            }
            KitListRow(r.provider.label, Modifier.testTag("hub:connections:${r.provider.id}"), tag = "connections:${r.provider.id}",
                leading = r.provider.label.take(1).uppercase(), secondary = next, pill = worst.word to stateOf(worst),
                onClick = { open = if (open == r.provider.id) null else r.provider.id })
            if (open == r.provider.id) {
                for (m in AuthMethod.values()) {
                    val c = r.cells.getValue(m)
                    val action = when (c.status) { CellStatus.CONNECTED -> "Test"; CellStatus.EXPIRED -> "Re-auth"; CellStatus.MISSING -> "Set up"; CellStatus.NOT_USED -> "" }
                    val act: (@Composable () -> Unit)? = if (action.isEmpty()) null else {
                        {
                            TextButton(onClick = {
                                when (c.status) {
                                    CellStatus.CONNECTED -> scope.launch {
                                        tested[r.provider.id] = withContext(Dispatchers.IO) { HubAndroid.providerTarget(ctx, r.provider).run() }
                                    }
                                    CellStatus.MISSING, CellStatus.EXPIRED ->
                                        if (m == AuthMethod.WEB_OAUTH && r.provider.id in setOf("github", "gitea", "cloud-authelia")) go("home", "connect")
                                        else go("secrets", "providers")
                                    CellStatus.NOT_USED -> {}
                                }
                            }, modifier = Modifier.testTag("hub:cell:action:${r.provider.id}:${m.name}")) { Text(action) }
                        }
                    }
                    KitListRow(m.label, Modifier.testTag(HubTags.cell(r.provider.id, m)).padding(start = KitDensity.large), tag = "cell:${r.provider.id}:${m.name}",
                        secondary = if (c.paths.isEmpty()) "" else c.paths.joinToString(), pill = c.status.word to stateOf(c.status), trailing = act)
                }
                tested[r.provider.id]?.let { t -> KitListRow("Test ${r.provider.label}", tag = "tested:${r.provider.id}", secondary = t.line, pill = (if (t.ok) "ok" else "failed") to stateOf(t)) }
            }
        }
        TextButton(onClick = changed, modifier = Modifier.testTag("hub:connections:refresh")) { Text("Refresh") }
    }
}

@Composable
private fun TokenImporter(pickText: PickText, changed: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    val rows = remember { mutableStateListOf<TokenImport.Row>() }
    var note by remember { mutableStateOf("") }
    OutlinedTextField(text, { text = it }, label = { Text("Paste JSON, .env or key=value lines", fontSize = KitDensity.caption) }, minLines = 3,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("hub:tokens:text"))
    KitActionBar(listOf(
        KitAction("Parse", "hub:tokens:parse", enabled = text.isNotBlank()) {
            val r = TokenImport.parse(text); rows.clear(); rows.addAll(r.rows); text = ""
            note = "${r.rows.size} token(s) read as ${r.format}" + if (r.skipped > 0) ", ${r.skipped} line(s) skipped" else ""
        },
        KitAction("Pick file", "hub:tokens:file") {
            pickText { t -> val r = TokenImport.parse(t); rows.clear(); rows.addAll(r.rows)
                note = "${r.rows.size} token(s) read as ${r.format}" + if (r.skipped > 0) ", ${r.skipped} line(s) skipped" else "" }
        },
    ))
    if (note.isNotBlank()) Caption(note)
    if (rows.isNotEmpty()) {
        Row(Modifier.fillMaxWidth()) {
            for ((h, w) in listOf("" to 0.5f, "Provider" to 1f, "Type" to 1f, "Name" to 1.4f, "Value" to 1.4f)) Text(h, Modifier.weight(w),
                color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption)
        }
        rows.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().testTag("hub:tokens:row:$i"), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(r.selected, { rows[i] = r.copy(selected = it) }, Modifier.weight(0.5f))
                Text(Providers.byId(r.provider).label, Modifier.weight(1f).clickable {
                    val ids = Providers.CATALOG.map { it.id }; rows[i] = r.copy(provider = ids[(ids.indexOf(r.provider) + 1) % ids.size])
                }, fontSize = KitDensity.caption, color = LocalKitPalette.current.textPrimary)
                Text(r.type.label, Modifier.weight(1f).clickable {
                    val ms = AuthMethod.values(); rows[i] = r.copy(type = ms[(r.type.ordinal + 1) % ms.size])
                }, fontSize = KitDensity.caption, color = LocalKitPalette.current.textPrimary)
                Text(r.name, Modifier.weight(1.4f), fontSize = KitDensity.caption, color = LocalKitPalette.current.textPrimary)
                Text(r.masked, Modifier.weight(1.4f), fontSize = KitDensity.caption, color = LocalKitPalette.current.textSecondary)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Caption("→ ${r.path}" + if (r.expires.isNotBlank()) " · expires ${r.expires.take(10)}" else "")
                TextButton(onClick = {
                    TokenImport.importOne(r, HubAndroid.put(ctx)); rows.removeAt(i); note = "imported ${r.name}"; ActivityFeed.record(ctx, "import", "imported ${r.name}"); changed()
                }, modifier = Modifier.testTag("hub:tokens:import:$i")) { Text("Import") }
            }
        }
        Caption("Tap a provider or a type to change it.")
        KitActionBar(listOf(KitAction("Import all (${rows.count { it.selected }})", HubTags.TOKENS_IMPORT_ALL, enabled = rows.any { it.selected }) {
            val n = TokenImport.importAll(rows.toList(), HubAndroid.put(ctx))
            rows.removeAll { it.selected }; note = "imported $n token(s)"; ActivityFeed.record(ctx, "import", "imported $n token(s)"); changed()
        }, KitAction("Discard", "hub:tokens:discard") { rows.clear(); note = "" }))
    }
}

@Composable
private fun SetupAllCard(changed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateListOf<SetupResult>() }
    var busy by remember { mutableStateOf(false) }
    KitCard(Modifier.testTag(HubTags.SETUP_ALL)) {
        Caption("Runs each provider's Setup, then each app's, one after the other, and lists what happened.")
        KitActionBar(listOf(KitAction(if (busy) "Running…" else "Setup all", "hub:setup-all:run", enabled = !busy) {
            busy = true; results.clear()
            scope.launch {
                val targets = withContext(Dispatchers.IO) { HubAndroid.allTargets(ctx) }
                withContext(Dispatchers.IO) { SetupAll.run(targets) { r -> scope.launch { results += r } } }
                busy = false; changed()
            }
        }))
        if (results.isNotEmpty()) {
            val ok = results.count { it.ok }
            KitStatusBanner("$ok of ${results.size} set up", if (ok == results.size) KitState.OK else KitState.WARN, tag = "setup-all")
        }
        for (r in results) KitListRow(r.label, tag = "setup-all:${r.id}", secondary = r.line, pill = (if (r.ok) "ok" else "failed") to stateOf(r))
    }
}
