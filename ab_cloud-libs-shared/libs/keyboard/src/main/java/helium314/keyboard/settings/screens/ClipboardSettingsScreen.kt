// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference

// SuperApp addition — clipboard settings, promoted out of the general
// Preferences screen to its own top-level menu entry (was previously a
// buried category inside Preferences). All Setting() definitions moved
// here verbatim from PreferencesScreen.kt's createPreferencesSettings.

@Composable
fun ClipboardSettingsScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val clipboardHistoryEnabled = prefs.getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)
    val items = listOf(
        Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME else null,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST else null,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_USE_FILES else null,
        if (clipboardHistoryEnabled && prefs.getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, Defaults.PREF_CLIPBOARD_USE_FILES))
            Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT else null,
        if (clipboardHistoryEnabled) SettingsWithoutKey.CLIPBOARD_EXPORT_JSON else null,
        if (clipboardHistoryEnabled) SettingsWithoutKey.CLIPBOARD_IMPORT_JSON else null,
        if (clipboardHistoryEnabled) SettingsWithoutKey.CLIPBOARD_RENAME_LIST else null,
        if (clipboardHistoryEnabled) SettingsWithoutKey.CLIPBOARD_CLEAR_HISTORY else null,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_clipboard),
        settings = items
    )
}

/**
 * Export/import use a fixed path outside the app sandbox ([ClipboardDao.exportDir]), which needs
 * all-files access. Returns true when we already have it; otherwise opens the Permissions page on it
 * (SuperApp #1274) and returns false, so they retry the action once it is granted.
 */
private fun ensureAllFilesAccess(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) return true
    Toast.makeText(ctx, R.string.clipboard_needs_storage_access, Toast.LENGTH_LONG).show()
    // SuperApp #1274: the Permissions page (libs:perms) is the one place that asks; this screen never opens a settings page.
    com.diegonmarcos.superapp.perms.PermsLink.open(ctx, "keyboard", listOf("all_files"))
    return false
}

