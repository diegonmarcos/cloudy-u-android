package com.diegonmarcos.cgomniverse.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cgomniverse.Voxelverse
import com.diegonmarcos.cgomniverse.VoxelverseDecl
import com.diegonmarcos.cgomniverse.Decl
import com.diegonmarcos.cgomniverse.Pages
import com.diegonmarcos.cgomniverse.R
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test tags of the Voxelverse page. */
object VoxTags {
    const val PAGE = "voxelverse"
    const val PLAN = "voxelverse_plan"
    const val VIEWER = "voxelverse_viewer"
    const val STATUS = "voxelverse_status"
    fun sub(id: String): String = "voxelverse_sub:$id"
}

/** What the page shows: the plan document and the repo's status, as fetched (or the device's saved copy when offline). */
private class VoxState(val plan: String?, val commit: String?, val engine: Boolean?, val live: Boolean, val loading: Boolean)

/** The reader: https only, bounded, a saved copy per document under filesDir/voxelverse so the page works offline. */
private object VoxelverseRepo {
    private const val MAX_BYTES = 1024 * 1024
    private fun cache(ctx: Context, name: String) = File(File(ctx.filesDir, "voxelverse"), name)

    fun saved(ctx: Context, name: String): String? = cache(ctx, name).takeIf { it.isFile && it.length() > 0 }?.readText()

    /** The document at [url], saved as [name] on success; null on any failure (the saved copy is then the answer). */
    fun fetch(ctx: Context, url: String, name: String): String? = runCatching {
        if (!url.startsWith("https://")) return null
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            val text = String(bytes, Charsets.UTF_8)
            val f = cache(ctx, name); f.parentFile?.mkdirs()
            val tmp = File(f.path + ".part"); tmp.writeText(text); if (!tmp.renameTo(f)) tmp.delete()
            text
        } finally { c.disconnect() }
    }.getOrNull()
}

/**
 * #1784 the Voxelverse page: two subpages (data/voxelverse.json::subpages). Plan renders the vox-rust build plan read from its repo
 * (goals, architecture, milestones, risks, status); Viewer is the engine-coming page with the repo's latest commit and open
 * milestones until the repo publishes a build. Every word and address is the declaration's; the plan text is the repo's.
 */
@Composable
fun VoxelversePage() {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val decl = rememberDecl()
    val subs = listOf("plan" to stringResource(R.string.vox_plan), "viewer" to stringResource(R.string.vox_viewer))
    var sub by rememberSaveable { mutableStateOf("plan") }
    var tick by remember { mutableStateOf(0) }
    var st by remember { mutableStateOf(VoxState(VoxelverseRepo.saved(ctx, "plan.md"), VoxelverseRepo.saved(ctx, "commit.atom"), null, live = false, loading = true)) }
    LaunchedEffect(tick) {
        st = VoxState(st.plan, st.commit, st.engine, st.live, loading = true)
        st = withContext(Dispatchers.IO) {
            val plan = VoxelverseRepo.fetch(ctx, decl.planUrl, "plan.md")
            val commit = VoxelverseRepo.fetch(ctx, decl.commitUrl, "commit.atom")
            val marker = VoxelverseRepo.fetch(ctx, decl.markerUrl, "marker.txt")
            VoxState(plan ?: VoxelverseRepo.saved(ctx, "plan.md"), commit ?: VoxelverseRepo.saved(ctx, "commit.atom"),
                if (marker != null) true else if (commit != null) false else null, live = plan != null, loading = false)
        }
    }
    Column(Modifier.fillMaxSize().testTag(VoxTags.PAGE)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            subs.forEach { (id, label) ->
                val on = id == sub
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) p.textPrimary else p.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) p.surfaceSelected else p.surface)
                        .then(if (on) Modifier.border(1.dp, p.accent, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable { sub = id }.padding(horizontal = 16.dp, vertical = 8.dp).testTag(VoxTags.sub(id)))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sub == "viewer") ViewerSubpage(decl, st) else PlanSubpage(decl, st) { tick++ }
        }
    }
}

