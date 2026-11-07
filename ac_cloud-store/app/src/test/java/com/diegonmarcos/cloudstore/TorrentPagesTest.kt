package com.diegonmarcos.cloudstore

import android.app.Application
import android.content.ComponentName
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.appstore.StoreFeedPanels
import com.diegonmarcos.superapp.uikit.KitDenseMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * The Torrent destination, Configs > Torrent and the Store's nav, as the shell hosts them (a bare KitDenseMaterial + Surface), with
 * no device and no peers. On the JVM libtorrent cannot load, which is the point: the engine reports Unavailable in words and the
 * pages still draw (the degraded path), instead of the app dying.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TorrentPagesTest {
    private val compose = createAndroidComposeRule<FragmentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, FragmentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    @Test fun `the bottom nav has Torrent and not Feed, and Search has the three source subpages`() {
        val nav = MainActivity.NAV
        val ids = nav.sections.map { it.id }
        assertEquals(listOf("cloud", "phone", "search", "torrent", "access"), nav.bottomNav)
        assertFalse("Feed is no destination any more", "feed" in ids)
        assertEquals(listOf("search-cloudy", "search-android", "search-torrent"), nav.section("search")!!.pages.map { it.id })
        assertEquals("Torrent", nav.section("torrent")!!.label)
        assertTrue("Configs has a Torrent page", nav.section("settings")!!.pages.any { it.id == ConfigsPages.TORRENT })
    }

    @Test fun `the Torrent page draws the add bar, both lists and says the engine is unavailable on this device`() {
        compose.setContent { KitDenseMaterial(darkColorScheme()) { Surface(Modifier.fillMaxSize()) { TorrentPage() } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithTagCount(TorrentTags.TOTALS) > 0 }
        for (tag in listOf(TorrentTags.PAGE, TorrentTags.MAGNET_FIELD, TorrentTags.ADD, TorrentTags.IMPORT, TorrentTags.ACTIVE, TorrentTags.COMPLETED, TorrentTags.TOTALS))
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Active (0)", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Completed (0)", useUnmergedTree = true).assertExists()
        // no libtorrent on the JVM: a sentence, not a crash
        compose.waitUntil(15_000) { compose.onAllNodesWithTagCount(TorrentTags.STATUS) > 0 }
        compose.onNodeWithTag(TorrentTags.STATUS, useUnmergedTree = true).assertTextContains("BitTorrent engine unavailable", substring = true)
    }

    @Test fun `Configs Torrent shows the default folder, the drive choice, the import and the grant`() {
        compose.setContent { KitDenseMaterial(darkColorScheme()) { Surface(Modifier.fillMaxSize()) { ConfigsPage(ConfigsPages.TORRENT) } } }
        compose.waitForIdle()
        for (tag in listOf(TorrentConfigTags.PATH, TorrentConfigTags.DEFAULT_NOTE, TorrentConfigTags.USE_DEFAULT, TorrentConfigTags.CHOOSE,
            TorrentConfigTags.DRIVES, TorrentConfigTags.IMPORT, TorrentConfigTags.SEED, TorrentConfigTags.ENGINE))
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(TorrentConfigTags.DEFAULT_NOTE, useUnmergedTree = true).assertTextContains("Downloads/Cloudy-store/Torrent", substring = true)
        compose.onNodeWithTag(TorrentConfigTags.PATH, useUnmergedTree = true).assertTextContains("Cloudy-store/Torrent", substring = true)
    }

    @Test fun `the Cloud page's Commits and CI-CD are buttons that open a page each`() {
        compose.setContent { KitDenseMaterial(darkColorScheme()) { Surface(Modifier.fillMaxSize()) { 
            var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
            val o = open
            if (o == null) StoreFeedPanels.Buttons { open = it } else StoreFeedPanels.Page(o, onBack = { open = null }) } } }
        compose.waitForIdle()
        compose.onNodeWithTag(StoreFeedPanels.TAG_PANELS, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(StoreFeedPanels.buttonTag("commits"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(StoreFeedPanels.buttonTag("cicd"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(StoreFeedPanels.tag("commits"), useUnmergedTree = true).assertDoesNotExist()   // no inline dropdown
        compose.onNodeWithTag(StoreFeedPanels.buttonTag("cicd"), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(StoreFeedPanels.pageTag("cicd"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(StoreFeedPanels.BACK_TAG, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(StoreFeedPanels.buttonTag("commits"), useUnmergedTree = true).assertExists()
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(false).size
}
