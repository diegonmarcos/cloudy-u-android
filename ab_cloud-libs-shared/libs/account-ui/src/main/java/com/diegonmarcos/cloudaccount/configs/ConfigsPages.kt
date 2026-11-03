package com.diegonmarcos.cloudaccount.configs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.accountui.AccountUiHost
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitSegmented
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.updater.Fleet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * #1438 Configs (PUBLIC: no page here draws a secret value). Every page takes its data through a `load` parameter
 * whose default reads the real source (the baked fleet manifest, PackageManager, the owner's repos through
 * [RepoSource]); a test hands in fixtures.
 */
object ConfigsTags {
    const val CLOUD_APPS = "configs:cloud-apps"
    const val PHONE_APPS = "configs:phone-apps"
    const val RUNBOOK = "configs:runbook"
    const val DESKTOP = "configs:desktop"
    const val INFRA = "configs:infra"
    const val CONTAINERS = "configs:containers"
    const val REFRESH = "configs:refresh"
    fun app(pkg: String) = "configs:app:$pkg"
    fun toggle(id: String) = "configs:toggle:$id"
    fun node(path: String) = "configs:node:$path"
    fun infra(dir: String) = "configs:infra:$dir"
    fun container(dir: String) = "configs:container:$dir"
}

// ── models + the real loaders ────────────────────────────────────────

class AppRow(val pkg: String, val label: String, val version: String?, val note: String)
class DesktopTree(val label: String, val repo: String, val root: TreeNode?, val note: String)
class InfraRow(val dir: String, val name: String, val description: String, val category: String, val tf: TfSummary?)
class ContainerRow(val dir: String, val services: List<ServiceDecl>)

object ConfigsLoad {
    private fun version(ctx: Context, pkg: String): String? = runCatching { ctx.packageManager.getPackageInfo(pkg, 0).versionName ?: "" }.getOrNull()

    private fun fleet(): List<Fleet.App> = Fleet.parse(com.diegonmarcos.superapp.updater.BuildConfig.CONSTELLATION_FLEET_B64)

    /** The fleet manifest (libs:updater's baked CONSTELLATION_FLEET_B64), each with its installed version or null. */
    fun cloudApps(ctx: Context): List<AppRow> =
        fleet()
            .sortedBy { it.label.lowercase() }
            .map { a -> AppRow(a.pkg, a.label, version(ctx, a.pkg) ?: a.altId?.let { version(ctx, it) }, a.kind) }

    /** Every launchable app the fleet manifest does not name. */
    fun phoneApps(ctx: Context): List<AppRow> {
        val fleet = fleet().flatMap { listOfNotNull(it.pkg, it.altId) }.toSet()
        val pm = ctx.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        return launchable.filterKeys { it !in fleet && it != ctx.packageName }
            .map { (pkg, label) -> AppRow(pkg, label, version(ctx, pkg), "") }.sortedBy { it.label.lowercase() }
    }

    /** The gh engine's GitHub token, for a private repo only; null when none is held. */
    fun ghToken(ctx: Context): String? = runCatching { AccountUiHost.current.ghToken(ctx) }.getOrNull()

    fun desktop(src: ConfigsSources, token: () -> String?): List<DesktopTree> = src.desktop.map { d ->
        val repo = d.optString("repo"); val root = d.optString("root")
        val priv = d.optBoolean("private")
        val t = if (priv) token() else null
        val paths = if (priv && t == null) null else RepoSource.tree(src.owner, repo, d.optString("ref", "main"), t)
        val node = paths?.let { TreeNode.of(it, root, Regex(d.optString("match", ".*")), src.exclude) }
        DesktopTree(d.optString("label", root), repo, node, when {
            priv && t == null -> "private repo: sign in to GitHub on Connect to read it"
            paths == null -> "could not read $repo"
            node!!.fileCount() == 0 -> "nothing declared yet"
            else -> "${node.fileCount()} file(s)"
        })
    }

