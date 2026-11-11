package com.diegonmarcos.superapp.navbartop1

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1157 per-app guard: every app on the shared nav bars draws its chrome with the shared scaffold (NavBarTopFirstRowScaffold,
 * which owns the ONE scroll connection) AND the shared bottom nav, and none hides the first row from its own state. A page
 * scrolled inside that scaffold then hides the first row and compacts the second row and the bottom nav; scrolling up restores
 * them (NavChromeScrollTest holds the behaviour). A missing app directory FAILS.
 */
class ChromeScrollGuardTest {
    private val apps = listOf(
        "ac_cloud-mail", "ac_cloud-me", "ac_cloud-drive", "ac_cloud-nav", "ac_cloud-account", "ac_cloud-store", "ac_cloud-c3", "ac_cloud-tasks", "ac_cloud-contacts", "ac_cloud-news", "ac_cloud-writer", "ac_cloud-search", "ac_cloud-browser", "ac_cloud-dialer", "ac_cloud-superintel", "ac_cloud-kg-store", "ac_cloud-notes", "ac_cloud-tools-small-llms", "ac_cloud-calc", "ac_cloud-gallery", "ad_Capital-Stack-FinTerminal", "ac_cloud-shopping",
    )

    /** #1356 ui.sections[id=<section>].title_row in a build.json text: false only when declared false (default true). */
    private fun titleRowOf(buildJson: String, section: String): Boolean =
        !Regex("""\{\s*"id":\s*"$section",[^{}]*?"title_row":\s*false""").containsMatchIn(buildJson)

    /** null when [body] matches its declared title row, else the reason. */
    private fun titleRowProblem(page: String, body: String, titleRow: Boolean): String? = when {
        !titleRow && "ToolbarIsland(" in body -> "$page draws a title row (ToolbarIsland) but its section declares title_row false"
        titleRow && "ToolbarIsland(" !in body -> "$page has no island but its section declares a title row"
        else -> null
    }

    private fun sources(app: String): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            // Cloud Notes is a Capacitor project: its Android app sits under the vendored tree.
            val root = listOf("$app/app/src/main", "$app/packages/frontend/apps/android/App/app/src/main").map { File(dir, it) }.firstOrNull { it.isDirectory }
            if (root != null) {
                // #1652 an app whose UI lives in a shared lib names it in build.json android.ui_module_dirs (Cloud Writer: libs:writer-ui).
                val extra = File(dir, "$app/build.json").takeIf { it.isFile }?.readText()
                    ?.let { Regex(""""ui_module_dirs"\s*:\s*\[([^\]]*)]""").find(it)?.groupValues?.get(1) }
                    ?.let { Regex(""""([^"]+)"""").findAll(it).map { m -> File(File(dir, app), m.groupValues[1] + "/src/main").canonicalFile }.filter { d -> d.isDirectory }.toList() }
                    .orEmpty()
                return (listOf(root) + extra).flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            }
            dir = dir.parentFile
        }
        throw AssertionError("$app/app/src/main not found above ${File("").absolutePath}")
    }

    @Test fun `every app draws its chrome with the shared scaffold and the shared bottom nav`() {
        for (app in apps) {
            val text = sources(app).joinToString("\n") { it.readText() }
            assertTrue("$app has no NavBarTopFirstRowScaffold (the scroll connection's owner)", "NavBarTopFirstRowScaffold(" in text)
            assertTrue("$app has no shared bottom nav", "NavBarBottomHost(" in text || "NavBarBottom(" in text)
        }
    }