fun createClipboardSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        R.string.enable_clipboard_history, R.string.enable_clipboard_history_summary)
    {
        val ctx = LocalContext.current
        SwitchPreference(it, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY) { ClipboardDao.getInstance(ctx)?.clearNonPinned() }
    },
    Setting(context, Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, R.string.clipboard_history_retention_time) { setting ->
        val ctx = LocalContext.current
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_CLIPBOARD_HISTORY_RETENTION_TIME,
            description = {
                if (it > 120) stringResource(R.string.settings_no_limit)
                else stringResource(R.string.abbreviation_unit_minutes, it.toString())
            },
            range = 1f..121f,
        ) { ClipboardDao.getInstance(ctx)?.clearOldClips(true) }
    },
    Setting(context, Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST, R.string.clipboard_history_pinned_first) {
        SwitchPreference(it, Defaults.PREF_CLIPBOARD_HISTORY_PINNED_FIRST)
    },
    Setting(context, Settings.PREF_CLIPBOARD_USE_FILES, R.string.clipboard_history_files) {
        val ctx = LocalContext.current
        SwitchPreference(it, Defaults.PREF_CLIPBOARD_USE_FILES) {
            ClipboardDao.getInstance(ctx)?.cleanupFiles(ctx.prefs())
        }
    },
    Setting(context, Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT, R.string.clipboard_history_max_file_size) { setting ->
        val ctx = LocalContext.current
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_CLIPBOARD_FILES_SIZE_LIMIT,
            description = {
                if (it > 1000) stringResource(R.string.settings_no_limit)
                else stringResource(R.string.abbreviation_unit_mb, it.toString())
            },
            range = 1f..1001f,
        ) { ClipboardDao.getInstance(ctx)?.cleanupFiles(ctx.prefs()) }
    },
    Setting(context, SettingsWithoutKey.CLIPBOARD_EXPORT_JSON, R.string.clipboard_export_json, R.string.clipboard_export_json_summary) {
        val ctx = LocalContext.current
        Preference(name = stringResource(R.string.clipboard_export_json), onClick = {
            val dao = ClipboardDao.getInstance(ctx)
            if (dao == null || !ensureAllFilesAccess(ctx)) return@Preference
            runCatching {
                val count = dao.exportToDir(ClipboardDao.exportDir())
                Toast.makeText(ctx, ctx.getString(R.string.clipboard_export_success, count), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(ctx, R.string.clipboard_import_failed, Toast.LENGTH_SHORT).show()
            }
        })
    },
    Setting(context, SettingsWithoutKey.CLIPBOARD_IMPORT_JSON, R.string.clipboard_import_json, R.string.clipboard_import_json_summary) {
        val ctx = LocalContext.current
        var confirm by remember { mutableStateOf(false) }
        Preference(name = stringResource(R.string.clipboard_import_json), onClick = {
            val dao = ClipboardDao.getInstance(ctx)
            if (dao == null || !ensureAllFilesAccess(ctx)) return@Preference
            // import replaces everything, so confirm first
            confirm = true
        })
        if (confirm) {
            val dao = ClipboardDao.getInstance(ctx)
            ConfirmationDialog(
                onDismissRequest = { confirm = false },
                onConfirmed = {
                    runCatching {
                        val count = dao?.importFromDir(ClipboardDao.exportDir(), ctx) ?: 0
                        Toast.makeText(ctx, ctx.getString(R.string.clipboard_import_success, count), Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(ctx, R.string.clipboard_import_failed, Toast.LENGTH_SHORT).show()
                    }
                },
                title = { Text(stringResource(R.string.clipboard_import_json)) },
                content = { Text(stringResource(R.string.clipboard_import_replace_warning, dao?.count() ?: 0)) },
            )
        }
    },
    // Rename a pin list from Settings (the in-panel version can't work: while the
    // clipboard is open the keyboard shows the clipboard, so there's nothing to type into).
    Setting(context, SettingsWithoutKey.CLIPBOARD_RENAME_LIST, R.string.clipboard_rename_list) {
        val ctx = LocalContext.current
        var picking by remember { mutableStateOf(false) }
        var renaming by remember { mutableStateOf<String?>(null) }
        Preference(name = stringResource(R.string.clipboard_rename_list), onClick = {
            if (ClipboardDao.getInstance(ctx)?.getListNames().orEmpty().isNotEmpty()) picking = true
        })
        if (picking) {
            ListPickerDialog(
                onDismissRequest = { picking = false },
                items = ClipboardDao.getInstance(ctx)?.getListNames().orEmpty(),
                onItemSelected = { renaming = it },
                title = { Text(stringResource(R.string.clipboard_rename_list)) },
                showRadioButtons = false,
            )
        }
        renaming?.let { old ->
            TextInputDialog(
                onDismissRequest = { renaming = null },
                onConfirmed = { text ->
                    val newName = text.trim()
                    if (newName.isNotEmpty()) ClipboardDao.getInstance(ctx)?.renameList(old, newName)
                },
                title = { Text(stringResource(R.string.clipboard_rename_list)) },
                initialText = old,
                textInputLabel = { Text(stringResource(R.string.clipboard_rename_list_hint)) },
            )
        }
    },
    // Clear the clipboard history. Owner: this action was removed from the keyboard toolchain
    // (the clipboard toolbar's clear-clipboard key is gone), so Config is now its only home.
    Setting(context, SettingsWithoutKey.CLIPBOARD_CLEAR_HISTORY, R.string.clear_clipboard, R.string.clipboard_clear_history_summary) {
        val ctx = LocalContext.current
        var confirm by remember { mutableStateOf(false) }
        Preference(name = stringResource(R.string.clear_clipboard), onClick = { confirm = true })
        if (confirm) {
            val dao = ClipboardDao.getInstance(ctx)
            ConfirmationDialog(
                onDismissRequest = { confirm = false },
                onConfirmed = {
                    dao?.clearNonPinned()
                    Toast.makeText(ctx, R.string.clear_clipboard, Toast.LENGTH_SHORT).show()
                },
                title = { Text(stringResource(R.string.clear_clipboard)) },
                content = { Text(stringResource(R.string.clipboard_clear_history_warning, dao?.count() ?: 0)) },
            )
        }
    },
)

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            ClipboardSettingsScreen {}
        }
    }
}
