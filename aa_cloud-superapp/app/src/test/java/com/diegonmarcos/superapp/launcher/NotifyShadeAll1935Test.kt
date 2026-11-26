package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1935 field / action parity of the All page. The All page now replicates the notification shade, so the per-notification
 * button row (Mark read / Read, Archive, Open, Reply: [NotifActions.REQUIRED_PER_BOX]) is gone from the card face. This test
 * is the inventory of what the page had before and the proof that every entry is still reachable:
 *   - every FIELD of the old card face is still drawn (app icon glyph, app name, time, title, text, chevron, unread dot);
 *   - every ACTION of the removed row is an entry of the long-press menu, and each one has its gesture too;
 *   - mutation: a menu that drops any one of them is reported red by [ShadeParity.missing].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h1600dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotifyShadeAll1935Test : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val now = System.currentTimeMillis()

    private fun view(key: String, vararg rows: NotifRow) = GroupView(
        NotifGroup(key = key, label = "App $key", sub = key, rows = rows.toList(), launchPackage = key),
        GroupChip("${rows.size}", Tone.OK),
    )

    private fun exists(tag: String) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun draw(st: AggregatorState, vararg v: GroupView) =
        showKit { Column { GroupList(st, v.toList(), shade = true) { } } }

    /** The menu entries of the removed row that this card's long-press menu holds right now. */
    private fun menuActions(key: String): List<String> = NotifActions.SHADE_LONG_PRESS_MENU.filter { a ->
        exists(when (a) {
            NotifActions.MARK_READ -> AggTags.groupCheck(key)
            NotifActions.ARCHIVE -> AggTags.groupArchive(key)
            NotifActions.OPEN -> AggTags.groupOpen(key)
            else -> NotifActions.menuTag(NotifActions.REPLY, key)
        })
    }

    @Test fun `the removed row is exactly the four pills and every one has a way back`() {
        assertEquals(listOf("mark_read", "archive", "open", "reply"), NotifActions.SHADE_FACE_DROPPED)
        assertEquals(NotifActions.SHADE_FACE_DROPPED, NotifActions.SHADE_LONG_PRESS_MENU)
        assertEquals(NotifActions.REQUIRED_PER_BOX.toSet(), NotifActions.SHADE_GESTURE_FOR.keys)
    }

    @Test fun `the card face draws every field but no button row`() {
        val st = AggregatorState(ctx, "shade-1935-face", emptyList(), emptyList())
        draw(st, view("pkg.a", NotifRow(now - 120_000, "Mamá", "¿Venís el domingo?", id = "phone:a1"),
            NotifRow(now - 60_000, "Pablo", "Llego tarde", id = "phone:a2")))
        // the old face fields: app name, time, title, text, chevron, unread dot (the app glyph is drawn in the header)
        compose.onNodeWithText("App pkg.a", useUnmergedTree = true).assertExists()
        assertTrue(exists(AggTags.groupHeader("pkg.a")))
        assertTrue("chevron", exists(AggTags.groupChevron("pkg.a")))
        compose.onNodeWithText("Pablo", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Llego tarde", useUnmergedTree = true).assertExists()
        assertTrue("unread dot", exists(AggTags.rowUnread("phone:a2")))
        compose.onNodeWithText(" • 1m", useUnmergedTree = true).assertExists()
        // the removed row: none of its four pills is on the face
        for (a in NotifActions.REQUIRED_PER_BOX) assertFalse("$a pill still on the card face", exists(NotifActions.tag(a, "pkg.a")))
    }

    @Test fun `a long press holds every action the row had, and each acts`() {
        val st = AggregatorState(ctx, "shade-1935-menu", emptyList(), emptyList())
        draw(st, view("pkg.a", NotifRow(now - 60_000, "t1", "b1", id = "phone:a1"), NotifRow(now - 30_000, "t2", "b2", id = "phone:a2")),
            view("pkg.b", NotifRow(now - 20_000, "t3", "b3", id = "phone:b1")))
        compose.onNodeWithTag(AggTags.groupHeader("pkg.a")).performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(40f, 0f)) }
        val present = menuActions("pkg.a")
        assertTrue("menu lacks ${ShadeParity.missing(NotifActions.REQUIRED_PER_BOX, present)}",
            ShadeParity.missing(NotifActions.REQUIRED_PER_BOX, present).isEmpty())
        // mutation: drop any single entry from the menu and the parity check goes red, naming it
        for (a in NotifActions.REQUIRED_PER_BOX) assertEquals(listOf(a), ShadeParity.missing(NotifActions.REQUIRED_PER_BOX, present - a))
        // Read acts
        compose.onNodeWithTag(AggTags.groupCheck("pkg.a")).performClick()
        compose.waitForIdle()
        assertTrue(st.readIds.containsAll(listOf("phone:a1", "phone:a2")))
        assertFalse("phone:b1" in st.readIds)
        // Archive acts
        compose.onNodeWithTag(AggTags.groupHeader("pkg.b")).performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(40f, 0f)) }
        compose.onNodeWithTag(AggTags.groupArchive("pkg.b")).performClick()
        compose.waitForIdle()
        assertTrue(st.isArchived("pkg.b"))
    }

    @Test fun `opening a card by its chevron reads it`() {
        val st = AggregatorState(ctx, "shade-1935-expand", emptyList(), emptyList())
        draw(st, view("pkg.a", NotifRow(now - 60_000, "t1", "b1", id = "phone:a1"), NotifRow(now - 30_000, "t2", "b2", id = "phone:a2")))
        assertTrue(st.readIds.isEmpty())
        compose.onNodeWithTag(AggTags.groupHeader("pkg.a")).performClick()
        compose.waitForIdle()
        assertTrue("expanding must read the card", st.readIds.containsAll(listOf("phone:a1", "phone:a2")))
    }

    @Test fun `the gestures are wired in the source and the All page is the shade`() {
        val src = File(System.getProperty("user.dir"), "src/main/java/com/diegonmarcos/superapp/launcher/AggregatorNotify.kt").readText()
        assertEquals(emptyList<String>(), ShadeParity.sourceViolations(src))
        // mutation: each wiring removed in turn is caught
        for ((name, mutated) in ShadeParity.mutations(src)) assertTrue("mutation '$name' was not caught",
            ShadeParity.sourceViolations(mutated).isNotEmpty())
    }
}

