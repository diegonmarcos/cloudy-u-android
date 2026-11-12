package com.diegonmarcos.superapp.settings
import com.diegonmarcos.superapp.account.BuildConfig
import com.diegonmarcos.superapp.account.R

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudlib.auth.VaultConnect
import com.diegonmarcos.cloudlib.auth.VaultFile
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1693 Cloud Account ▸ Connect ▸ Import (moved here from the SuperApp, which no longer links libs:account;
 * the SuperApp's Import configs shortcut opens this page in Cloud Account).
 *
 * Was: Configs ▸ Profile ▸ "Import from a file instead" (the last line of journey
 * step 4, #573) and the paste box behind it.
 *
 * TWO SHAPES, ONE DOOR, NO SILENCE (#585). What comes in is classified by
 * [VaultFile.classify] before anything is written:
 *   • the DECRYPTED vault export (root `schema_version` + sections) lands in
 *     [VaultConnect.Imported] — the Fleet cockpit — exactly where the server
 *     fetch lands, and is applied there per section, never here;
 *   • the paste shape (build.json::ui.import_schema, baked into
 *     BuildConfig.UI_IMPORT_SCHEMA_B64) is merged SECTION BY SECTION into
 *     [ConfigsPrefs] through its own `putSecret`, so a blob without an `auth`
 *     section no longer erases the stored Authelia bearer;
 *   • everything else — the sops-ENCRYPTED file, zero bytes, not JSON, an
 *     unknown schema, sections nothing here knows — is refused with what was
 *     read and what to feed instead.
 * Every exit of [loadFromUri] and [describe] goes through [report]; the
 * silence guard (1_cicd/src/data/silence-guard.json) fails the build on any
 * `return` that does not.
 *
 * Compose since #773 (was fragment_import_configs.xml): the paste box is
 * [input] and the verdict line is [status], both plain state; the verdict line
 * is a polite live region, so a screen reader still hears every sentence
 * [report] puts on screen, as the View version's announceForAccessibility did.
 */
class ImportConfigsFragment : KitComposeFragment() {

    /** The paste box's text. */
    var input by mutableStateOf("")
    /** The last sentence [report] said. */
    var status by mutableStateOf("")

    /**
     * The encrypted store, behind three seams. [ConfigsPrefs] needs the Android
     * Keystore for its MasterKey, which a JVM test does not have; the test
     * swaps these for a map and drives the whole route on the real bytes.
     * ponytail: three lambdas, not an interface with one implementation.
     */
    var readBlob: (Context) -> String = { ConfigsPrefs(it).json }
    var putSecret: (Context, String, String, String) -> Unit =
        { c, section, key, value -> ConfigsPrefs(c).putSecret(section, key, value) }
    var clearBlob: (Context) -> Unit = { ConfigsPrefs(it).clear() }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // A dismissed picker is the owner's choice, not a failure: nothing to report.
        if (uri != null) loadFromUri(uri)
    }

    override fun palette() = AccountHost.palette(requireContext())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        input = readBlob(requireContext())
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    @Composable
    override fun Content() {
        val p = LocalKitPalette.current
        val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text(stringResource(R.string.import_title), color = p.textPrimary,
                style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.import_subtitle), color = p.textSecondary,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(12.dp))
            KitCard {
                SelectionContainer {
                    Text(importSchemaJson(), color = p.textPrimary,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.import_input_hint)) },
                textStyle = mono,
                modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 12.dp).testTag(TAG_INPUT),
            )
            com.diegonmarcos.superapp.uikit.KitOutlinedButton(
                // Accept anything; ACTION_OPEN_DOCUMENT respects the device file
                // picker (Files / Storage / 3rd-party). JSON or plain-text fine.
                onClick = { pickFile.launch(arrayOf("application/json", "text/*", "*/*")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(TAG_OPEN_FILE),
            ) {
                Icon(painterResource(R.drawable.ic_account_import), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.import_from_file))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                com.diegonmarcos.superapp.uikit.KitButton(onClick = { describe(classify(input), store = true) },
                    modifier = Modifier.weight(1f).testTag(TAG_SAVE)) { Text(stringResource(R.string.import_save)) }
                com.diegonmarcos.superapp.uikit.KitOutlinedButton(
                    onClick = {
                        clearBlob(requireContext())
                        input = ""
                        report(R.string.import_cleared)
                    },
                    modifier = Modifier.weight(1f).testTag(TAG_CLEAR),
                ) { Text(stringResource(R.string.import_clear)) }
            }
            Text(status, color = p.textSecondary, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp).testTag(TAG_STATUS)
                    .semantics { liveRegion = LiveRegionMode.Polite })
        }
    }

    /**
     * Read the picked file and SAY what it is, before Save. A file that reads
     * as zero bytes (a cloud folder that has not synced, a provider that
     * streams lazily) is reported as such, not as an empty import.
     */
    fun loadFromUri(uri: Uri) {
        val name = uri.lastPathSegment ?: uri.toString()
        val text = try {
            requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (t: Throwable) { report(R.string.import_file_error, "${t.javaClass.simpleName}: ${t.message}"); return }
        if (text == null) { report(R.string.import_file_no_stream, name); return }
        if (text.isEmpty()) { report(R.string.import_file_empty, name); return }
        input = text
        describe(classify(text), store = false)
    }

    /**
     * One sentence per verdict; on Save, the write that verdict allows. The
     * encrypted file, an unknown schema and an unrecognised shape write
     * NOTHING and say so — with the counts, the sections and the way out.
     */
    fun describe(v: VaultFile.Verdict, store: Boolean) {
        val ctx = requireContext()
        when (v) {
            VaultFile.Verdict.Empty -> report(R.string.import_nothing)
            is VaultFile.Verdict.NotJson -> report(R.string.import_invalid, v.chars, v.head, v.reason)
            is VaultFile.Verdict.Encrypted -> report(R.string.import_encrypted, v.encValues, v.recipients, v.sections.joinToString(", "))
            is VaultFile.Verdict.UnknownSchema -> report(R.string.vault_connect_schema_unknown, v.version, VaultConnect.knownSchemaVersions.sorted().joinToString(", "))
            is VaultFile.Verdict.Unrecognised -> report(R.string.import_unrecognised, v.keys.joinToString(", "), v.expected.joinToString(", "))
            is VaultFile.Verdict.Bundle -> {
                val sections = VaultConnect.sections(v.bundle)
                val values = sections.sumOf { it.rows.size }
                if (!store) { report(R.string.import_bundle_read, values, sections.size); return }
                VaultConnect.Imported.last = sections
                VaultConnect.Imported.bundle = v.bundle
                report(R.string.import_bundle_landed, values, sections.size)
            }
            is VaultFile.Verdict.Blob -> {
                val ignored = v.ignored.joinToString(", ").ifEmpty { "—" }
                if (!store) { report(R.string.import_blob_read, v.recognised.joinToString(", "), ignored); return }
                var written = 0
                for (section in v.recognised) {
                    val o = v.root.optJSONObject(section) ?: continue
                    for (key in o.keys()) {
                        if (key.startsWith("_")) continue
                        putSecret(ctx, section, key, o.opt(key).toString())
                        written++
                    }
                }
                if (written == 0) { report(R.string.import_blob_no_keys, v.recognised.joinToString(", ")); return }
                report(R.string.import_blob_stored, written, v.recognised.joinToString(", "), ignored)
            }
        }
    }

    /** THE reporter: the sentence goes on screen and, through the live region, to the screen reader. */
    private fun report(@StringRes res: Int, vararg args: Any) {
        status = getString(res, *args)
    }

    companion object {
        fun newInstance() = ImportConfigsFragment()

        /** Test tags of the page's parts, for compose tests and device checks. */
        const val TAG_INPUT = "import:input"
        const val TAG_OPEN_FILE = "import:open_file"
        const val TAG_SAVE = "import:save"
        const val TAG_CLEAR = "import:clear"
        const val TAG_STATUS = "import:status"

        /** THE classifier for a picked or pasted config: this page and the
         *  Account ▸ Connect ▸ Import File line (#711) both read through it. */
        fun classify(text: String): VaultFile.Verdict =
            VaultFile.classify(text, VaultConnect.knownSchemaVersions, VaultFile.blobSections(importSchemaJson()))

        private fun importSchemaJson(): String =
            String(Base64.decode(BuildConfig.UI_IMPORT_SCHEMA_B64, Base64.NO_WRAP))

        /**
         * Why a file is NOT the decrypted vault export, in this page's own
         * sentences — for the Import File line, which lands only the export.
         * Null for the export itself. The paste-shape blob is refused there
         * (it is credentials, it fills no Infos) and pointed back here.
         * ponytail: mirrors describe()'s refusal branches, which the silence
         * guard requires to name their R.string literally.
         */
        fun refusal(ctx: Context, v: VaultFile.Verdict): String? = when (v) {
            is VaultFile.Verdict.Bundle -> null
            VaultFile.Verdict.Empty -> ctx.getString(R.string.import_nothing)
            is VaultFile.Verdict.NotJson -> ctx.getString(R.string.import_invalid, v.chars, v.head, v.reason)
            is VaultFile.Verdict.Encrypted -> ctx.getString(R.string.import_encrypted, v.encValues, v.recipients, v.sections.joinToString(", "))
            is VaultFile.Verdict.UnknownSchema -> ctx.getString(R.string.vault_connect_schema_unknown, v.version, VaultConnect.knownSchemaVersions.sorted().joinToString(", "))
            is VaultFile.Verdict.Unrecognised -> ctx.getString(R.string.import_unrecognised, v.keys.joinToString(", "), v.expected.joinToString(", "))
            is VaultFile.Verdict.Blob -> ctx.getString(R.string.connect_file_blob, v.recognised.joinToString(", "))
        }
    }
}
