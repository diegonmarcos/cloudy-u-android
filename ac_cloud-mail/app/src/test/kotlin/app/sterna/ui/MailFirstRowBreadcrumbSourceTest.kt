package app.sterna.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT (nothing in this module can run a Composable, see [BottomNavSharedSourceTest]) over the Mail first row.
 * The row itself, its left-aligned title, the absent hamburger and the breadcrumb dropdown are rendered and asserted in
 * libs:ui-nav-bars's own tests (NavBarTopFirstRowTest, BreadcrumbTest), which run inside THIS module's unit
 * suite; what only Mail can break is the wiring below: it must feed the shared row from its nav declaration, draw no
 * hamburger or drawer of its own, and keep every section reachable (bottom island, dropdown) once the drawer is gone.
 */
class MailFirstRowBreadcrumbSourceTest {
    private val bar = codeLines("app/src/main/kotlin/app/sterna/ui/MailBottomNav.kt")

    @Test
    fun `the first row has no hamburger and no drawer of Mail's own`() {
        assertFalse("Mail draws a hamburger icon", bar.any { Regex("""Icons\.\S*Menu\b""").containsMatchIn(it) })
        assertFalse("Mail fills the removed side drawer again", bar.any { it.contains("TopBarDrawer") })
        assertFalse("Mail opens the drawer", bar.any { it.contains("openDrawer") || it.contains("onMenu") })
    }

    @Test
    fun `the title path comes from the nav declaration, not from hand-coded pages`() {
        assertTrue("the shared first row scaffold is not used", bar.any { it.startsWith("NavBarTopFirstRowScaffold(") })
        assertTrue("the hierarchy is not derived from the declared section (titleHierarchy)",
            bar.any { it.contains("titleHierarchy(") && it.contains("currentRoute") })
        assertTrue("every declared section (bar and off-bar) is not handed to the row (peers in the title dropdown, #1483)",
            bar.any { it.contains("sections = mailNav.sections") })
        assertFalse("the first row is switched back to the centred island", bar.any { it.contains("onTitleClick") })
    }

    @Test
    fun `every section the drawer listed stays reachable through the bottom island`() {
        assertTrue("the island is no longer fed by every declared section",
            bar.any { it.startsWith("entries = entries") } && bar.any { it.contains("mailNav.bottomSections().map") })
    }

    private fun codeLines(relPath: String): List<String> =
        File(root, relPath).readLines().map { it.trim() }.filterNot {
            it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }

    private companion object {
        private const val STERNA_APP_HINT = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, STERNA_APP_HINT).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
    }
}
