package com.diegonmarcos.superapp.profile

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.diegonmarcos.superapp.uikit.KitDates
import com.diegonmarcos.superapp.uikit.KitChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudlib.auth.SignIn
import com.diegonmarcos.cloudlib.auth.SignInHost
import com.diegonmarcos.cloudlib.auth.SignInResult
import com.diegonmarcos.cloudlib.auth.SignInWays
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitDeviceCard
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitFingerprint
import com.diegonmarcos.superapp.uikit.KitHero
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitSegmented
import com.diegonmarcos.superapp.uikit.KitStatTile
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatePill
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSelectableTile
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cloud Account redesign task 3: the pages of the new shell that draw existing lib data —
 * Account ▸ profile (spec 4.1), Account ▸ connect (4.2), Settings (4.11) and the placeholder
 * every later task replaces. Compose on libs:ui-kit; the host wraps them in CloudKitTheme.
 */
object AccountPageTags {
    const val PROFILE = "account:profile"
    const val CONNECT = "account:connect"
    const val SETTINGS = "account:settings"
    const val BACKUP = "profile:backup"
    const val FETCH = "connect:fetch"
    const val LINK_ORPHAN = "connect:link-orphan"
    fun way(kind: String) = "connect:way:$kind"
    fun placeholder(section: String, page: String) = "placeholder:$section/$page"
}

/**
 * Which device this phone is: Connections `device.id`, else the cockpit's pick, else DERIVED from
 * the model — `Build.MODEL` / `Build.DEVICE` against the fetched vault's `electronics.fleet` entries'
 * `model` and the cached devices-folder listing's `model` (refreshed by [DeviceVault.devices]). A
 * single match becomes the id and is persisted to Connections `device.id`; zero or several stay blank
 * (the candidates are reported so the picker / runbook can name them). Local data only: safe on main.
 */
object AccountDevice {
    const val SRC_CONNECTIONS = "connections"
    const val SRC_COCKPIT = "cockpit"
    const val SRC_DERIVED = "derived"
    const val SRC_NONE = "none"
    private const val K_DERIVED = "device.derived"
    const val K_LISTING = "account.devices.listing"

    data class Resolved(val id: String, val source: String, val model: String, val candidates: List<String>) {
        fun json(): JSONObject = JSONObject().put("id", id).put("source", source).put("model", model)
            .put("candidates", org.json.JSONArray(candidates))
    }

    fun id(ctx: Context): String = resolve(ctx).id

    /**
     * The legacy id this process renamed by a declared alias (`galaxy` → `galaxy-s21`), for the device
     * card's "was galaxy" note. Process memory only: the note lasts one session, the rename is for good.
     */
    @Volatile var renamedFrom: String? = null
        private set

    /**
     * App start: run the alias migration once, before any page (the card must not show the legacy id
     * until the profile step runs). First on the cached devices listing (local, safe on main), then —
     * on a background thread, when the listing has never been fetched — after refreshing it from the
     * forge, since [migrated] only renames against a known listing.
     */
    fun migrateAtStart(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { resolve(app) }
        if (listingIds(app).isEmpty()) kotlin.concurrent.thread(name = "account-device-alias") {
            runCatching { DeviceVault(app).devices(); resolve(app) }
        }
    }

    fun resolve(ctx: Context): Resolved {
        val v = AccountVault(ctx)
        val model = Build.MODEL.orEmpty()
        var conn = (v.connection("device.id") as? String)?.trim().orEmpty()
        migrated(conn, listingIds(ctx), aliases())?.let { to ->
            // A legacy id the vault no longer lists, renamed by a declared alias: rewrite it once, for good.
            if ((v.connection(K_DERIVED) as? String).orEmpty() == conn) v.putConnection(K_DERIVED, to)
            v.putConnection("device.id", to)
            renamedFrom = conn
            conn = to
        }
        if (conn.isNotBlank()) {
            val derived = (v.connection(K_DERIVED) as? String).orEmpty() == conn
            return Resolved(conn, if (derived) SRC_DERIVED else SRC_CONNECTIONS, model, emptyList())
        }
        val picked = VaultCockpit.selectedDevice(ctx).trim()
        val cockpit = migrated(picked, listingIds(ctx), aliases()) ?: picked
        if (cockpit.isNotBlank()) return Resolved(cockpit, SRC_COCKPIT, model, emptyList())
        val c = candidates(ctx)
        if (c.size == 1) {
            v.putConnection("device.id", c[0]); v.putConnection(K_DERIVED, c[0])
            return Resolved(c[0], SRC_DERIVED, model, c)
        }
        return Resolved("", SRC_NONE, model, c)
    }

