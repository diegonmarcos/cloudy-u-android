// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import helium314.keyboard.settings.KbDensity
import helium314.keyboard.settings.KeyboardSettingsExtras
import helium314.keyboard.settings.UserDataBacking
import helium314.keyboard.settings.UserDataTables
import helium314.keyboard.settings.UserRow
import helium314.keyboard.settings.UserTable
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.autocomplete.AutocompleteEngine
import helium314.keyboard.latin.autocomplete.AutocompletePage
import helium314.keyboard.latin.autocomplete.AutocompleteStore
import helium314.keyboard.latin.autocomplete.AutocompleteSync
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.PreferenceCategory

/**
 * #1103 Configs > Autocomplete. The keyboard's own Autocomplete store, page by page: how many rows each holds, whether
 * the page is masked, when Cloud Account last filled it. The rows themselves are never listed here; "Sync now" asks
 * Cloud Account again over the local provider and "Clear" empties the store on this phone.
 */
@Composable
fun AutocompleteScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { AutocompleteStore.open(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_autocomplete),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(innerPadding)) {
                tick // read: a sync or a clear redraws the counts
                val all = store.all()
                PreferenceCategory(stringResource(R.string.autocomplete_pages))
                AutocompletePage.entries.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = KbDensity.pageGutter, vertical = KbDensity.innerGap)) {
                        Text(p.title, fontSize = 14.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                        Text(AutocompleteEngine.rows(all, p).size.toString(), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        if (p.masked) Text(stringResource(R.string.autocomplete_masked), fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(stringResource(R.string.autocomplete_source_note), fontSize = 12.sp, modifier = Modifier.padding(KbDensity.pageGutter).alpha(0.7f),
                    color = MaterialTheme.colorScheme.onSurface)
                PreferenceCategory(stringResource(R.string.autocomplete_actions))
                Text(stringResource(R.string.autocomplete_sync_now), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable {
                        Thread { AutocompleteSync.pull(ctx.applicationContext, store); tick++ }.start()
                    }.padding(KbDensity.pageGutter))
                Text(stringResource(R.string.autocomplete_clear), fontSize = 14.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().clickable { store.clear(); tick++ }.padding(KbDensity.pageGutter))
                UserDataTablesSection()
            }
        }
    }
}

/** The prefs-backed [UserDataBacking]: one key holding the JSON document of every table. */
private class PrefsUserDataBacking(private val prefs: android.content.SharedPreferences) : UserDataBacking {
    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(value: String) { prefs.edit().putString(KEY, value).apply() }
    private companion object { const val KEY = "tables" }
}

/**
 * The tables of user data Autocomplete draws on: Personal Data, Keys, Urls, Notes, Others. Each table shows its scheme (the
 * columns, and for Personal Data the suggested fields), lists its rows and supports add, edit and delete, saved in the app's
 * declared store. Nothing is baked into the APK and no row is shown that the user did not type.
 */
@Composable
private fun UserDataTablesSection() {
    val ctx = LocalContext.current
    val tables = remember(ctx) {
        runCatching { KeyboardSettingsExtras.userDataPrefs?.invoke(ctx)?.let { UserDataTables(PrefsUserDataBacking(it)) } }.getOrNull()
    }
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Pair<UserTable, UserRow?>?>(null) }
    var preset by remember { mutableStateOf<String?>(null) }
    PreferenceCategory(stringResource(R.string.autocomplete_userdata))
    Text(stringResource(R.string.autocomplete_userdata_note), fontSize = 12.sp, modifier = Modifier.padding(horizontal = KbDensity.pageGutter).alpha(0.7f),
        color = MaterialTheme.colorScheme.onSurface)
    if (tables == null) {
        Text(stringResource(R.string.autocomplete_userdata_unavailable), fontSize = 14.sp, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(KbDensity.pageGutter))
        return
    }
    tick // read: an add, an edit or a delete redraws the rows
    val all = tables.all()
    UserTable.entries.forEach { t ->
        val rows = all.filter { it.table == t }
        PreferenceCategory("${t.title} (${rows.size})")
        Text(t.columns.joinToString("  |  "), fontSize = 11.sp, modifier = Modifier.padding(horizontal = KbDensity.pageGutter).alpha(0.7f).testTag("ud:scheme:${t.id}"),
            color = MaterialTheme.colorScheme.onSurface)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().testTag("ud:row:${t.id}").clickable { preset = null; editing = t to r }
                .padding(horizontal = KbDensity.pageGutter, vertical = KbDensity.rowPadV)) {
                Text(r.name, fontSize = 14.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                val shown = r.cells.drop(1).firstOrNull().orEmpty()
                Text(if (t.maskedColumn == 1 && shown.isNotEmpty()) "••••••" else shown.lineSequence().firstOrNull().orEmpty().take(40), fontSize = 14.sp,
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
                Text(stringResource(R.string.autocomplete_userdata_delete), fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = KbDensity.blockGap).testTag("ud:delete:${t.id}").clickable { tables.delete(r.id); tick++ })
            }
        }
        // the scheme's suggested fields that have no row yet: one tap adds one
        t.suggested.filter { f -> rows.none { it.name.equals(f, true) } }.forEach { f ->
            Text("$f: " + stringResource(R.string.autocomplete_userdata_empty), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().testTag("ud:suggest:${t.id}").clickable { preset = f; editing = t to null }
                    .padding(horizontal = KbDensity.pageGutter, vertical = KbDensity.rowPadV))
        }
        Text(stringResource(R.string.autocomplete_userdata_add, t.title), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().testTag("ud:add:${t.id}").clickable { preset = null; editing = t to null }
                .padding(horizontal = KbDensity.pageGutter, vertical = KbDensity.rowPadV))
    }
    editing?.let { (t, row) ->
        UserRowDialog(t, row, preset, onSave = { cells ->
            val ok = if (row == null) tables.add(t, cells) != null else tables.edit(row.id, cells)
            if (ok) { editing = null; preset = null; tick++ }
            ok
        }, onDismiss = { editing = null; preset = null })
    }
}

/** Add / edit one row: a text field per column of the table, refused with the reason when the row cannot be saved. */
@Composable
private fun UserRowDialog(t: UserTable, row: UserRow?, preset: String?, onSave: (List<String>) -> Boolean, onDismiss: () -> Unit) {
    val cells = remember(t, row, preset) { mutableStateListOf(*Array(t.columns.size) { i -> row?.cells?.getOrNull(i) ?: if (i == 0) preset.orEmpty() else "" }) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.title) },
        text = {
            Column {
                t.columns.forEachIndexed { i, label ->
                    OutlinedTextField(
                        value = cells[i], onValueChange = { cells[i] = it; error = null }, label = { Text(label) },
                        singleLine = t != UserTable.NOTES || i == 0,
                        visualTransformation = if (t.maskedColumn == i) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth().padding(vertical = KbDensity.innerGap / 2).testTag("ud:field:${t.id}:$i"),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val list = cells.toList()
                error = UserDataTables.problemOf(t, list)
                if (error == null && !onSave(list)) error = "Not saved: this table is full"
            }, modifier = Modifier.testTag("ud:save")) { Text(stringResource(R.string.autocomplete_userdata_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