    @Test fun `no app and not the row itself brings the hamburger back`() {
        val menu = Regex("""TopFirstRowTags\.MENU|\.openDrawer\(\)""")
        for (app in apps) {
            // The history bar's Menu item (Clean mode, the bottom row) opens the drawer on purpose (#1369); that wiring
            // line is the one exemption, any other drawer opener or MENU tag still counts.
            val hits = sources(app).filter { f -> f.readLines().any { menu.containsMatchIn(it) && "HistoryBarItem.Menu ->" !in it } }.map { it.name }
            assertEquals("$app draws or wires a hamburger (the first row has none; drawer content goes through TopBarDrawer into the title dropdown)", emptyList<String>(), hits)
        }
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ab_cloud-libs-shared/libs/ui-nav-bars/src/main").isDirectory) dir = dir.parentFile
        val row = File(dir ?: throw AssertionError("first-row lib not found"), "ab_cloud-libs-shared/libs/ui-nav-bars/src/main").walkTopDown()
            // Clean mode (NavUiMode) brings the hamburger back ON PURPOSE, in ONE file: NavBarTopCleanRow.kt. Everywhere else the first row has none.
            .filter { it.isFile && it.extension == "kt" && it.name != "NavBarTopCleanRow.kt" }.joinToString("\n") { it.readText() }
        assertTrue("the first row draws a Menu icon again", !Regex("""Icons\.\w+\.Menu\b""").containsMatchIn(row))
        assertTrue("the first row takes an onMenu again", "onMenu" !in row)
        val clean = File(dir, "ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop1/NavBarTopCleanRow.kt")
        assertTrue("Clean mode's hamburger lives in NavBarTopCleanRow.kt", clean.isFile && Regex("""Icons\.\w+\.Menu\b""").containsMatchIn(clean.readText()))
    }

    @Test fun `no app hides the first row from its own state`() {
        val own = Regex("""barVisible\s*=\s*[^\n]*collapse""", RegexOption.IGNORE_CASE)
        for (app in apps) {
            val hits = sources(app).filter { own.containsMatchIn(it.readText()) }.map { it.name }
            assertEquals("$app hand-rolls the first row's visibility", emptyList<String>(), hits)
        }
    }

    /** The scroll surfaces a Compose nestedScroll cannot see (a WebView, a Fragment page, a View list) and the token proving each app feeds them to the ONE collapse. */
    private val webScrollers = mapOf(
        "ac_cloud-browser" to "ChromeWebScroll.attach(",
        "ac_cloud-contacts" to "ChromeWebScroll.attach(",
        "ac_cloud-news" to "ChromeWebScroll.attach(",
        "ac_cloud-dialer" to "ChromeScrollHooks.install(",
        "ac_cloud-tasks" to "ChromeWebScroll.attach(",
        "ac_cloud-me" to "ChromeWebScroll.attach(",
        "ac_cloud-notes" to "ChromeWebScroll.attach(",
    )

    /** Apps whose WebViews are secondary screens (own Activity, dialog, editor or print helper), never the page under the chrome. */
    private val secondaryWebViewsOnly = setOf("ac_cloud-nav", "ac_cloud-mail", "ac_cloud-calc", "ac_cloud-drive")

    @Test fun `every WebView page under the chrome feeds the one collapse connection`() {
        val webView = Regex("""\bWebView\(""")
        for (app in apps) {
            val text = sources(app).joinToString("\n") { it.readText() }
            val token = webScrollers[app]
            if (token != null) assertTrue("$app hosts a scrolling page but never feeds it to the chrome collapse ($token)", token in text)
            else if (app !in secondaryWebViewsOnly) assertTrue("$app builds a WebView but is not in webScrollers: wire it to BottomNavCollapse.onScrollDelta (ChromeWebScroll) and list it", !webView.containsMatchIn(text))
        }
    }

