package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.notificationcenter.BadgeCardData
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1935 one source for the persistent badges. The Persistents page draws [BadgeCardBody]; the All page's super expanded
 * state draws the same function from the same model. Proved three ways:
 *   - by render: every badge's super expanded view on All has the same tags (every field, every action) and texts as the
 *     one on Persistents, drawn side by side from one model;
 *   - by source: the All row has no field renderer of its own, and the two pages call one function;
 *   - by mutation: a field added to one side only turns both checks red.
 * The cause of the old gap: the All page's expanded state drew the platform's RemoteViews of the posted notification
 * ([ShadeState]), a second renderer that has none of the card's fields (progress, lines, weather, media hub) - removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h4000dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BadgeSingleSource1935Test : KitPageHarness() {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun seed() {
        NotifyFixtures.install()
        ctx.getSharedPreferences("notify_all_badge_stage", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("notify_badge_collapse", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun restore() { NotifyFixtures.uninstall() }

    private fun signature(n: SemanticsNode, out: MutableList<String> = mutableListOf()): List<String> {
        n.config.getOrNull(SemanticsProperties.TestTag)?.let { out += "tag:$it" }
        n.config.getOrNull(SemanticsProperties.Text)?.let { out += "text:" + it.joinToString("|") { t -> t.text } }
        for (c in n.children) signature(c, out)
        return out
    }

    @Test fun `every badge's super expanded view on All is the Persistents view`() {
        val ids = orderedBadges(ctx).map { it.id }
        for (id in ids) BadgeExpansion.set(ctx, id, BadgeExpansion.Stage.SECOND)   // also proves the stage is remembered
        showKit {
            Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                BadgeCards(summary = false)
                BadgeCards(summary = true)
            }
        }
        compose.waitForIdle()
        for (id in ids) {
            val nodes = compose.onAllNodesWithTag(BadgeCardTags.expanded(id), useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals("$id: one expanded view on Persistents and one super expanded on All", 2, nodes.size)
            val persistents = signature(nodes[0]); val all = signature(nodes[1])
            assertEquals("$id: All's super expanded view differs from Persistents", persistents, all)
            assertTrue("$id: the view carries its title", "tag:${BadgeCardTags.title(id)}" in all)
            // mutation: one field present on one side only is red
            assertTrue("$id: a field on one side only was not caught", SingleSource.diff(persistents + "tag:extra-field", all).isNotEmpty())
            assertTrue("$id: a field dropped from one side was not caught",
                SingleSource.diff(persistents, all.dropLast(1)).isNotEmpty())
        }
    }

    @Test fun `the actions of each badge are on its super expanded view`() {
        val ids = orderedBadges(ctx).map { it.id }
        for (id in ids) BadgeExpansion.set(ctx, id, BadgeExpansion.Stage.SECOND)
        showKit { Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { BadgeCards(summary = true) } }
        compose.waitForIdle()
        for (b in orderedBadges(ctx)) {
            val d = BadgeCardData.read(ctx, b)
            for (a in d.actions) assertTrue("${b.id}: action ${a.id} missing on All", compose.onAllNodesWithTag(
                BadgeCardTags.action(b.id, a.id), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test fun `the source has one renderer and both pages call it`() {
        val src = File(System.getProperty("user.dir"), "src/main/java/com/diegonmarcos/superapp/launcher/BadgeCards.kt").readText()
        assertEquals(emptyList<String>(), SingleSource.violations(src))
        for ((name, mutated) in SingleSource.mutations(src)) assertTrue("mutation '$name' was not caught",
            SingleSource.violations(mutated).isNotEmpty())
    }
}

internal object SingleSource {
    fun diff(a: List<String>, b: List<String>): List<String> =
        (a - b.toSet()) + (b - a.toSet()) + (if (a.size != b.size) listOf("size ${a.size} != ${b.size}") else emptyList())

    private fun slice(src: String, from: String, to: String): String {
        val i = src.indexOf(from); val j = src.indexOf(to, i + 1)
        return if (i < 0 || j < 0) "" else src.substring(i, j)
    }

    private val FIELD_READS = listOf("d.lines", "d.actions", "d.progress", "d.subText", "d.sub", "d.note", "d.idle", "d.albumArt")

    fun violations(src: String): List<String> = buildList {
        val all = slice(src, "private fun SummaryBadgeLine", "/** #1794 the right-side arrow")
        val persistents = slice(src, "internal fun BadgeCard(", "internal fun BadgeCardBody")
        if (all.isEmpty() || persistents.isEmpty()) add("could not find the two pages' functions")
        if (!all.contains("BadgeCardBody(d")) add("the All row's super expanded state does not call BadgeCardBody")
        if (!persistents.contains("BadgeCardBody(d")) add("the Persistents card does not call BadgeCardBody")
        for (f in FIELD_READS) if (Regex(Regex.escape(f) + "\\b").containsMatchIn(all)) add("the All row reads $f itself: a second renderer")
        if (Regex("fun BadgeCardBody").findAll(src).count() != 1) add("BadgeCardBody is not declared exactly once")
    }

    fun mutations(src: String): Map<String, String> = mapOf(
        "All stops calling the shared view" to src.replace("BadgeCardBody(d, onChevron = step)", "Unit"),
        "Persistents stops calling it" to src.replace("    BadgeCardBody(d, flip)\n", ""),
        "a field read added to the All row only" to src.replace("BadgeCardBody(d, onChevron = step)",
            "BadgeCardBody(d, onChevron = step); androidx.compose.material3.Text(d.lines.size.toString())"),
        "an action read added to the All row only" to src.replace("BadgeCardBody(d, onChevron = step)",
            "BadgeCardBody(d, onChevron = step); for (a in d.actions) Unit"),
    )
}
