package com.diegonmarcos.cloudme

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.superapp.uiicons.UiIcons
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.KitTonalButton

object RepoTreeTags {
    const val LIST = "me:repotree:list"
    const val EXPAND_ALL = "me:repotree:expand_all"
    const val COLLAPSE_ALL = "me:repotree:collapse_all"
    const val FULL = "me:repotree:full"
    const val CLOSE = "me:repotree:close"
    const val EMPTY = "me:repotree:empty"
    fun row(code: String) = "me:repotree:row:$code"
}

/** Read once per process: the outline asset is ~40 KB and identical for every page that shows it. */
private object RepoOutlineAsset {
    @Volatile private var cached: List<RepoNode>? = null
    fun get(ctx: Context, asset: String): List<RepoNode> = cached ?: runCatching {
        RepoOutline.parse(CloudMeApiData.text(ctx, asset))
    }.getOrDefault(emptyList()).also { cached = it }
}

/**
 * Lnktree > Proj > <area>: [areaCode] non-null shows that registry area's projects; null shows every shown area
 * (the full outline the Proj label opens). Containers collapse and expand at every level, Expand all / Collapse all
 * act on what this screen shows, and the open set is persisted ([RepoTreeStore]) so the tree reopens as it was left.
 */
@Composable
fun RepoTreeScreen(areaCode: String?, modifier: Modifier = Modifier, config: RepoTreeConfig = RepoTreeConfig.baked) {
    val ctx = LocalContext.current
    val outline = remember(config.outlineAsset) { RepoOutlineAsset.get(ctx, config.outlineAsset) }
    val roots = remember(outline, areaCode) {
        if (areaCode == null) config.shown(outline) else config.area(outline, areaCode)?.children.orEmpty()
    }
    val store = remember { RepoTreeStore(ctx) }
    // #1323 the scroll position outlives a tab switch: the activity-scoped holder keeps it per area page.
    val listState = LocalLnktreeHolder.current?.list("repo-tree:${areaCode.orEmpty()}") ?: androidx.compose.foundation.lazy.rememberLazyListState()
    val model = remember(store) { RepoTreeModel(store.load()) }
    // The model is plain; this counter is what recomposes the list after a toggle.
    var version by remember { mutableStateOf(0) }
    val change: (RepoTreeModel.() -> Unit) -> Unit = { f -> model.f(); store.save(model.expanded); version++ }
    val rows = remember(roots, version) { model.rows(roots) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitTonalButton(onClick = { change { expandAll(roots) } }, modifier = Modifier.testTag(RepoTreeTags.EXPAND_ALL)) {
                Text(stringResource(R.string.repo_tree_expand_all))
            }
            KitTonalButton(onClick = { change { collapseAll(roots) } }, modifier = Modifier.testTag(RepoTreeTags.COLLAPSE_ALL)) {
                Text(stringResource(R.string.repo_tree_collapse_all))
            }
        }
        if (rows.isEmpty()) {
            Text(stringResource(R.string.repo_tree_empty), Modifier.padding(16.dp).testTag(RepoTreeTags.EMPTY))
            return@Column
        }
        // Bottom clearance: the shell already ends the page above the island; the extra pad keeps the last leaf
        // clear of the island's shadow and the gesture bar when the list is scrolled to the end.
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(RepoTreeTags.LIST), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
            items(rows) { row ->
                RepoTreeRow(row, onClick = {
                    val leaf = row.node.repo
                    if (leaf == null) change { toggle(row.node.code) } else openRepo(ctx, config.target(leaf))
                })
            }
        }
    }
}

@Composable
private fun RepoTreeRow(row: RepoRow, onClick: () -> Unit) {
    val n = row.node
    val leaf = n.repo
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(RepoTreeTags.row(n.code))
            .padding(start = (12 + row.depth * 18).dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
            if (n.isContainer) Text(if (row.open) "▾" else "▸", color = dim)
        }
        if (leaf != null) Icon(UiIcons.painter("code"), null, Modifier.size(18.dp), tint = dim)
        else if (n.icon.isNotBlank()) Text(n.icon, fontSize = 16.sp)
        else Icon(UiIcons.painter("account_tree"), null, Modifier.size(18.dp), tint = dim)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(n.label, color = ink, fontWeight = if (leaf == null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(n.code, n.description).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (leaf != null) Icon(
            UiIcons.painter(if (leaf.private) "lock" else "public"),
            stringResource(if (leaf.private) R.string.repo_tree_private else R.string.repo_tree_public),
            Modifier.size(16.dp), tint = dim,
        )
    }
}

/** The whole outline (every shown area), opened by tapping the Proj label. */
@Composable
fun RepoTreeFullDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag(RepoTreeTags.FULL)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.repo_tree_title), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                KitTextButton(onClick = onDismiss, modifier = Modifier.testTag(RepoTreeTags.CLOSE)) { Text(stringResource(R.string.repo_tree_close)) }
            }
            RepoTreeScreen(null, Modifier.weight(1f))
        }
    }
}

private fun openRepo(ctx: Context, target: RepoTarget) {
    val url = when (target) {
        is RepoTarget.App -> {
            val launch = ctx.packageManager.getLaunchIntentForPackage(target.pkg)
            if (launch != null) { ctx.startActivity(launch); return }
            target.fallbackUrl
        }
        is RepoTarget.Url -> target.url
    }
    if (url.isBlank()) return
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) { }
}
