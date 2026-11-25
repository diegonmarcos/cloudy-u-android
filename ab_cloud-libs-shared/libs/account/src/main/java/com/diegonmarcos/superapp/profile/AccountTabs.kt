package com.diegonmarcos.superapp.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.account.R
import com.diegonmarcos.superapp.profile.AccountStore.Slot
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSelectableTile
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #778 Configs ▸ Account ▸ PROFILES, RUNTIME and DRIFT — Compose (libs:ui-kit), hosted in
 * ProfileFragment's columns through `kitComposeView` (Connect is still that fragment's Views; this
 * screen is #778's, so #773's fan-out skips it). Every button calls [AccountModel] — the same
 * function the debug API's op calls — and every surface carries a test tag ([AccountTags]).
 *
 * NO SECRET REACHES A COMPOSABLE: a value is drawn only through [shownValue], which passes the
 * Infos mask (build.json::ui.profile.infos.mask) and otherwise draws the value's length alone.
 */
object AccountTags {
    fun tab(id: String) = "account:$id"
    const val POPULATE_RUNTIME = "profiles:populate_runtime"
    const val POPULATE_SERVER = "profiles:populate_server"
    const val SAVE = "profiles:save"
    const val EXPORT = "profiles:export"
    fun topic(id: String) = "profiles:topic:$id"
    fun edit(path: String) = "profiles:edit:$path"
    const val EDIT_FIELD = "profiles:edit_field"
    const val EDIT_OK = "profiles:edit_ok"
    const val REFRESH = "runtime:refresh"
    fun runtimeApp(id: String) = "runtime:app:$id"
    fun runtimeStatus(id: String) = "runtime:status:$id"
    fun runtimeCounts(id: String) = "runtime:counts:$id"
    fun runtimeRoster(id: String) = "runtime:roster:$id"
    fun applyApps(device: String) = "infos:apps:apply:$device"
    /** #573 the card's one Apply all button (cockpit `runtime.apply_all`). */
    fun runtimeApplyAll(id: String) = "runtime:apply_all:$id"
    fun exportFile(slot: Slot) = "drift:export:${slot.name}"
    const val EXPORT_REPORT = "drift:export:report"
    fun pair(id: String) = "drift:pair:$id"
    fun driftApp(id: String) = "drift:app:$id"
    const val PUSH_ALL = "drift:push:all"
    /** #783 the new phone: install every declared app that is missing, then apply all. */
    const val MIGRATE = "drift:migrate"
    const val PULL_ALL = "drift:pull:all"
    fun pushApp(id: String) = "drift:push:app:$id"
    fun pullApp(id: String) = "drift:pull:app:$id"
    fun pushItem(path: String) = "drift:push:$path"
    fun pullItem(path: String) = "drift:pull:$path"
    const val UPLOAD = "drift:upload"
    const val DISCARD = "drift:discard"
    const val RESULT = "account:result"
    const val PAT_FIELD = "drift:pat_field"
    const val PAT_GO = "drift:pat_go"
}

/** A value as it may be drawn: through the mask, else its length only; long text clipped. */
fun shownValue(path: String, text: String?): String = when {
    text == null -> "—"
    InfoMask.declared.hides(path, text) -> "•••• (${text.length})"
    text.length > 160 -> text.take(160) + "… (+${text.length - 160})"
    else -> text
}

@Composable
private fun ActionButton(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(label) }
}

@Composable
private fun ResultLine(m: AccountModel) {
    if (m.last.isNotBlank()) Text(m.last, color = LocalKitPalette.current.textSecondary,
        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag(AccountTags.RESULT))
}

private fun metaLine(d: AccountStore.Doc): String = "${d.meta.source} · ${d.meta.at} · ${d.meta.sha256.take(8)}"

// ── B · PROFILES ─────────────────────────────────────────────────────────

/**
 * The declared config held in app storage — the local copy (else the server file) — per TOPIC
 * (the vault's sections, build.json::ui.profile.infos.schema), EVERY declared field shown, filled
 * or empty (#766). A shown, unmasked field is tapped to edit the working copy; Save writes it.
 */
