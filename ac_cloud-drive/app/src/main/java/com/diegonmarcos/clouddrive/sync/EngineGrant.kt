package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.superapp.perms.PermsLink
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import android.os.Environment
import com.diegonmarcos.superapp.perms.PermsPromptCard
import com.diegonmarcos.superapp.perms.PermsStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * #1358 THE GIT ENGINE'S ALL FILES ACCESS, asked up front.
 *
 * Two apps run git for this page. A repository inside the shared store (cld.termux's private home) runs in cld.termux
 * through CLOUD_EXEC and needs no all-files grant. A repository in SHARED STORAGE (/storage/emulated/0/..., the Obsidian
 * vaults) (#1408: now in-process, so [PACKAGE] is Cloud Drive itself) reads and writes shared storage only with
 * Cloud Drive's own MANAGE_EXTERNAL_STORAGE app-op (the separate engine uid is retired). Without it every verb answered "the git sync
 * engine has no All files access", which the row classified as "the git engine did not answer".
 *
 * The engine itself is the only reliable reader of its grant (AppOps refuses another uid's op without a privileged
 * permission), so [refresh] asks its `info` call ({files_access}) on IO and [state] holds the answer for the banner
 * ([EngineGrantBanner], the fleet's compact libs:perms prompt card, #1372), Configs > General > Perms (check
 * `engine_all_files`) and the row reason ([REASON]). Drive opens on the Git page, so the card is also the first-run ask. The pure parts ([fromInfo], [touchesSharedStorage]) are JVM-tested (EngineGrantTest).
 */
object EngineGrant {
    enum class Grant { UNKNOWN, NOT_INSTALLED, MISSING, GRANTED }

    /** The engine's package (libs:git-sync resolves it from the fleet manifest at build time). */
    /** #1408 git runs in-process, so the grant git needs is Cloud Drive's own. */
    val PACKAGE: String get() = com.diegonmarcos.clouddrive.BuildConfig.APPLICATION_ID

    /** The row reason when the grant is missing: RepoStatus.errorKind reads "all files access" as FILES_ACCESS. */
    const val REASON = "Cloud Drive has no All files access, so git cannot read or write shared storage"

    private val _state = MutableStateFlow(Grant.UNKNOWN)
    val state: StateFlow<Grant> = _state

    /** Pure: the grant from the engine's `info` answer (null = no answer). An answer without `files_access` (an old engine) says nothing. */
    fun fromInfo(info: JSONObject?, sdk: Int = Build.VERSION.SDK_INT): Grant = when {
        sdk < 30 -> Grant.GRANTED
        info == null || !info.optBoolean("ok", false) || !info.has("files_access") -> Grant.UNKNOWN
        info.optBoolean("files_access", false) -> Grant.GRANTED
        else -> Grant.MISSING
    }

    /** Pure: the engine's own rule (GitCalls.touchesSharedStorage) for the paths that need its grant. */
    fun touchesSharedStorage(path: String): Boolean =
        path.startsWith("/storage/") || path.startsWith("/sdcard") || path.startsWith("/mnt/")

    /** The row is in shared storage and the engine is known not to hold the grant. */
    fun blocks(path: String): Boolean = _state.value == Grant.MISSING && touchesSharedStorage(path)

    /** Blocking (binds the engine): call on IO. Updates [state] and returns it. */
    fun probe(@Suppress("UNUSED_PARAMETER") ctx: Context): Grant {
        val g = if (Build.VERSION.SDK_INT < 30) Grant.GRANTED
                else if (Environment.isExternalStorageManager()) Grant.GRANTED else Grant.MISSING
        _state.value = g
        return g
    }

    suspend fun refresh(ctx: Context): Grant = withContext(Dispatchers.IO) { probe(ctx.applicationContext) }

    /** The engine's launcher label in words (what Settings lists: "Cloud Git Sync Engine"), never its package or fleet id. */
    fun label(ctx: Context): String = com.diegonmarcos.superapp.perms.Perms.appLabel(ctx, PACKAGE)

    /** #1515 the grant is made in Configs > Perms (the all_files row), never from here: opens the page on that row. */
    fun open(ctx: Context): Boolean { PermsLink.open(ctx, "git", listOf("all_files")); return true }

    const val TAG_BANNER = "engine_grant_banner"
}

/** Re-asks the engine every time the host resumes (the owner comes back from Settings), and returns the live grant. */
@Composable
fun rememberEngineGrant(): EngineGrant.Grant {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { runCatching { EngineGrant.refresh(ctx) } } }
    val g by EngineGrant.state.collectAsState()
    return g
}

/** THE ONE PROMPT of the Git page: libs:perms' compact card (icon, one sentence, Grant, dismiss), only while the engine lacks the grant. */
@Composable
fun EngineGrantBanner() {
    val ctx = LocalContext.current
    if (rememberEngineGrant() != EngineGrant.Grant.MISSING) return
    val label = remember { EngineGrant.label(ctx) }
    PermsPromptCard(stringResource(R.string.engine_grant_title, label), onGrant = { EngineGrant.open(ctx) },
        modifier = Modifier.padding(horizontal = DriveMetrics.gutter, vertical = DriveMetrics.gap), icon = "folder", dismissKey = EngineGrant.TAG_BANNER,
        style = PermsStyle(ink = MaterialTheme.colorScheme.onSurface, dim = MaterialTheme.colorScheme.onSurfaceVariant, accent = MaterialTheme.colorScheme.primary),
        tag = EngineGrant.TAG_BANNER)
}