    fun infra(src: ConfigsSources): List<InfraRow>? {
        val i = src.infra; val repo = i.optString("repo"); val ref = i.optString("ref", "main")
        val paths = RepoSource.tree(src.owner, repo, ref) ?: return null
        val decl = i.optString("decl", "build.json"); val tfs = i.optJSONArray("tf").strings()
        return i.optJSONArray("roots").strings().flatMap { root ->
            paths.mapNotNull { p -> Regex("^${Regex.escape(root)}/([^/]+)/${Regex.escape(decl)}$").find(p)?.groupValues?.get(1) }
                .filter { d -> src.exclude.none { it.equals(d, true) } && !d.startsWith("_") }
                .map { d ->
                    val dir = "$root/$d"
                    val bj = RepoSource.file(src.owner, repo, ref, "$dir/$decl")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
                    val tf = tfs.map { "$dir/$it" }.firstOrNull { it in paths }?.let { RepoSource.file(src.owner, repo, ref, it) }?.let(TfDecl::parse)
                    InfraRow(dir, bj.optString("name", d), bj.optString("description"), bj.optString("category", root), tf)
                }
        }
    }

    fun containers(src: ConfigsSources): List<ContainerRow>? {
        val c = src.containers; val repo = c.optString("repo"); val ref = c.optString("ref", "main")
        val paths = RepoSource.tree(src.owner, repo, ref)?.toSet() ?: return null
        val exclude = c.optJSONArray("exclude").strings() + src.exclude
        val compose = c.optJSONArray("compose").strings()
        val dirs = paths.map { it.substringBefore('/') }.filter { d -> d !in exclude && !d.startsWith(".") }.distinct().sorted()
        return dirs.mapNotNull { d ->
            val f = compose.map { "$d/$it" }.firstOrNull { it in paths } ?: return@mapNotNull null
            val text = RepoSource.file(src.owner, repo, ref, f) ?: return@mapNotNull ContainerRow(d, emptyList())
            ContainerRow(d, ComposeDecl.parse(text))
        }
    }

    /** One adb-shell switch's state: its check's trimmed output equals on_when. Null = no channel answered. */
    fun toggleState(ctx: Context, t: ConfigsSources.Toggle): Boolean? =
        AccountUiHost.current.shell(ctx, t.check)?.trim()?.let { it == t.onWhen }

    fun setToggle(ctx: Context, t: ConfigsSources.Toggle, on: Boolean): Boolean =
        AccountUiHost.current.shell(ctx, if (on) t.on else t.off) != null
}

// ── pages ────────────────────────────────────────────────────────────

@Composable
private fun <T> loaded(key: Int, load: suspend () -> T?): Pair<Boolean, T?> {
    val v by produceState<Pair<Boolean, T?>>(false to null, key) { value = true to runCatching { load() }.getOrNull() }
    return v
}

@Composable
private fun AppsList(tag: String, title: String, rows: List<AppRow>?, done: Boolean, open: (AppRow) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { GroupTitle(title) }
        item {
            KitStatusBanner(when {
                !done -> "Reading…"
                rows == null -> "Could not read the list"
                else -> "${rows.size} app(s), ${rows.count { it.version != null }} installed"
            }, if (!done) KitState.BUSY else if (rows == null) KitState.BAD else KitState.OK, tag = "$tag:summary")
        }
        items(rows.orEmpty(), key = { it.pkg }) { r ->
            KitListRow(r.label, secondary = listOf(r.pkg, r.note).filter { it.isNotBlank() }.joinToString(" · "),
                pill = if (r.version != null) (r.version.ifBlank { "installed" }) to KitState.OK else "not installed" to KitState.IDLE,
                tag = ConfigsTags.app(r.pkg), onClick = { open(r) })
        }
    }
}

private fun appDetails(ctx: Context, pkg: String) = runCatching {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Configs > Android > Cloud Apps: the fleet manifest, installed or not. A row opens the app (or Cloud Store to get it). */
@Composable
fun CloudAppsPage(load: suspend (Context) -> List<AppRow>? = { withContext(Dispatchers.IO) { ConfigsLoad.cloudApps(it) } }) {
    val ctx = LocalContext.current
    val (done, rows) = loaded(0) { load(ctx) }
    AppsList(ConfigsTags.CLOUD_APPS, "Cloud Apps · fleet manifest", rows, done) { r ->
        val pm = ctx.packageManager
        val i = (if (r.version != null) pm.getLaunchIntentForPackage(r.pkg) else null)
            ?: pm.getLaunchIntentForPackage("com.diegonmarcos.cloudstore")
        if (i != null) runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } else appDetails(ctx, r.pkg)
    }
}

/** Configs > Android > Phone Apps: the launchable apps that are not the fleet's. A row opens Android's App info. */
@Composable
fun PhoneAppsPage(load: suspend (Context) -> List<AppRow>? = { withContext(Dispatchers.IO) { ConfigsLoad.phoneApps(it) } }) {
    val ctx = LocalContext.current
    val (done, rows) = loaded(0) { load(ctx) }
    AppsList(ConfigsTags.PHONE_APPS, "Phone Apps · not in the fleet", rows, done) { appDetails(ctx, it.pkg) }
}