@Composable
fun ProfilesTab(m: AccountModel, connectLabel: String, export: (name: String, text: String) -> Unit, openStore: (String) -> Unit) {
    m.version.intValue
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<String?>(null) }
    val shown = m.shown()
    Column(Modifier.fillMaxWidth().testTag(AccountTags.tab("profiles")), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val file = if (m.local != null) m.savedLocal() else m.server()
        KitSectionHeader(stringResource(R.string.account_profiles_title), when {
            shown == null -> stringResource(R.string.account_profiles_none, connectLabel)
            m.local != null && file != null -> stringResource(R.string.account_profiles_local, metaLine(file))
            m.local != null -> stringResource(R.string.account_profiles_local_unsaved)
            else -> stringResource(R.string.account_profiles_server, file?.let(::metaLine).orEmpty())
        })
        if (m.dirty) Text(stringResource(R.string.account_profiles_unsaved), color = p.accent)
        ActionButton(stringResource(R.string.account_populate_runtime), AccountTags.POPULATE_RUNTIME) { m.populateFromRuntime() }
        ActionButton(stringResource(R.string.account_populate_server), AccountTags.POPULATE_SERVER) { m.populateFromServer() }
        ActionButton(stringResource(R.string.account_save), AccountTags.SAVE, enabled = m.local != null) { m.save() }
        ActionButton(stringResource(R.string.account_export), AccountTags.EXPORT, enabled = shown != null) {
            val slot = if (m.local != null) { m.save(); Slot.L } else Slot.S
            m.store.export(slot)?.let { export("account-${slot.name}.json", it) }
        }
        ResultLine(m)
        for (section in InfoMask.sectionsFor(InfoMask.schema, emptyList(), shown)) {
            val rows = InfoMask.declared.schemaRows(section, shown?.opt(section.id))
            var open by remember(section.id) { mutableStateOf(false) }
            KitCard(Modifier.testTag(AccountTags.topic(section.id))) {
                Column(Modifier.fillMaxWidth().clickable { open = !open }) {
                    Text(section.label, color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(ctx.getString(R.string.infos_schema_summary, section.fields.size,
                        rows.count { it.kind != InfoMask.Kind.EMPTY }, rows.count { it.kind == InfoMask.Kind.EMPTY },
                        rows.count { it.kind == InfoMask.Kind.MASKED }), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                if (open) {
                    for (row in rows) {
                        val full = if (row.path.isBlank()) section.id else section.id + AccountDrift.SEP + row.path
                        val editable = row.kind == InfoMask.Kind.SHOWN || row.kind == InfoMask.Kind.EMPTY
                        Column(Modifier.fillMaxWidth()
                            .then(if (editable && '[' !in row.path) Modifier.clickable { editing = full }.testTag(AccountTags.edit(full)) else Modifier)) {
                            Text(row.path.ifBlank { section.id }, color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
                            Text(when (row.kind) {
                                InfoMask.Kind.MASKED -> ctx.getString(R.string.infos_row_masked, row.size)
                                InfoMask.Kind.COLLAPSED -> ctx.getString(R.string.infos_row_collapsed, row.size)
                                InfoMask.Kind.PENDING -> ctx.getString(R.string.infos_row_pending, row.text)
                                InfoMask.Kind.EMPTY -> ctx.getString(R.string.infos_row_empty)
                                InfoMask.Kind.SHOWN -> shownValue(full, row.text)
                            }, color = if (row.kind == InfoMask.Kind.SHOWN) p.textPrimary else p.textSecondary,
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (section.render == "apps") DeclaredApps(shown, section.route, openStore)
                }
            }
        }
    }
    editing?.let { path ->
        var value by remember(path) { mutableStateOf(AccountDrift.leaves(shown)[path]?.let(AccountDrift::text).orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.account_edit_title, path)) },
            text = { OutlinedTextField(value, { value = it }, modifier = Modifier.testTag(AccountTags.EDIT_FIELD)) },
            confirmButton = {
                TextButton(enabled = !InfoMask.declared.hides(path, value), modifier = Modifier.testTag(AccountTags.EDIT_OK),
                    onClick = { m.edit(path, value); editing = null }) { Text(stringResource(R.string.account_edit_ok)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** #727 the apps topic: each declared app per device, installed here or not, and the way into the Store. */
@Composable
private fun DeclaredApps(bundle: org.json.JSONObject?, route: String, openStore: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val devices = bundle?.optJSONObject("apps")?.optJSONObject("devices")
    val fleet = com.diegonmarcos.superapp.appstore.AppInventory.fleetPackages()
    val sources = remember { com.diegonmarcos.superapp.appstore.PhoneAppActions.sourcesOrNull(ctx) }   // #1566 null = not served yet
    var listed = 0
    if (bundle != null) devices?.keys()?.forEach { id ->
        val apps = VaultCockpit.appsListed(bundle, id, fleet)
        if (apps.isEmpty()) return@forEach
        listed += apps.size
        val here = apps.map { a -> runCatching { ctx.packageManager.getPackageInfo(a.pkg, 0) }.isSuccess }
        Text(ctx.getString(R.string.infos_apps_device, id, apps.size, here.count { it }), color = p.textPrimary)
        apps.forEachIndexed { i, a ->
            Text(ctx.getString(if (here[i]) R.string.infos_apps_row_installed else R.string.infos_apps_row_missing,
                a.label, a.pkg, sources?.let { VaultCockpit.storeLabel(it, a) } ?: ctx.getString(R.string.infos_apps_no_store)),
                color = if (here[i]) p.accent else p.textSecondary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        // #570 the declared set → the Store's import plan (StoreImport), the same path as a picked
        // inventory file: the Phone page shows every missing app with its install rung and
        // "Install all missing". Idempotent (diffed against this phone); the Store never uninstalls.
        if (route.isNotBlank()) TextButton(modifier = Modifier.testTag(AccountTags.applyApps(id)), onClick = {
            com.diegonmarcos.superapp.appstore.StoreImport.pending =
                com.diegonmarcos.superapp.appstore.AppInventory.toJson(VaultCockpit.appsDeclared(bundle, id, fleet))
            openStore(route)
        }) { Text(ctx.getString(R.string.infos_apps_apply_store, id, here.count { !it })) }
    }
    if (listed == 0) Text(ctx.getString(R.string.infos_apps_none), color = p.textSecondary)
    if (route.isNotBlank()) TextButton(onClick = { openStore(route) }) { Text(ctx.getString(R.string.infos_apps_open_store)) }
}

// ── C · RUNTIME ──────────────────────────────────────────────────────────

/**
 * What each fleet app is using right now, PER APP, read live (a stopped serving app is woken by the
 * bind). Status per app: reachable / not installed / not reporting. Read once when the tab is
 * composed, then on Refresh. Applying moved to Drift.
 */
@Composable
fun RuntimeTab(m: AccountModel) {
    m.version.intValue
    val p = LocalKitPalette.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    fun refresh() { reading = true; scope.launch { withContext(Dispatchers.IO) { m.refreshRuntime() }; reading = false } }
    fun applyAll(id: String) { reading = true; scope.launch { withContext(Dispatchers.IO) { m.applySection(id) }; reading = false } }
    LaunchedEffect(Unit) { refresh() }
    Column(Modifier.fillMaxWidth().testTag(AccountTags.tab("runtime")), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val r = m.runtime()
        KitSectionHeader(stringResource(R.string.account_runtime_title),
            if (r == null) stringResource(R.string.account_runtime_none) else stringResource(R.string.account_runtime_meta, metaLine(r)))
        ActionButton(stringResource(if (reading) R.string.account_runtime_reading else R.string.account_runtime_refresh), AccountTags.REFRESH, !reading) { refresh() }
        ResultLine(m)
        val values = AccountDrift.leaves(r?.body)
        val apps = r?.apps
        // #783 the cockpit's apps, then every fleet app the manifest declares — one card each.
        for (section in m.apps) {
            val a = apps?.optJSONObject(section.id) ?: continue
            val status = a.optString("status")
            val observed = a.optJSONArray("observed")?.let { o -> (0 until o.length()).map { o.optString(it) } }.orEmpty()
            KitCard(Modifier.testTag(AccountTags.runtimeApp(section.id))) {
                Text(section.label, color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(when (status) {
                    "reachable" -> R.string.account_status_reachable
                    "not_installed" -> R.string.account_status_not_installed
                    else -> R.string.account_status_not_reporting
                }) + a.optString("detail").let { if (it.isBlank()) "" else " · $it" } +
                    a.optString("summary").let { if (it.isBlank()) "" else " · $it" },
                    color = if (status == "reachable") p.accent else p.textSecondary, modifier = Modifier.testTag(AccountTags.runtimeStatus(section.id)))
                // #781 what the app declares (cockpit vault_fields) against what it reported.
                val counts = a.optJSONObject("counts")
                if (counts != null) Text(stringResource(R.string.account_runtime_counts, counts.optInt("declared"), counts.optInt("reported"),
                    counts.optInt("missing"), counts.optInt("unread")), color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(AccountTags.runtimeCounts(section.id)))
                for ((key, res) in listOf("missing" to R.string.account_runtime_missing, "unread" to R.string.account_runtime_unread)) {
                    val paths = a.optJSONArray(key)?.let { o -> (0 until o.length()).map { o.optString(it) } }.orEmpty()
                    if (paths.isNotEmpty()) Text(stringResource(res, paths.joinToString(", ")), color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                // #810 declared fields not read here WITH their reason.
                a.optJSONObject("justified")?.let { j -> j.keys().asSequence().sorted().forEach { f ->
                    Text(stringResource(R.string.account_runtime_justified, f, j.optString(f)), color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
                } }
                // #782 the fleet roster (apps) / the live tunnel (mesh): listed with no device pick.
                a.optJSONArray("roster")?.let { ro -> for (i in 0 until ro.length()) Text(rosterLine(ro.optJSONObject(i)),
                    color = p.textPrimary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(AccountTags.runtimeRoster(section.id))) }
                // #573 the whole declared section applied on ONE tap (mesh: all profiles, mail: all accounts).
                if (VaultCockpit.layout.sections.firstOrNull { it.id == section.id }?.runtime?.applyAll == true) ActionButton(stringResource(R.string.account_apply_all), AccountTags.runtimeApplyAll(section.id), !reading) { applyAll(section.id) }
                if (observed.isNotEmpty()) Text(stringResource(R.string.account_runtime_fields, observed.size), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                for (path in observed) {
                    Text(path, color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
                    Text(shownValue(path, values[path]?.let(AccountDrift::text)), color = p.textPrimary,
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ── D · DRIFT ────────────────────────────────────────────────────────────

/**
 * The three files side by side with their metadata, the declared pairs (S↔R, L↔S, L↔R) with
 * counts, and per app the drifted fields with their sync actions — per item, per app and all.
 */
@Composable
fun DriftTab(m: AccountModel, device: String, export: (name: String, text: String) -> Unit) {
    m.version.intValue
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pairs = remember { AccountModel.pairs() }
    var pairId by remember { mutableStateOf(pairs.firstOrNull()?.id.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var askPat by remember { mutableStateOf(false) }
    fun io(block: () -> String) { busy = true; scope.launch { withContext(Dispatchers.IO) { block() }; busy = false } }
    fun upload() = io {
        val token = AccountModel.ghHost()?.let { runCatching { GhEngine(ctx).token(it) }.getOrNull() }
        if (token == null) { askPat = true; "" } else m.upload(token, device)
    }
    Column(Modifier.fillMaxWidth().testTag(AccountTags.tab("drift")), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KitSectionHeader(stringResource(R.string.account_drift_title), stringResource(R.string.account_drift_caption))
        KitCard {
            for (slot in Slot.values()) {
                val d = m.doc(slot)
                Row(Modifier.fillMaxWidth()) {
                    Text(when {
                        d == null -> stringResource(R.string.account_file_absent, slot.name)
                        !d.intact -> stringResource(R.string.account_file_corrupt, slot.name)
                        else -> "${slot.name} · ${metaLine(d)}"
                    }, color = p.textPrimary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(end = 8.dp))
                    TextButton(enabled = d != null, modifier = Modifier.testTag(AccountTags.exportFile(slot)),
                        onClick = { m.store.export(slot)?.let { export("account-${slot.name}.json", it) } }) { Text(stringResource(R.string.account_export)) }
                }
            }
            TextButton(modifier = Modifier.testTag(AccountTags.EXPORT_REPORT),
                onClick = { export("account-drift-report.json", m.report().toString(2)) }) { Text(stringResource(R.string.account_export_report)) }
        }
        val three = m.threeWay().groupingBy { it.state }.eachCount()
        Text(stringResource(R.string.account_three_way, three[AccountDrift.Three.IN_SYNC] ?: 0, three[AccountDrift.Three.LOCAL_EDIT] ?: 0,
            three[AccountDrift.Three.RUNTIME_DRIFT] ?: 0, three[AccountDrift.Three.AGREED] ?: 0, three[AccountDrift.Three.CONFLICT] ?: 0),
            color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        val diffs = pairs.associate { it.id to m.diff(it) }
        for (pair in pairs) {
            KitSelectableTile(pair.label, stringResource(R.string.account_pair_drift, AccountDrift.counts(diffs[pair.id].orEmpty()).drift),
                pair.id == pairId, { pairId = pair.id }, Modifier.testTag(AccountTags.pair(pair.id)))
        }
        val pair = pairs.firstOrNull { it.id == pairId } ?: return@Column
        val fields = diffs[pair.id].orEmpty()
        val touchesR = Slot.R == pair.a || Slot.R == pair.b
        val withS = Slot.S == pair.a || Slot.S == pair.b
        val withL = Slot.L == pair.a || Slot.L == pair.b
        if (touchesR && withS) ActionButton(stringResource(R.string.account_push_all), AccountTags.PUSH_ALL, !busy) { io { m.pushServerToRuntime(AccountDrift.drifted(fields)) } }
        if (touchesR && withS) ActionButton(stringResource(R.string.account_migrate), AccountTags.MIGRATE, !busy) { io { m.migrate() } }
        if (touchesR) ActionButton(stringResource(R.string.account_pull_all), AccountTags.PULL_ALL, !busy) { io { m.pullRuntimeToLocal(AccountDrift.drifted(fields)) } }
        if (withL && withS) {
            ActionButton(stringResource(R.string.account_upload), AccountTags.UPLOAD, !busy) { upload() }
            ActionButton(stringResource(R.string.account_discard), AccountTags.DISCARD, !busy) { m.discardLocal() }
        }
        ResultLine(m)
        val labels = m.apps.associate { it.id to it.label }
        for ((app, c) in AccountDrift.byApp(fields, m.apps)) {
            val drifted = fields.filter { it.app == app && it.kind != AccountDrift.Kind.SAME }
            KitCard(Modifier.testTag(AccountTags.driftApp(app.ifBlank { "-" }))) {
                Text(labels[app] ?: stringResource(R.string.account_unowned), color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.account_app_counts, c.same, c.changed, c.onlyA, pair.a.name, c.onlyB, pair.b.name),
                    color = if (c.drift == 0) p.textSecondary else p.accent, style = MaterialTheme.typography.bodySmall)
                if (drifted.isNotEmpty() && app.isNotBlank()) Row {
                    if (touchesR && withS) TextButton(enabled = !busy, modifier = Modifier.testTag(AccountTags.pushApp(app)),
                        onClick = { io { m.pushServerToRuntime(drifted.map { it.path }) } }) { Text(stringResource(R.string.account_push)) }
                    if (touchesR) TextButton(enabled = !busy, modifier = Modifier.testTag(AccountTags.pullApp(app)),
                        onClick = { io { m.pullRuntimeToLocal(drifted.map { it.path }) } }) { Text(stringResource(R.string.account_pull)) }
                }
                for (f in drifted) {
                    Text(f.path + " · " + when (f.kind) {
                        AccountDrift.Kind.CHANGED -> stringResource(R.string.account_kind_changed)
                        AccountDrift.Kind.ONLY_A -> stringResource(R.string.account_kind_only, pair.a.name)
                        else -> stringResource(R.string.account_kind_only, pair.b.name)
                    }, color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
                    Text("${pair.a.name}: ${shownValue(f.path, f.a)}\n${pair.b.name}: ${shownValue(f.path, f.b)}",
                        color = p.textPrimary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    if (app.isNotBlank()) Row {
                        if (touchesR && withS) TextButton(enabled = !busy, modifier = Modifier.testTag(AccountTags.pushItem(f.path)),
                            onClick = { io { m.pushServerToRuntime(listOf(f.path)) } }) { Text(stringResource(R.string.account_push)) }
                        if (touchesR) TextButton(enabled = !busy, modifier = Modifier.testTag(AccountTags.pullItem(f.path)),
                            onClick = { io { m.pullRuntimeToLocal(listOf(f.path)) } }) { Text(stringResource(R.string.account_pull)) }
                    }
                }
            }
        }
    }
    if (askPat) {
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPat = false },
            title = { Text(stringResource(R.string.account_pat_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.account_pat_caption, com.diegonmarcos.cloudlib.auth.AuthDeclaration.configSource.gitRepo))
                    OutlinedTextField(token, { token = it }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.testTag(AccountTags.PAT_FIELD))
                }
            },
            confirmButton = {
                TextButton(enabled = token.isNotBlank(), modifier = Modifier.testTag(AccountTags.PAT_GO), onClick = {
                    val t = token.trim(); token = ""; askPat = false
                    io { m.upload(t, device) }
                }) { Text(stringResource(R.string.account_upload)) }
            },
            dismissButton = { TextButton(onClick = { token = ""; askPat = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** #782 One roster row as a line: a fleet app (label · version · stage · auto-update) or the live tunnel. */
fun rosterLine(o: org.json.JSONObject?): String = when {
    o == null -> ""
    o.has("tunnel") -> "${o.optString("tunnel")} · ${o.optString("address")} · ${o.optInt("peers")} peers"
    else -> o.optString("label") + " · " +
        (if (o.optBoolean("installed")) "${o.optString("version_name")} (${o.optLong("version_code")})" else "not installed") +
        " · " + o.optString("stage") + (if (o.optBoolean("auto_update")) " · auto-update" else "")
}
