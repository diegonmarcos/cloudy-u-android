// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.compose.ui.unit.Dp
import com.diegonmarcos.superapp.uikit.KitDensity

/**
 * The density of every keyboard settings page with text, declared ONCE and derived from the fleet kit's own tokens
 * ([KitDensity]) so the keyboard's pages are as dense as every other fleet page. Shared widgets (Preference,
 * PreferenceCategory, the setting rows of the AI / text-tool pages, the info pages) take their paddings, row heights and
 * section gaps from here; a page does not choose its own literal. KbDensityTest holds the values below the old ones
 * (row 10 dp / min 44 dp, section 12 + 8 dp, gutter 16 dp) and fails when a page goes back to a literal.
 */
object KbDensity {
    /** Minimum height of a settings row (was 44 dp). */
    val rowMinHeight: Dp = KitDensity.touch
    /** Top and bottom padding of a settings row (was 10 dp). */
    val rowPadV: Dp = KitDensity.rowPadV
    /** Left and right padding of a settings row (was 12 dp, 16 dp on the info pages). */
    val rowPadH: Dp = KitDensity.medium
    /** The page gutter of the info / table pages (was 16 dp). */
    val pageGutter: Dp = KitDensity.medium
    /** Space above a section title (was 12 dp). */
    val sectionTop: Dp = KitDensity.small
    /** Space between a section title and its first row (was 8 dp). */
    val sectionBottom: Dp = KitDensity.buttonPadV
    /** The gap between two blocks inside a page (was 16 dp / 8 dp). */
    val blockGap: Dp = KitDensity.small
    /** The gap between a row's title and its description or control (was 8 dp). */
    val innerGap: Dp = KitDensity.buttonPadV
}
