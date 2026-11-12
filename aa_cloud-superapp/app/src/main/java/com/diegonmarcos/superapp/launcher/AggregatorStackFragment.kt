package com.diegonmarcos.superapp.launcher

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.cloud.ContainerSheet
import com.diegonmarcos.superapp.settings.GridColumns
import com.diegonmarcos.superapp.settings.redrawOnGridColumns
import com.diegonmarcos.superapp.shell.Collapsible
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import androidx.compose.runtime.withFrameNanos

/**
 * Stack-render variant of an aggregator section. When an aggregator declares `stack_*` in build.json, the shell launches
 * this fragment instead of [TileGridFragment]. Each panel becomes a collapsable card whose body is dispatched by
 * [Sections.StackPanel.kind]:
 *
 *   c3_public / c3_private   → embedded [C3HealthFragment] (scope-filtered)
 *   wg_mesh                   → embedded [C3MeshFragment]
 *   rss                       → embedded [RssFeedFragment]
 *   calendar_month / calendar_agenda / tasks → embedded child fragments
 *   notification_center       → per-app grouped notification list, drawn inline
 *   linktree_slide            → grouped link grid sourced from data/linktree.json
 *   link_grid                 → grouped link grid declared inline in build.json
 *   tile_row                  → inline mini-tile row (deep links into sections)
 *   mail_accounts             → per-account list with read/unread placeholders
 *   chat_matrix /chat_mattermost  → server list, tap → opens the chat section
 *   feed                       → config-driven repo/run feed; `source` picks the fetcher (github_runs |
 *                                github_run_stats | github_commits | gitea_commits | dagu_runs)
 *   open_link                 → single tappable row that opens a URL
 *   placeholder               → empty hint card
 *
 * All panels default to **expanded** (matches user spec "all uncolapsed one after the other"). Tap the header chevron to
 * collapse/expand.
 *
 * An INBOX card (mail_accounts, chat_*, and a stats card that names an app) keeps its summary counts and draws that
 * app's own notification boxes underneath them: the counts say how much is in there, the boxes say what arrived.
 *
 * Every app/publisher box inside a card carries two group-level controls. The double tick marks that box's
 * notifications read; ARCHIVE moves the box itself out of its card into the page's collapsed Archive section at the
 * bottom. Both are per-page state in [StackFilters], so both survive the process death this launcher sees many times an
 * hour.
 *
 * Compose since #773 (was ScrollView + SwipeRefreshLayout + 3600 lines of hand-built Views). The data rules live in
 * [AggregatorModel] (pure, unit-tested), the page's memory in [AggregatorState], the notification half in
 * AggregatorNotify, the repo/run feeds in AggregatorFeeds and the cards in AggregatorPanels. The pull-down gesture is
 * the Material pull-to-refresh, armed only on a page with something to re-ask (a spinner over a page with nothing to
 * re-ask is a control reporting a fetch it never made).
 */
