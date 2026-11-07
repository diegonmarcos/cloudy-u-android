package com.diegonmarcos.cloudme

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * Buro > Wallet: the wallet records as what they actually are — folders and
 * files, browsed in place.
 *
 * The tree under data/files/ ships verbatim as assets, so this needs no
 * manifest: AssetManager.list() enumerates a directory, and a path it returns
 * nothing for is a file. That is the whole reason the source of truth is a
 * directory rather than one blob — Cloud Wallet flattens it into wallet.json
 * to draw its 3D cards, and this shows the same records unflattened.
 *
 * Ordering is alphabetical, which is the only order a directory can promise;
 * the block's optional `order` puts the folders a human names first (Pay, IDs,
 * Vcards, Events) ahead of it, and `labels` gives them their capitalisation.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The browsed path is saved state and Back
 * climbs the tree before it leaves the section.
 */
@Composable
fun FilesScreen(block: JSONObject, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val root = block.optString("root")
    val order = remember(block) {
        block.optJSONArray("order").let { arr -> (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) } }
    }
    val labels = remember(block) { parseLabels(block.optJSONObject("labels")?.toString().orEmpty()) }
    /** Path below [root]; "" is the root itself. */
    var path by rememberSaveable { mutableStateOf("") }

    // Registered after the shell's own handler, so it wins while there is somewhere to go up to —
    // Back inside a tree should climb it, not leave the section.
    BackHandler(enabled = path.isNotEmpty()) { path = path.substringBeforeLast('/', "") }

    fun label(name: String): String {
        val bare = name.substringBeforeLast('.')
        return labels[bare] ?: bare.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    Column(modifier.fillMaxSize()) {
        val crumb = (listOf(label(root.substringAfterLast('/'))) +
            path.split('/').filter { it.isNotEmpty() }.map { label(it) })
            .joinToString("  ›  ")
            .let { if (path.isEmpty()) it else "↑  $it" }
        Text(
            crumb, color = colorResource(R.color.me_text_dim), fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth()
                .clickable { if (path.isNotEmpty()) path = path.substringBeforeLast('/', "") }
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 2.dp),
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 28.dp),
        ) {
            val full = if (path.isEmpty()) root else "$root/$path"
            val entries = list(ctx, full, order)
            when {
                entries == null -> FileCard(ctx, full)
                entries.isEmpty() -> Card { Note("Empty folder", "Nothing is filed under this path yet.") }
                else -> entries.forEach { name ->
                    val child = if (path.isEmpty()) name else "$path/$name"
                    EntryRow(ctx, full, name, order, label(name)) { path = child }
                }
            }
        }
    }
}

/** Names inside [p], or null when [p] is a file. AssetManager returns an
 *  empty array for both an empty folder and a file, so a file is told
 *  apart by having a suffix — every record in the tree has one. */
private fun list(ctx: Context, p: String, order: List<String>): List<String>? {
    val names = runCatching { ctx.assets.list(p) }.getOrNull()?.toList() ?: emptyList()
    if (names.isEmpty() && p.substringAfterLast('/').contains('.')) return null
    return names.sortedWith(compareBy<String>({ rank(order, it) }, { it.lowercase() }))
}

private fun rank(order: List<String>, name: String): Int =
    order.indexOf(name.substringBeforeLast('.')).let { if (it < 0) order.size else it }

@Composable
private fun EntryRow(ctx: Context, parent: String, name: String, order: List<String>, label: String, onOpen: () -> Unit) {
    val isDir = list(ctx, "$parent/$name", order) != null
    // Records below the folder, counted so a row says how much is in it
    // rather than making you open it to find out.
    val count = if (isDir) countLeaves(ctx, "$parent/$name", order) else 0
    Card(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(if (isDir) R.drawable.ic_p_folder else R.drawable.ic_code), null,
                Modifier.padding(end = 12.dp).size(22.dp),
                colorFilter = ColorFilter.tint(colorResource(R.color.me_primary)),
            )
            Text(label, color = colorResource(R.color.me_text), fontSize = 15.sp,
                fontWeight = if (isDir) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
            Text(if (isDir) "$count" else "file", color = colorResource(R.color.me_text_dim), fontSize = 12.sp)
        }
    }
}

private fun countLeaves(ctx: Context, p: String, order: List<String>): Int {
    val names = list(ctx, p, order) ?: return 1
    return names.sumOf { countLeaves(ctx, "$p/$it", order) }
}

@Composable
private fun FileCard(ctx: Context, p: String) {
    val body = remember(p) {
        runCatching { CloudMeApiData.text(ctx, p) }
            .getOrElse { "Could not read this file." }
    }
    Card {
        Text(p.substringAfterLast('/'), color = colorResource(R.color.me_text), fontSize = 15.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        Text(body, color = colorResource(R.color.me_text_dim), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Note(title: String, body: String) {
    Text(title, color = colorResource(R.color.me_text), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Text(body, color = colorResource(R.color.me_text_dim), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Card(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).background(colorResource(R.color.me_surface))
            .border(1.dp, colorResource(R.color.me_outline), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) { content() }
}

private fun parseLabels(json: String): Map<String, String> = runCatching {
    val o = JSONObject(json)
    o.keys().asSequence().associateWith { o.optString(it) }
}.getOrDefault(emptyMap())
