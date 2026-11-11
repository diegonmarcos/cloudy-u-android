package helium314.keyboard.settings

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * #1352 entries the hosting app puts at the top of the keyboard's Configs, under the fleet's own additions. The standalone
 * Cloud Keyboard registers Configs ▸ General and Configs ▸ About (libs:ui-nav-bars) from its Application; a host that
 * registers nothing (the keyboard engines APK) shows nothing, and this lib does not depend on the pages.
 */
object KeyboardSettingsExtras {
    class Entry(val label: String, val description: String, val open: (Context) -> Unit)

    @Volatile var entries: List<Entry> = emptyList()

    /**
     * #1651 the host's chrome around the settings pages: the fleet default top and bottom nav bars (libs:ui-nav-bars), which this
     * lib does not depend on. Called with the open route, a way to open a route, and the page content to draw between the bars.
     * null (the keyboard engines APK) draws the pages bare.
     */
    /** #1652 true when [chrome] draws the one top bar: pages then draw no title bar of their own (back = system back, search stays). */
    val chromeActive: Boolean get() = chrome != null

    /** #1652 pages the host draws itself (the Writer tab's cloud-writer pages, libs:writer-ui): called with the page id of a `host/<id>` route. */
    @Volatile var hostPage: (@Composable (id: String) -> Unit)? = null

    @Volatile var chrome: (@Composable (route: String?, open: (String) -> Unit, content: @Composable () -> Unit) -> Unit)? = null
}