/**
 * Configs > Android > Runbook: Switches = the privileged channel selector (ChannelModeBar, the one Cloud Store's ADB
 * Shell page draws) and the declared adb-shell switches, each run through the ShellChannels ladder (embedded adb, the
 * local server, Shizuku, the SuperApp bridge); Setup = libs:account's setup steps, as they were.
 */
@Composable
fun RunbookTogglesPage(
    go: (String, String) -> Unit,
    toggles: List<ConfigsSources.Toggle> = ConfigsSources.baked.toggles,
    state: suspend (Context, ConfigsSources.Toggle) -> Boolean? = { c, t -> withContext(Dispatchers.IO) { ConfigsLoad.toggleState(c, t) } },
    set: suspend (Context, ConfigsSources.Toggle, Boolean) -> Boolean = { c, t, on -> withContext(Dispatchers.IO) { ConfigsLoad.setToggle(c, t, on) } },
) {
    var tab by remember { mutableStateOf("switches") }
    Column(Modifier.fillMaxSize().testTag(ConfigsTags.RUNBOOK)) {
        KitSegmented(listOf("switches" to "Switches", "setup" to "Setup steps"), tab, { tab = it },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), tag = "configs:runbook:tab")
        if (tab == "setup") AccountUiHost.current.RunbookSetup(go) else RunbookSwitches(toggles, state, set)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.RunbookSwitches(
    toggles: List<ConfigsSources.Toggle>,
    state: suspend (Context, ConfigsSources.Toggle) -> Boolean?,
    set: suspend (Context, ConfigsSources.Toggle, Boolean) -> Boolean,
) {
    run {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var tick by remember { mutableIntStateOf(0) }
        val states = remember { mutableStateMapOf<String, Boolean?>() }
        var line by remember { mutableStateOf("") }
        LaunchedEffect(tick) { for (t in toggles) states[t.id] = runCatching { state(ctx, t) }.getOrNull() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupTitle("Privileged channel")
            AccountUiHost.current.ChannelBar { tick++ }
            GroupTitle("adb-shell switches")
            if (states.isNotEmpty() && states.values.all { it == null })
                KitStatusBanner("No shell channel answered: pair one above (or open the SuperApp)", KitState.WARN, tag = "configs:runbook:nochannel")
            if (line.isNotBlank()) KitStatusBanner(line, KitState.WARN, tag = "configs:runbook:line")
            for (t in toggles) {
                val on = states[t.id]
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        KitListRow(t.label, secondary = if (on == null) "state unknown" else if (on) "on" else "off", tag = "configs:toggle-row:${t.id}")
                    }
                    Switch(checked = on == true, enabled = on != null, onCheckedChange = { want ->
                        scope.launch {
                            line = if (set(ctx, t, want)) "" else "${t.label}: no shell channel ran it"
                            tick++
                        }
                    }, modifier = Modifier.testTag(ConfigsTags.toggle(t.id)))
                }
            }
        }
    }
}

/** Configs > Desktop: the tree of the owner's OS Terraforms and Nix flakes, one per declared source. */
@Composable
fun DesktopTreePage(load: suspend (Context) -> List<DesktopTree>? = { c ->
    withContext(Dispatchers.IO) { ConfigsLoad.desktop(ConfigsSources.baked) { ConfigsLoad.ghToken(c) } }
}) {
    val ctx = LocalContext.current
    var key by remember { mutableIntStateOf(0) }
    val (done, trees) = loaded(key) { load(ctx) }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag(ConfigsTags.DESKTOP), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { GroupTitle("Desktop · OS Terraform, Nix OS and Home Manager") }
        item { KitActionBar(listOf(KitAction("Refresh", ConfigsTags.REFRESH) { RepoSource.clear(); key++ })) }
        if (!done) item { KitStatusBanner("Reading the repos…", KitState.BUSY, tag = "configs:desktop:busy") }
        for (t in trees.orEmpty()) {
            val sk = "${t.repo}:${t.label}"
            item(key = "src:$sk") {
                KitListRow(t.label, secondary = "${t.repo} · ${t.note}", pill = (if ((t.root?.fileCount() ?: 0) > 0) "read" to KitState.OK else "empty" to KitState.IDLE),
                    tag = ConfigsTags.node("$sk:"))
            }
            val rows = t.root?.rows({ open["$sk:$it"] == true }).orEmpty()
            items(rows, key = { "$sk:${it.second.path}" }) { (depth, n) ->
                val k = "$sk:${n.path}"
                Text((if (n.isDir) (if (open[k] == true) "▾ " else "▸ ") else "  ") + n.name,
                    Modifier.fillMaxWidth().padding(start = (12 * (depth + 1)).dp, top = 4.dp, bottom = 4.dp).testTag(ConfigsTags.node(k))
                        .then(if (n.isDir) Modifier.clickable { open[k] = open[k] != true } else Modifier))
            }
        }
    }
}

