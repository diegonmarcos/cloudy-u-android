package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.os.Looper
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.AppsMesh
import com.diegonmarcos.superapp.appstore.AppsMeshFragment
import com.diegonmarcos.superapp.appstore.StoreBar
import com.diegonmarcos.superapp.appstore.StoreCloudFragment
import com.diegonmarcos.superapp.appstore.StoreControls
import com.diegonmarcos.superapp.appstore.StoreMesh
import com.diegonmarcos.superapp.appstore.StoreSemantics
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #793 the Store ▸ Apps Mesh page draws its actions and filters with the SAME
 * component the Store's main page uses — not a look-alike. Asserted on what the
 * two pages actually RENDER: Store ▸ Cloud (its bar, an app row, its filter
 * chips), then the same Apps Mesh page hosted by Access / Configs (AppsMeshFragment).
 *
 * "Same component" is checked two ways that can each fail on their own: the
 * node carries the mark only [StoreBar.Button] / [StoreBar.Chip] / [StoreBar.PageButton] set
 * ([StoreSemantics.Control]; a Text merely painted to look alike does not), and it measures the
 * same — text size, padding, corner radius ([StoreSemantics.Look]) — as the Store's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreSharedControlsTest : KitPageHarness() {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val b64 = com.diegonmarcos.superapp.appstore.BuildConfig.CONSTELLATION_FLEET_B64
    private val fleet = Fleet.parse(b64)
    private val decl = AppsMesh.load(ctx)
    private val meshLabel = JSONObject(ctx.assets.open(StoreControls.ASSET).use { it.readBytes().decodeToString() })
        .getJSONObject("pages").getJSONObject("mesh").getString("label")

    private fun idle() { shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle() }

    private fun tagged(prefix: String): List<SemanticsNode> =
        compose.onAllNodes(tagStartsWith(prefix)).fetchSemanticsNodes()

    private fun control(n: SemanticsNode) = n.config.getOrNull(StoreSemantics.Control)
    private fun look(n: SemanticsNode) = n.config.getOrNull(StoreSemantics.Look)

    @Test
    fun `the Store and its Apps Mesh page render the same button and chip components`() {
        val store = show(StoreCloudFragment())
        idle()

        // ── the Store's main page: its bar, an app row, its filter ──
        compose.onNodeWithTag(StoreBar.TAG).assertExists("Store ▸ Cloud draws no bar")
        val barButtons = StoreBar.Item.values().mapNotNull { item ->
            compose.onAllNodes(hasTestTag(StoreBar.itemTag(item))).fetchSemanticsNodes().firstOrNull()
        }
        assertTrue("the bar drew no buttons", barButtons.size >= 4)
        assertEquals("a Store bar control is not the shared button: ${barButtons.filter { control(it) != StoreBar.BUTTON }.map { it.tag() }}",
            emptyList<Any>(), barButtons.filter { control(it) != StoreBar.BUTTON })
        // an app row's buttons sit behind its chevron: open the first row
        val firstRow = tagged("store-head:").first().tag()!!
        compose.onNodeWithTag(firstRow).invokeClick()
        idle()
        val rowButtons = compose.onAllNodes(tagStartsWith("store-verb:about:")).fetchSemanticsNodes()
        assertTrue("no app row drew its About button", rowButtons.isNotEmpty())
        assertTrue("an app row's button is not the shared button", rowButtons.all { control(it) == StoreBar.BUTTON })
        assertTrue("an app row's About button is not labelled About", rowButtons.all { it.text() == "About" })
        val storeChips = compose.onAllNodes(hasStoreControl(StoreBar.CHIP)).fetchSemanticsNodes()
        assertEquals("the Store's filter is not four shared chips", 4, storeChips.size)
        val storeButtonLook = look(barButtons.first())!!
        val storeChipLook = look(storeChips.first())!!

        // ── Apps Mesh left the Cloud page (#896.3): it is Cloud Store's Access page, which hosts this same fragment ──
        assertTrue("Apps Mesh is still a tab on Store ▸ Cloud",
            compose.onAllNodes(androidx.compose.ui.test.hasText(meshLabel, substring = true)).fetchSemanticsNodes().isEmpty())

        // ── the page, hosted by Access (and by Configs ▸ Setup ▸ Network): AppsMeshFragment, no Store row ──
        compose.runOnUiThread { compose.activity.supportFragmentManager.beginTransaction().remove(store).commitNow() }
        show(AppsMeshFragment())
        idle()
        assertMeshPage(hasStore = false, storeButtonLook, storeChipLook)
    }

    private fun group(scope: String, type: String) = hasTestTag("${AppsMesh.TAG_GROUP}$scope:$type")

    private fun assertMeshPage(hasStore: Boolean, buttonLook: String, chipLook: String) {
        val where = if (hasStore) "Store ▸ Apps Mesh" else "Configs ▸ Apps Mesh"
        // #809 root controls: each declared page / action in the group of its declared type, never another
        assertControlsGrouped("root", where, buttonLook)
        // each member's row of buttons, like a Store app row's
        val actions = AppsMesh.actionsFor(decl, hasStore).map { it.id }
        val rowButtons = tagged(AppsMesh.TAG_ACTION)
        for (app in fleet) for (a in actions)
            assertTrue("$where: ${app.id} has no '$a' button",
                rowButtons.any { it.tag() == "${AppsMesh.TAG_ACTION}$a:${app.id}" })
        for (b in rowButtons) {
            val id = b.tag()!!.removePrefix(AppsMesh.TAG_ACTION).substringBefore(':')
            val type = decl.of("member").first { it.id == id }.type
            assertEquals("$where: member control $id ($type) is not the shared $type control",
                if (type == "page") StoreBar.PAGE else StoreBar.BUTTON, control(b))
            compose.onNode(hasTestTag(b.tag()!!) and hasAnyAncestor(group("member", type)))
                .assertExists("$where: member control $id sits outside its $type group")
        }
        assertEquals("$where: Store row offered where there is no Store", hasStore,
            rowButtons.any { it.tag()!!.startsWith("${AppsMesh.TAG_ACTION}store:") })
        assertEquals(buttonLook, look(rowButtons.first { control(it) == StoreBar.BUTTON }))
        // filter chips: the declared five, the shared chip, with a count, looking like the Store's filter
        val chips = tagged(AppsMesh.TAG_FILTER)
        assertEquals("$where chips", AppsMesh.FILTERS, chips.map { it.tag()!!.removePrefix(AppsMesh.TAG_FILTER) })
        assertTrue("$where: a filter chip is not the shared chip", chips.all { control(it) == StoreBar.CHIP })
        assertTrue("$where: a chip carries no count", chips.all { Regex("\\(\\d+\\)$").containsMatchIn(it.text()) })
        assertEquals(chipLook, look(chips.first()))
        for (c in chips) compose.onNode(hasTestTag(c.tag()!!) and hasAnyAncestor(group("root", "filter")))
            .assertExists("$where: a filter chip sits outside the filter group")
        // #809 Export lives on the endpoints page, not on root; opening it shows its own actions and a way back
        assertTrue("$where: an endpoints-page action is on the root page",
            tagged(AppsMesh.TAG_TOOL + "endpoints:").isEmpty())
        compose.onNodeWithTag(AppsMesh.TAG_TOOL + "root:endpoints").invokeClick(); idle()
        assertControlsGrouped("sub", where, buttonLook)
        compose.onNodeWithTag(AppsMesh.TAG_TOOL + "sub:back").invokeClick(); idle()
        assertTrue("$where: back did not return to Apps Mesh", tagged(AppsMesh.TAG_TOOL + "sub:").isEmpty())
        compose.onNodeWithTag(AppsMesh.TAG_TOOL + "root:gaps").invokeClick(); idle()
        compose.onNodeWithTag(AppsMesh.TAG_TOOL + "sub:back").invokeClick(); idle()
        // #809 a member's Details is a sub-page like App API Endpoints, not a dialog:
        // the page swaps, carries its own declared Copy and a way back, and no dialog opens
        val member = fleet.first().id
        compose.onNodeWithTag("${AppsMesh.TAG_ACTION}details:$member").invokeClick(); idle()
        assertEquals("$where: Details did not open as the details sub-page", "${AppsMesh.TAG_SUB}details",
            tagged(AppsMesh.TAG_SUB).single().tag())
        assertNull("$where: Details opened a dialog", org.robolectric.shadows.ShadowDialog.getLatestDialog())
        assertControlsGrouped("details", where, buttonLook)
        assertControlsGrouped("sub", where, buttonLook)
        assertEquals("$where: Details body is not this member's", 1, tagged(AppsMesh.TAG_DETAILS + member).size)
        compose.onNodeWithTag(AppsMesh.TAG_TOOL + "sub:back").invokeClick(); idle()
        assertTrue("$where: back did not leave Details", tagged(AppsMesh.TAG_TOOL + "details:").isEmpty())
        // static first: every member's card is on the page before any probe answered
        val cards = tagged(StoreMesh.TAG_NODE).map { it.tag()!!.removePrefix(StoreMesh.TAG_NODE) }
        assertEquals("$where: not every member is drawn up front", fleet.map { it.id }.sorted(), cards.sorted())
        assertFalse("$where: no probe-age line", tagged(AppsMesh.TAG_AGE).isEmpty())
    }

    /** Every declared page / action of [scope] is drawn once, the shared control of its
     *  type, inside the group of its type — and every group holds only its own type. */
    private fun assertControlsGrouped(scope: String, where: String, buttonLook: String) {
        val drawn = tagged("${AppsMesh.TAG_TOOL}$scope:")
        val want = decl.of(scope).filter { it.type != "filter" }
        assertEquals("$where $scope controls", want.map { it.id }.sorted(),
            drawn.map { it.tag()!!.substringAfterLast(':') }.sorted())
        for (c in want) {
            val v = drawn.single { it.tag()!!.endsWith(":${c.id}") }
            compose.onNode(hasTestTag(v.tag()!!) and hasAnyAncestor(group(scope, c.type)))
                .assertExists("$where: ${c.id} (${c.type}) is drawn in another group")
            if (c.type == "page") assertEquals("$where: page ${c.id} is not the shared page button", StoreBar.PAGE, control(v))
            else {
                assertEquals("$where: action ${c.id} is not the shared button", StoreBar.BUTTON, control(v))
                assertEquals(buttonLook.substringAfter(" r"), look(v)!!.substringAfter(" r"))
            }
        }
        for (type in listOf("page", "action", "filter")) {
            val inGroup = compose.onAllNodes(hasAnyAncestor(group(scope, type)) and
                (hasStoreControl(StoreBar.PAGE) or hasStoreControl(StoreBar.BUTTON) or hasStoreControl(StoreBar.CHIP))).fetchSemanticsNodes()
            for (v in inGroup)
                assertEquals("$where: group $type holds a control of another kind: ${v.tag()}", type,
                    when (control(v)) { StoreBar.PAGE -> "page"; StoreBar.BUTTON -> "action"; else -> "filter" })
        }
    }

    @Test
    fun `control - a Text painted like the button is not the shared component`() {
        // the mark check above discriminates: a look-alike fails it
        showKit {
            Column {
                StoreBar.Button("x", 0, {}, tag = "real")
                Text("x", Modifier.testTag("fake").background(Color.Blue))
                StoreBar.Chip("x", false, {}, tag = "chip")
                StoreBar.PageButton("i", "x", {}, tag = "page")
                StoreBar.Button("x", 0, null, tag = "off")
            }
        }
        fun node(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode()
        assertEquals(StoreBar.BUTTON, control(node("real")))
        assertNull("a look-alike carries the mark", control(node("fake")))
        assertEquals("a button reads as a chip", StoreBar.CHIP, control(node("chip")))
        assertEquals(StoreBar.PAGE, control(node("page")))
        assertNotNull(look(node("real")))
        // and a disabled verb is still the shared button, drawn dimmed and inert
        val off = node("off")
        assertEquals(StoreBar.BUTTON, control(off)); assertTrue(off.isDisabled())
        assertFalse(androidx.compose.ui.semantics.SemanticsActions.OnClick in off.config)
        // Base64 of the baked fleet is what both pages draw from
        assertTrue(String(Base64.decode(b64, Base64.DEFAULT)).contains("\"apps\""))
        compose.onNodeWithTag("nothing").assertDoesNotExist()
    }
}
