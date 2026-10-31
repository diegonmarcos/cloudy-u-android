package com.diegonmarcos.superapp.configs

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.launcher.StatusBarDecl
import com.diegonmarcos.superapp.launcher.StatusBarPrefs
import com.diegonmarcos.superapp.uiicons.UiIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.diegonmarcos.superapp.launcher.SectionPages
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.onehand.ArcMenu
import com.diegonmarcos.superapp.onehand.CircularMenu
import com.diegonmarcos.superapp.onehand.GestureAction
import com.diegonmarcos.superapp.onehand.OneHandConfig
import com.diegonmarcos.superapp.onehand.OneHandController
import com.diegonmarcos.superapp.onehand.OneHandPrefs
import com.diegonmarcos.superapp.floatingnav.FloatingNavPrefs
import com.diegonmarcos.superapp.floatingnav.FloatingNavService
import com.diegonmarcos.superapp.settings.ConfigButton
import com.diegonmarcos.superapp.settings.ConfigDropdown
import com.diegonmarcos.superapp.settings.ConfigRadioRow
import com.diegonmarcos.superapp.settings.ConfigSwitchRow
import com.diegonmarcos.superapp.settings.DropdownOption
import com.diegonmarcos.superapp.settings.HomeSwipePrefs
import com.diegonmarcos.superapp.ui.AppIconCache
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Configs > Launcher > One-Hand (the second tab of that page; it was a
 * standalone Configs entry until Theme and One-Hand were merged into the one
 * page they always described together). Master toggle + a per-sector action
 * editor laid out to
 * MIRROR the phone: the left handle's Top/Center/Down live in the LEFT column,
 * the right handle's in the RIGHT column. Grant the two permissions in the
 * centralized Configs > Permissions page.
 *
 * Compose since #773 (was a ScrollView of hand-built Views with platform Spinners): each sector picker is a
 * [ConfigDropdown], whose selection is a user tap only, so merely opening the page can no longer write an override.
 *
 * This module also owns the THREE home-screen stars (see MainActivity /
 * libs:launcher-onehand — not editable from this screen, this is descriptive only):
 *   - Sirius   — full multi-level circular-menu (Suite/Infos/Labs/Configs…)
 *   - Canopus  — single-level arc-menu over the Configs section's pages
 *   - Centauri — SAME arc-menu as Canopus, showing the last 9 recently-opened
 *                Android apps (needs Usage-access, Configs > Permissions)
 */
class OneHandFragment : KitComposeFragment() {

    /** Redraw key of the two mirrored columns of sector pickers: the reset bumps it so they re-read the (now empty) store
     *  instead of showing the discarded entries. */
    private var editorEpoch by mutableIntStateOf(0)

    private var showResetConfirm by mutableStateOf(false)

    private data class Option(
        val label: String, val action: GestureAction?,
        val icon: android.graphics.drawable.Drawable? = null,
    )

    override fun palette() = LauncherPalette.kit(requireContext())

    /** Where each section starts inside the scrolling column (px), recorded by [infoSection]; the index scrolls to it. */
    private val sectionTops = mutableStateMapOf<String, Int>()