    @Test fun `fragment pages hosted in a Compose shell hand their scroll to the chrome through the View parents`() {
        // A Fragment's ComposeView is its own composition: LocalNavChromeCollapse is empty there and nested scrolls stop at the
        // View boundary (the Store/Nav/C3 regression). KitPage is the one place every KitComposeFragment page passes through.
        val kit = File("ab_cloud-libs-shared/libs/ui-kit/src/main/kotlin/com/diegonmarcos/superapp/uikit/KitInterop.kt").let { f ->
            var dir: File? = File("").absoluteFile
            var found: File? = null
            while (dir != null && found == null) { File(dir, f.path).takeIf { it.isFile }?.let { found = it }; dir = dir.parentFile }
            found ?: throw AssertionError("KitInterop.kt not found")
        }.readText()
        assertTrue("KitPage no longer wires fragment pages to the View parents' nested scroll", "rememberNestedScrollInteropConnection()" in kit && "fun KitPage" in kit)
        assertTrue("KitPage no longer reports straight to the chrome connection published on the root View", "ChromeTagSink" in kit && "rootView.getTag(CHROME_SINK_TAG)" in kit)
        val tagRe = Regex("CHROME_SINK_TAG: Int = (0x[0-9a-fA-F]+)")
        var libDir: File? = File("").absoluteFile
        var scrollSrc: File? = null
        while (libDir != null && scrollSrc == null) { File(libDir, "ab_cloud-libs-shared/libs/bottomnav/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/NavChromeScroll.kt").takeIf { it.isFile }?.let { scrollSrc = it }; libDir = libDir.parentFile }
        assertEquals("the chrome sink tag differs between libs:bottomnav and libs:ui-kit", tagRe.find((scrollSrc ?: throw AssertionError("NavChromeScroll.kt not found")).readText())?.groupValues?.get(1), tagRe.find(kit)?.groupValues?.get(1))
        assertTrue("KitPage must wrap every page, with or without a host sink", !Regex("""sink == null\) content\(\)""").containsMatchIn(kit))
        // And the bridge into the host composition must stay: the hosting AndroidFragment/FragmentContainerView sits under the scaffold.
        for (app in listOf("ac_cloud-store", "ac_cloud-nav", "ac_cloud-c3")) {
            val text = sources(app).joinToString("\n") { it.readText() }
            assertTrue("$app hosts fragments outside the shared scaffold", "AndroidFragment<" in text || "FragmentContainerView(" in text)
            assertTrue("$app has no shared scaffold above its fragments", "NavBarTopFirstRowScaffold(" in text)
        }
    }

    @Test fun `ChromeWebScroll ends in the collapse connection`() {
        var dir: File? = File("").absoluteFile
        var f: File? = null
        while (dir != null && f == null) { File(dir, "ab_cloud-libs-shared/libs/bottomnav/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/ChromeWebScroll.kt").takeIf { it.isFile }?.let { f = it }; dir = dir.parentFile }
        val text = (f ?: throw AssertionError("ChromeWebScroll.kt not found")).readText()
        assertTrue("ChromeWebScroll no longer feeds onScrollDelta", "setOnScrollChangeListener" in text && "onScrollDelta(" in text && "addJavascriptInterface" in text)
    }

    /** Every file of an app's main sources, with its path relative to the app's main/java root. */
    private fun driveFiles(): List<File> = sources("ac_cloud-drive")

