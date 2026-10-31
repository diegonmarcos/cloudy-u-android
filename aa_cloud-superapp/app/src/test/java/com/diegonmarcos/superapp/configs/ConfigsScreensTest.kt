package com.diegonmarcos.superapp.configs

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.launcher.SectionPages
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.launcher.TileGridFragment
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * "The Configs buttons are STILL huge" (4th report). ConfigsIconSizeTest measured the grid's ICONS; this draws EVERY
 * Configs screen (the grid, each page and each tab it opens) on the owner's phone - Samsung Galaxy A37 (SM-A376B):
 * 1080 x 2340 px at Samsung's default 450 dpi = 384 x 832 dp, density 2.8125 - at fontScale 1.0 and 1.3, and
 * The window is 2600dp TALL (width and density stay the A37's) so a long page lays out and is measured whole: at 832dp
 * everything below the fold had zero visible bounds and One-Hand / Battery / Identity measured nothing.
 * measures every clickable thing it RENDERED (button, chip, tile, row, tab, FAB; Compose semantics and View clickables
 * both), in dp, from the layout.
 *
 * Evidence: app/build/configs-screens/<screen>@<fontScale>.png and .tsv (CI uploads the folder).
 * Guard (fontScale 1.0): no button taller than [MAX_BUTTON_DP], no row taller than [MAX_ROW_DP], no icon plate wider
 * than the icon + 8dp [MAX_ICON_BOX_DP].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h2600dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigsScreensTest(private val screen: String, private val fontScale: Float) : KitPageHarness() {

    companion object {
        const val MAX_BUTTON_DP = 32f
        const val MAX_ROW_DP = 44f
        const val EXTRA_LINE_DP = 18f
        /** Screens that are read-only by design (no control to measure). Empty unless proven by a render. */
        // The four tab STRIPS: under Robolectric the strip's pane (a FragmentContainerView inside an AndroidView pager)
        // stays empty, so only its tab pills are measured here. Every tab they hold is rendered and guarded as its own
        // screen above (presets, panels, panels-ui, panels-tools, apps-mesh-members, ipc, identity).
        // Battery: read-only stats that load from the system battery service, which Robolectric never answers
        // ("Reading the battery..." is all it draws), so it has no control to measure here.
        val NO_CONTROLS = setOf("controls", "panels", "battery")
        /** An icon's plate (Control's round badge): at most the icon + 8dp ("white box too big"). */
        const val ICON_DP = 22f
        const val MAX_ICON_BOX_DP = ICON_DP + 8f
        val ICON_BOX = Regex(":badge:")

        /** The Configs grid and every page/tab under build.json::ui.sections[config] that opens IN this app. */
        val SCREENS = listOf(
            "grid", "perms", "controls", "presets", "panels", "panels-ui", "panels-tools", "onehand",
            "firewall", "dns", "adb-shell", "kde",
            "xray", "logcat", "app-about", "battery", "profile", "store-cloud", "store-phone",
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}@{1}")
        fun params(): List<Array<Any>> = SCREENS.flatMap { s -> listOf(arrayOf<Any>(s, 1.0f), arrayOf<Any>(s, 1.3f)) }

        val OUT = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
    }

    data class Hit(val kind: String, val name: String, val wDp: Float, val hDp: Float, val lines: Int = 1)

    private fun fragmentFor(id: String): Fragment {
        if (id == "grid") {
            val section = Sections.byId("config")!!
            val ctx = compose.activity
            val tiles = section.pages.map { p ->
                TileGridFragment.Tile(
                    id = if (p.action.isNotBlank()) p.action else "page:config/${p.id}",
                    label = p.label,
                    iconRes = p.iconName?.let { Sections.iconResFor(ctx, it) } ?: 0,
                    group = if (p.isAction) "Actions" else p.group.ifBlank { "Pages" },
                    subgroup = if (p.isAction) "" else p.subgroup,
                    rowBreak = p.rowBreak,
                )
            }
            return com.diegonmarcos.superapp.launcher.ConfigsFragment.newInstance(tiles)
        }
        return SectionPages.pagesFor("config", includeHidden = true).first { it.id == id }.factory()
    }

    private fun kindOf(n: SemanticsNode): String? {
        val c = n.config
        val role = c.getOrNull(SemanticsProperties.Role)
        val tag = c.getOrNull(SemanticsProperties.TestTag).orEmpty()
        val clickable = c.contains(SemanticsActions.OnClick) || c.contains(SemanticsProperties.ToggleableState) ||
            c.contains(SemanticsProperties.Selected)
        // A kit list row is a row whether or not it is tappable (Identity is a read-only list of them).
        if (!clickable) return if (tag.startsWith("kit:listrow:")) "row" else null
        return when {
            c.contains(SemanticsProperties.EditableText) -> "field"
            tag.startsWith("cfg:button") || tag.contains("fab", true) -> "button"
            tag.contains("tile", true) || tag.startsWith("toggle:") || tag.startsWith("index:") -> "tile"
            tag.contains("chip", true) -> "chip"
            role == Role.Tab -> "tab"
            role == Role.Button && n.children.none { kindOfChildIsText(it) } -> "button"
            role == Role.Button -> "button"
            role == Role.Switch || role == Role.Checkbox || role == Role.RadioButton -> "row"
            role == Role.DropdownList -> "row"
            else -> "row"
        }
    }

    private fun kindOfChildIsText(n: SemanticsNode) = n.config.contains(SemanticsProperties.Text)

    private fun label(n: SemanticsNode): String {
        val c = n.config
        val t = c.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            ?: n.children.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString(" ")
        val tag = c.getOrNull(SemanticsProperties.TestTag).orEmpty()
        return (if (t.isBlank()) tag else "$t [$tag]").replace('\t', ' ').replace('\n', ' ').take(80)
    }

    private fun composeHits(): List<Hit> {
        val d = compose.density.density
        val nodes = runCatching {
            compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = false).fetchSemanticsNodes(false)
        }.getOrDefault(emptyList())
        return nodes.mapNotNull { n ->
            val tag = n.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            val k = (if (ICON_BOX.containsMatchIn(tag)) "icon-box" else null) ?: kindOf(n) ?: return@mapNotNull null
            val b = n.boundsInRoot
            if (b.width <= 0f || b.height <= 0f) null else Hit(k, label(n), b.width / d, b.height / d, lines(n))
        } + runCatching {
            // An icon plate sits inside a merged tile, so it is only visible in the unmerged tree.
            compose.onAllNodes(SemanticsMatcher("icon-box") {
                ICON_BOX.containsMatchIn(it.config.getOrNull(SemanticsProperties.TestTag).orEmpty())
            }, useUnmergedTree = true).fetchSemanticsNodes(false)
        }.getOrDefault(emptyList()).map { n ->
            Hit("icon-box", n.config.getOrNull(SemanticsProperties.TestTag).orEmpty(), n.boundsInRoot.width / d, n.boundsInRoot.height / d)
        }
    }

    /** Text lines a row carries (title + subtitle = 2): a row may grow [EXTRA_LINE_DP] per line past the first. */
    private fun lines(n: SemanticsNode): Int {
        val own = n.config.getOrNull(SemanticsProperties.Text).orEmpty()
        val kids = n.children.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        return (own + kids).sumOf { it.text.count { c -> c == '\n' } + 1 + it.text.length / 60 }.coerceAtLeast(1)
    }

    private fun viewHits(root: View): List<Hit> {
        val d = root.resources.displayMetrics.density
        val out = mutableListOf<Hit>()
        fun walk(v: View) {
            if (v.javaClass.name.contains("AndroidComposeView")) return
            if (v.isShown && v.isClickable && v.width > 0 && v !== root) {
                val kind = if (v is android.widget.Button) "view-button" else "view-row"
                val text = (v as? android.widget.TextView)?.text?.toString() ?: v.contentDescription?.toString() ?: v.javaClass.simpleName
                out += Hit(kind, text.replace('\n', ' ').take(80), v.width / d, v.height / d)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun `render and measure`() {
        RuntimeEnvironment.setFontScale(fontScale)
        val name = "$screen@$fontScale"
        val tsv = File(OUT, "$name.tsv")
        val hits = try {
            show(fragmentFor(screen))
            // A tab strip commits its page from a posted runnable, and pages load their state on the main looper:
            // run the looper (and its short delays) until the tree stops changing, or the body renders empty.
            repeat(4) {
                compose.runOnUiThread { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300)) }
                compose.waitForIdle()
            }
            val root = compose.activity.findViewById<View>(android.R.id.content)
            val bmp = compose.runOnUiThread {
                Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    .also { b -> Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) } }
            }
            File(OUT, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            composeHits() + compose.runOnUiThread { viewHits(root) }
        } catch (t: Throwable) {
            tsv.writeText("screen\t$name\nERROR\t${t.javaClass.simpleName}: ${t.message?.replace('\n', ' ')?.take(300)}\n")
            assertTrue("$name did not render: ${t.javaClass.simpleName}: ${t.message?.take(300)}", !ENFORCE)
            return
        }
        tsv.writeText(buildString {
            append("screen\t$name\tdensity=${compose.density.density}\tfontScale=${compose.density.fontScale}\n")
            append("kind\twidth_dp\theight_dp\tlabel\n")
            for (h in hits.sortedWith(compareBy({ it.kind }, { -it.hDp }))) {
                append("${h.kind}\t${"%.1f".format(h.wDp)}\t${"%.1f".format(h.hDp)}\t${h.name}\n")
            }
        })
        if (fontScale == 1.0f && ENFORCE) {
            // An empty render proves nothing: every screen must draw at least one control the guard measured.
            assertTrue("$name rendered no measurable control (empty body?): the size guard checked nothing",
                hits.any { it.kind != "tab" } || screen in NO_CONTROLS)
        }
        if (fontScale == 1.0f) {
            val tooTall = hits.filter {
                (it.kind == "button" || it.kind == "view-button" || it.kind == "chip") && it.hDp > MAX_BUTTON_DP + 0.5f ||
                    (it.kind == "row" || it.kind == "view-row" || it.kind == "tab") &&
                        it.hDp > MAX_ROW_DP + EXTRA_LINE_DP * (it.lines - 1) + 0.5f ||
                    it.kind == "icon-box" && maxOf(it.wDp, it.hDp) > MAX_ICON_BOX_DP + 0.5f
            }
            assertTrue("$name: ${tooTall.size} control(s) over ${MAX_BUTTON_DP}dp (button) / ${MAX_ROW_DP}dp (row):\n" +
                tooTall.joinToString("\n") { "  ${it.kind} ${"%.1f".format(it.hDp)}dp tall: ${it.name}" },
                !ENFORCE || tooTall.isEmpty())
        }
    }

    private val ENFORCE: Boolean get() = true
}
