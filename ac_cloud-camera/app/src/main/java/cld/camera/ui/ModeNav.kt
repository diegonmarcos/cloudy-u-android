package cld.camera.ui

import android.view.View
import android.view.ViewGroup
import cld.camera.CameraMode
import cld.camera.R
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.bottomnav.BottomNavViewItem
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.PageTabsView

/**
 * #868 THE camera mode switcher: libs:bottomnav's island and page strip in place of the
 * hand-rolled snapping TabLayout (ui/BottomTabLayout.kt, deleted).
 *
 * The modes are build.json::ui. The island carries the five families (QR Code | Objects | Camera | Video | VR)
 * and the Camera family's `pages` are the photo flavours (Auto, Face retouch, Portrait, Night,
 * HDR, Camera) as a [PageTabsView] strip above it. Every declared id is the lower-cased
 * [CameraMode] name, so nothing here maps ids to modes by hand.
 *
 * Which modes exist is decided at run time (a vendor extension may or may not be usable), so the
 * island and the strip only ever show what [setModes] was handed. Swiping the preview walks the
 * flat mode list ([modeAt] / [selectedIndex]), which is the declaration's order.
 *
 * It owns no camera logic: a tap on an island item or a strip pill is handed to [onPick], and
 * the host (MainActivity.finalizeMode) switches the camera and calls [select] back.
 */
class ModeNav(
    /** What camera_mode_tabs lays out: the strip above the island. Hidden/shown as one. */
    val view: ViewGroup,
    private val island: BottomNavIslandView,
    private val strip: PageTabsView,
    private val decl: NavDecl,
    private val onPick: (CameraMode) -> Unit,
) {
    private var available: List<CameraMode> = emptyList()
    private val lastInSection = HashMap<String, CameraMode>()

    /** The mode the highlight is on, or null before the first [setModes]. */
    var selected: CameraMode? = null
        private set

    /** Taps are ignored while false (a video-only screen has no mode to switch to). */
    var isEnabled: Boolean = true

    var visibility: Int
        get() = view.visibility
        set(v) { view.visibility = v }

    var alpha: Float
        get() = view.alpha
        set(v) { view.alpha = v }

    var isClickable: Boolean
        get() = view.isClickable
        set(v) { view.isClickable = v }

    /** The available modes in the declaration's order. */
    val modes: List<CameraMode> get() = available
    val tabCount: Int get() = available.size
    val selectedIndex: Int get() = selected?.let { available.indexOf(it) } ?: -1
    fun modeAt(index: Int): CameraMode? = available.getOrNull(index)
    fun getAllModes(): Set<CameraMode> = available.toSet()

    init {
        island.onSelect = { id -> decl.section(id)?.let { pick(modeOfSection(it)) } }
        island.onReselect = {}
        // The smaller nav: icons only, no labels (the island's own compact form).
        island.collapsed = true
        strip.underTopChrome = false
        strip.onSelect = { page -> modeOf(page.id)?.let { pick(it) } }
        strip.onReselect = {}
        strip.visibility = View.GONE
    }

    private fun pick(mode: CameraMode?) {
        if (mode != null && isEnabled && mode in available) onPick(mode)
    }

    /** The modes the camera can offer right now, [current] highlighted. Cheap to call again. */
    fun setModes(offered: Set<CameraMode>, current: CameraMode?) {
        available = decl.bottomSections().flatMap { s -> modesOf(s) }.filter { it in offered }
        island.items = decl.bottomSections()
            .filter { s -> modesOf(s).any { it in available } }
            .map { s -> BottomNavViewItem(s.id, labelOf(s), iconOf(s)) }
        select(current?.takeIf { it in available } ?: available.firstOrNull())
    }

    /** Move the highlight to [mode] (and the strip, when its family has one). */
    fun select(mode: CameraMode?) {
        selected = mode
        val section = mode?.let { sectionOf(it) }
        island.selectedId = section?.id
        if (section != null && mode != null) lastInSection[section.id] = mode
        val pages = section?.pages.orEmpty().filter { p -> modeOf(p.id)?.let { it in available } == true }
        strip.pages = pages.map { p -> p.copy(label = labelOf(p)) }
        strip.selectedId = mode?.let { idOf(it) }
        strip.visibility = if (pages.size >= 2) View.VISIBLE else View.GONE
    }

    // ── the declaration, read once ───────────────────────────────────────────────────────

    private fun modesOf(s: NavSection): List<CameraMode> =
        if (s.pages.isEmpty()) listOfNotNull(modeOf(s.id)) else s.pages.mapNotNull { modeOf(it.id) }

    private fun sectionOf(mode: CameraMode): NavSection? =
        decl.bottomSections().firstOrNull { s -> mode in modesOf(s) }

    /** Where tapping a family lands: the mode last used in it, else its Camera page, else its first. */
    private fun modeOfSection(s: NavSection): CameraMode? {
        val offered = modesOf(s).filter { it in available }
        return lastInSection[s.id]?.takeIf { it in offered }
            ?: offered.firstOrNull { it == CameraMode.CAMERA }
            ?: offered.firstOrNull()
    }

    private fun modeOf(id: String): CameraMode? = CameraMode.entries.firstOrNull { it.name.lowercase() == id }
    private fun idOf(mode: CameraMode): String = mode.name.lowercase()

    private fun labelOf(p: NavPage): String =
        modeOf(p.id)?.let { view.context.getString(it.uiName) } ?: p.label

    /** A page-less family is its one mode's label; a family with pages reads as the plain Camera. */
    private fun labelOf(s: NavSection): String {
        val mode = if (s.pages.isEmpty()) modeOf(s.id) else CameraMode.CAMERA
        return mode?.let { view.context.getString(it.uiName) } ?: s.label
    }

    private fun iconOf(s: NavSection): Int =
        view.resources.getIdentifier("ic_mode_${s.icon}", "drawable", view.context.packageName)
            .takeIf { it != 0 } ?: R.drawable.photo
}