/** Configs > C3 > Infra: cloudy-t-infra's declarations, read-only: name, category, description, providers, resources. */
@Composable
fun InfraPage(load: suspend () -> List<InfraRow>? = { withContext(Dispatchers.IO) { ConfigsLoad.infra(ConfigsSources.baked) } }) {
    var key by remember { mutableIntStateOf(0) }
    val (done, rows) = loaded(key) { load() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag(ConfigsTags.INFRA), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { GroupTitle("C3 · Infra (cloudy-t-infra, read-only)") }
        item { KitActionBar(listOf(KitAction("Refresh", ConfigsTags.REFRESH) { RepoSource.clear(); key++ })) }
        if (!done) item { KitStatusBanner("Reading cloudy-t-infra…", KitState.BUSY, tag = "configs:infra:busy") }
        else if (rows == null) item { KitStatusBanner("Could not read cloudy-t-infra", KitState.BAD, tag = "configs:infra:bad") }
        items(rows.orEmpty(), key = { it.dir }) { r ->
            val tf = r.tf
            KitListRow(r.name, secondary = listOfNotNull(
                r.description.takeIf { it.isNotBlank() },
                tf?.providers?.takeIf { it.isNotEmpty() }?.let { "providers: " + it.joinToString(", ") },
                tf?.resources?.takeIf { it.isNotEmpty() }?.let { "resources (${it.size}): " + it.take(8).joinToString(", ") + if (it.size > 8) ", …" else "" },
                tf?.variables?.takeIf { it.isNotEmpty() }?.let { "variables: " + it.joinToString(", ") },
                r.dir,
            ).joinToString("\n"), pill = r.category to KitState.IDLE, tag = ConfigsTags.infra(r.dir))
        }
    }
}

/** The lines a container row draws: names only (env, secrets, env files) and the volumes / mounts. */
fun containerLines(s: ServiceDecl): List<String> = listOfNotNull(
    s.image.takeIf { it.isNotBlank() }?.let { "image: $it" },
    s.envNames.takeIf { it.isNotEmpty() }?.let { "env: " + it.joinToString(", ") },
    s.secretNames.takeIf { it.isNotEmpty() }?.let { "secrets: " + it.joinToString(", ") },
    s.envFiles.takeIf { it.isNotEmpty() }?.let { "env files: " + it.joinToString(", ") },
    s.volumes.takeIf { it.isNotEmpty() }?.let { "volumes / mounts: " + it.joinToString(", ") },
)

/** Configs > C3 > Containers: what private data each cloudy-u-containers container gets, NAMES only, never a value. */
@Composable
fun ContainersPage(load: suspend () -> List<ContainerRow>? = { withContext(Dispatchers.IO) { ConfigsLoad.containers(ConfigsSources.baked) } }) {
    var key by remember { mutableIntStateOf(0) }
    val (done, rows) = loaded(key) { load() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag(ConfigsTags.CONTAINERS), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { GroupTitle("C3 · Containers (cloudy-u-containers): names, never values") }
        item { KitActionBar(listOf(KitAction("Refresh", ConfigsTags.REFRESH) { RepoSource.clear(); key++ })) }
        if (!done) item { KitStatusBanner("Reading cloudy-u-containers…", KitState.BUSY, tag = "configs:containers:busy") }
        else if (rows == null) item { KitStatusBanner("Could not read cloudy-u-containers", KitState.BAD, tag = "configs:containers:bad") }
        items(rows.orEmpty(), key = { it.dir }) { r ->
            KitListRow(r.dir, secondary = r.services.joinToString("\n") { s -> (listOf("▸ ${s.name}") + containerLines(s)).joinToString("\n") }
                .ifBlank { "no compose declared" },
                pill = "${r.services.sumOf { it.secretNames.size + it.envNames.size }} names" to KitState.IDLE, tag = ConfigsTags.container(r.dir))
        }
    }
}
