package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * #1679 REMOVE AUTO-CLONED COPIES. The old first-run seed (storage.seed, now OFF) cloned a declared set into the store's git folder
 * by itself. Those folders are NOT deleted silently (user data may be in them): this action lists the ones still present, reads
 * each for uncommitted changes and unpushed commits FIRST, and on ONE confirm deletes the clean ones through the page's own
 * folder Delete (runFolderAction, delete_local). A dirty or unpushed folder is listed with its reasons and kept.
 * The set of names is declared in build.json ui.sync.git.auto_cloned_cleanup.names (the seed's former manifest), never typed here.
 */
object AutoClonedCleanup {
    data class Decl(val label: String = "", val hint: String = "", val names: List<String> = emptyList(), val labels: Map<String, String> = emptyMap()) {
        fun label(key: String, default: String) = labels[key]?.takeIf { it.isNotBlank() } ?: default
    }

    /** ui.sync.git.auto_cloned_cleanup out of the baked ui.sync JSON; an absent or broken block is an empty declaration. */
    fun parse(syncJson: String): Decl = runCatching {
        val git = Json.parseToJsonElement(syncJson) as JsonObject
        val o = (git["git"] as? JsonObject ?: git)["auto_cloned_cleanup"] as? JsonObject ?: return Decl()
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        Decl(s("label"), s("hint"), (o["names"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { n -> n.isNotBlank() } }.orEmpty(),
            (o["labels"] as? JsonObject)?.entries?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty())
    }.getOrDefault(Decl())

    val decl: Decl by lazy { runCatching { parse(Declarations.decode(com.diegonmarcos.clouddrive.BuildConfig.UI_SYNC_B64)) }.getOrDefault(Decl()) }

    /** The registered clones that are auto-cloned copies: a declared name, directly in the store's git folder. Pure. */
    fun candidates(repos: List<ManagedRepo>, names: Collection<String>, gitRoot: String, canonical: (String) -> String = { it }): List<ManagedRepo> {
        val want = names.toSet()
        val root = File(gitRoot).absolutePath.trimEnd('/')
        return repos.filter { r ->
            val f = File(r.path)
            f.parentFile?.absolutePath?.trimEnd('/') == root && (f.name in want || canonical(f.name) in want)
        }.distinctBy { File(it.path).absolutePath }
    }

    /** Clean ones are removed, the rest kept with their reasons. Pure. */
    data class Plan(val remove: List<FolderActions.Target>, val keep: List<Pair<FolderActions.Target, List<String>>>)
    fun plan(targets: List<FolderActions.Target>, guards: Map<String, FolderActions.Guard>): Plan {
        val (dirty, clean) = targets.partition { t -> guards[t.path]?.strict ?: true }
        return Plan(clean, dirty.map { it to (guards[it.path]?.reasons() ?: listOf("not read")) })
    }
}

@Composable
internal fun AutoClonedCleanupDialog(repos: List<ManagedRepo>, coordinator: GitSyncCoordinator, onDone: () -> Unit) {
    val d = AutoClonedCleanup.decl
    val delete = Declarations.sync.git.folderActions.firstOrNull { it.kind == Declarations.FOLDER_DELETE }
    val targets = remember(repos) {
        AutoClonedCleanup.candidates(repos, d.names, com.diegonmarcos.clouddrive.SharedStore.gitRoot().absolutePath) { Declarations.repoSot.canonical(it) }
            .map { FolderActions.Target(it.path, it.remoteUrl, File(it.path).name) }
    }
    var guards by remember(targets) { mutableStateOf<Map<String, FolderActions.Guard>?>(null) }
    LaunchedEffect(targets) { guards = withContext(Dispatchers.IO) { targets.associate { it.path to runCatching { coordinator.deleteGuard(it.path) }.getOrElse { e -> FolderActions.Guard(emptyList(), null, e.message ?: "$e") } } } }
    val plan = guards?.let { AutoClonedCleanup.plan(targets, it) }
    AlertDialog(
        onDismissRequest = onDone,
        modifier = Modifier.testTag(DriveTags.SYNC_GIT_AUTO_CLONED),
        title = { Text(d.label.ifBlank { "Remove auto-cloned copies" }) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (d.hint.isNotBlank()) Text(d.hint, style = MaterialTheme.typography.bodySmall)
                when {
                    delete == null -> Text(d.label("no_delete", "No folder Delete action is declared."), color = MaterialTheme.colorScheme.error)
                    targets.isEmpty() -> Text(d.label("none", "No auto-cloned copy is left in the store."))
                    plan == null -> Text(d.label("reading", "Reading each folder for uncommitted changes and unpushed commits..."), style = MaterialTheme.typography.labelSmall)
                    else -> {
                        if (plan.remove.isNotEmpty()) Text(d.label("will_remove", "Removed (clean, nothing unpushed):"), style = MaterialTheme.typography.labelMedium)
                        plan.remove.forEach { Text(it.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
                        if (plan.keep.isNotEmpty()) Text(d.label("will_keep", "Kept (would lose work):"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        plan.keep.forEach { (t, why) ->
                            Text(t.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                            why.forEach { Text("  ! $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (delete != null && plan != null && plan.remove.isNotEmpty()) coordinator.runFolderAction(delete, plan.remove)
                onDone()
            }, enabled = delete != null && plan != null && plan.remove.isNotEmpty(), modifier = Modifier.testTag(DriveTags.SYNC_GIT_AUTO_CLONED_CONFIRM)) {
                Text(d.label("confirm", "Remove") + (plan?.let { " (${it.remove.size})" } ?: ""))
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(d.label("cancel", "Cancel")) } },
    )
}