@Composable
private fun StatusCard(decl: VoxelverseDecl, st: VoxState, onRefresh: (() -> Unit)?) {
    val p = LocalKitPalette.current
    val c = st.commit?.let { Voxelverse.commit(it) }
    KitCard(Modifier.testTag(VoxTags.STATUS)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(decl.t("status"), style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
            Text(if (st.loading && st.plan == null) decl.t("loading") else if (st.live) decl.t("live") else if (st.plan != null) decl.t("cached") else decl.t("unavailable"),
                style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
            Text(if (c != null) decl.t("last_commit") + ": " + c.sha + " " + c.date + " " + c.subject else decl.t("no_commit"),
                style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
            if (st.engine != null) Text(if (st.engine) decl.t("engine_present") else decl.t("plan_only"), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
            Text(decl.t("source") + ": " + decl.repoName + "/" + decl.planPath, style = MaterialTheme.typography.labelSmall, color = p.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val ctx = LocalContext.current
                KitOutlinedButton(onClick = { Web.openInBrowser(ctx, decl.planWebUrl) }) { Text(decl.t("open_source")) }
                if (onRefresh != null) KitOutlinedButton(onClick = onRefresh) { Text(decl.t("refresh")) }
            }
        }
    }
}

@Composable
private fun PlanSubpage(decl: VoxelverseDecl, st: VoxState, onRefresh: () -> Unit) {
    val p = LocalKitPalette.current
    val md = st.plan
    Column(Modifier.fillMaxWidth().testTag(VoxTags.PLAN), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusCard(decl, st, onRefresh)
        if (md == null) return@Column
        Voxelverse.plan(decl, md).forEach { b ->
            GroupTitle(b.title)
            KitCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = p.textPrimary) }
                    b.bullets.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = p.textPrimary) }
                }
            }
        }
        Phases(decl, md)
    }
}

@Composable
private fun Phases(decl: VoxelverseDecl, md: String) {
    val p = LocalKitPalette.current
    val phases = Voxelverse.phases(decl, md)
    if (phases.isEmpty()) return
    GroupTitle(decl.roadmapTitle)
    phases.forEach { ph ->
        KitCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(ph.title, style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                if (ph.summary.isNotBlank()) Text(ph.summary, style = MaterialTheme.typography.bodySmall, color = p.textPrimary)
                if (ph.gate.isNotBlank()) Text(decl.t("gate") + ": " + ph.gate, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
            }
        }
    }
}

@Composable
private fun ViewerSubpage(decl: VoxelverseDecl, st: VoxState) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().testTag(VoxTags.VIEWER), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KitCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(decl.t("viewer_title"), style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                Text(decl.t("viewer_body"), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
            }
        }
        StatusCard(decl, st, null)
        st.plan?.let { Phases(decl, it) }
    }
}

/** The declaration of this page: the repo and plan path of build.json ui.stacks.voxelverse (resolved by Pages through the SuperApp's repo_base) and the string resources. */
@Composable
private fun rememberDecl(): VoxelverseDecl {
    val keys = mapOf("goals" to R.string.vox_goals, "architecture" to R.string.vox_architecture, "risks" to R.string.vox_risks, "milestones" to R.string.vox_milestones,
        "status" to R.string.vox_status, "source" to R.string.vox_source, "open_source" to R.string.vox_open_source, "refresh" to R.string.vox_refresh,
        "loading" to R.string.vox_loading, "live" to R.string.vox_live, "cached" to R.string.vox_cached, "unavailable" to R.string.vox_unavailable,
        "last_commit" to R.string.vox_last_commit, "no_commit" to R.string.vox_no_commit, "plan_only" to R.string.vox_plan_only,
        "engine_present" to R.string.vox_engine_present, "viewer_title" to R.string.vox_viewer_title, "viewer_body" to R.string.vox_viewer_body, "gate" to R.string.vox_gate)
    val text = keys.mapValues { stringResource(it.value) }
    return remember(text) {
        val e = Pages.entries(Decl.stacks, "voxelverse").first()
        Voxelverse.decl(Pages.resolve(e, Decl.targets).orEmpty(), e.path, text)
    }
}