    /** The legacy-id aliases the host declares (build.json `ui.account.device_aliases`, `{old: new}`), never written in Kotlin. */
    fun aliases(b64: String = com.diegonmarcos.superapp.account.BuildConfig.UI_ACCOUNT_B64): Map<String, String> {
        val o = runCatching { JSONObject(String(java.util.Base64.getDecoder().decode(b64)).ifBlank { "{}" }) }.getOrNull()
            ?.optJSONObject("device_aliases") ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotBlank() }
    }

    /** The device ids in the cached devices-folder listing (empty until [DeviceVault.devices] has run). */
    fun listingIds(ctx: Context): Set<String> {
        val a = ConfigsPrefs(ctx).text(K_LISTING).takeIf { it.isNotBlank() }
            ?.let { runCatching { org.json.JSONArray(it) }.getOrNull() } ?: return emptySet()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }.toSet()
    }

    /**
     * Pure: the id [conn] migrates to, or null. Only when the listing is known, no longer holds [conn],
     * and a declared alias maps [conn] to an id the listing does hold.
     */
    fun migrated(conn: String, listing: Set<String>, aliases: Map<String, String>): String? {
        if (conn.isBlank() || listing.isEmpty() || conn in listing) return null
        return aliases[conn]?.takeIf { it in listing }
    }

    /** The ids whose declared model is this phone's (`Build.MODEL` or `Build.DEVICE`). */
    fun candidates(ctx: Context): List<String> {
        val bundle = AccountModel.get(ctx).server()?.body
        val listing = ConfigsPrefs(ctx).text(K_LISTING).takeIf { it.isNotBlank() }
            ?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
        val al = aliases(); val ids = listingIds(ctx)
        return matches(bundle, listing, listOf(Build.MODEL.orEmpty(), Build.DEVICE.orEmpty()))
            .map { migrated(it, ids, al) ?: it }.distinct()
    }

    /** Pure: `electronics.fleet.<id>.model` and listing rows' `model` matched (case-insensitive) to [models]. */
    fun matches(bundle: JSONObject?, listing: org.json.JSONArray?, models: List<String>): List<String> {
        val want = models.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        if (want.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        val fleet = bundle?.optJSONObject("electronics")?.optJSONObject("fleet")
        fleet?.keys()?.forEach { id ->
            val m = (fleet.optJSONObject(id)?.opt("model") as? String)?.trim()?.lowercase()
            if (m != null && m in want) out += id
        }
        if (listing != null) for (i in 0 until listing.length()) {
            val row = listing.optJSONObject(i) ?: continue
            val id = row.optString("id")
            val m = (row.opt("model") as? String)?.trim()?.lowercase()
            if (id.isNotBlank() && id != DeviceProfile.DEFAULT_ID && m != null && m in want) out += id
        }
        return out.toList()
    }

    fun setId(ctx: Context, id: String) {
        AccountVault(ctx).putConnection("device.id", id.trim())
        AccountVault(ctx).putConnection(K_DERIVED, "")
        VaultCockpit.selectDevice(ctx, id.trim())
    }

    /** The devices the landed vault declares (electronics), by id. */
    fun declared(ctx: Context): List<VaultCockpit.Device> =
        AccountModel.get(ctx).server()?.body?.let { runCatching { VaultCockpit.devices(it) }.getOrNull() }.orEmpty()
}

