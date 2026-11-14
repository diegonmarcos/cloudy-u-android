package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.ui.ShellTopInset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1080 the framework rule, for EVERY registered page without rendering it (ShellTopInsetGuardTest renders the Configs and
 * Cloud pages): the shell owns where the top row ends and content begins, so
 *
 *   S1  the rule: the floor is the declared dimen, a shown row lower than it wins, a hidden or unlaid row never lowers it
 *   S2  the shell wires it: installed once at create, re-applied after every chrome change, the dimen declared once
 *   S3  every content host (phone container, tablet master / detail / divider, overlay host) takes its top from that dimen,
 *       never from a typed number
 *   S4  no page source declares, measures or works around the top row: nothing outside the shell reads the row's size or the
 *       system-bar insets, and nothing offsets itself upward
 *   S5  every page is swapped into the one padded container (no page owns the chrome)
 */
class ShellTopInsetStaticGuardTest {

    private val app = File(System.getProperty("user.dir"))
    private val main = File(app, "src/main")
    private val java = File(main, "java")
    private fun text(f: File) = f.readText().replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("(?m)(?<=\\s|^)//.*$"), "")

    @Test fun `S1 the inset is the declared floor, or the shown row's bottom when that is lower`() {
        assertEquals(90, ShellTopInset.insetPx(90, islandShown = true, islandBottomPx = 70))
        assertEquals(90, ShellTopInset.insetPx(90, islandShown = true, islandBottomPx = 90))
        assertEquals(142, ShellTopInset.insetPx(90, islandShown = true, islandBottomPx = 142))
        assertEquals("a hidden row is never a reason to move the page", 90, ShellTopInset.insetPx(90, islandShown = false, islandBottomPx = 400))
        assertEquals("a row not laid out yet (bottom 0) never lowers the floor", 90, ShellTopInset.insetPx(90, islandShown = true, islandBottomPx = 0))
    }

    @Test fun `S2 the shell installs the inset once and re-applies it after every chrome change`() {
        val shell = text(File(java, "com/diegonmarcos/superapp/ShellActivity.kt"))
        assertEquals("installed once, at create", 1, Regex("ShellTopInset\\.install\\(").findAll(shell).count())
        assertEquals("re-applied by applyLauncherChrome", 1, Regex("ShellTopInset\\.apply\\(").findAll(shell).count())
        val chrome = shell.substringAfter("override fun applyLauncherChrome()").substringBefore("\n    }\n")
        assertTrue("apply sits inside applyLauncherChrome, after the island's margin is set", chrome.contains("ShellTopInset.apply(") &&
            chrome.indexOf("ShellTopInset.apply(") > chrome.lastIndexOf("setToolbarIslandTopMargin("))
        val dimens = File(main, "res/values/dimens.xml").readText()
        assertEquals("shell_top_inset is declared once", 1, Regex("name=\"shell_top_inset\"").findAll(dimens).count())
    }

    @Test fun `S3 every content host takes its top from the declared dimen`() {
        val res = File(main, "res")
        val hosts = listOf("layout/view_content_panes.xml", "layout-sw600dp/view_content_panes.xml", "layout/activity_main.xml")
        var declared = 0
        for (rel in hosts) {
            val xml = File(res, rel).readText().replace(Regex("(?s)<!--.*?-->"), "")
            declared += Regex("android:(paddingTop|layout_marginTop)=\"@dimen/shell_top_inset\"").findAll(xml).count()
            val typed = Regex("<([\\w.]+)[^>]*?android:id=\"@\\+id/(fragment_container|detail_container|overlay_container|pane_divider)\"[^>]*>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).map { it.value }.filter { Regex("android:(paddingTop|layout_marginTop)=\"[0-9.]+(dp|px)\"").containsMatchIn(it) }.toList()
            assertTrue("$rel: a content host pads its top with a typed number: $typed", typed.isEmpty())
        }
        assertTrue("the hosts declare their top from shell_top_inset ($declared places)", declared >= 5)
    }

    private val BANNED = Regex(
        "shell_top_inset|shell_top_row_margin|actionBarSize|statusBarsPadding|safeDrawing|safeContent|systemBarsPadding|" +
            "WindowInsets\\.(statusBars|systemBars|displayCutout)|windowInsetsPadding|fitsSystemWindows|" +
            "offset\\(\\s*y\\s*=\\s*-|padding\\(\\s*top\\s*=\\s*-|translationY\\s*=\\s*-")

    /** The same, for shared libs: an animation may lift a card by a negative translationY (wallet, cropper), so that one pattern is app-side only. */
    private val LIB_BANNED = Regex(BANNED.pattern.removeSuffix("|translationY\\s*=\\s*-"))

    /** Files that MAY name the row or the system insets, each with why. A file listed here that no longer needs it fails: the list only shrinks. */
    private val ALLOWED = mapOf(
        "com/diegonmarcos/superapp/ui/ShellToolbar.kt" to "the top row's own file: ShellTopInset, the owner of the rule",
        "com/diegonmarcos/superapp/launcher/themes/minimalist/MinimalistBlackFragment.kt" to "the mode's own Home root: it draws its own header with the island hidden, so it clears the system bars itself",
    )

    @Test fun `S4 no page declares, measures or works around the top row`() {
        val hits = java.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(java).path to BANNED.findAll(text(it)).map { m -> m.value }.toSet() }
            .filter { it.second.isNotEmpty() }.toMap()
        val stray = hits.filterKeys { it !in ALLOWED }
        assertTrue("page code that handles the top row itself (the shell does, ShellTopInset): $stray", stray.isEmpty())
        val stale = ALLOWED.keys.filter { it !in hits }
        assertTrue("allowlist entries that no longer name the row or insets (delete them): $stale", stale.isEmpty())
    }

    @Test fun `S4b the shared libs' pages do not either`() {
        val libs = File(app, "../../ab_cloud-libs-shared/libs")
        if (!libs.isDirectory) return   // a checkout without the shared libs: the app-side scan above still ran
        val own = setOf("bottomnav", "keyboard", "ui-nav-bars", "ui-nav-bars", "ui-nav-bars", "theme-background")   // bottomnav and its #1154 split (the nav, the second row) draw the row and the nav; the keyboard is not a page
        val hits = libs.listFiles().orEmpty().filter { it.isDirectory && it.name !in own }.flatMap { lib ->
            File(lib, "src").walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.contains("/test/") && !it.path.contains("/androidTest/") }
                .filter { LIB_BANNED.containsMatchIn(text(it)) }.map { it.relativeTo(libs).path }.toList()
        }
        assertTrue("shared page code that handles the top row itself: $hits", hits.isEmpty())
    }

    @Test fun `S5 every page reaches the screen through the one padded container`() {
        val shell = text(File(java, "com/diegonmarcos/superapp/ShellActivity.kt"))
        val swaps = Regex("\\.replace\\(R\\.id\\.([a-z_]+),").findAll(shell).map { it.groupValues[1] }.toSet()
        assertTrue("pages are swapped into the page container (and the tablet detail pane; the side drawer fills its own panel) only: $swaps",
            "fragment_container" in swaps && swaps.all { it == "fragment_container" || it == "detail_container" || it == "drawer_content" })
        val implementers = java.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("[:,]\\s*ShellOverride\\b").containsMatchIn(text(it)) }.map { it.name }.toList()
        // #1719 the one declared exception: the Firewall page (#1737: the unified Network page is gone) replaces the shell's bottom nav with its own (libs:bottomnav island) and
        // takes the whole screen (own nav + Back). Each is a named file and each still starts under the shell's padded container; any other
        // page taking the chrome over still fails here.
        val declared = setOf("NgFirewallFragment.kt")
        assertEquals("only the declared #1719 pages may own chrome", declared, implementers.filter { it in declared }.toSet())
        // ...and they own the BOTTOM nav only: the top row is the shell's, so nothing can draw under it.
        // ...and, owning their own bottom nav, they answer the shell's Back arrow themselves (one Back control, #1719).
        for (f in java.walkTopDown().filter { it.name in declared }) assertTrue("${f.name} must be a BackHandler", text(f).contains("override fun tryHandleBack()"))
        for (f in java.walkTopDown().filter { it.name in declared }) assertFalse("${f.name} must not hide the top row", text(f).contains("fun ownsToolbar(): Boolean = true"))
        assertFalse("these pages take the chrome over: ${implementers - declared}", (implementers - declared).isNotEmpty())
    }

    @Test fun `S6 a tab strip under the shell takes no second top inset`() {
        // The container is already padded to the row's bottom edge; the lib's default (status-bar inset + a 10 dp gap above the
        // strip) is a second inset on top of it, the empty line between the row and the tabs. So no call may rely on the default,
        // and the strips the shell hosts (section / page tabs, Cloud Mesh) start flush at the container's edge.
        val shellHosted = setOf("com/diegonmarcos/superapp/launcher/SectionTabsFragment.kt", "com/diegonmarcos/superapp/network/mesh/MeshScreen.kt")
        for (f in java.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val src = text(f)
            val calls = Regex("\\bPageTabs\\(").findAll(src).count()
            if (calls == 0) continue
            val rel = f.relativeTo(java).path
            assertEquals("$rel: every PageTabs call states underTopChrome = false (the default adds the status-bar inset again)",
                calls, Regex("underTopChrome\\s*=\\s*false").findAll(src).count())
            if (rel in shellHosted)
                assertEquals("$rel: the shell-hosted strip is flush with the container's edge", calls, Regex("flushTop\\s*=\\s*true").findAll(src).count())
        }
    }
}