    @Test fun `every Cloud Drive page is routed through the one shell and its scroll container rides the shared connection`() {
        val files = driveFiles()
        val main = files.first { it.name == "MainActivity.kt" }.readText()
        val shell = files.first { it.name == "DriveShell.kt" }.readText()
        // The shell: NavBarBottomHost inside NavBarTopFirstRowScaffold, so the ONE connection (LocalNavChromeCollapse) reaches every page.
        assertTrue("DriveShell lost the shared scaffold", "NavBarTopFirstRowScaffold(" in shell && "NavBarBottomHost(" in shell)
        assertTrue("MainActivity must draw its pages through DriveShell", "DriveShell(" in main)
        // Each routed page: `"id" -> SomeScreen(...)` in MainActivity. Its package holds a scrollable Compose container.
        val routes = Regex("""^\s*"([a-z_]+)"\s*->\s*([A-Z]\w+)\(""", RegexOption.MULTILINE).findAll(main).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue("MainActivity routes no page?", routes.size >= 8)
        // A page whose scrolling lives in a shared lib (libs:ai's chat page) is checked by that lib; every other page must own a container here.
        val libOwned = setOf("ai")
        for ((id, screen) in routes.filter { it.first !in libOwned }) {
            val def = files.firstOrNull { Regex("""fun\s+$screen\(""").containsMatchIn(it.readText()) } ?: throw AssertionError("page $id: no definition of $screen")
            val pkg = files.filter { it.parentFile == def.parentFile }.joinToString("\n") { it.readText() }
            assertTrue("page $id ($screen) has no scrollable Compose container (LazyColumn / verticalScroll / pager) for the chrome to follow", Regex("""LazyColumn|LazyVerticalGrid|verticalScroll\(|HorizontalPager|VerticalPager""").containsMatchIn(pkg))
        }
        // No page owns a second connection or switches the chrome's state by hand: that is what detaches it from the shell.
        val own = Regex("""rememberBottomNavCollapse\(|NavChromeScroll\s*[({]|LocalNavChromeCollapse\s+provides|BottomNavCollapse\(\)""")
        val bad = files.filter { it.name != "DriveShell.kt" && own.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("a Drive page builds its own chrome connection", emptyList<String>(), bad)
    }

    /** The source of top-level function [name] in [text]: from its `fun` to the next top-level declaration. */
    private fun body(text: String, name: String): String {
        val start = Regex("""(?m)^(?:private |internal )?fun\s+$name\(""").find(text)?.range?.first ?: throw AssertionError("fun $name not found")
        val end = Regex("""(?m)^(?:@Composable|fun |private fun |internal fun |/\*\*)""").find(text, start + 8)?.range?.first ?: text.length
        return text.substring(start, end)
    }

    @Test fun `the Cloud Drive Fsync and Mounts pages are routed through the shell, carry a second row and their bodies own a scroll container (#1173)`() {
        val files = driveFiles()
        val main = files.first { it.name == "MainActivity.kt" }.readText()
        assertTrue("MainActivity does not route the fsync page", Regex("""^\s*"fsync"\s*->\s*FsyncPageScreen\(""", RegexOption.MULTILINE).containsMatchIn(main))
        assertTrue("MainActivity does not route the mounts page", Regex("""^\s*"mounts"\s*->\s*MountsPageScreen\(""", RegexOption.MULTILINE).containsMatchIn(main))
        assertTrue("the old rsync route is back", !Regex("""^\s*"rsync"\s*->""", RegexOption.MULTILINE).containsMatchIn(main))
        val fsync = files.first { it.name == "FsyncPage.kt" }.readText()
        val mounts = files.first { it.name == "MountsPage.kt" }.readText()
        // #1356 the title row is DECLARED per section (ui.sections[].title_row in Cloud Drive's build.json): false = no island,
        // the page starts with its strip under the shell's top bar; absent/true = the page draws its island.
        val build = files.firstOrNull { it.name == "build.json" }?.readText() ?: File(files.first().path.substringBefore("/app/src/") + "/build.json").readText()
        // mutation: the rule must reject a title-row-less section that draws an island, and one with a title row that draws none
        assertTrue("M: an island on a title_row=false section passed", titleRowProblem("X", "NavBarTopSecondRow(); ToolbarIsland(title = x)", false) != null)
        assertTrue("M: a title_row=true section without an island passed", titleRowProblem("X", "NavBarTopSecondRow()", true) != null)
        for ((page, text, section) in listOf(Triple("FsyncPageScreen", fsync, "fsync"), Triple("MountsPageScreen", mounts, "mounts"))) {
            val b = body(text, page)
            assertTrue("$page has no shared second row (NavBarTopSecondRow)", "NavBarTopSecondRow(" in b)
            titleRowProblem(page, b, titleRowOf(build, section))?.let { throw AssertionError(it) }
            assertTrue("$page does not draw its sub-pages from the declaration", "Declarations.navPages(" in b)
        }
        // each sub-page body owns the scrollable container the one connection follows (the Unison list is drawn by unisonSection inside FolderSyncScreen's LazyColumn)
        for ((screen, text) in listOf("FolderSyncScreen" to fsync, "RcloneRemotesScreen" to mounts, "FuseMountsScreen" to mounts))
            assertTrue("$screen has no LazyColumn for the chrome to follow", "LazyColumn(" in body(text, screen))
        val wizard = files.first { it.name == "RemoteWizard.kt" }.readText()
        assertTrue("the rclone wizard is a dialog, never a second page with its own chrome", "AlertDialog(" in wizard && "NavBarTopSecondRow(" !in wizard)
    }
}
