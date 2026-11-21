package com.diegonmarcos.cloudbrowser

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.browser.BrowserGridRow
import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.superapp.browser.BrowserTabGrid
import com.diegonmarcos.superapp.browser.TabCardA11y
import com.diegonmarcos.superapp.browser.TabThumbConfig
import com.diegonmarcos.superapp.browser.TabThumbnails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executor

/**
 * The Compose tab switcher: a card with a snapshot draws it (top-aligned crop); one without shows the favicon plate;
 * a horizontal swipe closes a card; a long-press drag inside a group reorders it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserTabSnapshotUiTest {

    private val tmp = TemporaryFolder()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val direct = Executor { it.run() }

    private fun tab(id: String, group: String = "", pinned: Boolean = false) =
        BrowserTab("https://$id.example/", id, 1L, id = id, group = group, pinned = pinned)

    private fun show(
        thumbs: TabThumbnails?, rows: List<BrowserGridRow>,
        onSwipeClose: (BrowserTab) -> Unit = {}, onReorder: (List<String>) -> Unit = {},
    ) {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                BrowserTabGrid(rows, onOpen = {}, onSwipeClose = onSwipeClose, onPin = {}, onMenu = {},
                    onToggleGroup = {}, onReorder = onReorder, thumbs = thumbs)
            }
        }
        compose.waitForIdle()
    }

    private fun cards(vararg tabs: BrowserTab) = tabs.map { BrowserGridRow.TabCard(it) }

    private fun count(desc: String) =
        compose.onAllNodesWithContentDescription(desc, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `a card with a snapshot renders the image and hides the fallback plate`() {
        val thumbs = TabThumbnails(tmp.newFolder("t"), TabThumbConfig(), direct, direct)
        thumbs.put("a", Bitmap.createBitmap(90, 160, Bitmap.Config.RGB_565).also { it.eraseColor(0xFFFF8800.toInt()) })
        show(thumbs, cards(tab("a")))

        assertEquals("one card shows its snapshot", 1, count(TabCardA11y.SNAPSHOT))
        assertEquals("the fallback plate is gone", 0, count(TabCardA11y.PLACEHOLDER))
    }

    @Test
    fun `the snapshot is cropped to fill the card and keeps its top edge`() {
        // Compose's ContentScale.Crop + Alignment.TopCenter: scaled to cover the card, the page's top edge kept.
        assertEquals(ContentScale.Crop, com.diegonmarcos.superapp.browser.TAB_SNAPSHOT_SCALE)
        assertEquals(Alignment.TopCenter, com.diegonmarcos.superapp.browser.TAB_SNAPSHOT_ALIGN)
    }

    @Test
    fun `a card without a snapshot renders the favicon plate`() {
        val thumbs = TabThumbnails(tmp.newFolder("t"), TabThumbConfig(), direct, direct)
        show(thumbs, cards(tab("b")))

        assertEquals("no snapshot drawn", 0, count(TabCardA11y.SNAPSHOT))
        assertEquals("the plate is there", 1, count(TabCardA11y.PLACEHOLDER))
    }

    @Test
    fun `a snapshot that lands from disk after the first draw replaces the plate`() {
        val dir = tmp.newFolder("t")
        TabThumbnails(dir, TabThumbConfig(), direct, direct).put("c", Bitmap.createBitmap(90, 160, Bitmap.Config.RGB_565).also { it.eraseColor(0xFFFF8800.toInt()) })   // #1841 a real page colour: an all-black picture is dropped on read
        val fresh = TabThumbnails(dir, TabThumbConfig(), direct, direct)   // empty memory, file on disk
        show(fresh, cards(tab("c")))
        compose.waitForIdle()
        assertEquals(1, count(TabCardA11y.SNAPSHOT))
        assertEquals(0, count(TabCardA11y.PLACEHOLDER))
    }

    @Test
    fun `a horizontal swipe on a card closes it`() {
        val closed = mutableListOf<String>()
        show(null, cards(tab("a"), tab("b")), onSwipeClose = { closed += it.key })
        compose.onNodeWithTag("browser:tabcard:a", useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf("a"), closed)
    }

    @Test
    fun `a long-press drag inside a group reorders it and reports the new order`() {
        var order: List<String>? = null
        val rows = listOf<BrowserGridRow>(BrowserGridRow.GroupHeader("g", false, 2)) + cards(tab("a", "g"), tab("b", "g"))
        show(null, rows, onReorder = { order = it })
        val card = compose.onNodeWithTag("browser:tabcard:a", useUnmergedTree = true)
        card.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(800)   // past the long-press timeout, on the test clock
        compose.waitForIdle()
        card.performTouchInput {
            moveBy(androidx.compose.ui.geometry.Offset(width / 4f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(width / 2f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(width / 2f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("b", "a"), order)
    }
}
