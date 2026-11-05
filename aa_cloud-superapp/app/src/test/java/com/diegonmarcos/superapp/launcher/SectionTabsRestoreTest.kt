package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.configs.WifiFragment
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Crash "No view found for id section_pane_0 for fragment WifiFragment": the pane hosts are created by Compose after the child
 * FragmentManager restores, so a restored child had no container. Save with WifiFragment in section_pane_0, recreate the
 * activity, and the strip must come back without crashing and draw its tabs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SectionTabsRestoreTest : KitPageHarness() {

    @Test
    fun `R1 a restored child fragment in section_pane_0 does not crash the recreate`() {
        val section = Sections.all().first { s -> s.pages.count { it.action.isBlank() } >= 2 && s.pages.size <= SectionTabsFragment.MAX_PANES }
        val strip = show(SectionTabsFragment.newInstance(section.id))
        compose.runOnUiThread {
            strip.childFragmentManager.beginTransaction()
                .replace(R.id.section_pane_0, WifiFragment()).commitNowAllowingStateLoss()
        }
        compose.waitForIdle()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        for (p in section.pages) compose.onNodeWithTag(PageTabsTags.tab(p.id)).assertExists()
        compose.runOnUiThread {
            val again = compose.activity.supportFragmentManager.fragments.filterIsInstance<SectionTabsFragment>().single()
            assertNull(again.childFragmentManager.findFragmentById(R.id.section_pane_0))
        }
    }
}
