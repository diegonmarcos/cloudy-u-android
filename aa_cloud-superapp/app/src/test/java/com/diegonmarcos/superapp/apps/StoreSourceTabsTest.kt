package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.PhoneAppActions
import com.diegonmarcos.superapp.appstore.SourceResolver
import com.diegonmarcos.superapp.appstore.StoreSourceTabs
import com.diegonmarcos.superapp.appstore.R as StoreR
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #627 — THE SOURCE TABS ARE THE DECLARATION, not a list in Kotlin.
 *
 * Two mutations have to be red, and they are opposite ways of writing the same
 * bug:
 *
 * 1. a HARDCODED tab list — [`renders one tab per declared kind, whatever the
 *    declaration says`] hands the renderer a kind list it invented, including a
 *    store nothing in this repository has ever heard of. A strip built from a
 *    fixed five-item list draws five tabs and fails;
 * 2. a DECLARED KIND WITH NO TAB — the same test asserts the labels one-to-one
 *    and in order, so a renderer that filters, dedupes or truncates the declared
 *    kinds fails too.
 *
 * Plus the modelling this ticket had to get right rather than paper over:
 * Samsung's Galaxy Store is a real separate catalogue with no fetchable APK, and
 * Aurora Store is a CLIENT for Google Play's catalogue — so `aurora` aliases
 * `play` and its tab is every app with a play rung, which is asserted here
 * rather than asserted in a comment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreSourceTabsTest : KitPageHarness() {

    init { FleetPrivateFixtures.install() }   // #1566 the install-source map is a fleet-private dataset
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val sources: JSONObject get() = PhoneAppActions.sources(ctx)
    private val cfg get() = PhoneAppActions.resolver(sources)

    /** The pills the strip drew, left to right, as their labels. */
    private fun pills(): List<String> =
        compose.onAllNodes(tagStartsWith(StoreSourceTabs.TAG_PREFIX)).fetchSemanticsNodes().map { it.text() }

    @Test
    fun `renders one tab per declared kind, whatever the declaration says`() {
        // A declaration this repository does not contain. A hardcoded strip
        // cannot draw it, which is the whole point of passing it in.
        val invented = listOf(
            SourceResolver.Kind("vendor", "Direct", null, null, true),
            SourceResolver.Kind("fdroid", "F-Droid", "org.fdroid.fdroid", null, true),
            SourceResolver.Kind("samsung", "Samsung", "com.sec.android.app.samsungapps", null, false),
            SourceResolver.Kind("aurora", "Aurora", "com.aurora.store", "play", false),
            SourceResolver.Kind("play", "Google Play", "com.android.vending", null, false),
            SourceResolver.Kind("huawei", "AppGallery", "com.huawei.appmarket", null, false),
        )
        showKit { StoreSourceTabs.Strip(invented, null, { }) }
        assertEquals(
            "one tab per declared kind, in declared order, plus All",
            listOf(ctx.getString(StoreR.string.store_phone_source_all)) + invented.map { it.label },
            pills())
    }

    @Test
    fun `the selected tab is the one that reads as selected`() {
        val kinds = cfg.kinds
        val chosen = kinds.first { it.id == SourceResolver.KIND_FDROID }
        var picked: SourceResolver.Kind? = chosen
        showKit { StoreSourceTabs.Strip(kinds, chosen, { picked = it }) }
        val labels = StoreSourceTabs.labels(ctx, kinds)
        assertEquals("the strip renders every declared label", labels, pills())
        // Tapping All must hand back null — the absence of a source filter, not
        // a source called "All".
        compose.onNodeWithTag(StoreSourceTabs.TAG_PREFIX + labels.first()).performClick()
        assertNull("All is the absence of a filter", picked)
        compose.onNodeWithTag(StoreSourceTabs.TAG_PREFIX + chosen.label).performClick()
        assertEquals(chosen.id, picked?.id)
    }

    @Test
    fun `Samsung is a separate catalogue and Aurora is a client for Play's`() {
        val kinds = cfg.kinds.associateBy { it.id }
        val samsung = kinds.getValue("samsung")
        assertNull("Galaxy Store is its own catalogue — it aliases nothing", samsung.catalogue)
        assertFalse("Galaxy Store publishes no APK we can fetch", samsung.fetches)
        assertEquals("com.sec.android.app.samsungapps", samsung.installer)
        assertEquals("a samsung tab holds exactly the apps that declare samsung", "samsung", samsung.member)

        val aurora = kinds.getValue("aurora")
        assertEquals("Aurora serves Play's catalogue, so its tab is Play's app set",
            SourceResolver.KIND_PLAY, aurora.catalogue)
        assertEquals(SourceResolver.KIND_PLAY, aurora.member)
        assertFalse("Aurora is a client, not a feed we can version-compare", aurora.fetches)

        // Membership is DERIVED from the alias, not from a per-app duplicate of
        // the Play ladder: every app with a play rung is in the Aurora tab.
        val play = cfg.apps.values.filter { a -> a.sources.any { it.kind == SourceResolver.KIND_PLAY } }
        assertTrue("the declaration must hold at least one Play app", play.isNotEmpty())
        assertEquals("the Aurora tab is exactly the Play app set",
            play.map { it.pkg }.toSet(),
            cfg.apps.values.filter { it.inTab(aurora) }.map { it.pkg }.toSet())
    }

    @Test
    fun `every declared kind that cannot fetch names the store it hands off to`() {
        for (k in cfg.kinds) {
            if (k.fetches) continue
            assertTrue("${k.id} is a hand-off and must name its installer", !k.installer.isNullOrBlank())
            assertTrue("${k.id}'s installer must be in the #564 sources map, not written in code",
                sources.getJSONObject("sources").has(k.installer!!))
        }
    }

    @Test
    fun `a tab holds only apps whose declared ladder names that source`() {
        for (k in cfg.kinds) {
            for (app in cfg.apps.values) {
                val declares = app.sources.any { it.kind == k.member }
                assertEquals("${app.pkg} under the ${k.label} tab must declare ${k.member}",
                    declares, app.inTab(k))
            }
        }
    }
}