class AggregatorStackFragment : KitComposeFragment(),
    TileGridFragment.TileClickListener,
    Collapsible {

    private val sectionId: String get() = arguments?.getString(ARG_SECTION_ID).orEmpty()
    private val label: String get() = arguments?.getString(ARG_LABEL).orEmpty()
    private val mode: String get() = arguments?.getString(ARG_MODE).orEmpty()

    private var state: AggregatorState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        redrawOnGridColumns(GridColumns.Kind.CLOUD)
    }

    override fun palette() = LauncherPalette.kit(requireContext())

    /** Called by the shell when the user re-taps the bottom-nav slot they're already on. Collapses every panel if any is
     *  open; expands every panel if all are closed. */
    override fun toggleAllCollapsed(): Boolean = state?.toggleAllCollapsed() ?: false

    /** Badge live state goes stale the moment a service dies, so the panes are redrawn on every return to the page and
     *  not only when first built. */
    override fun onResume() {
        super.onResume()
        state?.let { it.badgeEpoch++ }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val ctx = requireContext()
        val sec = remember { Sections.byId(sectionId) }
        if (sec == null) { AggEmptyHint("Section not found: $sectionId", Modifier.testTag(AggTags.EMPTY_PAGE)); return }
        val panels = remember { Sections.aggregatorStackFor(sec, mode) }
        if (panels.isEmpty()) {
            AggEmptyHint("No panels for ${sec.label} · $mode", Modifier.testTag(AggTags.EMPTY_PAGE)); return
        }
        // The page id is the prefs key whether or not this page draws a toggle row: C3 ▸ Observability carries
        // notification cards and declares no filters, and its swipes must not land in a blank-keyed bucket shared with
        // every other stackless page. The watermark moves on EVERY visit (it is read before it is advanced, in the state).
        val filters = remember { Sections.stackFiltersFor(sec, mode) }
        // #1672 the Notify sub-pages share ONE state key (state_pages), so Configs, read marks and the Archive agree.
        val stateKey = remember { Sections.statePageFor(sec, mode) }
        val st = remember { AggregatorState(ctx, stateKey, panels, filters) }
        state = st
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val anchors = remember { PageAnchors(st, scroll) }
        val actions = remember {
            PageActions(
                open = { target ->
                    when {
                        target.isEmpty() -> Unit
                        // `anchor:` never leaves the page — it scrolls this stack to the panel (or dashboard sub-table)
                        // that claimed the id. Checked before the URI tests because it is not a URI.
                        anchors.dispatch(target, scope) -> Unit
                        // URI-shaped targets bubble up to the activity, which owns the intent:// parsing +
                        // browser_fallback_url handling — same path tile clicks take.
                        else -> onTileClicked(target)
                    }
                },
                openContainer = { name, label, url ->
                    val act = activity
                    if (act is FragmentActivity) ContainerSheet.show(act, name, label, url) else onTileClicked(url)
                },
                host = this@AggregatorStackFragment,
            )
        }

        AggregatorEffects(st)
        // A cross-page `page:<section>/<page>#<anchor>` link left its fragment waiting for whichever stack answers to that
        // page. Waits for the first layout pass (offsets are all zero before it).
        LaunchedEffect(Unit) {
            StackAnchors.consumePending("$sectionId/$mode")?.let { id ->
                withFrameNanos { }
                anchors.dispatch(StackAnchors.PREFIX + id, scope)
            }
        }

        // A page that carries notification containers sits on One UI's dark, blurred panel shade.
        val shade = remember(panels) { panels.any { it.kind == "notification_center" || it.kind == "class_inbox" ||
            it.kind == "notification_badges" || it.kind == "notification_badge_configs" } }
        val page: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxSize()
                    .then(if (shade) Modifier.background(NotifCard.panel) else Modifier)
                    .verticalScroll(scroll).onGloballyPositioned { anchors.content(it) }
                    .padding(if (shade) 16.dp else 12.dp).testTag(AggTags.PAGE),
            ) {
                CompositionLocalProvider(LocalAnchors provides anchors) {
                    RefreshNote(st)
                    // `above_filters` panels lead the page, ahead of the Filters they do not answer to (Notify's
                    // persistent badges, #580).
                    val (above, rest) = panels.withIndex().partition { it.value.aboveFilters }
                    val (bottom, below) = rest.partition { it.value.bottom }
                    for ((i, panel) in above) if (st.visible(panel)) PagePanel(st, i, panel, anchors, actions)
                    for ((i, panel) in below) if (st.visible(panel)) PagePanel(st, i, panel, anchors, actions)
                    // One UI's bottom pill row ("Notification settings" / "Clear"), centred under the cards.
                    NotifyListActions(st)
                    ArchiveSection(st, actions.open)
                    // Every classified card hidden means the toggle did it, not a dead page — say so, because an empty
                    // screen with no reason reads as broken and teaches the user to stop opening it.
                    if (st.allSourcesHidden) {
                        AggEmptyHint("No panel on this page carries ${st.sourceLabel()} notifications. Switch Source back to Both.",
                            Modifier.testTag(AggTags.SOURCE_EMPTY))
                    }
                    // #1068: every control of the page (the Filters, badge settings) is ONE folded section at the very bottom,
                    // below all the cards, the Archive and the page actions.
                    // #1672 a page that shares its state with a Configs sub-page leaves the configuration to that page;
                    // the Configs sub-page itself (it declares the badge-configs panel) draws it open, on one level.
                    val configsPage = bottom.any { it.value.kind == "notification_badge_configs" }
                    val shared = sec.statePages.containsKey(mode)
                    if (!shared || configsPage) ConfigSection(st, filters.isNotEmpty(), bottom, anchors, actions, flat = shared && configsPage)
                }
            }
        }
        if (st.refreshable) {
            PullToRefreshBox(isRefreshing = st.refreshing, onRefresh = { st.startRefresh(scope) }, modifier = Modifier.fillMaxSize()) {
                page()
            }
        } else page()
    }

    /** Forward tile clicks to the activity-level dispatcher (same one [TileGridFragment] uses for its tile grammar). */
    override fun onTileClicked(tileId: String) {
        (activity as? TileGridFragment.TileClickListener)?.onTileClicked(tileId)
    }

    companion object {
        private const val ARG_SECTION_ID = "section_id"
        private const val ARG_LABEL = "label"
        private const val ARG_MODE = "mode"

        fun newInstance(sectionId: String, label: String, mode: String): AggregatorStackFragment =
            AggregatorStackFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION_ID, sectionId)
                    putString(ARG_LABEL, label)
                    putString(ARG_MODE, mode)
                }
            }
    }
}
