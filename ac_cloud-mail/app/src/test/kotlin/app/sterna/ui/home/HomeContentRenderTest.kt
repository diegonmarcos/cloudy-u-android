package app.sterna.ui.home

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.sterna.R
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.AccountHomeCounts
import app.sterna.core.jmap.model.Mailbox
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #501: the Home page RENDERED — [HomeContent] under Robolectric, over a mailbox this file builds and
 * pushes through the same [accountMailStatsList] the app uses, read the way a person reads it: the
 * numbers on screen, the empty states, the tap targets, the colour of the bar, and — the ticket's
 * hard constraint — what happens to the animation under battery saver and "Remove animations".
 *
 * This is how the page is verified to RENDER and not merely to compile: nothing here reads source
 * text. The clock is driven by hand ([manualClock]) for the animation cases so a frame in the middle
 * of the count-up can be observed. mdpi keeps px == dp. The plain Application stands in for
 * SternaApplication, whose onCreate wires the whole mail stack; the page needs none of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeContentRenderTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    // The app's manifest has no entry for the activity the compose rule launches.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private lateinit var hostView: View
    private val opened = mutableListOf<HomeDestination>()

    // ── the known mailbox ───────────────────────────────────────────────────────────────────────

    private fun mailbox(id: String, unread: Int, subscribed: Boolean = true) =
        Mailbox(id = id, name = id, role = null, unreadForList = unread, isSubscribed = subscribed)

    private fun account(id: String, protocol: MailProtocol = MailProtocol.JMAP, color: Int? = null) = StoredAccount(
        id = id,
        server = "$id.example.org",
        username = "$id@example.org",
        accountName = id,
        protocol = protocol,
        imapHost = "imap.example.org",
        color = color,
    )

    private val workRed = 0xFFE53935.toInt()
    private val homeBlue = 0xFF1E88E5.toInt()
    private val workOldest = 1_552_521_600_000L // 2019-03-14T00:00:00Z
    private val homeOldest = 1_625_097_600_000L // 2021-07-01T00:00:00Z

    /** Four accounts: two that hold mail (1 200 and 30 unread), an IMAP one, and a fresh empty one. */
    private fun knownMailbox(): HomeUi {
        val accounts = listOf(
            account("work", color = workRed),
            account("home", color = homeBlue),
            account("imap", protocol = MailProtocol.IMAP),
            account("fresh"),
        )
        return HomeUi(
            loaded = true,
            accounts = accountMailStatsList(
                accounts = accounts,
                foldersPerAccount = listOf(
                    listOf(mailbox("Inbox", 1_000), mailbox("Archive", 200), mailbox("Hidden", 0, subscribed = false)),
                    listOf(mailbox("Inbox", 30), mailbox("Sent", 0)),
                    listOf(mailbox("INBOX", 0)),
                    emptyList(),
                ),
                cachedMessages = mapOf("work" to 5_000, "home" to 240, "imap" to 100),
                unreadIsCounted = { it != "imap" },
                homeCounts = mapOf(
                    "work" to AccountHomeCounts("work", starred = 7, withAttachments = 33, recent = 41, oldest = workOldest),
                    "home" to AccountHomeCounts("home", starred = 1, withAttachments = 2, recent = 5, oldest = homeOldest),
                    "imap" to AccountHomeCounts("imap", starred = 0, withAttachments = 4, recent = 0, oldest = null),
                ),
            ),
        )
    }

    // ── harness ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Renders [ui]. [batterySaver] is the SYSTEM's real setting, put on the very PowerManager the
     * activity hands out, so the whole chain from the platform to the pixels is what runs.
     * [manualClock] stops the test clock so a frame part-way through an animation can be read.
     */
    private fun show(
        ui: HomeUi,
        batterySaver: Boolean = false,
        manualClock: Boolean = false,
        animatorScale: Float? = null,
    ) {
        val activity = compose.activity
        shadowOf(activity.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsPowerSaveMode(batterySaver)
        animatorScale?.let {
            Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, it)
        }
        compose.mainClock.autoAdvance = !manualClock
        compose.setContent {
            hostView = LocalView.current
            MaterialTheme { HomeContent(ui, onOpen = { opened += it }) }
        }
        // One frame lets the first-frame state flip apply; waitForIdle would wait on a clock that is
        // deliberately stopped.
        if (manualClock) compose.mainClock.advanceTimeByFrame() else compose.waitForIdle()
    }

    private fun textOf(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun date(millis: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    // ── every number is the store's ─────────────────────────────────────────────────────────────

    @Test
    fun `501 every number on the page is the one the mailbox holds`() {
        show(knownMailbox(), batterySaver = true)
        val expected = mapOf(
            "work" to mapOf("unread" to "1200", "cached" to "5000", "starred" to "7", "attachments" to "33", "recent" to "41", "folders" to "3", "subscribed" to "2"),
            "home" to mapOf("unread" to "30", "cached" to "240", "starred" to "1", "attachments" to "2", "recent" to "5", "folders" to "2", "subscribed" to "2"),
        )
        expected.forEach { (account, stats) ->
            stats.forEach { (key, value) ->
                compose.onNodeWithTag(statTag(account, key)).assertTextEquals(value)
            }
        }
        compose.onNodeWithTag(statTag("work", "oldest")).assertTextEquals(date(workOldest))
        compose.onNodeWithTag(statTag("home", "oldest")).assertTextEquals(date(homeOldest))
        compose.onNodeWithTag(HOME_HERO_NUMBER_TAG).assertTextEquals("1230")
        compose.onNodeWithTag(HOME_MOOD_TAG).assertTextEquals(string(R.string.home_mood_avalanche))
    }

    @Test
    fun `501 changing the mailbox changes the tiles — no tile is decoration`() {
        var ui by mutableStateOf(knownMailbox())
        compose.activity.let {
            shadowOf(it.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsPowerSaveMode(true)
        }
        compose.setContent { MaterialTheme { HomeContent(ui, onOpen = {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag(statTag("work", "starred")).assertTextEquals("7")
        compose.onNodeWithTag(HOME_HERO_NUMBER_TAG).assertTextEquals("1230")

        // The same accounts after mail arrived: work gains 10 starred and reads 1 000 fewer unread.
        val work = ui.accounts.first { it.accountId == "work" }
        ui = ui.copy(accounts = ui.accounts.map { if (it === work) work.copy(starred = 17, unread = 200) else it })
        compose.waitForIdle()

        compose.onNodeWithTag(statTag("work", "starred")).assertTextEquals("17")
        compose.onNodeWithTag(statTag("work", "unread")).assertTextEquals("200")
        compose.onNodeWithTag(HOME_HERO_NUMBER_TAG).assertTextEquals("230")
        compose.onNodeWithTag(HOME_MOOD_TAG).assertTextEquals(string(R.string.home_mood_heavy))
        compose.onNodeWithTag(statTag("home", "starred")).assertTextEquals("1")
    }

    // ── honest empty states ─────────────────────────────────────────────────────────────────────

    @Test
    fun `501 an account with no mail says so instead of drawing zeros`() {
        show(knownMailbox(), batterySaver = true)
        compose.onNodeWithTag(HOME_ACCOUNT_EMPTY_TAG).assertTextEquals(string(R.string.home_account_empty))
        listOf("unread", "cached", "starred", "attachments", "recent", "folders", "subscribed", "oldest").forEach {
            compose.onNodeWithTag(statTag("fresh", it)).assertDoesNotExist()
        }
        // …and the empty sentence appears once: the other three accounts hold mail.
        compose.onAllNodesWithTag(HOME_ACCOUNT_EMPTY_TAG).assertCountEquals(1)
    }

    @Test
    fun `501 an IMAP account's unread is not counted, never zero`() {
        show(knownMailbox(), batterySaver = true)
        compose.onNodeWithTag(statTag("imap", "unread")).assertTextEquals(string(R.string.home_stat_unread_unavailable))
        compose.onNodeWithTag(statTag("imap", "cached")).assertTextEquals("100")
        // The hero only adds what could be counted: 1 200 + 30, the IMAP account contributes nothing.
        compose.onNodeWithTag(HOME_HERO_NUMBER_TAG).assertTextEquals("1230")
    }

    @Test
    fun `501 a phone where nothing has synced shows no number and no inbox zero`() {
        val ui = HomeUi(
            loaded = true,
            accounts = accountMailStatsList(
                accounts = listOf(account("a"), account("b")),
                foldersPerAccount = listOf(emptyList(), emptyList()),
                cachedMessages = emptyMap(),
                unreadIsCounted = { true },
            ),
        )
        show(ui, batterySaver = true)
        compose.onAllNodesWithTag(HOME_HERO_NUMBER_TAG).assertCountEquals(0)
        compose.onNodeWithTag(HOME_MOOD_TAG).assertTextEquals(string(R.string.home_mood_nothing))
        compose.onAllNodesWithTag(HOME_ACCOUNT_EMPTY_TAG).assertCountEquals(2)
        compose.onAllNodesWithTag(barSegmentTag("a")).assertCountEquals(0)
    }

    @Test
    fun `501 no accounts, and not yet loaded, are different pages`() {
        show(HomeUi(loaded = true, accounts = emptyList()), batterySaver = true)
        compose.onAllNodesWithTag(HOME_MOOD_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(SHORTCUT_CIRCLE_TAG).assertCountEquals(0)
    }

    @Test
    fun `501 before the account list is read the page draws nothing at all`() {
        show(HomeUi(loaded = false), batterySaver = true)
        compose.onAllNodesWithTag(HOME_MOOD_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(SHORTCUT_CIRCLE_TAG).assertCountEquals(0)
    }

    // ── shortcuts and quickmarks: rendered from the declaration ─────────────────────────────────

    @Test
    fun `501 the shortcuts and quickmarks are the declared destinations and each opens its own`() {
        show(knownMailbox(), batterySaver = true)
        val shortcuts = HomeDestination.entries.filter { it.kind == HomeDestination.Kind.SHORTCUT }
        val quickmarks = HomeDestination.entries.filter { it.kind == HomeDestination.Kind.QUICKMARK }
        compose.onAllNodesWithTag(SHORTCUT_CIRCLE_TAG).assertCountEquals(shortcuts.size)
        compose.onAllNodesWithTag(QUICKMARK_TAG).assertCountEquals(quickmarks.size)
        shortcuts.indices.forEach { compose.onAllNodesWithTag(SHORTCUT_CIRCLE_TAG)[it].performClick() }
        // The chips sit in a horizontal scroller and most are off a 360 dp screen: fire the click
        // action itself rather than a touch at coordinates that are not on screen.
        quickmarks.indices.forEach {
            compose.onAllNodesWithTag(QUICKMARK_TAG)[it].performSemanticsAction(SemanticsActions.OnClick)
        }
        assertEquals("each tile opens the destination it was declared for, in declaration order", shortcuts + quickmarks, opened)
    }

    @Test
    fun `1751 Webmail is a full-width Home subpage row that is displayed, not only a chip in the scrolling row`() {
        show(knownMailbox(), batterySaver = true)
        val subpages = HomeDestination.entries.filter { it.subpage }
        assertEquals("Webmail is declared as a subpage", listOf(HomeDestination.WEBMAIL), subpages)
        compose.onAllNodesWithTag(SUBPAGE_TAG).assertCountEquals(subpages.size)
        compose.onAllNodesWithTag(SUBPAGE_TAG)[0].assertIsDisplayed()
        // nothing removed: the chip is still in the quickmarks row.
        compose.onAllNodesWithTag(QUICKMARK_TAG).assertCountEquals(
            HomeDestination.entries.count { it.kind == HomeDestination.Kind.QUICKMARK },
        )
        opened.clear()
        compose.onAllNodesWithTag(SUBPAGE_TAG)[0].performClick()
        assertEquals(listOf(HomeDestination.WEBMAIL), opened)
    }

    // ── animation: the ticket's hard constraint ─────────────────────────────────────────────────

    /** The hero's number part-way through its count-up, and at the end of it. */
    private fun heroAt(millisAfterStart: Long): Int {
        compose.mainClock.advanceTimeBy(millisAfterStart)
        return textOf(HOME_HERO_NUMBER_TAG).toInt()
    }

    @Test
    fun `501 with motion on, the hero really counts up — the animation exists`() {
        show(knownMailbox(), manualClock = true)
        val midway = heroAt(HOME_COUNT_UP_MS / 2L)
        assertTrue("half-way through the count-up the hero must be between 0 and 1230, was $midway", midway in 1 until 1230)
        assertEquals("…and it arrives at the real total", 1230, heroAt(HOME_COUNT_UP_MS * 3L))
    }

    @Test
    fun `501 battery saver holds the count-up still — the true number is on the first frame`() {
        show(knownMailbox(), batterySaver = true, manualClock = true)
        assertEquals("first frame, battery saver on: already the real total", 1230, textOf(HOME_HERO_NUMBER_TAG).toInt())
        assertEquals("half a count-up later it has not moved", 1230, heroAt(HOME_COUNT_UP_MS / 2L))
        compose.onNodeWithTag(statTag("work", "cached")).assertTextEquals("5000")
    }

    @Test
    fun `501 Remove animations holds the count-up still`() {
        show(knownMailbox(), animatorScale = 0f, manualClock = true)
        assertEquals(1230, textOf(HOME_HERO_NUMBER_TAG).toInt())
        assertEquals(1230, heroAt(HOME_COUNT_UP_MS / 2L))
    }

    @Test
    fun `501 once the count-up ends nothing is left animating`() {
        show(knownMailbox(), manualClock = true)
        compose.mainClock.advanceTimeBy(HOME_COUNT_UP_MS * 10L)
        // Every animation finished on its own (an endless one would keep the clock busy): the page
        // is idle, and idle costs no frames.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1230, textOf(HOME_HERO_NUMBER_TAG).toInt())
    }

    // ── colour: the bar is split by real share, in each account's own colour ────────────────────

    private fun pixels(b: Rect) =
        compose.runOnUiThread {
            Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888).also { hostView.draw(Canvas(it)) }
        }.let {
            Bitmap.createBitmap(it, b.left.roundToInt(), b.top.roundToInt(), b.width.roundToInt(), b.height.roundToInt())
                .asImageBitmap().toPixelMap()
        }

    private fun near(a: Color, b: Color) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue)) <= 4f / 255f

    @Test
    fun `501 the unread bar is split in proportion to the unread, in each account's own colour`() {
        show(knownMailbox(), batterySaver = true)
        val work = compose.onNodeWithTag(barSegmentTag("work")).fetchSemanticsNode().boundsInRoot
        val home = compose.onNodeWithTag(barSegmentTag("home")).fetchSemanticsNode().boundsInRoot
        assertEquals("only accounts with unread mail get a segment", 0, compose.onAllNodesWithTag(barSegmentTag("imap")).fetchSemanticsNodes().size)
        assertEquals("only accounts with unread mail get a segment", 0, compose.onAllNodesWithTag(barSegmentTag("fresh")).fetchSemanticsNodes().size)
        // 1 200 : 30 = 40 : 1
        assertEquals("work's segment is 40× home's", 40f, work.width / home.width, 1f)

        val workPixel = pixels(work).let { it[it.width / 2, it.height / 2] }
        val homePixel = pixels(home).let { it[it.width / 2, it.height / 2] }
        assertTrue("work's segment is painted in work's own red, was $workPixel", near(workPixel, Color(workRed)))
        assertTrue("home's segment is painted in home's own blue, was $homePixel", near(homePixel, Color(homeBlue)))
    }
}
