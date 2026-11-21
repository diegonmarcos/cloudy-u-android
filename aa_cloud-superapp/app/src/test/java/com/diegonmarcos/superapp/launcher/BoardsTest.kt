package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.boards.Boards
import com.diegonmarcos.superapp.boards.BoardsSource
import com.diegonmarcos.superapp.boards.BoardsWire
import com.diegonmarcos.superapp.boards.InboxItem
import com.diegonmarcos.superapp.boards.PlaceVisit
import com.diegonmarcos.superapp.boards.PlacesDay
import com.diegonmarcos.superapp.boards.Read
import com.diegonmarcos.superapp.boards.HealthAgg
import com.diegonmarcos.superapp.boards.HealthBoard
import com.diegonmarcos.superapp.boards.HealthFixtures
import com.diegonmarcos.superapp.health.HealthBoardReader
import com.diegonmarcos.superapp.boards.StepsBoard
import com.diegonmarcos.superapp.boards.StepsDay
import com.diegonmarcos.superapp.boards.TaskProject
import com.diegonmarcos.superapp.boards.TimelineBoard
import com.diegonmarcos.superapp.boards.TimelineSeg
import com.diegonmarcos.superapp.boards.Why
import com.diegonmarcos.superapp.uikit.KitPartTags
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Boards (was Projects): the rename and its aliases, Work-PM and C3-PM gone, Tasks in their place, and every board
 * drawn from its provider — a fake [BoardsSource] here, the owner apps over the fleet IPC door in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BoardsTest : KitPageHarness() {

    private val real = Boards.source
    private val H = 3_600_000L
    private val opened = mutableListOf<String>()

    private class Fake(
        val mail: Read<List<InboxItem>> = Read.Ok(emptyList()),
        val phone: Read<List<InboxItem>> = Read.Ok(emptyList()),
        val steps: Read<StepsBoard> = Read.Unavailable(Why.NOT_INSTALLED, "Cloud Nav is not installed"),
        val health: Read<HealthBoard> = Read.Unavailable(Why.NOT_INSTALLED, "Health Connect is not available on this phone"),
        val places: Read<List<PlacesDay>> = Read.Ok(emptyList()),
        val projects: Read<List<TaskProject>> = Read.Ok(emptyList()),
        val timeline: Read<TimelineBoard> = Read.Unavailable(Why.NOT_INSTALLED, "Cloud Nav is not installed"),
    ) : BoardsSource {
        var calls = 0
        override fun fleetMail(limit: Int) = mail.also { calls++ }
        override fun phoneNotifications() = phone.also { calls++ }
        override fun steps(days: Int) = steps.also { calls++ }
        override fun health(days: Int) = health.also { calls++ }
        override fun places(days: Int) = places.also { calls++ }
        override fun timeline(hours: Int) = timeline.also { calls++ }
        override fun taskProjects() = projects.also { calls++ }
    }

    private fun use(f: Fake): Fake { Boards.source = { f }; return f }

    @Before fun clear() = opened.clear()
    @After fun restore() { Boards.source = real }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    // ── the rename ──────────────────────────────────────────────────

    @Test fun `the section is Boards and the old id still resolves`() {
        val boards = Sections.byId("boards")
        assertNotNull(boards)
        assertEquals("Boards", boards!!.label)
        assertTrue("#1848 Boards left the bottom nav for Notify; Actions took its slot", "boards" !in Sections.bottomNavIds() && "actions" in Sections.bottomNavIds())
        assertFalse("bottom nav no longer names infos", "infos" in Sections.bottomNavIds())
        assertEquals("boards", Sections.byId("infos")?.id)
        assertEquals("boards", Sections.canonicalId("infos"))
        assertEquals("boards" to "my-pm", Sections.canonicalPage("infos", "my-pm"))
        // #1114: My-PM is now ME; the id and its deep links are kept, and `me` resolves too.
        assertEquals("boards" to "my-pm", Sections.canonicalPage("boards", "me"))
        assertEquals("ME", boards.allPages.first { it.id == "my-pm" }.label)
        assertTrue(Sections.all().none { it.label == "Projects" && it.id == "boards" })
    }

    @Test fun `Work-PM and C3-PM are gone and Tasks replaces them`() {
        val boards = Sections.byId("boards")!!
        assertEquals(listOf("msgs", "my-pm", "tasks", "resources", "quick-actions"), boards.allPages.map { it.id })
        assertTrue(boards.allPages.none { it.label in setOf("Work-PM", "C3-PM") })
        assertFalse("work-pm" in boards.stackByPage || "work-pm" in boards.tilesByPage)
        assertEquals("boards" to "tasks", Sections.canonicalPage("infos", "work-pm"))
        assertEquals("boards" to "tasks", Sections.canonicalPage("boards", "cloud"))
        val tasks = boards.stackByPage["tasks"].orEmpty().map { it.kind }
        // #1114: the tasks table moved here from ME, which has no calendar any more.
        assertEquals(listOf("board_task_projects", "tasks", "calendar_month"), tasks)
        val me = boards.stackByPage["my-pm"].orEmpty().map { it.kind }
        assertFalse("ME has no calendar", "calendar_month" in me)
        assertFalse("the tasks table left ME", "tasks" in me)
    }

    @Test fun `no board carries sample numbers`() {
        val boards = Sections.byId("boards")!!
        val panels = boards.stackByPage.values.flatten()
        assertTrue(panels.none { it.kind == "stats" })
        assertTrue(panels.all { it.rows.isEmpty() })
        val myPm = boards.stackByPage["my-pm"].orEmpty().map { it.kind }
        assertTrue("board_steps" in myPm && "board_timeline" in myPm)
        assertEquals("board_inbox", boards.stackByPage["msgs"].orEmpty().first().kind)
    }

    // ── each board reads its provider ───────────────────────────────

    @Test fun `Tasks lists the declared projects and opens one in cloud-tasks`() {
        val f = use(Fake(projects = Read.Ok(listOf(
            TaskProject("p1", "Work", 3, 1, "Ship boards", "2026-10-20"),
            TaskProject("p2", "Home", 0, 4, null, null),
        ))))
        showKit { BoardTaskProjectsBody(0) { opened += it } }
        waitForText("Work")
        compose.onNodeWithText("3 open · 1 done · Next: Ship boards · 2026-10-20").assertExists()
        compose.onNodeWithText("0 open · 4 done · Nothing due").assertExists()
        compose.onNodeWithTag(KitPartTags.listRow(BoardTags.project("p1"))).performClick()
        assertEquals(listOf("extapp:cloud-tasks#project:p1"), opened)
        assertTrue(f.calls > 0)
    }

    @Test fun `Tasks with no projects says so, and an absent app is not an empty list`() {
        use(Fake(projects = Read.Ok(emptyList())))
        showKit { BoardTaskProjectsBody(0) { opened += it } }
        waitForText("declares no projects")
    }

    @Test fun `an unreachable cloud-tasks shows the reason`() {
        use(Fake(projects = Read.Unavailable(Why.NOT_INSTALLED, "Cloud Tasks is not installed")))
        showKit { BoardTaskProjectsBody(0) { opened += it } }
        waitForText("Cloud Tasks is not installed")
    }

    @Test fun `MyHealth shows steps in km, today and the averages`() {
        val today = LocalDate.now()
        use(Fake(health = Read.Ok(HealthAgg.board(HealthFixtures.seeded(today), today))))
        showKit { BoardStepsBody(0) { opened += it } }
        waitFor("steps-chart")
        compose.onNodeWithTag("steps-chart").assertExists()
        compose.onNodeWithTag("rhr-chart").assertExists()
        compose.onNodeWithTag("sleep-chart").assertExists()
        compose.onNodeWithText("Today (11,000 steps)").assertExists()
    }

    @Test fun `MyHealth without the permissions says so and offers the grant`() {
        val today = LocalDate.now()
        val raw = HealthBoardReader.Raw(HealthBoardReader.Status.NEEDS_PERMISSION, emptySet())
        use(Fake(health = Read.Ok(HealthAgg.board(raw, today))))
        showKit { BoardStepsBody(0) { opened += it } }
        waitForText("needs read access")
        compose.onNodeWithText("Grant access").assertExists()
        compose.onNodeWithTag("health-perm:Sleep").assertExists()
    }

    @Test fun `MyHealth unavailable shows the reason`() {
        use(Fake(health = Read.Unavailable(Why.OUTDATED, "Health Connect needs an update before it can be read")))
        showKit { BoardStepsBody(0) { opened += it } }
        waitForText("needs an update")
    }

    @Test fun `MyTrips draws the last 24 h timeline in order`() {
        val t = 1_760_000_000_000L
        use(Fake(timeline = Read.Ok(TimelineBoard(t, 24, listOf(
            TimelineSeg("stop", t - 20 * H, t - 14 * H, "Home", 40.4, -3.7, 120, 0, "gps"),
            TimelineSeg("move", t - 14 * H, t - 13 * H, "", 40.41, -3.71, 2100, 1800, ""),
            TimelineSeg("stop", t - 13 * H, t - 5 * H, "Office, Madrid", 40.42, -3.69, 900, 0, "net"),
            TimelineSeg("move", t - 5 * H, t - 4 * H, "", 40.4, -3.7, 1500, 1400, ""),
            TimelineSeg("sleep", t - 4 * H, t - 1 * H, "Home", 40.4, -3.7, 10, 0, "gps"),
        )))))
        showKit { BoardTimelineBody(0) { opened += it } }
        waitFor(KitPartTags.listRow(BoardTags.tlRow(4, "sleep")))
        compose.onNodeWithTag(KitPartTags.listRow(BoardTags.TL_START)).assertExists()
        for ((i, k) in listOf("stop", "move", "stop", "move", "sleep").withIndex()) compose.onNodeWithTag(KitPartTags.listRow(BoardTags.tlRow(i, k))).assertExists()
        compose.onNodeWithText("Stop · Office, Madrid").assertExists()
        compose.onNodeWithText("Sleep · Home").assertExists()
        compose.onNodeWithTag(KitPartTags.listRow(BoardTags.tlRow(1, "move"))).performClick()
        assertEquals(listOf("extapp:cloud-nav"), opened)
    }

    @Test fun `MyTrips with an empty store is honest`() {
        use(Fake(timeline = Read.Ok(TimelineBoard(0, 24, emptyList()))))
        showKit { BoardTimelineBody(0) { opened += it } }
        waitForText("Nothing on the timeline")
    }

    @Test fun `MyTrips with Cloud Nav absent shows the reason`() {
        use(Fake(timeline = Read.Unavailable(Why.NOT_INSTALLED, "Cloud Nav is not installed")))
        showKit { BoardTimelineBody(0) { opened += it } }
        waitForText("Cloud Nav is not installed")
    }

    @Test fun `the tracker timeline wire is parsed and ordered`() {
        val body = """{"now":100,"hours":24,"segments":[
            {"kind":"move","start":50,"end":60,"place":"","steps":10,"distance_m":12,"loc_src":""},
            {"kind":"stop","start":10,"end":50,"place":"Home","lat":1.5,"lon":2.5,"steps":3,"distance_m":0,"loc_src":"net"}]}"""
        val b = BoardsWire.timeline(body)
        assertEquals(listOf("stop", "move"), b.segments.map { it.kind })
        assertEquals(1.5, b.segments[0].lat!!, 0.0)
        assertEquals(null, b.segments[1].lat)
        assertEquals("net", b.segments[0].locSrc)
        assertEquals(24, b.hours)
    }

    @Test fun `Navigation and Tracker lists the places per day`() {
        val d = LocalDate.of(2026, 10, 16)
        use(Fake(places = Read.Ok(listOf(PlacesDay(d, listOf(
            PlaceVisit("08:00–08:30", "Calle Mayor, Madrid", 40.4168, -3.7038, false),
            PlaceVisit("10:00–10:40", "Paseo de la Castellana, Madrid", 40.45, -3.69, false),
        ), 0)))))
        showKit { BoardPlacesBody(0) { opened += it } }
        waitForText("Calle Mayor, Madrid")
        compose.onNodeWithText("Paseo de la Castellana, Madrid").assertExists()
        compose.onNodeWithTag("places-map:$d").assertExists()
    }

    @Test fun `Navigation and Tracker with no fixes is honest`() {
        use(Fake(places = Read.Ok(listOf(PlacesDay(LocalDate.of(2026, 10, 16), emptyList(), 0)))))
        showKit { BoardPlacesBody(0) { opened += it } }
        waitForText("No places recorded")
    }

    private fun inboxState() = AggregatorState(
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>(),
        "board-inbox-test", emptyList(), emptyList())

    @Test fun `Inboxes groups fleet mail and phone apps by app`() {
        val now = System.currentTimeMillis()
        use(Fake(
            mail = Read.Ok(listOf(InboxItem(Boards.MAIL_PKG, "Cloud Mail", "Ana", "Invoice October", now - 60_000, true,
                "extapp:cloud-mail", fleet = true))),
            phone = Read.Ok(listOf(
                InboxItem("com.whatsapp", "WhatsApp", "Luis", "See you at ten", now - 120_000, true, "app:com.whatsapp", "k1"),
                InboxItem("com.whatsapp", "WhatsApp", "Marta", "ok!", now - 300_000, true, "app:com.whatsapp", "k2"),
                InboxItem(Boards.MAIL_PKG, "Cloud Mail", "dup", "dup", now, true, "extapp:cloud-mail", "k3", fleet = true),
            )),
        ))
        showKit { BoardInboxBody(inboxState()) { opened += it } }
        waitFor(BoardTags.group("com.whatsapp"))
        compose.onNodeWithTag(BoardTags.group(Boards.MAIL_PKG)).assertExists()
        compose.onNodeWithText("Invoice October").assertExists()
        compose.onNodeWithText("2 unread").assertExists()
        // Mail's own notification is the same message the provider gave: not drawn twice.
        assertEquals(0, compose.onAllNodesWithText("dup").fetchSemanticsNodes().size)
        compose.onNodeWithText("See you at ten").performClick()
        assertEquals(listOf("app:com.whatsapp"), opened)
    }

    @Test fun `every Inbox box carries every required action and the pills act`() {
        val now = System.currentTimeMillis()
        use(Fake(phone = Read.Ok(listOf(
            InboxItem("com.whatsapp", "WhatsApp", "Luis", "See you at ten", now - 120_000, true, "app:com.whatsapp", "k1"),
            InboxItem("org.thoughtcrime.securesms", "Signal", "Ana", "hola", now - 90_000, true, "app:org.thoughtcrime.securesms", "k2"),
        ))))
        val st = inboxState()
        showKit { BoardInboxBody(st) { opened += it } }
        waitFor(BoardTags.group("com.whatsapp"))
        for (pkg in listOf("com.whatsapp", "org.thoughtcrime.securesms")) {
            for (a in NotifActions.REQUIRED_PER_BOX) compose.onNodeWithTag(NotifActions.tag(a, pkg)).assertExists()
        }
        // Mark read: the box's unread count goes away.
        compose.onNodeWithTag(NotifActions.tag(NotifActions.MARK_READ, "com.whatsapp")).performClick()
        compose.waitForIdle()
        assertTrue(st.readIds.any { it.startsWith("board:com.whatsapp/") })
        // Open: the thread / app route.
        compose.onNodeWithTag(NotifActions.tag(NotifActions.OPEN, "org.thoughtcrime.securesms")).performClick()
        assertEquals(listOf("app:org.thoughtcrime.securesms"), opened)
        // Archive: the box leaves the list and keeps a Restore.
        compose.onNodeWithTag(NotifActions.tag(NotifActions.ARCHIVE, "com.whatsapp")).performClick()
        compose.waitForIdle()
        assertTrue(st.isArchived("board:com.whatsapp"))
        compose.onNodeWithTag(BoardTags.group("com.whatsapp")).assertDoesNotExist()
        compose.onNodeWithTag(NotifActions.tag("restore", "com.whatsapp")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(BoardTags.group("com.whatsapp")).assertExists()
    }

    @Test fun `Inboxes without notification access says why`() {
        use(Fake(phone = Read.Unavailable(Why.NO_PERMISSION, "Notification Access is off, so other apps' messages cannot be read")))
        showKit { BoardInboxBody(inboxState()) { opened += it } }
        waitForText("Notification Access is off")
    }

    // ── the wire ────────────────────────────────────────────────────

    @Test fun `the fleet routes' JSON parses`() {
        val s = BoardsWire.steps("""{"today":12,"days":[{"date":"2026-10-17","steps":12},{"date":"2026-10-16","steps":900}],
            "source":"DEVICE","status":"DEVICE_FALLBACK","status_line":"x","hint":""}""")
        assertEquals(12, s.today); assertEquals(LocalDate.of(2026, 10, 16), s.days.first().date); assertTrue(s.ready)
        val p = BoardsWire.places("""{"days":[{"date":"2026-10-17","visits":[{"part":"08:00","label":"A","lat":1.0,"lon":2.0,"predicted":true}]}]}""")
        assertEquals("A", p.single().visits.single().label)
        val t = BoardsWire.projects("""{"projects":[{"id":"p","name":"N","open":1,"done":2,"next_due_name":"","next_due":""}]}""")
        assertEquals(TaskProject("p", "N", 1, 2, null, null), t.single())
    }
}
