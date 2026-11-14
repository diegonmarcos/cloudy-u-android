package app.sterna.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument as
 * [app.sterna.ui.inbox.CollapsedFoldersSurfaceWiringTest], for the same reason: nothing in this
 * module can run a Composable or a NavHost, so the wiring between the drawer's Home row and the
 * screen it opens exists nowhere a behavioural test can reach it. Every link of that chain can be
 * cut with one line while every other test in the repository stays green, and this file is what
 * stands over it.
 */
class HomeDestinationWiringTest {

    @Test fun `Home left the side pane, which is Inbox, Starred and Assigned`() {
        assertEquals(
            "the pane has a Home row again: the owner moved Home to the bottom nav (2026-10-13).",
            emptyList<String>(),
            codeLines(INBOX_SCREEN).filter { "R.string.home_title" in it && "DrawerLabel" in it },
        )
    }

    @Test fun `the row's callback resolves to the Home route, and the route to the Home screen`() {
        assertEquals(
            "the drawer's Home callback no longer navigates to the \"home\" route, or no longer " +
                "does it through the shared guard. Unguarded, a double tap during the drawer's " +
                "close animation pushes the screen twice (NavGuard); pointed at another route, the " +
                "row silently opens something else.",
            listOf("""onOpenHome = { entry.navigateOnce { nav.navigate("home") } },"""),
            block(STERNA_APP, "onOpenHome =", count = 1),
        )
        assertEquals(
            "the \"home\" route no longer resolves to HomeScreen with its shortcuts and quickmarks " +
                "wired (#501), or no longer pops back through the guard. A route declared but never " +
                "reached from the drawer, or a drawer row pointing at a route the NavHost does not " +
                "declare, is a row that does nothing at all — and nothing else in this repository " +
                "would say so. Each onOpenXxx below must resolve to a REAL route this NavHost " +
                "declares elsewhere: a shortcut or quickmark pointed at a route that does not exist " +
                "is a tap that does nothing, silently.",
            listOf(
                """composable("home") { entry ->""",
                "val homeContext = LocalContext.current",
                "val leaveOnce = rememberLeaveOnce(entry)",
                """BottomNavBar(nav = nav, currentRoute = "home") {""",
                "HomeScreen(",
                "onBack = { entry.navigateOnce { nav.popBackStack() } },",
                """onOpenCompose = { entry.navigateOnce { nav.navigate("compose") } },""",
                """onOpenSearch = { q -> entry.navigateOnce { nav.navigate("search?q=${'$'}{Uri.encode(q)}") } },""",
                """onOpenStarred = { entry.navigateOnce { nav.navigate("search?flagged=true") } },""",
                """onOpenAppSettings = { entry.navigateOnce { nav.navigate("settings") } },""",
                """onOpenScheduled = { entry.navigateOnce { nav.navigate("scheduled") } },""",
                """onOpenSnoozed = { entry.navigateOnce { nav.navigate("snoozed") } },""",
                """onOpenOutbox = { entry.navigateOnce { nav.navigate("outbox") } },""",
                """onOpenMailBySender = { entry.navigateOnce { nav.navigate("bysender") } },""",
                """onOpenRss = { entry.navigateOnce { nav.navigate("rss") } },""",
                """onOpenWebmail = { entry.navigateOnce { nav.navigate("webmail") } },""",
                "onOpenAgents = { leaveOnce { openAgents(homeContext) } },",
                "onOpenWorkflows = { leaveOnce { openWorkflows(homeContext) } },",
                "onOpenAi = { leaveOnce { openHermesAi(homeContext) } },",
                ")",
            ),
            block(STERNA_APP, """composable("home")""", count = 20),
        )
    }

    // ── instrument (copied from CollapsedFoldersSurfaceWiringTest) ───────────────────────────────

    /** [count] consecutive code lines starting [from] lines before the ONE line matching [prefix]. */
    private fun block(file: File, prefix: String, count: Int, from: Int = 0): List<String> {
        val lines = codeLines(file)
        val at = only(lines, prefix) + from
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** The index of the single code line starting with [needle]; fails loudly on none or several. */
    private fun only(lines: List<String>, needle: String): Int {
        val hits = lines.indices.filter { lines[it].startsWith(needle) }
        return hits.singleOrNull()
            ?: error(
                "${hits.size} code lines start with `$needle` — this lint reads the shipped source " +
                    "and must be taught the new shape rather than left green over something it " +
                    "never read",
            )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val INBOX_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        val STERNA_APP: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/SternaApp.kt") }
    }
}
