package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1794 the All page's persistent badges: grouped under the declared categories, and each row walks COLLAPSED → FIRST
 * (shade state 1) → SECOND (shade state 2) → COLLAPSED by its right-side arrow or a swipe down on its box only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class BadgeAllPageStatesTest : KitPageHarness() {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun clean() {
        ctx.getSharedPreferences("notify_all_badge_stage", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun render() {
        showKit {
            Column(Modifier.fillMaxSize().background(NotifCard.panel).padding(12.dp).verticalScroll(rememberScrollState())) {
                BadgeCards(summary = true)
            }
        }
        compose.waitForIdle()
    }

    private fun has(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun node(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true)[0]

    private fun stageOnScreen(id: String): BadgeExpansion.Stage = when {
        has(BadgeCardTags.summarySecond(id)) -> BadgeExpansion.Stage.SECOND
        has(BadgeCardTags.summaryFirst(id)) -> BadgeExpansion.Stage.FIRST
        else -> BadgeExpansion.Stage.COLLAPSED
    }

    @Test fun `the persistent badges are grouped by the declared categories`() {
        render()
        val sections = badgeSections(ctx)
        for (s in sections) {
            if (s.title.isNotBlank()) assertTrue("no ${s.id} subtitle", has(BadgeCardTags.summarySection(s.id)))
            for (b in s.badges) assertTrue("${b.id} not on the All page", has(BadgeCardTags.summary(b.id)))
        }
    }

    @Test fun `the arrow walks every row through its three states and back`() {
        render()
        for (b in orderedBadges(ctx)) {
            val id = b.id
            assertEquals(id, BadgeExpansion.Stage.COLLAPSED, stageOnScreen(id))
            for (want in listOf(BadgeExpansion.Stage.FIRST, BadgeExpansion.Stage.SECOND, BadgeExpansion.Stage.COLLAPSED)) {
                node(BadgeCardTags.summaryArrow(id)).performScrollTo().performClick()
                compose.waitForIdle()
                assertEquals(id, want, stageOnScreen(id))
                assertEquals("$id stage not remembered", want, BadgeExpansion.stage(ctx, id))
            }
        }
    }

    @Test fun `a swipe down on the box expands, a tap or a sideways swipe does not`() {
        render()
        val id = orderedBadges(ctx).first().id
        node(BadgeCardTags.summary(id)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("a tap keeps its meaning and does not expand", BadgeExpansion.Stage.COLLAPSED, stageOnScreen(id))
        node(BadgeCardTags.summaryBox(id)).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals("sideways is not a swipe down", BadgeExpansion.Stage.COLLAPSED, stageOnScreen(id))
        node(BadgeCardTags.summaryBox(id)).performTouchInput { swipeDown(startY = top, endY = top + 300f) }
        compose.waitForIdle()
        assertEquals(BadgeExpansion.Stage.FIRST, stageOnScreen(id))
        node(BadgeCardTags.summaryBox(id)).performTouchInput { swipeDown(startY = top, endY = top + 300f) }
        compose.waitForIdle()
        assertEquals(BadgeExpansion.Stage.SECOND, stageOnScreen(id))
    }
}
