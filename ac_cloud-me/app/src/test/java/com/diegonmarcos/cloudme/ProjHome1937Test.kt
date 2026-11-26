package com.diegonmarcos.cloudme

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * #1937 Proj > Home and the failed remote page, with the NETWORK OFF (Robolectric has none; the fetch used in these tests throws):
 *  - every banner is a bundled asset that is a real image, read without touching the network; a banner whose asset is lost and whose fetch fails is null (the screen then draws its titled panel);
 *  - the rendered Home draws one banner node of the banner's height per category and one icon per project, tile labels carry no emoji, no tile uses the shared globe;
 *  - a failed remote page (Lnktree) draws a visible error with Retry, never a blank page.
 * test/test-proj-1937.sh holds the same rules on the declarations and mutates each red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjHome1937Test {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val noNetwork: (String) -> ByteArray? = { throw java.io.IOException("network is off") }

    @Test fun `every banner is a bundled image read with the network off`() {
        val entries = ProjBanners.entries(ctx)
        assertEquals(setOf("pj-unix", "pj-cg", "pj-capital", "pj-hardtech", "pj-wiki", "pj-projects", "pj-lecole42", "pj-repos"), entries.keys)
        var fetched = 0
        for ((id, e) in entries) {
            assertTrue("$id declares no asset", e.asset.isNotBlank())
            val b = ProjBanners.bytes(e, { ctx.assets.open(it) }, { fetched++; throw java.io.IOException("off") })
            assertNotNull("$id banner did not load from its bundled asset ${e.asset}", b)
            assertTrue("$id is not an image", ProjBanners.looksLikeImage(b))
            assertTrue("$id asset is implausibly small", b!!.size > 10_000)
        }
        assertEquals("a bundled banner never touches the network", 0, fetched)
    }

    @Test fun `a lost asset with the network off is nothing to draw, so the screen draws its panel`() {
        val gone = ProjBanners.Entry("https://example.invalid/x.gif", "banners/does-not-exist.gif")
        assertNull(ProjBanners.bytes(gone, { ctx.assets.open(it) }, noNetwork))
        assertNull("an HTML error page is not a banner", ProjBanners.bytes(gone, { null }, { "<html>502</html>".toByteArray() }))
        assertNull(ProjBanners.bytes(ProjBanners.Entry("", ""), { null }, noNetwork))
        // the network is a fallback for a missing asset only
        val gif = "GIF89a".toByteArray() + ByteArray(32)
        assertEquals(gif.size, ProjBanners.bytes(gone, { null }, { gif })!!.size)
    }

    private fun showHome() {
        compose.setContent {
            CloudKitTheme(KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Magenta, Color.Gray, Color.Black)) { ProjHomeScreen() }
        }
        compose.waitForIdle()
    }

    @Test fun `the rendered Home draws a banner of its height per category and a project icon per tile, network off`() {
        showHome()
        val groups = Sections.byId("proj")!!.pages.filter { it.pages.isNotEmpty() || it.id in ProjBanners.entries(ctx) }
        assertTrue(groups.size >= 8)
        val grid = compose.onNodeWithTag(ProjHomeTags.GRID)
        for (g in groups) {
            grid.performScrollToNode(hasTestTag(ProjHomeTags.banner(g.id)))
            val b = compose.onNodeWithTag(ProjHomeTags.banner(g.id)).fetchSemanticsNode().boundsInRoot
            assertTrue("${g.id} banner is empty (${b.height} high)", b.height > 50f && b.width > 50f)
            for (leaf in g.pages) {
                grid.performScrollToNode(hasTestTag(ProjHomeTags.tile(leaf.id)))
                val i = compose.onNodeWithTag(ProjHomeTags.icon(leaf.id), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("${leaf.id} icon is empty", i.height > 10f && i.width > 10f)
            }
        }
    }

    @Test fun `tile labels carry no emoji and every project resolves its own declared drawable, never the globe`() {
        val leaves = Sections.byId("proj")!!.pages.flatMap { it.pages }
        assertEquals(26, leaves.size)
        val seen = HashMap<String, String>()
        for (leaf in leaves) {
            assertTrue("${leaf.id} tile label ${ProjTileIcons.plainLabel(leaf.label)} starts with a symbol", ProjTileIcons.plainLabel(leaf.label).first().isLetterOrDigit())
            val name = ProjTileIcons.declared[leaf.id]
            assertNotNull("${leaf.id} declares no tile_icon", name)
            assertFalse("${leaf.id} wears a shared icon $name", name in setOf("ic_world", "ic_link_tile"))
            assertTrue("${leaf.id}: drawable $name is not bundled", ctx.resources.getIdentifier(name, "drawable", ctx.packageName) != 0)
            assertNull("${leaf.id} and ${seen[name]} share $name", seen.put(name!!, leaf.id))
        }
        assertEquals("Home", ProjTileIcons.plainLabel("🏠 Home")); assertEquals("Cloudy", ProjTileIcons.plainLabel("☁️ Cloudy")); assertEquals("42", ProjTileIcons.plainLabel("🎓 42"))
    }

    @Test fun `the Proj strips draw no glyph, the Home is followed by a separator, declared`() {
        val proj = Sections.byId("proj")!!
        fun all(ps: List<Page>): List<Page> = ps.flatMap { listOf(it) + all(it.pages) }
        for (p in all(proj.pages)) assertTrue("${p.id} still draws a glyph", p.emojiOnly)
        assertEquals("pj-home", proj.pages[0].id)
        assertTrue("a separator follows Home", proj.pages[1].separatorBefore && proj.pages.drop(2).none { it.separatorBefore })
    }

    @Test fun `a failed remote page draws a visible error with Retry, and the rules that decide it`() {
        var retried = 0
        compose.setContent { WebLoadErrorPanel(WebLoadState.describe("net::ERR_INTERNET_DISCONNECTED", null), { retried++ }) }
        compose.waitForIdle()
        val box = compose.onNodeWithTag("me:web:error").fetchSemanticsNode().boundsInRoot
        assertTrue("the error panel is empty", box.height > 50f)
        compose.onNodeWithTag("me:web:retry").performClick()
        assertEquals(1, retried)
        assertTrue(WebLoadState.isBlank("0") && WebLoadState.isBlank("\"0\"") && WebLoadState.isBlank(null) && WebLoadState.isBlank("null"))
        assertFalse(WebLoadState.isBlank("1234"))
        assertTrue(WebLoadState.isHttpFailure(502) && WebLoadState.isHttpFailure(404) && !WebLoadState.isHttpFailure(200) && !WebLoadState.isHttpFailure(304))
        assertEquals("The server answered HTTP 502.", WebLoadState.describe(null, 502))
        assertTrue(WebLoadState.describe(null, null).isNotBlank())
    }
}
