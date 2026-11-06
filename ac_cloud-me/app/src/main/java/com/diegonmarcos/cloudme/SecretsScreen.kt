package com.diegonmarcos.cloudme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Text
import com.diegonmarcos.cloudme.VaultwardenMapping.SecretType

/**
 * Buro > Secrets > <type>: the Vaultwarden export rendered as one authenticator-first table, with
 * the owner's "All | By Folders" scope control, and every secret / huge value behind a file-link
 * popup (view, copy, export the single value) so nothing secret is drawn inline or into a
 * screenshot. The data comes from [BuroData] (the me-secrets pack); when signed-out it shows the
 * sign-in state and renders no table.
 */
@Composable
fun SecretsScreen(secretType: String, modifier: Modifier = Modifier) {
    val type = SecretType.of(secretType)
    if (type == null) { MeNote("Unknown secret type", secretType, modifier); return }
    val ctx = LocalContext.current
    val state by produceState<BuroState>(BuroState.Loading, type) { value = BuroData.load(ctx, "me-secrets") }

    when (val s = state) {
        is BuroState.Loading -> MeNote("Loading…", "Fetching the encrypted me-secrets pack.", modifier)
        is BuroState.SignedOut -> MeNote("Sign in with GitHub", BuroData.PENDING, modifier)
        is BuroState.NoAccess -> MeNote("No access", s.reason, modifier)
        is BuroState.Error -> MeNote("Could not load secrets", s.message, modifier)
        is BuroState.Ready -> SecretsTable(type, s.json, s.fromCache, modifier)
    }
}

@Composable
private fun SecretsTable(type: SecretType, exportJson: String, fromCache: Boolean, modifier: Modifier) {
    val folders = remember(exportJson) { VaultwardenMapping.folders(exportJson) }
    var byFolder by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf(folders.firstOrNull()) }
    val table = remember(type, exportJson, byFolder, folder) {
        VaultwardenMapping.table(exportJson, type, onlyFolder = if (byFolder) folder else null)
    }
    var reveal by remember { mutableStateOf<VaultwardenMapping.SecretCell?>(null) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(type.label, color = colorResource(R.color.me_text), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (fromCache) Text("Last known good (offline copy)", color = colorResource(R.color.me_text_dim), fontSize = 12.sp)

        // scope control: All | By Folders
        Row(Modifier.padding(top = 10.dp)) {
            ScopePill("All", !byFolder) { byFolder = false }
            Box(Modifier.padding(start = 8.dp))
            ScopePill("By Folders", byFolder) { if (folders.isNotEmpty()) byFolder = true }
        }
        if (byFolder) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                folders.forEach { f ->
                    Box(Modifier.padding(end = 6.dp)) { ScopePill(f, folder == f) { folder = f } }
                }
            }
        }

        // the table
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            HeaderRow(table.columns)
            if (table.rows.isEmpty()) {
                Text("Nothing in this scope.", color = colorResource(R.color.me_text_dim), fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp))
            }
            table.rows.forEachIndexed { i, row ->
                DataRow(row, onReveal = { reveal = it }, tag = "me:secrets:${type.id}:row:$i")
            }
        }
    }
    reveal?.let { cell -> RevealDialog(cell, onDismiss = { reveal = null }) }
}

@Composable
private fun ScopePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = colorResource(if (selected) R.color.me_primary else R.color.me_surface)
    val ink = colorResource(if (selected) R.color.me_on_primary else R.color.me_text)
    Text(
        label, color = ink, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(bg)
            .border(1.dp, colorResource(R.color.me_outline), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp).testTag("me:secrets:scope:$label"),
    )
}

@Composable
private fun HeaderRow(cols: List<String>) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        cols.forEachIndexed { i, c ->
            Text(c, color = colorResource(R.color.me_text_dim), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                modifier = Modifier.weight(if (i == 0) 1.6f else 1f))
        }
    }
}

@Composable
private fun DataRow(row: VaultwardenMapping.SecretRow, onReveal: (VaultwardenMapping.SecretCell) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        row.cells.forEachIndexed { i, cell ->
            val w = if (i == 0) 1.6f else 1f
            if (cell.reveal) {
                // a secret / huge value: never inline — a file-link that opens the popup
                Text("View", color = colorResource(R.color.me_primary), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End, maxLines = 1,
                    modifier = Modifier.weight(w).clickable { onReveal(cell) }.testTag("$tag:reveal:${cell.label}"))
            } else {
                Text(cell.value, color = colorResource(if (i == 0) R.color.me_text else R.color.me_text_dim),
                    fontSize = 13.sp, fontWeight = if (i == 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = if (i == 0) TextAlign.Start else TextAlign.End, modifier = Modifier.weight(w))
            }
        }
    }
}

/** The single-value popup: view, copy, export. The value lives here only while the dialog is open. */
@Composable
private fun RevealDialog(cell: VaultwardenMapping.SecretCell, onDismiss: () -> Unit) {
    val clip = LocalClipboardManager.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colorResource(R.color.me_surface))
                .border(1.dp, colorResource(R.color.me_outline), RoundedCornerShape(16.dp)).padding(18.dp),
        ) {
            Text(cell.label, color = colorResource(R.color.me_text), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp))
                .background(colorResource(R.color.me_bg)).padding(12.dp)) {
                Text(cell.value, color = colorResource(R.color.me_text), fontSize = 12.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()))
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                DialogAction("Copy") { clip.setText(AnnotatedString(cell.value)) }
                Box(Modifier.padding(start = 10.dp))
                DialogAction("Export") { SecretExport.pending = cell.label to cell.value; onDismiss() }
                Box(Modifier.padding(start = 10.dp))
                DialogAction("Close", onDismiss)
            }
        }
    }
}

@Composable
private fun DialogAction(label: String, onClick: () -> Unit) =
    Text(label, color = colorResource(R.color.me_primary), fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(onClick = onClick).padding(6.dp).testTag("me:secrets:dialog:$label"))

/** Hand-off for a single value the user asked to export; the Backup surface writes it to a picked file. */
object SecretExport {
    @Volatile var pending: Pair<String, String>? = null
}

// ── small shared helpers (kept local so Secrets/Idd/Backup do not depend on StackScreen privates) ──

@Composable
internal fun MeNote(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colorResource(R.color.me_surface))
                .border(1.dp, colorResource(R.color.me_outline), RoundedCornerShape(14.dp)).padding(14.dp),
        ) {
            Column {
                if (title.isNotBlank()) Text(title, color = colorResource(R.color.me_text), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (body.isNotBlank()) Text(body, color = colorResource(R.color.me_text_dim), fontSize = 13.sp,
                    lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