private fun jsonAt(ctx: Context, path: String): JSONObject? =
    (AccountVault(ctx).connection(path) as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }

/** A page a later task of the redesign fills; it says which, so the app is navigable now. */
@Composable
fun AccountPlaceholderPage(section: String, page: String, task: String, body: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        .testTag(AccountPageTags.placeholder(section, page)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KitEmptyState("$section ▸ $page", "filled by $task of the Cloud Account redesign")
        body?.invoke()
    }
}

// ── Account ▸ profile ────────────────────────────────────────────────────

/** `| Product Engineering 💾 | Venture C… |` → its titles, trimmed, blanks dropped (the stored string is unchanged). */
fun titleChips(value: String): List<String> = value.split('|').map { it.trim() }.filter { it.isNotEmpty() }

/** A titles field (`profile › titles`, `titles_v2`): its last path segment, version suffix dropped. */
private fun isTitles(path: String): Boolean =
    path.split(InfoMask.SEP.trim(), ".", "/").lastOrNull()?.trim()?.replace(Regex("""_v\d+$"""), "") == "titles"

/** The trailing value of a schema row: a fingerprint for a masked one, "Add" for an unfilled one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutValue(row: InfoMask.Row, full: String, shown: JSONObject?, editable: Boolean) {
    val p = LocalKitPalette.current
    when (row.kind) {
        InfoMask.Kind.MASKED -> KitFingerprint(
            AccountDrift.leaves(shown)[full]?.let(AccountDrift::text)?.let(::fingerprint), length = row.size, tag = full)
        InfoMask.Kind.EMPTY -> Text(if (editable) "Add" else "—", color = if (editable) p.accent else p.textSecondary,
            style = MaterialTheme.typography.bodyMedium)
        InfoMask.Kind.COLLAPSED -> Text("${row.size} entries ›", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
        InfoMask.Kind.PENDING -> KitStatePill("pending", KitState.WARN)
        InfoMask.Kind.SHOWN -> if (isTitles(full) && titleChips(row.text).isNotEmpty()) FlowRow(
            Modifier.widthIn(max = 220.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((i, t) in titleChips(row.text).withIndex()) KitChip(t, onRemove = null, tag = "$full:$i")
        } else Text(shownValue(full, row.text) + if (editable) "  ›" else "", color = p.textSecondary,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountProfilePage(open: (section: String, page: String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember { AccountModel.get(ctx) }
    model.version.intValue
    var tick by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val shown = model.shown()
    val about = InfoMask.schema.firstOrNull { it.id == "about" }
    val resolved = remember(tick) { AccountDevice.resolve(ctx) }
    val deviceId = resolved.id
    // This phone's CURRENT device file (DeviceVault.current: the slot, refreshed from the forge when stale), off main.
    var working by remember { mutableStateOf(DeviceVault(ctx).working()?.takeIf { it.optString("device") == deviceId }) }
    var drift by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick, deviceId, model.version.intValue) {
        working = withContext(Dispatchers.IO) { runCatching { DeviceVault(ctx).current(deviceId) }.getOrNull() } ?: working
        // The diff op's number: the same count Profiles ▸ diff draws, over the refreshed slot.
        drift = withContext(Dispatchers.IO) { runCatching { profilesDriftCount(ctx, model) }.getOrDefault(0) }
    }
    val rows = if (about == null) emptyList() else InfoMask.declared.schemaRows(about, shown?.opt(about.id))
    fun shownAt(vararg path: String): String {
        val want = path.fold("") { acc, s -> InfoMask.join(acc, s) }
        return rows.firstOrNull { it.path == want && it.kind == InfoMask.Kind.SHOWN }?.text.orEmpty()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(AccountPageTags.PROFILE),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val name = shownAt("profile", "name")
        KitHero(
            name = name.ifBlank { "Not connected yet" },
            lines = listOf(
                shownAt("profile", "email").ifBlank { if (shown == null) "fetch the vault on Account ▸ connect" else "" },
                listOf(shownAt("profile", "location"), shownAt("profile", "company")).filter { it.isNotBlank() }.joinToString(" · "),
            ),
            initials = shownAt("profile", "initials").ifBlank { initialsOf(name) },
            tag = "profile",
            onClick = { if (shown == null) open("account", "connect") },
        )

        val titles = titleChips(shownAt("profile", "titles"))
        if (titles.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, t) in titles.withIndex()) KitChip(t, onRemove = null, tag = "profile:title:$i")
        }

        KitSectionHeader("This phone", "", eyebrow = true)
        val fetched = remember(tick, model.version.intValue) { ConnectWays.lastFetch(ctx) }
        val backup = remember(tick) { jsonAt(ctx, "backup.last") }
        val restore = remember(tick) { jsonAt(ctx, "restore.last") }
        val prof = working?.optJSONObject("profile")
        val loaded = working?.optString("device")?.ifBlank { null }
        val derivedHint = if (resolved.source == AccountDevice.SRC_DERIVED) "derived from model ${resolved.model}" else ""
        val aliasNote = AccountDevice.renamedFrom?.takeIf { deviceId.isNotBlank() && it != deviceId }?.let { "was $it" }.orEmpty()
        KitDeviceCard(
            id = deviceId.ifBlank { "Pick this phone" },
            model = "${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
            state = listOf(
                loaded?.let { "profile $it loaded" } ?: "no profile loaded",
                backup?.optString("at")?.ifBlank { null }?.let { "backed up ${KitDates.relative(it)}" } ?: "never backed up",
                aliasNote,
                derivedHint,
            ).filter { it.isNotBlank() }.joinToString(" · "),
            badge = if (deviceId.isBlank()) "" else "this phone",
            pill = if (deviceId.isBlank()) "not picked" to KitState.WARN else null,
            onClick = { picking = true },
        )
        val counts = remember(prof) { prof?.let { DeviceProfile.counts(it) } }
        val perms = remember(prof) { AccountDrift.leaves(prof?.optJSONObject("perms")).size }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitStatTile("${counts?.first ?: 0}", "apps in the profile", if (counts == null) KitState.IDLE else KitState.OK,
                Modifier.weight(1f), tag = "apps") { open("setup", "apps") }
            KitStatTile("${counts?.second ?: 0}", if (drift == 0) "settings in sync" else "settings captured",
                if (counts == null) KitState.IDLE else if (drift == 0) KitState.OK else KitState.WARN,
                Modifier.weight(1f), tag = "settings") { open("profiles", "working") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitStatTile("$drift", if (drift == 0) "keys differ · in sync" else "keys differ", if (drift == 0) KitState.OK else KitState.WARN,
                Modifier.weight(1f), tag = "drift") { open("profiles", "diff") }
            KitStatTile("$perms", "permissions in the profile", if (perms == 0) KitState.IDLE else KitState.OK,
                Modifier.weight(1f), tag = "perms") { open("setup", "perms") }
        }
        Text(listOf(
            "vault fetched " + (fetched?.optString("at")?.ifBlank { null }?.let { KitDates.relative(it) } ?: "never"),
            "last restore " + (restore?.let { "${it.optString("device")} · ${it.optString("sha").take(7)}" } ?: "never"),
        ).joinToString(" · "), color = LocalKitPalette.current.textSecondary, style = MaterialTheme.typography.bodySmall)
        KitActionBar(listOf(
            KitAction(if (busy) "Backing up…" else "Backup now", AccountPageTags.BACKUP, !busy) {
                busy = true; result = "…"
                scope.launch {
                    result = withContext(Dispatchers.IO) { DeviceVault(ctx).backup(AccountDevice.id(ctx), false).optString("result") }
                    busy = false; tick++
                }
            },
            KitAction("Restore", "profile:restore") { open("setup", "runbook") },
            KitAction("Migrate this phone", "profile:migrate") { open("setup", "runbook") },
        ))
        SaidBanner(result, "profile:result")

        KitSectionHeader(about?.label ?: "About", "", eyebrow = true)
        KitCard {
            if (about == null) KitSettingsRow("No about topic is declared")
            else if (rows.isEmpty() || shown == null) KitSettingsRow("Nothing fetched yet", "Account ▸ connect", onClick = { open("account", "connect") })
            for (row in rows) {
                if (about == null || shown == null) break
                val full = if (row.path.isBlank()) about.id else about.id + AccountDrift.SEP + row.path
                val editable = (row.kind == InfoMask.Kind.SHOWN || row.kind == InfoMask.Kind.EMPTY) && '[' !in row.path
                KitListRow(fieldLabel(row.path.ifBlank { about.id }), tag = full,
                    secondary = if (row.kind == InfoMask.Kind.PENDING) row.text else "",
                    onClick = if (editable) ({ editing = full }) else null,
                    onLongClick = { copyPath(ctx, full) }) {
                    AboutValue(row, full, shown, editable)
                }
            }
        }
    }

    if (picking) {
        val devices = remember { AccountDevice.declared(ctx) }
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Which device is this phone?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (devices.isEmpty()) Text("The vault declares no devices yet: fetch it on Account ▸ connect.",
                        color = LocalKitPalette.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                    for (d in devices) KitSelectableTile(d.label.ifBlank { d.id },
                        when {
                            d.id == deviceId && resolved.source == AccountDevice.SRC_DERIVED -> "${d.id} · derived from model ${resolved.model}"
                            d.id in resolved.candidates -> "${d.id} · model ${resolved.model} matches"
                            else -> d.id
                        }, d.id == deviceId, {
                        AccountDevice.setId(ctx, d.id); picking = false; tick++
                    })
                    OutlinedTextField(typed, { typed = it }, label = { Text("new device…") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = typed.isNotBlank() && typed.trim() != DeviceProfile.DEFAULT_ID,
                    onClick = { AccountDevice.setId(ctx, typed); picking = false; tick++ }) { Text("Use new id") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }
    editing?.let { path ->
        var value by remember(path) { mutableStateOf(AccountDrift.leaves(shown)[path]?.let(AccountDrift::text).orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(fieldLabel(path)) },
            text = { OutlinedTextField(value, { value = it }) },
            confirmButton = {
                TextButton(enabled = !InfoMask.declared.hides(path, value), onClick = {
                    model.edit(path, value); result = model.save(); editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

// ── Account ▸ connect ────────────────────────────────────────────────────

/** The primary forge as a segmented pick, its repo and branch under it. Shared by connect and Settings. */
@Composable
private fun OriginPicker(tick: Int, changed: () -> Unit) {
    val ctx = LocalContext.current
    val decl = remember { ConnectWays.decl() }
    val primary = remember(tick) { DeviceVault(ctx).primary()?.id }
    KitSegmented(decl.forges.map { it.id to it.id.replaceFirstChar { c -> c.uppercase() } }, primary, { id ->
        if (decl.forge(id)?.repo != null) { AccountVault(ctx).putConnection("forge.primary", id); changed() }
    }, tag = "origin", enabled = { decl.forge(it)?.repo != null })
    val f = decl.forge(primary)
    val p = LocalKitPalette.current
    Text(f?.repo ?: "no vault repo declared on ${primary ?: "any forge"} yet", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
    Text("branch ${decl.branch}", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountConnectPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(Said(KitState.IDLE, "")) }
    var tick by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val ways = remember { ConnectWays.ways() }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val p = LocalKitPalette.current

    fun launchWay(block: () -> ConnectWays.Outcome) {
        status = Said(KitState.BUSY, "working")
        scope.launch {
            val o = withContext(Dispatchers.IO) { runCatching(block).getOrElse { ConnectWays.Outcome(false, it.javaClass.simpleName) } }
            status = Said(if (o.ok) KitState.OK else KitState.BAD, said(o.line).text); tick++
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        launchWay {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            if (text.isNullOrEmpty()) ConnectWays.Outcome(false, "could not read ${uri.lastPathSegment.orEmpty()}") else ConnectWays.importFile(ctx, text)
        }
    }
    val signInHost = remember {
        object : SignInHost {
            // #1693 the session the portal login earned is Cloud Account's: held short-lived in memory, served over the `session` method
            override fun onWebSession(cookie: String) { SessionLease.remember(cookie) }
            override fun onSignedIn(result: SignInResult) { status = said(ConnectWays.signedIn(ctx, result).line); tick++ }
        }
    }
    // #1693 only Cloud Account starts a sign-in: in any other host every way below is the same tile, opening Cloud
    // Account at that provider's page, its line the state Cloud Account reports over the accountdata provider.
    val owner = remember { SignInOwner.isOwner(ctx) }
    val linked = remember { mutableStateMapOf<String, com.diegonmarcos.superapp.fleetendpoints.SignInContract.Status>() }
    LaunchedEffect(owner, tick) {
        if (!owner) linked.putAll(withContext(Dispatchers.IO) {
            ways.map { SignInOwner.providerOf(it) }.distinct().associateWith { SignInOwner.status(ctx, it) }
        })
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(AccountPageTags.CONNECT),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val last = remember(tick) { ConnectWays.lastFetch(ctx) }
        val primary = remember(tick) { DeviceVault(ctx).primary()?.id }
        when {
            last == null -> KitStatusBanner("Not connected", KitState.WARN, tag = "connect",
                action = KitAction("Fetch now", "connect:banner:fetch") { launchWay { ConnectWays.fetchNow(ctx) } })
            last.optBoolean("ok", false) -> KitStatusBanner(
                "Connected · ${primary?.replaceFirstChar { it.uppercase() } ?: "vault"} · fetched ${KitDates.relative(last.optString("at"))}", KitState.OK, tag = "connect")
            else -> KitStatusBanner("Last fetch failed · ${said(last.optString("line")).text}", KitState.BAD, tag = "connect",
                action = KitAction("Retry", "connect:banner:fetch") { launchWay { ConnectWays.fetchNow(ctx) } })
        }
        if (status.text.isNotBlank()) KitStatusBanner(status.text, status.state, tag = "connect:status")
        // A bearer filed before the address pairing existed is unusable until it has one: say so, and link it on a tap.
        val orphan = remember(tick) { ConnectWays.orphanAddress(ctx) }
        if (orphan != null) KitStatusBanner("A stored sign-in token has no account address", KitState.WARN, tag = "connect:orphan",
            action = KitAction("Link it to $orphan", AccountPageTags.LINK_ORPHAN) { status = said(ConnectWays.linkOrphan(ctx, orphan).line); tick++ })

        for ((forge, group) in ways.groupBy { it.forge }) {
            val readOnly = group.all { ConnectWays.handler(it.kind) == ConnectWays.Handler.Ssh }
            KitCard {
                Text(forge?.replaceFirstChar { it.uppercase() } ?: "File", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text(if (forge == null) "offline" else if (readOnly) "read only" else "read and write",
                    color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (way in group) {
                        val tile = Modifier.widthIn(max = 220.dp).testTag(AccountPageTags.way(way.kind))
                        if (!owner) {
                            val provider = SignInOwner.providerOf(way)
                            KitSelectableTile(way.label, linked[provider]?.line ?: "opens Cloud Account to sign in", linked[provider]?.signedIn == true, {
                                SignInOwner.open(ctx, provider)?.let { status = Said(KitState.BAD, it) }
                            }, tile)
                        } else when (val h = ConnectWays.handler(way.kind)) {
                            ConnectWays.Handler.GhLogin -> KitSelectableTile(way.label, way.note, false, {
                                launchWay { ConnectWays.ghLogin(ctx) { code, page -> main.post { status = said(ConnectWays.ghPrompt(ctx, code, page)) } } }
                            }, tile)
                            ConnectWays.Handler.Pat, ConnectWays.Handler.Ssh, ConnectWays.Handler.GiteaToken ->
                                KitSelectableTile(way.label, way.note, dialog == way.kind, { dialog = if (dialog == way.kind) null else way.kind }, tile)
                            ConnectWays.Handler.AutheliaWeb -> {
                                val ids = remember { SignIn.offered(emptyList()).filter { it.kind == SignIn.Kind.AUTHELIA_WEB }.map { it.id } }
                                if (ids.isEmpty()) KitSelectableTile(way.label, "not offered by this build's sign-in declaration", false, {}, tile)
                                else SignInWays(host = signInHost, policy = ids, pill = { _, tag, onClick ->
                                    KitSelectableTile(way.label, way.note, false, onClick, Modifier.widthIn(max = 220.dp).testTag(tag))
                                })
                            }
                            ConnectWays.Handler.File -> KitSelectableTile(way.label, way.note, false, {
                                filePicker.launch(arrayOf("application/json", "text/*", "*/*"))
                            }, tile)
                            is ConnectWays.Handler.Unwired -> KitSelectableTile(way.label, h.why, false, {}, tile)
                        }
                    }
                }
                val open = dialog
                if (open != null && group.any { it.kind == open }) WayForm(open, ways.firstOrNull { it.kind == open }?.label ?: open,
                    onCancel = { dialog = null }) { s, pw ->
                    dialog = null
                    launchWay {
                        when (ConnectWays.handler(open)) {
                            ConnectWays.Handler.Pat -> ConnectWays.pat(ctx, s)
                            ConnectWays.Handler.Ssh -> ConnectWays.ssh(ctx, s, pw)
                            ConnectWays.Handler.GiteaToken -> ConnectWays.giteaToken(ctx, s)
                            else -> ConnectWays.Outcome(false, "$open takes no secret")
                        }
                    }
                }
            }
        }
        // #1693 the credentials other apps used to ask for themselves are entered here (owner only)
        AppCredentialsPanel()
        KitSectionHeader("Origin", "", eyebrow = true)
        OriginPicker(tick) { tick++ }
        KitActionBar(listOf(KitAction("Fetch now", AccountPageTags.FETCH) { launchWay { ConnectWays.fetchNow(ctx) } }))
        Text(last?.let { "last fetch ${KitDates.relative(it.optString("at"))}: ${said(it.optString("line")).text}" } ?: "no fetch yet",
            color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

/** The selected way's form, inside its forge card: a token, or a key + passphrase for SSH. */
@Composable
private fun WayForm(kind: String, label: String, onCancel: () -> Unit, submit: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    var secret by remember(kind) { mutableStateOf("") }
    var pass by remember(kind) { mutableStateOf("") }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        secret = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            ?.let(ConnectWays::extractSshKey).orEmpty()
    }
    val ssh = kind == ConnectWays.GITHUB_SSH
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
        Text(if (ssh) "Read only: a shallow bare clone, the one file read, then deleted. Backups need a token."
             else "Filed in this phone's encrypted vault once it works; never shown back.",
            color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        // A credential box is kept out of the keyboard's learned words and corrections (libs:auth's bearer box does the same).
        val keep = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
        OutlinedTextField(secret, { secret = it }, label = { Text(if (ssh) "private key" else "token") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = keep, singleLine = !ssh, modifier = Modifier.fillMaxWidth())
        if (ssh) {
            TextButton(onClick = { keyPicker.launch("*/*") }) { Text("Import key from file…") }
            OutlinedTextField(pass, { pass = it }, label = { Text("passphrase (blank if none)") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = keep, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        KitActionBar(listOf(
            KitAction(if (ssh) "Clone & read" else "Read", "connect:form:go:$kind") {
                val s = secret; val pw = pass
                secret = ""; pass = ""
                submit(s, pw)
            },
            KitAction("Cancel", "connect:form:cancel:$kind") { secret = ""; pass = ""; onCancel() },
        ))
    }
}

// ── Settings ─────────────────────────────────────────────────────────────

/** Connections `backup.auto`: off | daily | wifi. */
object AutoBackup {
    const val OFF = "off"
    const val DAILY = "daily"
    const val WIFI = "wifi"
    fun mode(ctx: Context): String = (AccountVault(ctx).connection("backup.auto") as? String)?.ifBlank { null } ?: OFF
}

/** Connections `debug.api`: "off" keeps the loopback debug server stopped. */
object DebugApiSwitch {
    fun enabled(ctx: Context): Boolean = (AccountVault(ctx).connection("debug.api") as? String) != "off"
    fun apply(ctx: Context) { if (enabled(ctx)) AppDebugServer.start(ctx) else AppDebugServer.stop() }
}

@Composable
fun AccountSettingsPage(version: String) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf(AccountDevice.id(ctx)) }
    var token by remember { mutableStateOf("") }
    var line by remember { mutableStateOf(Said(KitState.IDLE, "")) }
    val vault = remember { AccountVault(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(AccountPageTags.SETTINGS),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (line.text.isNotBlank()) KitStatusBanner(line.text, line.state, tag = "settings")
        KitSectionHeader("Device", "", eyebrow = true)
        KitCard {
            KitSettingsRow("Device id", remember(tick) { AccountDevice.id(ctx) }.ifBlank { "not picked" } + " · overrides the pick on Account ▸ profile")
            OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("device id") })
            KitActionBar(listOf(KitAction("Save device id", "settings:device") {
                if (typed.isNotBlank() && typed.trim() != DeviceProfile.DEFAULT_ID) { AccountDevice.setId(ctx, typed); line = Said(KitState.OK, "device id = ${typed.trim()}"); tick++ }
                else line = Said(KitState.BAD, "a device id, not blank and not DEFAULT")
            }))
        }
        KitSectionHeader("Vault", "", eyebrow = true)
        KitCard {
            KitSettingsRow("Primary forge", "where Fetch now reads and Backup writes")
            OriginPicker(tick) { tick++ }
        }
        KitSectionHeader("Backup", "", eyebrow = true)
        KitCard {
            KitSettingsRow("Auto-backup", "capture, compare, commit only when something changed; Wi-Fi = daily, unmetered only")
            val mode = remember(tick) { AutoBackup.mode(ctx) }
            KitSegmented(listOf(AutoBackup.OFF to "Off", AutoBackup.DAILY to "Daily", AutoBackup.WIFI to "Wi-Fi"), mode, { id ->
                vault.putConnection("backup.auto", id); AccountHost.autoBackupChanged(ctx); tick++
            }, tag = "autobackup")
        }
        KitSectionHeader("Fleet token", "", eyebrow = true)
        KitCard {
            val held = remember(tick) { DeviceVault(ctx).credentials() }
            for (f in held.keys().asSequence().toList()) {
                val has = held.optBoolean(f)
                KitListRow(f.replaceFirstChar { it.uppercase() }, secondary = "filed encrypted; never shown back", tag = "token:$f",
                    pill = if (has) "held" to KitState.OK else "none" to KitState.IDLE)
            }
            OutlinedTextField(token, { token = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), label = { Text("paste a token") })
            KitActionBar(listOf(KitAction("File token for the primary forge", "settings:token") {
                val f = DeviceVault(ctx).primary()
                line = when {
                    f == null -> Said(KitState.BAD, "no usable forge declared")
                    token.isBlank() -> Said(KitState.BAD, "paste a token first")
                    else -> { vault.putConnection("forge.${f.id}.token", token.trim()); Said(KitState.OK, "token filed for ${f.id}") }
                }
                token = ""; tick++
            }))
        }
        KitSectionHeader("Debug", "", eyebrow = true)
        KitCard {
            val on = remember(tick) { DebugApiSwitch.enabled(ctx) }
            KitSwitchRow("Debug API", if (AppDebugServer.isRunning()) "the loopback /api/account/… routes · listening" else "the loopback /api/account/… routes · stopped", on, { v ->
                vault.putConnection("debug.api", if (v) "on" else "off"); DebugApiSwitch.apply(ctx); tick++
            })
        }
        KitSectionHeader("About", "", eyebrow = true)
        KitCard {
            KitSettingsRow("Cloud Account", version)
            KitSettingsRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}")
        }
    }
}