    @Composable
    override fun Content() {
        val ctx = requireContext()
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        Column(Modifier.fillMaxSize()) {
            // The sticky index: icon anchors declared in build.json::onehand_page, one per section, in the page's order.
            IndexRow(OneHandPageDecl.sections) { id -> sectionTops[id]?.let { y -> scope.launch { scroll.animateScrollTo(y) } } }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(16.dp)) {
                Text("One-Hand", color = LocalKitPalette.current.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp).testTag(TAG_TITLE))
                caption(
                    "Everything reachable with the thumb alone: the three Home-screen " +
                    "stars, the edge menu that works on top of any app, and the four " +
                    "Home swipes, then the status bar those sit under. Content and defaults are data-driven from " +
                    "build.json::onehand — the switches and pickers below are runtime " +
                    "overrides kept in the onehand_prefs store, and each one says which " +
                    "it is.")

                // One `when` per declared section id: the order and the headings are the declaration's, the bodies are here.
                for (sec in OneHandPageDecl.sections) {
                    infoSection(sec)
                    when (sec.id) {
                        OneHandPageDecl.STARS -> addStars(ctx)
                        OneHandPageDecl.EDGE_MENU -> addEdgeMenu(ctx)
                        OneHandPageDecl.FLOATING_MENU -> addFloatingMenu(ctx)
                        OneHandPageDecl.HOME_GESTURES -> addHomeGestures(ctx)
                        OneHandPageDecl.STATUS_BAR -> addStatusBar(ctx)
                    }
                }
            }
        }
        if (showResetConfirm) ResetConfirm(ctx)
    }

    /** The sticky row of icon anchors; scrolls sideways if the captions outgrow a narrow screen. */
    @Composable
    private fun IndexRow(sections: List<OneHandPageDecl.Section>, onPick: (String) -> Unit) {
        val p = LocalKitPalette.current
        Row(Modifier.fillMaxWidth().background(p.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(TAG_INDEX), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (sec in sections) {
                Column(Modifier.clickable { onPick(sec.id) }.padding(horizontal = 10.dp, vertical = 4.dp).testTag("onehand:index:${sec.id}"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.Icon(UiIcons.painter(sec.icon), contentDescription = sec.label, tint = p.textPrimary,
                        modifier = Modifier.size(22.dp))
                    Text(sec.label, color = p.textSecondary, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }

    // ────────────────────────────── 1. Stars ──────────────────────────────

    /**
     * Descriptive only — the stars are wired in MainActivity and have no on/off
     * switch here. Every card is read from the SAME config the star itself
     * reads, so this page cannot drift from what the star draws.
     */
    @Composable
    private fun addStars(ctx: Context) {
        val cm = CircularMenu.config()

        caption(stringResource(R.string.onehand_stars_intro, cm.starPairOffsetXDp))

        // The one distinction the whole design rests on. Everything a star can
        // show is either a place you GO or a thing you DO, and the ring it
        // lands on is what tells them apart.
        subhead("Two rings, two meanings")
        caption(
            "OUTER full circle — APPS / PAGES. Where you GO. A branch redraws " +
            "the ring one level deeper, so the outer ring is a tree you walk " +
            "with the thumb; a leaf navigates and closes. Nothing on this ring " +
            "changes anything.\n\n" +
            "INNER half circle — ACTIONS. What you DO. One tap fires it and the " +
            "menu closes. An action never has children and never navigates, " +
            "which is why it sits apart on its own smaller disc, " +
            "${cm.ringGapDp}dp inside the outer ring — the gap IS the meaning.\n\n" +
            "Same two rings on every star. Only the source of each ring " +
            "changes, which is the whole difference between them:")

        val nodes = cm.nodes
        starCard("✦ Sirius — all apps",
            "screen centre · radius ${cm.radiusDp}dp",
            "The full section tree. " +
                "${nodes.count { it.childKey != null }} of its ${nodes.size} entries " +
                "open a deeper ring, the rest navigate straight to a page.",
            nodes.map { it.label },
            cm.actions.map { it.label })

        starCard("✦ Centauri — recent apps",
            "midway to the island · shares Canopus's half-moon fan",
            "The last 9 Android apps you opened, newest first, with their real " +
                "launcher icons — a live list from the system, not build.json. " +
                "Needs Usage access (Configs › Permissions); without it the ring " +
                "comes back empty.",
            listOf("(the 9 most recent apps, live)"),
            CircularMenu.actionsOf("recents_menu").map { it.label })

        // Centauri's twin on the same row. Its list is the ONLY one on this page
        // that is about this app's own pages rather than the phone's apps, which
        // is the distinction the card has to carry — the two stars are side by
        // side and wear the identical glyph.
        starCard(stringResource(R.string.onehand_star_recent_tabs_name),
            stringResource(R.string.onehand_star_recent_tabs_place, cm.starPairOffsetXDp),
            stringResource(R.string.onehand_star_recent_tabs_what),
            listOf(stringResource(R.string.onehand_star_recent_tabs_outer)),
            emptyList())

        // Polaris gets its own three lines rather than a starCard, because
        // starCard always prints an outer and an inner ring and this star has
        // neither. "Outer (0): —" would read as a star that failed to load
        // instead of a star that goes straight somewhere.
        val search = CircularMenu.searchStar()
        if (search.enabled) {
            subhead(stringResource(R.string.onehand_star_search_name))
            caption(stringResource(R.string.onehand_star_search_place, search.topOffsetDp))
            caption(stringResource(R.string.onehand_star_search_what))
            caption("  → ${search.target}")
        }

        val arc = ArcMenu.config()
        starCard("✦ Canopus — configs",
            "above the bottom-nav island · radius ${arc.radiusDp}dp",
            "The pages of the \"${arc.section}\" section, so the settings you " +
                "reach for most are one thumb-touch from Home instead of two " +
                "taps deep.",
            SectionPages.pagesFor(arc.section).map { it.label },
            CircularMenu.actionsOf("arc_menu").map { it.label })

        caption(
            "Glyph ${cm.starGlyph} and size ${cm.starSizeSp}sp are shared by all " +
            "of them (onehand.circular_menu.star), so they always match. Editing " +
            "any ring means editing build.json and shipping an APK — these are " +
            "baked at build time, not runtime settings.")
    }

    /** One star: name, where it sits, and its two rings spelled out. */
    @Composable
    private fun starCard(name: String, place: String, what: String, outer: List<String>, inner: List<String>) {
        subhead(name)
        caption(place)
        caption(what)
        caption("  ◯ Outer — apps/pages (${outer.size}): " +
            if (outer.isEmpty()) "—" else outer.joinToString(" · "))
        caption("  ◑ Inner — actions (${inner.size}): " +
            if (inner.isEmpty()) "none" else inner.joinToString(" · "))
    }

    // ───────────────────────────── 2. Edge menu ─────────────────────────────

    @Composable
    private fun addEdgeMenu(ctx: Context) {
        val cfg = OneHandConfig.effective(ctx)

        caption(
            "Two handles pinned to the screen edges, live on top of whatever app " +
            "is in front. Each handle answers several gestures, and every gesture " +
            "can be pointed at an action or at any installed app. Unlike the " +
            "stars this needs two system permissions, so it can be off.")

        // Re-read on every return to the page: the grants are made on another screen.
        var status by remember { mutableStateOf("") }
        var masterOn by remember { mutableStateOf(OneHandController.isOn(ctx)) }
        var floatingOn by remember { mutableStateOf(FloatingNavPrefs.enabled(ctx)) }
        val lifecycle = viewLifecycleOwner.lifecycle
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val overlay = if (OneHandController.canDrawOverlay(ctx)) "✓" else "✗"
                status = "Requires (grant in Configs › Permissions):\n" +
                    "Display over apps: $overlay"
                masterOn = OneHandController.isOn(ctx)
                floatingOn = FloatingNavPrefs.enabled(ctx)
            }
        }
        floatingState = floatingOn
        Text(status, color = LocalKitPalette.current.textPrimary, fontSize = 13.sp)
        // An app update can drop "Display over other apps"; then every strip silently fails to draw. Say so, with the fix.
        var overlayOk by remember { mutableStateOf(OneHandController.canDrawOverlay(ctx)) }
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { overlayOk = OneHandController.canDrawOverlay(ctx) }
        }
        if (!overlayOk) {
            Text("Edge menus cannot work: 'Display over other apps' is OFF for SuperApp (an update can revoke it).",
                color = Color(0xFFFF8A80), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("onehand:overlay-banner"))
            ConfigButton("Grant 'Display over other apps'", plate = Color(0xFFB3261E)) {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        ConfigSwitchRow("One-Hand edge handles", masterOn, tag = "master") { on ->
            if (on) {
                if (OneHandController.ready(ctx)) { masterOn = true; OneHandController.enable(ctx) }
                else { masterOn = false; Toast.makeText(ctx,
                    "Grant 'Display over apps' in Configs › Permissions",
                    Toast.LENGTH_LONG).show() }
            } else { masterOn = false; OneHandController.disable(ctx) }
        }

        // How the menu is summoned. Swipe (Samsung edge-panel style) is the default:
        // touch the edge handle + drag inward. A plain tap passes through to the app.
        subhead("Activation")
        val triggers = listOf(
            OneHandConfig.Trigger.SWIPE to "Hold & swipe in (Samsung edge style)",
            OneHandConfig.Trigger.LONG_PRESS to "Long-press the handle",
            OneHandConfig.Trigger.TOUCH to "Touch the handle",
        )
        var trigger by remember { mutableStateOf(cfg.trigger) }
        triggers.forEach { (t, label) ->
            ConfigRadioRow(label, selected = trigger == t, tag = t.name) {
                trigger = t
                OneHandPrefs.setTrigger(ctx, t); OneHandController.refresh(ctx)
            }
        }
        caption(
            "Saved as a runtime override. Swipe-in needs ${cfg.swipeThresholdDp}dp " +
            "of inward travel before it counts; long-press needs " +
            "${cfg.longPressMs}ms. Both come from onehand.defaults — under them " +
            "the touch passes through to the app underneath, which is what keeps " +
            "the handles from stealing edge swipes.")

        // Two mirrored columns: left-edge handles on the left, right on the right.
        subhead("Handle gestures")
        caption(
            "One block per handle, mirrored to the edge it lives on. Every row " +
            "is a runtime override; 'None' clears it back to the build.json " +
            "default. The app list holds your build.json favourites (★) first, " +
            "then every launchable app on the phone.")
        val (editorCfg, options) = remember(editorEpoch) { fillHandleEditors(ctx) }
        Row(Modifier.fillMaxWidth().testTag("onehand:editors"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rightColumn in listOf(false, true)) {
                Column(Modifier.weight(1f)) {
                    editorCfg.handles.filter { (it.edge == OneHandConfig.Edge.RIGHT) == rightColumn }.forEach { handle ->
                        androidx.compose.runtime.key(editorEpoch, handle.id) { addHandleEditor(ctx, handle, options) }
                    }
                }
            }
        }

        // The owner's own "reset to default", asked for on this surface after
        // task #258 changed the baked order and his stored overrides hid it.
        // ONE button for BOTH handles: the twelve entries he sent were a single
        // layout described as two blocks, so resetting one side and not the
        // other would leave him with half the design he asked for.
        ConfigButton(stringResource(R.string.onehand_edge_reset_button)) { confirmEdgeMenuReset(ctx) }
        caption(stringResource(R.string.onehand_edge_reset_caption))

        // Full disclosure: the geometry is real and it is NOT editable here.
        // Saying so beats letting someone hunt for a slider that was never built.
        subhead("Handle placement (build-time)")
        caption(cfg.handles.joinToString("\n") { h ->
            val len = if (h.lengthDp > 0) "${h.lengthDp}dp" else "${h.lengthPct}% of the edge"
            "  ${h.id}: ${h.edge.name.lowercase()} edge · ${h.positionPct}% down · " +
                "$len long · ${h.thicknessDp}dp thick · ${h.transparency}% transparent" +
                (if (h.edgeInsetDp > 0) " · inset ${h.edgeInsetDp}dp" else "")
        })
        caption(
            "Baked in onehand.handles — no runtime override exists, so changing " +
            "these means editing build.json. The inset is deliberate on curved " +
            "screens: the border pixels sit on the curve where touch is " +
            "unreliable. Gesture-nav inset: ${cfg.edgeInsetGestureDp}dp.")

        // A whole feature the page never mentioned. Off by default, and with no
        // switch anywhere, so without this block it is invisible.
        subhead("Two-finger radial menu")
        val r = cfg.radial
        caption(
            if (r.enabled)
                "ON. Hold two fingers still for ${r.holdMs}ms anywhere " +
                "(within ${r.slopDp}dp of drift) and a ${r.radiusDp}dp ring of " +
                "${r.items.size} shortcuts opens under them. Independent of the " +
                "edge handles — it has no handle to hit."
            else
                "OFF. A separate summon: two fingers held still for " +
                "${r.holdMs}ms anywhere on screen open a ${r.radiusDp}dp " +
                "ring of ${r.items.size} shortcuts, no handle needed. It has no " +
                "runtime switch — enable onehand.radial.enabled in build.json. Needs Android 14+; the detector is observe-only below that.")

        // Samsung's back-gesture zone is wider than the default strip clears (Settings > Navigation bar > Back gesture
        // sensitivity), and Edge panels sit on the right edge. Tune the strip here; the red outline below shows the result live.
        subhead("Strip width and inset")
        var stripDp by remember { mutableIntStateOf(OneHandPrefs.stripWidthDp(ctx)) }
        var insetDp by remember { mutableStateOf(OneHandPrefs.edgeInsetDp(ctx)) }
        val shownInset = insetDp ?: OneHandConfig.effective(ctx).let { c -> if (com.diegonmarcos.superapp.configs.Diagnostics.navMode(ctx) == 2) c.edgeInsetGestureDp else c.handles.firstOrNull()?.edgeInsetDp ?: 0 }
        Text("Strip width: ${stripDp}dp (default ${OneHandPrefs.STRIP_DEFAULT_DP})", color = LocalKitPalette.current.textPrimary, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConfigButton("Thinner", Modifier.weight(1f)) {
                stripDp = (stripDp - 4).coerceAtLeast(OneHandPrefs.STRIP_MIN_DP); OneHandPrefs.setStripWidthDp(ctx, stripDp); OneHandController.refresh(ctx) }
            ConfigButton("Wider", Modifier.weight(1f)) {
                stripDp = (stripDp + 4).coerceAtMost(com.diegonmarcos.superapp.onehand.EdgeHandleWindow.USER_MAX_THICKNESS_DP); OneHandPrefs.setStripWidthDp(ctx, stripDp); OneHandController.refresh(ctx) }
        }
        Text("Distance from the screen edge: ${shownInset}dp" + (if (insetDp == null) " (automatic)" else " (set by you)"),
            color = LocalKitPalette.current.textPrimary, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConfigButton("Closer", Modifier.weight(1f)) {
                insetDp = (shownInset - 4).coerceAtLeast(0); OneHandPrefs.setEdgeInsetDp(ctx, insetDp); OneHandController.refresh(ctx) }
            ConfigButton("Farther", Modifier.weight(1f)) {
                insetDp = (shownInset + 4).coerceAtMost(OneHandPrefs.INSET_MAX_DP); OneHandPrefs.setEdgeInsetDp(ctx, insetDp); OneHandController.refresh(ctx) }
            ConfigButton("Auto", Modifier.weight(1f)) { insetDp = null; OneHandPrefs.setEdgeInsetDp(ctx, null); OneHandController.refresh(ctx) }
        }
        caption(
            "Each press rebuilds the strips at once. Switch 'Show strip outline' on below to see them as red bars while you tune; " +
            "Configs > Observability > X-Ray (Diagnostics) lists where they really are and what the last touches did.")

        subhead("Troubleshooting")
        var debug by remember { mutableStateOf(OneHandPrefs.debugVisible(ctx)) }
        ConfigSwitchRow("Show strip outline (debug — bright red bars)", debug, tag = "debug") { on ->
            debug = on
            OneHandPrefs.setDebugVisible(ctx, on)
            OneHandController.refresh(ctx)  // rebuild handles with new visibility
        }
        caption(
            "The handles are invisible by default — paint them red to find out " +
            "where they actually are before blaming a gesture.")
        ConfigButton("Activate 30s (test)") {
            val ok = OneHandController.testActivate(ctx, 30_000L)
            Toast.makeText(ctx,
                if (ok) "One-Hand ON for 30s, then auto-off"
                else "Grant Display-over-apps first (Configs › Permissions)",
                Toast.LENGTH_LONG).show()
        }
        caption(
            "Turns everything on for 30 seconds and off again, so a permission " +
            "check does not leave the handles enabled.")
    }

    /** The floating button's switch state, set by [addEdgeMenu]'s resume effect and read by [addFloatingMenu] (one page, one value). */
    private var floatingState by mutableStateOf(false)

    /**
     * (Re)read the per-sector pickers' inputs from the CURRENT stored state.
     *
     * Called once while the page is composed and again after a reset (the epoch moves). A picker keeps whatever selection
     * it was constructed with, so clearing the store without re-reading would leave the discarded entries on display — the
     * classic "the button does nothing" report, from a button that did exactly what it said. Nothing from the previous
     * read is kept: the config and the option list are rebuilt whole, and the pickers are keyed on the epoch.
     */
    private fun fillHandleEditors(ctx: Context): Pair<OneHandConfig, List<Option>> {
        val cfg = OneHandConfig.effective(ctx)
        return cfg to buildOptions(cfg)
    }

    /**
     * Ask, then discard the stored edge-menu overrides.
     *
     * Confirmed because it throws away work only the owner can reproduce, and confirmed in the shape the other destructive
     * actions in this app already use: a dialog that SAYS WHAT IS LOST rather than asking "are you sure", Cancel on the
     * dismiss button, the verb on the confirm one.
     *
     * The message also lists what survives. This page carries four unrelated groups of settings in one scroll — stars,
     * edge menus, the floating button, the Home swipes — and only one of them is being discarded; a reset that left the
     * owner unsure which is a reset he will not press.
     */
    private fun confirmEdgeMenuReset(@Suppress("UNUSED_PARAMETER") ctx: Context) { showResetConfirm = true }

    @Composable
    private fun ResetConfirm(ctx: Context) {
        val stored = remember { OneHandPrefs.edgeMenuOverrideCount(ctx) }
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.onehand_edge_reset_title)) },
            text = { Text(stringResource(R.string.onehand_edge_reset_message, stored)) },
            dismissButton = {
                com.diegonmarcos.superapp.uikit.KitTextButton(onClick = { showResetConfirm = false }) { Text(stringResource(android.R.string.cancel)) }
            },
            confirmButton = {
                com.diegonmarcos.superapp.uikit.KitTextButton(onClick = {
                    showResetConfirm = false
                    resetEdgeMenu(ctx)
                }, modifier = Modifier.testTag("onehand:reset-confirm")) { Text(stringResource(R.string.onehand_edge_reset_confirm)) }
            },
        )
    }

    /** The delete, reachable ONLY from the confirm button above. */
    private fun resetEdgeMenu(ctx: Context) {
        val removed = OneHandPrefs.clearEdgeMenuOverrides(ctx)
        // Both surfaces that draw these entries, because they are two different windows: the pickers on this page, and the
        // overlay the edge-menu service owns on top of every other app.
        editorEpoch++
        OneHandController.refresh(ctx)
        Toast.makeText(ctx, getString(R.string.onehand_edge_reset_done, removed), Toast.LENGTH_LONG).show()
    }

    // ───────────────────────── 3. Floating menu ─────────────────────────

    /**
     * A separate service from the edge handles: different lifetime (both need only the overlay permission). It is a
     * START_STICKY foreground service, so once on it OUTLIVES the SuperApp — closing the app does not take the button with
     * it, which is the whole point of it and the reason it needs an off switch that sticks just as hard.
     */
    @Composable
    private fun addFloatingMenu(ctx: Context) {
        caption(
            "A round button that floats over every app and opens the same nav " +
            "menu. Runs as a foreground service, so it stays after Cloud " +
            "SuperApp is closed and returns after a reboot. Needs only " +
            "'Display over apps' — no accessibility grant.")
        // The state is READ from the store; only a user tap writes it, so opening this screen cannot start or stop the service.
        ConfigSwitchRow("Floating button (persists when the app is closed)", floatingState, tag = "floating") { on ->
            if (on && !android.provider.Settings.canDrawOverlays(ctx)) {
                floatingState = false
                Toast.makeText(ctx,
                    "Grant 'Display over apps' in Configs › Permissions",
                    Toast.LENGTH_LONG).show()
            } else {
                floatingState = on
                FloatingNavService.setEnabled(ctx, on)
            }
        }

        ConfigButton("Reset position to top-center") { FloatingNavService.resetPosition(ctx) }
        caption(
            "The button remembers wherever you drag it. This forgets that spot, " +
            "so it goes back to the middle of the top edge — measured from the " +
            "screen as it is right now, so it lands correctly whatever the size " +
            "or rotation. A visible button moves at once; a switched-off one " +
            "comes back centred.")
    }

    // ─────────────────────────── 4. Home gestures ───────────────────────────

    @Composable
    private fun addHomeGestures(ctx: Context) {
        // Runtime-editable overrides of build.json::onehand.home_swipes. These
        // fire ONLY while on the Home section (MainActivity guards on
        // currentSection == "home"); off-home the inner fragment owns the gesture.
        caption(
            "Swipes on the Home screen itself — no handle, no permission, no " +
            "star. They fire ONLY while Home is the current section: open any " +
            "other section and the page you are on owns the gesture again, so " +
            "these can never eat a list scroll or a map pan.")
        addHomeSwipePicker("Swipe up",    { HomeSwipePrefs(ctx).up },    { HomeSwipePrefs(ctx).up = it })
        addHomeSwipePicker("Swipe left",  { HomeSwipePrefs(ctx).left },  { HomeSwipePrefs(ctx).left = it })
        addHomeSwipePicker("Swipe right", { HomeSwipePrefs(ctx).right }, { HomeSwipePrefs(ctx).right = it })
        addHomeSwipePicker("Swipe down (left half)",  { HomeSwipePrefs(ctx).downLeft },  { HomeSwipePrefs(ctx).downLeft = it })
        addHomeSwipePicker("Swipe down (right half)", { HomeSwipePrefs(ctx).downRight }, { HomeSwipePrefs(ctx).downRight = it })
        caption(
            "Draw a shape on Home (a long curved stroke; straight strokes stay swipes). " +
            "The shapes and their tolerances are declared in onehand.shape_gestures.")
        for (t in com.diegonmarcos.superapp.launcher.ShapeRecogniser.declared.templates) {
            addHomeSwipePicker("Draw: ${t.label}", { HomeSwipePrefs(ctx).shape(t.id, t.action) }, { HomeSwipePrefs(ctx).setShape(t.id, it) })
        }
        caption(
            "Runtime overrides; defaults come from onehand.home_swipes, baked " +
            "into BuildConfig at build time.")
    }

    // ─────────────────────────── 5. Status bar ───────────────────────────

    /**
     * The home status bar, read from the SAME declaration the strip draws (build.json::home_status_bar), cluster by cluster
     * in the order it is drawn: a switch per element, and up / down within its cluster. Both write status_bar_prefs, which
     * the strip observes, so the bar changes while this page is open. The toolbar island's two pills are listed read-only.
     */
    @Composable
    private fun addStatusBar(ctx: Context) {
        val decl = StatusBarDecl.declared
        @Suppress("UNUSED_VARIABLE") val redraw = StatusBarPrefs.version
        val hidden = StatusBarPrefs.hidden(ctx)
        val orders = StatusBarPrefs.orders(ctx)
        caption("The top row of the Home screen, ${decl.rowHeightDp} dp tall: three clusters, left, centre and right. Every " +
            "element is declared in build.json::home_status_bar with its cluster, order and visibility, and the bar is drawn from " +
            "that list. The switches and arrows below are your overrides, kept in status_bar_prefs.")
        for (pos in StatusBarDecl.POSITIONS) {
            val els = StatusBarDecl.ordered(decl, pos, orders)
            if (els.isEmpty()) continue
            subhead(pos.replaceFirstChar { it.uppercase() })
            els.forEachIndexed { i, el ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        ConfigSwitchRow(el.label, !(hidden[el.id] ?: !el.visible), tag = "sb:${el.id}") { on -> StatusBarPrefs.setVisible(ctx, el.id, on) }
                    }
                    val ids = els.map { it.id }
                    Text("▲", color = LocalKitPalette.current.textPrimary, fontSize = 16.sp,
                        modifier = Modifier.clickable(enabled = i > 0) { StatusBarPrefs.setOrder(ctx, pos, StatusBarDecl.move(ids, i, -1)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp).testTag("sb:up:${el.id}"))
                    Text("▼", color = LocalKitPalette.current.textPrimary, fontSize = 16.sp,
                        modifier = Modifier.clickable(enabled = i < els.lastIndex) { StatusBarPrefs.setOrder(ctx, pos, StatusBarDecl.move(ids, i, 1)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp).testTag("sb:down:${el.id}"))
                }
            }
        }
        if (decl.toolbarRow.isNotEmpty()) {
            subhead("Toolbar island (read-only)")
            caption("Drawn by the shell right under the status bar, not by it.")
            for (el in decl.toolbarRow.sortedBy { it.order }) caption("${el.order}. ${el.label}")
        }
        ConfigButton("Reset the status bar to the declaration") { StatusBarPrefs.reset(ctx) }
    }

    // ──────────────────────────── small builders ────────────────────────────

    /** Section rule + heading — the top-level parts of this page. Records where it starts, for the index to scroll to. */
    @Composable
    private fun infoSection(sec: OneHandPageDecl.Section) {
        Column(Modifier.fillMaxWidth().padding(top = 20.dp).onPlaced { sectionTops[sec.id] = it.positionInParent().y.roundToInt() }) {
            Column(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF))) {}
            Text(sec.heading, color = LocalKitPalette.current.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp).testTag("onehand:section:${sec.id}"))
        }
    }

    @Composable
    private fun subhead(t: String) {
        Text(t, color = LocalKitPalette.current.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
    }

    @Composable
    private fun caption(t: String) {
        Text(t, color = LocalKitPalette.current.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
    }

    private fun buildOptions(cfg: OneHandConfig): List<Option> = buildList {
        val ctx = requireContext()
        add(Option("None", null))
        // In-app destinations, from the DERIVED action_catalogue: the
        // circular_menu.actions entries the radial star shows PLUS every
        // `action:` target the handles declare, each already carrying the
        // label and icon its tile owns. Offering only the former is what let a
        // declared sector be missing from its own picker and fall back to
        // index 0 — "None" over a working slot, three times.
        cfg.appActions.forEach {
            add(Option(
                it.label,
                GestureAction.AppTarget(it.target.removePrefix("action:")),
                // Blank is meaningful: the text-only entries carry no icon and
                // must stay text-only. iconResFor answers ic_link_tile for a
                // blank name, so it must never see one.
                it.icon.takeIf { n -> n.isNotBlank() }
                    ?.let { n -> Sections.iconResFor(ctx, n).takeIf { r -> r != 0 } }
                    ?.let { r -> ctx.getDrawable(r) },
            ))
        }
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        // Curated favourites first (from build.json), with their nice labels.
        cfg.apps.forEach {
            if (seen.add(it.pkg)) {
                // PackageManager FIRST — an installed app shows its own launcher
                // icon — then the drawable the tile declares. The fallback is not
                // decoration: these favourites include OUR fleet apps, and one
                // extracted into its own APK (#302/#304 did this to Drive) is
                // legitimately not installed, so getApplicationIcon throws and the
                // row drew a label with no glyph. Same order, same catalogue and
                // same normalised key the overlay uses, so the picker and the menu
                // cannot show a sector differently.
                val icon = AppIconCache.load(ctx, it.pkg)
                    ?: declaredAppIcon(ctx, cfg, "app:${it.pkg}")
                add(Option("★ ${it.label}", GestureAction.OpenApp(it.pkg), icon))
            }
        }
        // Then EVERY installed launchable app, alphabetical.
        val li = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(li, 0)
            .mapNotNull { ri -> ri.activityInfo?.packageName?.let { Triple(it, ri.loadLabel(pm).toString(), ri) } }
            .filter { it.first !in seen }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .forEach { (pkg, label, ri) ->
                seen.add(pkg)
                add(Option(label, GestureAction.OpenApp(pkg), ri.loadIcon(pm)))
            }
    }

    /** The drawable the DERIVED action_catalogue declares for a raw target
     *  ("app:<pkg>"), matched on the same normalised key
     *  [OneHandConfig.AppAction.key] uses — never on the raw string, which is
     *  what let one destination be two spellings. */
    private fun declaredAppIcon(ctx: Context, cfg: OneHandConfig, target: String) =
        cfg.appActions.firstOrNull { it.key == "action:" + target.removePrefix("action:") }
            ?.icon?.takeIf { n -> n.isNotBlank() }
            ?.let { n -> Sections.iconResFor(ctx, n).takeIf { r -> r != 0 } }
            ?.let { r -> ctx.getDrawable(r) }

    /** One home-swipe row: a caption + a picker over the shared action vocabulary. Reads the current value via [get],
     *  persists via [set]. */
    @Composable
    private fun addHomeSwipePicker(label: String, get: () -> String, set: (String) -> Unit) {
        Text(label, color = LocalKitPalette.current.textPrimary, modifier = Modifier.padding(top = 8.dp))
        val actions = HomeSwipePrefs.ACTIONS
        val cur = actions.indexOfFirst { it.first == get() }.coerceAtLeast(0)
        var selected by remember { mutableIntStateOf(cur) }
        ConfigDropdown(actions.map { DropdownOption(it.second) }, selected, tag = label) { pos ->
            selected = pos
            set(actions[pos].first)
        }
    }

    /** One handle's block: its title and one picker per sector. */
    @Composable
    private fun addHandleEditor(ctx: Context, h: OneHandConfig.Handle, options: List<Option>) {
        Text("${h.edge.name.lowercase().replaceFirstChar { it.uppercase() }} handle",
            color = LocalKitPalette.current.textPrimary, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp))
        for (slot in OneHandConfig.slotsFor(h.edge)) {
            Text(slot.label, color = LocalKitPalette.current.textPrimary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            val current = h.gestures[slot.key]
            val hit = options.indexOfFirst { it.action?.serialize() == current?.serialize() }
            // NOT coerceAtLeast(0). Index 0 is "None", so a target the list did
            // not contain silently displayed a WORKING sector as empty — and
            // because the picker persists what it displays, one careless tap
            // then wrote that None over the real action. That is the mechanism
            // behind all three "AI Claude is missing" reports. A target we
            // cannot name now gets its own visible row, carrying the unchanged
            // action, so it reads as wrong instead of reading as unset.
            val rowOptions =
                if (hit >= 0) options
                else options + Option(stringResource(R.string.onehand_sector_unresolved,
                    current?.serialize().orEmpty()), current)
            var sel by remember { mutableIntStateOf(if (hit >= 0) hit else rowOptions.lastIndex) }
            // A selection happens ONLY on a tap (see ConfigDropdown), so merely opening this screen writes nothing — the
            // programmatic-selection callback the old Spinner delivered, and its `suppress` flag, no longer exist.
            ConfigDropdown(rowOptions.map { DropdownOption(it.label, it.icon) }, sel, tag = "${h.id}:${slot.key}") { pos ->
                sel = pos
                OneHandPrefs.setAction(ctx, h.id, slot.key, rowOptions[pos].action)
                OneHandController.refresh(ctx)
            }
        }
    }

    companion object {
        const val TAG_TITLE = "onehand:title"
        const val TAG_INDEX = "onehand:index"
        fun newInstance() = OneHandFragment()
    }
}
