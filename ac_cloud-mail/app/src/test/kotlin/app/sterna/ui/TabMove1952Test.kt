package app.sterna.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1952: a bottom-bar tap never builds a second mail list. Mutations: workflow mail-cache-1952-mutation. */
class TabMove1952Test {
    /** The back stack as the plan moves it: what NavController does with Return (pop to the entry) and Open (pop to [above], push). */
    private fun walk(stack: List<String>, target: String): List<String> = when (val move = tabMove(target, stack.last())) {
        TabMove.Stay -> stack
        is TabMove.Return -> stack.subList(0, stack.indexOf(move.route) + 1)
        is TabMove.Open -> stack.subList(0, stack.indexOf(move.above) + 1) + move.route
    }

    @Test
    fun `returning to Mail goes back to the list entry already on the stack, never a new one`() {
        assertEquals(TabMove.Return("inbox"), tabMove("inbox", "rss"))
        var stack = listOf("inbox")
        repeat(3) {
            stack = walk(stack, "rss")
            assertEquals(listOf("inbox", "rss"), stack)
            stack = walk(stack, "inbox")
            assertEquals("three round trips leave ONE list entry, so one ViewModel and its loaded pages", listOf("inbox"), stack)
        }
    }

    @Test
    fun `every other page opens directly above the list entry, so the stack never grows with the taps`() {
        var stack = listOf("inbox")
        for (page in listOf("rss", "tasks", "home", "contacts", "rss", "home")) {
            stack = walk(stack, page)
            assertEquals(listOf("inbox", page), stack)
        }
        assertEquals(TabMove.Stay, tabMove("home", "home"))
    }

    @Test
    fun `the bar's tap handler moves through the plan and no longer pushes the route`() {
        val nav = repoFile("app/src/main/kotlin/app/sterna/ui/MailBottomNav.kt").readText()
        val tap = nav.substringAfter("private fun onItemTap(").substringBefore("BottomNavAction.LAUNCH")
        assertTrue("onItemTap must apply tabMove(route, currentRoute) through moveToTab", "val move = tabMove(route, currentRoute)" in tap && "nav.moveToTab(move)" in tap)
        assertTrue("onItemTap must not push the route itself: that is the second list ViewModel of #1952", "nav.navigate(route)" !in tap)
        val plan = repoFile("app/src/main/kotlin/app/sterna/ui/TabMove.kt").readText()
        assertTrue(
            "Return must pop back to the list entry (state of the page left saved), with a push only as the fallback",
            "is TabMove.Return -> if (!popBackStack(move.route, inclusive = false, saveState = true)) navigate(move.route)" in plan,
        )
    }

    private fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, path) }.firstOrNull { it.isFile }
            ?: error("cannot find $path from ${File("").absolutePath}")
}
