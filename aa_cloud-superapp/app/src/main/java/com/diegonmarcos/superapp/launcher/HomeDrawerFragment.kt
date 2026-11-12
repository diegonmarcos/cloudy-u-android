package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.BuildConfig
import androidx.compose.runtime.getValue
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.ModePrefs
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.fleetendpoints.android.AccountClient

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * "Home" drawer page — the main-menu: the site-map index of the whole app.
 * Menu is built from [Sections] (one declaration in
 * `build.json::ui.main_menu` for the quick/search entries below the Profile
 * badge, plus `ui.home_groups` / `ui.sections[*].pages[*]`), so the menu holds
 * the ONE site map and has no hardcoded Kotlin inventory.
 *
 * Each top-level group is a section; its sub-items are that section's pages
 * (or `drawer_default_children` as fallback when no pages[] is declared).
 * Selecting a sub-item bubbles up via [NavigationListener] so the host
 * Activity can open the right page in the content pane.
 *
 * Compose since #773 (was a NavigationView + nav_header.xml): [rows] is the menu as data, [Header]
 * the Profile badge above it, [DrawerList] draws both.
 */
class HomeDrawerFragment : KitComposeFragment() {

    interface NavigationListener {
        fun onDrawerSectionSelected(sectionId: String, label: String)
        fun onDrawerPageSelected(sectionId: String, pageId: String, label: String)
        fun onDrawerActionSelected(actionType: String)
        /** Drawer swap-icon tap → flip Apps↔Admin globally and refresh
         *  the visible section so the new mode's tiles render. */
        fun onDrawerModeToggle()
        /** Drawer identity-row tap (anywhere EXCEPT the swap icon) → open
         *  the Virtual Business Card screen. */
        fun onDrawerBusinessCardOpen()
    }

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val rows = remember { rows(ctx) }
        DrawerList(rows, ::onItemPicked, Modifier.fillMaxSize()) { Header() }
    }

    /** The header strip: the app banner (build info) above the user banner (the Profile badge). */
    @Composable
    private fun Header() {
        val ctx = LocalContext.current
        val view = LocalView.current
        val p = LocalKitPalette.current
        val buildLine = "${BuildConfig.VERSION_NAME}  vc:${BuildConfig.VERSION_CODE}"
        val tsLine = BuildConfig.BUILD_TIMESTAMP
        Column(Modifier.fillMaxWidth().testTag(TAG_HEADER)) {
            // Tap on the header strip → trigger the in-app updater check;
            // long-press → copy the full build descriptor to the clipboard.
            Row(Modifier.fillMaxWidth().background(p.surface).heightIn(min = 84.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { (activity as? NavigationListener)?.onDrawerActionSelected("check_updates") },
                        onLongPress = {
                            val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            clip?.setPrimaryClip(android.content.ClipData.newPlainText(
                                "Cloud SuperApp build", "$buildLine\n$tsLine"))
                            android.widget.Toast.makeText(ctx, "Build info copied", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                .padding(horizontal = 14.dp, vertical = 10.dp).testTag(TAG_APP_BANNER),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_home), contentDescription = null, tint = p.textPrimary,
                    modifier = Modifier.size(36.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(ctx.getString(R.string.app_name), color = p.textPrimary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(buildLine, color = p.textPrimary, maxLines = 1, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp).alpha(0.7f))
                    Text(tsLine, color = p.textPrimary, maxLines = 1, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 1.dp).alpha(0.55f))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))

            // User-banner row — avatar (initials derived from the name) + 3 stacked text lines:
            // [Name]  /  [Email]  /  Mode: {Apps|Admin}.
            // Bound from ProfilePrefs + ModePrefs so it always reflects current state — works even
            // when the drawer is re-opened after the user edited Configs → Profile.
            //
            // Narrower hit-target: only the round AVATAR (the circle with the initials) opens the
            // Virtual Business Card. The rest of the row — name / email / mode text — is not
            // tappable, so casual interaction with the drawer header doesn't accidentally trigger
            // the BC screen. The Apps↔Admin global swap icon is RETIRED: Apps and Admin are
            // ordinary PAGES of the sections that have both — pick one from the section grid, the
            // fan menu or the Sirius ring. NavigationListener.onDrawerModeToggle is kept for any
            // other surface that triggers it programmatically.
            // #1693 the profile is Cloud Account's, read off the main thread over its provider.
            val profile by androidx.compose.runtime.produceState(AccountClient.Profile(emptyMap())) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AccountClient.profile(ctx) }
            }
            val modeLabel = remember { if (ModePrefs(ctx).mode == "admin") "Admin" else "Apps" }
            Row(Modifier.fillMaxWidth().background(p.surfaceSelected).heightIn(min = 84.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp).testTag(TAG_USER_ROW),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(p.accent)
                    .clickable {
                        Haptics.tap(view)
                        (activity as? NavigationListener)?.onDrawerBusinessCardOpen()
                    }.testTag(TAG_AVATAR),
                    contentAlignment = Alignment.Center) {
                    // Derived from the name, not stored: one less personal field to hold, disclose
                    // and delete, and it can no longer disagree with the name shown next to it.
                    Text(profile.initials(), color = p.tileInk, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(profile.name.ifBlank { NO_PROFILE }, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag(TAG_NAME))
                    Text(profile.email, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.8f).testTag(TAG_EMAIL))
                    Text("Mode: $modeLabel", color = p.textPrimary, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(TAG_MODE))
                }
            }
        }
    }

    private fun onItemPicked(t: DrawerTarget) {
        val listener = activity as? NavigationListener ?: return
        when (t) {
            is DrawerTarget.Section -> listener.onDrawerSectionSelected(t.id, t.label)
            is DrawerTarget.Page    -> listener.onDrawerPageSelected(t.sectionId, t.pageId, t.label)
            is DrawerTarget.Action  -> listener.onDrawerActionSelected(t.actionType)
            is DrawerTarget.Tile    -> Unit   // the Home drawer has no tile rows
        }
    }

    companion object {
        const val TAG_HEADER = "drawer:header"
        const val TAG_APP_BANNER = "drawer:header:app"
        const val TAG_USER_ROW = "drawer:header:user"
        const val TAG_AVATAR = "drawer:header:avatar"
        /** #1693 what the badge reads when Cloud Account holds no profile for this app (absent, not granted, empty). */
        const val NO_PROFILE = "Profile in Cloud Account"
        const val TAG_NAME = "drawer:header:name"
        const val TAG_EMAIL = "drawer:header:email"
        const val TAG_MODE = "drawer:header:mode"

        /** The whole-app menu as rows: the main-menu entries, then one titled group per home_groups group. */
        internal fun rows(ctx: Context): List<DrawerRow> {
            val out = mutableListOf<DrawerRow>()
            // Prepend entries (build.json::ui.main_menu) — the main-menu site map,
            // rendered ABOVE the first section, directly below the header's Profile
            // badge, so the two search entries (Search ML, Search CGC) sit below the
            // profile in their declared order. Same dispatch as home_actions.
            for (action in Sections.mainMenu()) {
                out += DrawerRow(action.label, DrawerRow.Kind.ITEM, Sections.iconResFor(ctx, action.iconName),
                    DrawerTarget.Action(action.actionType))
            }

            // Drawer mirrors the swipe-up Home Apps view 1:1 — same
            // `home_groups` schema, same per-group ordering, same tile ids.
            // Each group becomes a titled group; each tile under it is a tappable row.
            // Single source of truth for both surfaces is build.json::ui.home_groups — change there
            // to reshape both at once.
            val mode = ModePrefs(ctx).mode
            // Icon resolution: when a tile's id is `section:X`, the section's
            // iconForMode(mode) wins so drawer / bottom-nav / sheet all show
            // the same glyph per mode. For `page:S/P` tiles the page's icon
            // is used; everything else falls back to the tile's own icon.
            fun iconForTile(tile: Sections.HomeTile): String {
                if (tile.id.startsWith("section:")) {
                    Sections.byId(tile.id.removePrefix("section:"))?.let {
                        return it.iconForMode(mode)
                    }
                }
                return tile.iconForMode(mode)
            }
            for (group in Sections.homeGroups()) {
                out += DrawerRow(group.title, DrawerRow.Kind.TITLE)
                // `tiles`, deliberately: this drawer reads ui.home_groups, a schema
                // of its own that never nests — no folder has ever appeared in it,
                // so there is nothing here to flatten.
                for (tile in group.tiles) {
                    // Tile id format mirrors HomeGroupedFragment / MainActivity:
                    //   "section:<X>"        → switch to section X
                    //   "page:<sec>/<page>"  → deep-link to that page
                    val target = when {
                        tile.id.startsWith("section:") -> DrawerTarget.Section(
                            tile.id.removePrefix("section:"), tile.label,
                        )
                        tile.id.startsWith("page:") -> {
                            val rest = tile.id.removePrefix("page:")
                            val slash = rest.indexOf('/')
                            if (slash > 0) DrawerTarget.Page(
                                rest.substring(0, slash),
                                rest.substring(slash + 1),
                                tile.label,
                            ) else DrawerTarget.Section(rest, tile.label)
                        }
                        tile.id.startsWith("action:") -> DrawerTarget.Action(tile.id.removePrefix("action:"))
                        else -> DrawerTarget.Section(tile.id, tile.label)
                    }
                    out += DrawerRow(tile.label, DrawerRow.Kind.ITEM, Sections.iconResFor(ctx, iconForTile(tile)), target)

                    // One extra depth level: when the tile is `section:X`, expand its first-level
                    // pages as indented siblings inside the same group. Each page becomes its own
                    // tappable row that deep-links to that page. `page:` tiles don't get expanded
                    // (they're already a leaf), and free-form tiles have no pages by definition.
                    if (tile.id.startsWith("section:")) {
                        val section = Sections.byId(tile.id.removePrefix("section:")) ?: continue
                        // Declared headings (#649) as disabled rows at the same indent, so Configs
                        // expands as Launcher / Watchdog ▸ Setup / Observability, not a flat run —
                        // see [Sections.headingOf].
                        val pad = "  ".repeat(6)
                        var shownGroup = ""; var shownSub = ""
                        for (page in section.pages) {
                            val (g, s) = Sections.headingOf(section, page)
                            if (g.isNotEmpty() && g != shownGroup) {
                                out += DrawerRow(pad + g.uppercase(), DrawerRow.Kind.HEADING)
                                shownGroup = g; shownSub = ""
                            }
                            if (s.isNotEmpty() && s != shownSub) {
                                out += DrawerRow("$pad$pad$s", DrawerRow.Kind.HEADING)
                                shownSub = s
                            }
                            // Visual indent so children read clearly as children of their parent
                            // tile, with the tree-view "└─" prefix.
                            out += DrawerRow("      └─── ${page.label}", DrawerRow.Kind.ITEM,
                                page.iconName?.let { Sections.iconResFor(ctx, it) } ?: 0,
                                DrawerTarget.Page(section.id, page.id, page.label))
                        }
                    }
                }
            }
            return out
        }

        fun newInstance() = HomeDrawerFragment()
    }
}
