// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsActivity2

/**
 * #1103: the shortcut to Android's own keyboard (input method) settings, behind the toolbar key ANDROID_SETTINGS
 * and the entry of the same name in Configs. One intent, declared once, so the two never differ.
 *
 * #1818 the toolbar ICON (ToolbarKey.ANDROID_SETTINGS, glyph ic_toolbar_android_settings, code KeyCode.ANDROID_IME_SETTINGS) used to open
 * [ACTION], the phone's system list of on-screen keyboards: the wrong menu for a key whose job is the keyboard's Android configs. It now
 * opens the keyboard's own settings screen ([CONFIGS_ACTIVITY] at [CONFIGS_OPEN_AT], the grouped All settings: Keyboard / Text / Configs).
 * The system page stays reachable: the Configs row of the same name still opens [ACTION] through [launch] (nothing was removed).
 */
object AndroidKeyboardSettings {
    const val ACTION = Settings.ACTION_INPUT_METHOD_SETTINGS

    fun intent(): Intent = Intent(ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /** #1818 where the toolbar icon goes: the keyboard's own IME settings activity ... */
    val CONFIGS_ACTIVITY: Class<*> = SettingsActivity2::class.java
    /** ... opened at the grouped All settings (a key of SettingsDestination.external). */
    const val CONFIGS_OPEN_AT = "all_settings"

    fun configsIntent(context: Context): Intent = Intent().setClass(context, CONFIGS_ACTIVITY)
        .putExtra(SettingsActivity.EXTRA_OPEN_AT, CONFIGS_OPEN_AT)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** Opens the keyboard's own settings screen (the toolbar icon's destination); false when it cannot start. */
    fun launchConfigs(context: Context): Boolean = runCatching { context.startActivity(configsIntent(context)); true }.getOrDefault(false)

    /** Opens the system settings page; false when the phone has no activity for it. */
    fun launch(context: Context): Boolean = runCatching { context.startActivity(intent()); true }.getOrDefault(false)
}
