// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.repoFile
import helium314.keyboard.latin.utils.AndroidKeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1818 follow-up: the first-row icon (ToolbarKey.ANDROID_SETTINGS) opens Android's "Choose input method" popup, falling back to the
 * system keyboards page; the keyboard's own settings stay on the second-row Configs. Pins icon -> key code -> handler -> destination, and that
 * the system page and the own-settings intent are still there (rule 16).
 */
class AndroidConfigsIconTest {
    private fun src(rel: String) = repoFile("ab_cloud-libs-shared/libs/keyboard/src/main/java/helium314/keyboard/$rel").readText()

    @Test fun theIconIsTheAndroidSettingsKeyAndItsCodeIsTheImeSettingsCode() {
        val kbIcons = src("keyboard/internal/KeyboardIconsSet.kt")
        assertTrue(kbIcons.contains("ToolbarKey.ANDROID_SETTINGS -> R.drawable.ic_toolbar_android_settings"))
        assertTrue(src("latin/utils/ToolbarUtils.kt").contains("ANDROID_SETTINGS -> KeyCode.ANDROID_IME_SETTINGS"))
        assertEquals(-310, KeyCode.ANDROID_IME_SETTINGS)
    }

    @Test fun theKeyCodeHandlerOpensTheSystemInputMethodPicker() {
        val logic = src("latin/inputlogic/InputLogic.java")
        val case = logic.substringAfter("case KeyCode.ANDROID_IME_SETTINGS:").substringBefore("break;")
        assertTrue(case.contains("mLatinIME.launchAndroidKeyboardSettings()"))
        val ime = src("latin/LatinIME.java")
        val body = ime.substringAfter("public void launchAndroidKeyboardSettings()").substringBefore("\n    }")
        assertTrue("opens Android's Choose input method popup", body.contains("AndroidKeyboardSettings.INSTANCE.showSystemPicker(this)"))
        assertTrue("falls back to the system keyboards page", body.contains("AndroidKeyboardSettings.INSTANCE.launch(this)"))
        assertTrue("not the keyboard's own settings (that is the second-row Configs)", !body.contains("launchConfigs(this)"))
        val helper = src("latin/utils/AndroidKeyboardSettings.kt")
        assertTrue(helper.contains("showInputMethodPicker()"))
    }

    @Test fun theDestinationIsTheImeSettingsActivityAtTheGroupedAllSettings() {
        assertEquals("helium314.keyboard.settings.SettingsActivity2", AndroidKeyboardSettings.CONFIGS_ACTIVITY.name)
        assertEquals("all_settings", AndroidKeyboardSettings.CONFIGS_OPEN_AT)
        assertEquals("the activity honours that name", SettingsDestination.Settings, SettingsDestination.externalRoute(AndroidKeyboardSettings.CONFIGS_OPEN_AT))
        assertEquals("open_at", SettingsActivity.EXTRA_OPEN_AT)
    }

    @Test fun theSystemKeyboardListStaysReachableFromTheConfigsRow() {
        assertEquals("android.settings.INPUT_METHOD_SETTINGS", AndroidKeyboardSettings.ACTION)
        assertTrue(src("settings/screens/MainSettingsScreen.kt").contains("AndroidKeyboardSettings.launch(ctx)"))
    }
}
