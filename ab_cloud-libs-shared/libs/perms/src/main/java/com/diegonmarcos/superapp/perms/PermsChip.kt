package com.diegonmarcos.superapp.perms

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight

/**
 * #1515 the only thing a page outside Configs > Perms shows about a missing grant: one compact chip, "Needs permission -
 * Fix in Perms >", that deep-links to the exact row(s) ([PermsLink.open]). No dialog, no settings intent and no shell
 * line lives outside libs:perms and the Perms page (perms-guard P7). [rememberPermsMissing] is the live check, refreshed
 * every time the screen resumes (the user comes back from the Perms page or from Settings).
 */
object PermsChip {
    const val TEXT = "Needs permission — Fix in Perms ›"
    const val TAG = "perms:fix-chip"

    /** The text of the chip: blank when nothing is missing. */
    fun text(missing: Collection<*>): String = if (missing.isEmpty()) "" else TEXT

    /** Of [ids] (in [ids] order), the ones whose state is a missing grant: the rows the chip points at. */
    fun fixIds(ids: Collection<String>, states: Map<String, PermState>): List<String> =
        ids.distinct().filter { id -> states[id].let { it == PermState.DENIED || it == PermState.PERMANENTLY_DENIED } }

    /** The applicable, not-granted catalogue entries among [ids] on this device now. */
    fun missing(ctx: Context, ids: Collection<String>): List<PermEntry> {
        val cat = Perms.catalogue(ctx)
        val vars = Perms.vars(ctx)
        val decl = Perms.declared(ctx)
        val entries = cat.entries.filter { it.id in ids }
        val bad = fixIds(ids, entries.associate { it.id to PermStatusReader.status(ctx, it, null, vars, decl).state }).toSet()
        return entries.filter { it.id in bad }
    }
}

/** Counts every resume of the hosting activity: read it in a composition and the screen re-checks its grants on return. */
@Composable
fun rememberPermsResumes(): Int {
    val ctx = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    val activity = remember(ctx) { ctx.findActivity() }
    DisposableEffect(activity) {
        val owner = activity as? androidx.lifecycle.LifecycleOwner
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes++ }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    return resumes
}

/** The missing entries among [ids], re-read on every resume of the hosting activity. */
@Composable
fun rememberPermsMissing(ids: Collection<String>): List<PermEntry> {
    val ctx = LocalContext.current
    val resumes = rememberPermsResumes()
    val key = ids.joinToString("|")
    return remember(resumes, key) { PermsChip.missing(ctx, ids) }
}

/** Draws nothing while every grant in [ids] is held; else the chip, which opens the Perms page on those rows. */
@Composable
fun PermsFixChip(ids: Collection<String>, feature: String = "app", modifier: Modifier = Modifier, style: PermsStyle = PermsStyle()) {
    val missing = rememberPermsMissing(ids)
    if (missing.isEmpty()) return
    val p = style.resolve()
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(PermsType.corner)
    Row(
        modifier.clip(shape).background(p.warn.copy(alpha = 0.14f)).border(1.dp, p.warn.copy(alpha = 0.6f), shape)
            .clickable { PermsLink.open(ctx, feature, missing.map { it.id }) }
            .heightIn(min = PermsDense.tap()).padding(horizontal = PermsType.inset).testTag(PermsChip.TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(PermsChip.text(missing), color = p.warn, fontSize = PermsType.caption, fontWeight = FontWeight.SemiBold)
    }
}
