package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1544 - a strip's pane child must never outlive the pane host it was committed into.
 *
 * Crash on the phone: "No view found for id section_pane_0 for fragment WifiFragment", raised from
 * Fragment.performViewCreated -> childFragmentManager.dispatchViewCreated. The pane hosts are AndroidViews Compose builds
 * AFTER that dispatch, so a child still registered when the strip's view is re-created has no container.
 *
 *   R1 a strip whose view is re-created (back-stack return; savedInstanceState is null) does not crash and holds no stale pane child
 *   R2 an Activity recreate (state restore) does not crash either
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SectionTabsPaneRestoreTest : KitPageHarness() {

    /** Stands in for WifiFragment: any page with a view of its own. */
    class PanePage : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View = View(requireContext())
    }

    class Other : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View = View(requireContext())
    }

    private fun stripWithPage(): SectionTabsFragment {
        val strip = show(SectionTabsFragment.newInstance("config"))
        compose.runOnUiThread {
            strip.childFragmentManager.beginTransaction().replace(R.id.section_pane_0, PanePage()).commitNow()
        }
        compose.waitForIdle()
        assertTrue("the page sits in pane 0", strip.childFragmentManager.findFragmentById(R.id.section_pane_0) is PanePage)
        return strip
    }

    @Test
    fun `R1 a re-created strip view drops its pane child instead of crashing`() {
        val strip = stripWithPage()
        compose.runOnUiThread {
            val fm = compose.activity.supportFragmentManager
            fm.beginTransaction().replace(android.R.id.content, Other()).addToBackStack(null).commit()
            fm.executePendingTransactions()
            // Before #1544 this threw IllegalArgumentException: No view found for id section_pane_0.
            fm.popBackStackImmediate()
        }
        compose.waitForIdle()
        assertTrue("the strip is back with a view", strip.isAdded && strip.view != null)
        assertEquals("no stale pane child survived the re-created view", 0,
            strip.childFragmentManager.fragments.count { it is PanePage && it.view == null })
    }

    @Test
    fun `R2 an activity recreate restores the strip without crashing`() {
        stripWithPage()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        val strip = compose.activity.supportFragmentManager.fragments.filterIsInstance<SectionTabsFragment>().single()
        assertTrue("restored strip has a view", strip.view != null)
        assertEquals("restored pane children were dropped", 0,
            strip.childFragmentManager.fragments.count { it is PanePage })
    }
}
