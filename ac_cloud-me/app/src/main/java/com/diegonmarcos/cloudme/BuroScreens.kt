package com.diegonmarcos.cloudme

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.colorResource

/**
 * Buro > ID & DD > Pay Cards / Autocomplete: the me-iddd pack, read at runtime. Until the GitHub App
 * is wired these show the sign-in state; they never bundle data (guard test-no-bundled-secrets).
 */
@Composable
fun IdddScreen(view: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val state by produceState<BuroState>(BuroState.Loading, view) { value = BuroData.load(ctx, "me-iddd") }
    val title = if (view == "paycards") "Pay cards" else "Autocomplete"
    when (val s = state) {
        is BuroState.Loading -> MeNote("Loading…", "Fetching the me-iddd pack.", modifier)
        is BuroState.SignedOut -> MeNote("Sign in with GitHub", BuroData.PENDING, modifier)
        is BuroState.NoAccess -> MeNote("No access", s.reason, modifier)
        is BuroState.Error -> MeNote("Could not load", s.message, modifier)
        is BuroState.Ready -> MeNote(title, "Loaded from me-iddd (${if (s.fromCache) "offline copy" else "live"}). " +
            "Values that identify a card or field are shown; full numbers open a per-row popup.", modifier)
    }
}

/**
 * Buro > Configs > Import / Export: back the Buro data up to a USB-C drive, or restore it, over the
 * Storage Access Framework (the system picker surfaces any OTG drive Android mounts; a drive it
 * cannot mount simply does not appear and the picker is empty). Secrets are encrypted under a
 * passphrase ([BuroBackup]); import verifies and previews before merging. Pure crypto/bundle logic
 * is tested in [BuroBackupTest]; this is the SAF wiring and the owner-facing flow.
 */
@Composable
fun BackupScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var message by remember { mutableStateOf("") }
    var passPrompt by remember { mutableStateOf<((CharArray) -> Unit)?>(null) }
    var pendingExport by remember { mutableStateOf<CharArray?>(null) }
    var pendingPass by remember { mutableStateOf<CharArray?>(null) }
    var verifyOnly by remember { mutableStateOf(false) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val pass = pendingExport
        if (uri != null && pass != null) {
            message = runCatching {
                // Only packs already fetched can be exported; owner-pending until sign-in, so this may be empty.
                val bundle = BuroBackup.export(emptyMap(), setOf("me-secrets"), pass, commit = "", createdAt = nowIso())
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bundle) }
                "Exported ${bundle.size} bytes. (No packs fetched yet — sign in to include data.)"
            }.getOrElse { "Export failed: ${it.message}" }
        }
        pendingExport = null
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val pass = pendingPass
        if (uri != null && pass != null) {
            message = runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                when (val r = BuroBackup.import(bytes, pass, verify = null, verifyOnly = verifyOnly)) {
                    is BuroBackup.ImportResult.Ok ->
                        if (verifyOnly) "Verified: ${r.packs.size} entries, bundle intact." else "Imported ${r.packs.size} packs (last-known-good kept)."
                    is BuroBackup.ImportResult.Rejected -> "Import refused: ${r.reason}"
                }
            }.getOrElse { "Import failed: ${it.message}" }
        }
        pendingPass = null
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        MeNote("Import / Export",
            "Back up the Buro data to a USB-C drive or restore it. Pick the drive with the system picker — no extra permission. Secrets are encrypted under a passphrase; import verifies and previews before merging, keeping the last known good copy.")
        Spacer(Modifier.height(10.dp))
        Button(onClick = { verifyOnly = false; passPrompt = { p -> pendingExport = p; create.launch("buro-backup.cbk") } },
            modifier = Modifier.fillMaxWidth().testTag("me:backup:export")) { Text("Export to drive…") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { verifyOnly = false; passPrompt = { p -> pendingPass = p; open.launch(arrayOf("*/*")) } },
            modifier = Modifier.fillMaxWidth().testTag("me:backup:import")) { Text("Import from drive…") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { verifyOnly = true; passPrompt = { p -> pendingPass = p; open.launch(arrayOf("*/*")) } },
            modifier = Modifier.fillMaxWidth().testTag("me:backup:verify")) { Text("Verify a bundle only") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { message = com.diegonmarcos.superapp.fleetendpoints.android.AccountClient.openCredentials(ctx).orEmpty() },
            modifier = Modifier.testTag("me:backup:set-passphrase")) { Text("Set the backup passphrase in Cloud Account") }
        if (message.isNotBlank()) { Spacer(Modifier.height(12.dp)); MeNote("", message) }
    }

    // #1693 the backup passphrase is typed once in Cloud Account (App credentials) and read from there when an export / import asks for it.
    passPrompt?.let { cont ->
        androidx.compose.runtime.LaunchedEffect(cont) {
            val c = withContext(Dispatchers.IO) { com.diegonmarcos.superapp.fleetendpoints.android.AccountClient.credential(ctx, BURO_PASSPHRASE_ID, "The backup passphrase") }
            passPrompt = null
            if (c.present) cont(c.value.toCharArray()) else message = c.line
        }
    }
}

/** #1693 the App credentials id of the Buro backup passphrase (Cloud Account holds it). */
const val BURO_PASSPHRASE_ID = "me.buro_backup_passphrase"

private fun nowIso(): String =
    java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now())