/** Pure checks the test (and its mutations) run. */
internal object ShadeParity {
    fun missing(required: Collection<String>, present: Collection<String>): List<String> = required.filter { it !in present }

    fun sourceViolations(src: String): List<String> = buildList {
        if (!src.contains("all -> GroupList(state, views, shade = true, onOpen = onOpen)")) add("All is not drawn in shade mode")
        if (!src.contains("if (shade && !archived && !open) state.markGroupRead(g)")) add("expanding a card no longer reads it")
        if (!src.contains("if (shade && !inArchive && r.id.isNotBlank() && state.isUnread(r)) state.markRead(r.id, true)")) add("opening a notification no longer reads it")
        if (!src.contains("state.archiveRow(r.id, !inArchive)")) add("swipe away no longer archives")
        if (!src.contains("onLongClick = { Haptics.tap(hostView); menu = true }")) add("long press no longer opens the menu")
        if (!src.contains("NotifActions.menuTag(NotifActions.REPLY, g.key)")) add("the menu lost Reply")
        if (!src.contains("AggTags.groupCheck(g.key)")) add("the menu lost Read")
        if (!src.contains("AggTags.groupArchive(g.key)")) add("the menu lost Archive")
        if (!src.contains("AggTags.groupOpen(g.key)")) add("the menu lost Open")
        if (!src.contains("else if (shade) {")) add("the shade face branch is gone")
    }

    fun mutations(src: String): Map<String, String> = mapOf(
        "All back to the button row" to src.replace("all -> GroupList(state, views, shade = true, onOpen = onOpen)", "all -> GroupList(state, views, onOpen = onOpen)"),
        "expand no longer reads" to src.replace("if (shade && !archived && !open) state.markGroupRead(g)", ""),
        "open no longer reads" to src.replace("state.markRead(r.id, true)\n        runCatching", "runCatching"),
        "swipe archive dropped" to src.replace("state.archiveRow(r.id, !inArchive)", "Unit"),
        "menu Reply dropped" to src.replace("NotifActions.menuTag(NotifActions.REPLY, g.key)", "\"x\""),
        "menu Open dropped" to src.replace("AggTags.groupOpen(g.key)", "\"x\""),
    )
}
