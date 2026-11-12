package com.diegonmarcos.superapp.configs

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.battery.EnergyUsageDialog
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.ui.StatusLight
import com.diegonmarcos.superapp.ui.snack
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Configs ▸ Launcher ▸ Controls — the switch board, as a Samsung-style grid of
 * quick-settings tiles. It was Configs ▸ Panel ▸ Control until #574 merged it
 * into the Launcher's Controls tab: [settings.LauncherConfigFragment] is the ONE
 * producer of that tab and hosts this fragment as a child ([newInstance] with
 * `embedded = true`), so every behaviour below — the ticker, the lights, tap and
 * hold — is this class's, unchanged, and the page around it owns none of it.
 *
 * Compose since #773 (was a LinearLayout of hand-built tiles): the tile is a composable, a tile's reading is state, and the
 * ticker is a LaunchedEffect that runs only while the page is RESUMED.
 *
 * Every group and every tile is declared in `build.json::ui.control_panel` and
 * implemented in [DeviceControls]. This file draws them and nothing else: it
 * decides no capability, names no permission, knows no Settings action, and
 * knows nothing about which group a tile is in.
 *
 * ── The tile ──────────────────────────────────────────────────────────────
 * A filled round badge holding the icon, the label under it, the status light
 * under that. TAP switches; HOLD opens the settings screen behind it.
 *
 * BLACK WHEN OFF, WHITE WHEN ON — AS AN INVERSION, NOT AS TWO LITERALS. The
 * badge fill and the icon on it are the SAME TWO THEME COLOURS SWAPPED: off is
 * `surface` filled with `text_primary`, on is `text_primary` filled with
 * `tile_ink`. Two consequences, and both are the reason it is written this
 * way. Contrast is symmetric, so a theme whose off tile is legible cannot have
 * an illegible on tile — there is no pair of colours that can be got wrong on
 * one side only. And it survives a theme: under a black theme where
 * `text_primary` IS white and `tile_ink` IS black, so the owner's words come
 * out exactly, while under Minimalistic Black the same rule reads as terminal
 * green on black inverting to black on green. Hard-coding #000 and #FFF would
 * have given the first theme and made the icon vanish on the others.
 *
 * THE LIGHT SITS OUTSIDE THE FILL, deliberately. [StatusLight]'s colours are
 * the ones that clear WCAG AA against this app's WINDOW background — its own
 * tester recomputes those ratios. Drawing a green dot on a white badge would
 * spend that guarantee: 4.5:1 on black is about 1.8:1 on white. So the badge
 * inverts and the light stays on the page behind it, where the arithmetic that
 * chose its colours still applies.
 *
 * ── Tap and hold do not fight ─────────────────────────────────────────────
 * The long-press listener ALWAYS returns true. That is the whole separation
 * and it is a platform contract, not a heuristic: `View.onTouchEvent` runs
 * `performClick()` on ACTION_UP only when `mHasPerformedLongPress` is false,
 * and that flag is set only by a `performLongClick()` that RETURNED TRUE.
 * Returning false — or leaving the tile without a long-press listener — is
 * what makes a hold toggle the control on the way to opening its menu.
 *
 * There is exactly ONE path from a gesture to a write, the tile's own click
 * listener, and nothing calls it back. This app has already shipped a tile
 * that fired twice because one tap took two routes into the same handler
 * (149 duplicates in one trace), and that was fixed by removing the second
 * route rather than by debouncing the first. No second route is introduced
 * here and no debounce is needed to make up for one.
 *
 * ── A hold on a tile with nothing behind it ───────────────────────────────
 * Six of the seventeen controls have no settings screen — there is nowhere for
 * a hold to go. They still ANSWER: the hold says, in the string table and in
 * both locales, that this one has no settings screen and that a tap switches
 * it. A long-press that silently did nothing is indistinguishable from a
 * broken tile, which is the failure shape this repository carries a CI guard
 * against elsewhere.
 *
 * ── One control, two tiles, one piece of state ────────────────────────────
 * A control flagged `battery_hungry` is drawn twice — once in its functional
 * group, once under Battery Hungers — and the two tiles are two views of ONE
 * thing. There is no per-tile copy of the state to drift: the only state a
 * tile holds is the id it was declared with, [DeviceControls.byId] maps that
 * id to exactly one [DeviceControls.Control], and both [refresh] and [write]
 * fan a landed reading out to EVERY tile bearing the id. That is why they
 * address rows by `rows.filter { it.id == ... }` and never by `firstOrNull` —
 * a `firstOrNull` here is precisely the bug where a tile reads on in one group
 * and off in another.
 *
 * [refresh] also reads each id ONCE rather than once per tile. Seventeen
 * controls drawn as twenty-six tiles would otherwise be twenty-six binder
 * calls and shell probes per tick for seventeen answers.
 *
 * ── What it will not draw ─────────────────────────────────────────────────
 * A [DeviceControls.Control] with no `set` does not switch on a tap. Its tile
 * opens the exact system screen for it and shows the live state read-only.
 * That is the whole difference between this page and the version of it that
 * would have been quicker to write, where Wi-Fi and airplane mode are tiles
 * that light up under the thumb and change nothing on the device.
 *
 * A tile that IS switchable never displays what the user asked for. After a
 * write it re-reads the device and shows THAT, so a refused write is visible
 * as the tile going back — with the reason in a snackbar — rather than as a
 * control that looks set and is not. Nothing here is a [android.widget.Switch]
 * any more, and that removes a whole class of bug with it: a repaint assigns a
 * plain field, so it cannot re-fire a listener and re-issue the last write.
 *
 * ── The lights ────────────────────────────────────────────────────────────
 * Every tile carries a [StatusLight]. It is painted from ONE source and only
 * that source: the [DeviceControls.Control.read] for that id. There is no
 * second state path, no cached colour, no per-tile special case. A control
 * whose read declines to answer is drawn UNKNOWN, never green and never red.
 *
 * THE LIGHT IS NOT THE TILE. The tile shows the SETTING, which is what was
 * asked for; the light shows the STATE, which is what is true. They agree most
 * of the time and the whole page exists for the times they do not — a mesh
 * tunnel the engine dropped, an accept loop that died, an overlay the system
 * killed. A light that were merely the tile's fill recoloured would be green
 * in exactly the situation it is here to catch.
 *
 * AND WHERE THERE IS NO STATE TO SHOW, IT SAYS SO. Some controls have nothing
 * readable but a preference this app stored — see
 * [DeviceControls.Control.observed] for which, and why each one. Those tiles
 * draw "? Not verifiable" permanently, which is the honest answer and the one
 * the owner can act on: it says go and look.
 *
 * STALE IS UNKNOWN. A reading is kept for [STALE_MS] and then stops counting
 * as an answer. The page stops reading the moment it is not visible, so by the
 * time the owner comes back every reading has aged out and the lights say
 * Unknown until fresh reads land. A green dot recalling an hour-old truth is
 * the same lie as a green dot that was never driven by anything.
 *
 * The tile's own fill deliberately does not age out. Un-filling a tile because
 * a read grew old would look like the device turning off; the fill keeps the
 * last real reading and the light carries the honesty about how old it is.
 *
 * ── Accessibility ─────────────────────────────────────────────────────────
 * Replacing labelled rows with icons took away the text a screen reader was
 * reading, so each tile carries the whole fact as its own
 * `contentDescription`: its name and the state, from [StatusLight.description] —
 * the same shared sentence the list rows used, not a second phrasing of it.
 * The icon, label and light inside are marked not-important so TalkBack
 * announces one node and not four fragments of one.
 *
 * A hold-to-open affordance is invisible to somebody who cannot see the grid,
 * so the long-press action is RENAMED through [ViewCompat] — TalkBack then
 * offers "Open settings" by name instead of a generic long press nobody knows
 * is there.
 *
 * ── Battery ───────────────────────────────────────────────────────────────
 * This is a phone. The ticker runs on [onResume] and is cancelled on
 * [onPause], so a page nobody is looking at costs nothing: no wakeups, no
 * binder calls into the WireGuard engine, no shell channel probes. Same shape,
 * and the same [REFRESH_MS] cadence, as the launcher status strip.
 *
 * ── Threading ─────────────────────────────────────────────────────────────
 * Reads and writes both block: shell channels, binder calls into the WireGuard
 * engine, a torch callback's first delivery. All of it runs on one background
 * thread and lands back on the main looper; [DeviceControls] never touches the
 * UI itself. One thread rather than a pool on purpose — these are a dozen
 * short reads, and a pool of them all binding services at once is how a
 * settings page becomes the thing that janks.
 */
class ControlFragment : KitComposeFragment() {

    /** What the device last said about one control, and WHEN. [readAt] 0 means never read, which is an unknown like any
     *  other. Written only by a read that landed ([landed]) — the only thing a tile is ever painted from. A control drawn
     *  in two groups has ONE of these and both tiles read it, which is what makes the Battery Hungers copy a second view of
     *  one control rather than a second control. */
    internal class Reading(val state: Boolean?, val readAt: Long, val blocked: String)

    private val readings = mutableStateMapOf<String, Reading>()

    /** Ids whose write is in flight: every tile of the control is disabled meanwhile, so a second tap cannot race the first. */
    private val writing = mutableStateMapOf<String, Boolean>()

    /** Bumped by the ticker so a reading ages into Unknown even when no read lands (a hung read must not hold the old answer). */
    private var aged by androidx.compose.runtime.mutableIntStateOf(0)

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "control-panel") }
    private val ioDispatcher = io.asCoroutineDispatcher()

    override fun palette() = LauncherPalette.kit(requireContext())

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    @Composable
    override fun Content() {
        val ctx = requireContext()
        val scope = rememberCoroutineScope()
        val p = LocalKitPalette.current
        val columns = DeviceControls.columns
        val lifecycle = viewLifecycleOwner.lifecycle
        // Age the lights, then read the device again — in that order, so a read that hangs or never comes back shows as
        // Unknown rather than as the previous answer held indefinitely. Runs ONLY while RESUMED: a page nobody is looking
        // at costs nothing (no wakeups, no binder calls into the WireGuard engine, no shell channel probes).
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    aged++
                    refresh(scope)
                    delay(REFRESH_MS)
                }
            }
        }
        val embedded = arguments?.getBoolean(ARG_EMBEDDED) == true
        // Embedded, the host page is the scroller: a scroll container inside a scroll container would fight it for every
        // vertical drag.
        val scroll = if (embedded) Modifier else Modifier.verticalScroll(rememberScrollState())
        Column(Modifier.fillMaxWidth().then(scroll).padding(16.dp)) {
            Text(stringResource(R.string.control_title), color = p.textPrimary, fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 4.dp).testTag(TAG_TITLE))
            // The paragraph is a resource, but the four words it quotes are asked of StatusLight at render time — so a
            // page that stopped drawing one of them could not go on describing it.
            Text(stringResource(R.string.control_caption,
                StatusLight.text(ctx, StatusLight.State.ON), StatusLight.text(ctx, StatusLight.State.OFF),
                StatusLight.text(ctx, StatusLight.State.UNKNOWN), StatusLight.text(ctx, StatusLight.State.UNVERIFIABLE),
                REFRESH_MS / 1000), color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            var drawn = 0
            for (group in DeviceControls.groups) {
                GroupHeading(groupText(ctx, group.labelRes, group.label), groupText(ctx, group.subtitleRes, group.subtitle))
                DetailsLink(group)
                // Chunked into rows of `columns`; the last row is padded with weightless spacers so four tiles and two
                // tiles start at the same left edge instead of the short row spreading itself out.
                for (line in group.rows.chunked(columns)) {
                    Row(Modifier.fillMaxWidth()) {
                        for (decl in line) {
                            val control = DeviceControls.byId[decl.id]
                            // Dropped, never drawn — but the column still has to be held open or the tiles after it
                            // slide left out of their grid positions.
                            if (control == null) Spacer(Modifier.weight(1f)) else {
                                drawn++
                                Tile(decl, control, scope, Modifier.weight(1f))
                            }
                        }
                        repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            // A build whose declaration failed to parse must say so rather than render an empty page that reads as
            // "nothing is controllable here".
            if (drawn == 0) Text(stringResource(R.string.control_none_declared), color = p.textSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp))
        }
    }

    // ─────────────────────────────── Tiles ──────────────────────────────

    /**
     * A filled round badge holding the icon, the label under it, the status light under that. TAP switches (a control that
     * cannot be set sends its tap where the setting lives); HOLD opens the settings screen behind it, and ALWAYS answers.
     * Tap and hold do not fight: combinedClickable runs onClick only for a press that was not a long press.
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Tile(decl: DeviceControls.Row, control: DeviceControls.Control, scope: CoroutineScope, modifier: Modifier) {
        val ctx = LocalContext.current
        val view = LocalView.current
        val p = LocalKitPalette.current
        aged   // a reading older than STALE_MS stops counting; the ticker re-reads this every tick
        val row = readings[decl.id]
        val reading = reading(row)
        val on = row?.state == true
        val blocked = row?.blocked.orEmpty()
        val state = StatusLight.of(reading, control.observed)
        // BLACK WHEN OFF, WHITE WHEN ON — the same two theme roles swapped, never two literals.
        val fill = if (on) p.textPrimary else p.surface
        val ink = if (on) p.tileInk else p.textPrimary
        val description = StatusLight.description(ctx, decl.label, state)
        Column(
            modifier.widthIn(min = TOUCH_TARGET_DP.dp).heightIn(min = TOUCH_TARGET_DP.dp)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .combinedClickable(
                    enabled = blocked.isEmpty() && writing[decl.id] != true,
                    onLongClickLabel = stringResource(R.string.control_tile_open_action),
                    onClick = {
                        Haptics.tap(view)
                        if (control.set != null) write(decl, control, !(currentlyOn(decl.id)), scope)
                        else openOrSayThereIsNowhere(decl, control)
                    },
                    onLongClick = {
                        Haptics.tap(view)
                        openOrSayThereIsNowhere(decl, control)
                    },
                ).padding(8.dp).testTag(tileTag(decl.id)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(BADGE_DP.dp).clip(CircleShape).background(fill).testTag("control:badge:${decl.id}"),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(Sections.iconResFor(ctx, decl.icon)), contentDescription = null, tint = ink,
                    modifier = Modifier.size(ICON_DP.dp))
            }
            Text(decl.label, color = p.textPrimary, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis)
            Text(StatusLight.text(ctx, state), color = Color(StatusLight.colour(ctx, state)), fontSize = 10.sp,
                textAlign = TextAlign.Center, modifier = Modifier.testTag("control:light:${decl.id}"))
            // Only ever visible when the control names a reason it cannot move. A tile that just looked inert would say
            // nothing about the grant it is waiting for.
            if (blocked.isNotEmpty()) Text(blocked, color = colorResource(R.color.control_blocked), fontSize = 9.sp,
                textAlign = TextAlign.Center, modifier = Modifier.testTag("control:note:${decl.id}"))
        }
    }

    /**
     * The settings screen behind a control, or a sentence saying there is not one.
     *
     * NEVER A SILENT RETURN. Six controls have no `open`, and a hold on one of them that did nothing at all would be
     * indistinguishable from a tile that is broken, from a gesture that was not registered, and from a page that has
     * stopped responding.
     */
    private fun openOrSayThereIsNowhere(decl: DeviceControls.Row, control: DeviceControls.Control) {
        val open = control.open
        if (open != null) open(requireContext())
        else view?.snack(getString(R.string.control_no_menu, decl.label))
    }

    /** What this control is showing right now: every tile of it reads the same entry, so any of them answers. */
    private fun currentlyOn(id: String): Boolean = readings[id]?.state == true

    /**
     * Re-read every control from the device and redraw every tile of it. ONE READ PER CONTROL, not one per tile: the ids
     * are distinct-ed first, because a control drawn in two groups is still one thing to ask about.
     */
    private fun refresh(scope: CoroutineScope) {
        val ctx = context ?: return
        for (id in DeviceControls.groups.flatMap { g -> g.rows.map { it.id } }.distinct()) {
            val control = DeviceControls.byId[id] ?: continue
            scope.launch(ioDispatcher) {
                val state = runCatching { control.read(ctx) }.getOrNull()
                val blocked = runCatching { control.blocked(ctx) }.getOrDefault("")
                withContext(kotlinx.coroutines.Dispatchers.Main) { if (isAdded) landed(id, state, blocked) }
            }
        }
    }

    /** A read came back. This is the ONLY writer of a control's reading, and it writes the one entry EVERY tile bearing the id reads. */
    private fun landed(id: String, state: Boolean?, blocked: String) {
        readings[id] = Reading(state, SystemClock.elapsedRealtime(), blocked)
    }

    /**
     * The control's reading, or null once it has aged out.
     *
     * A reading older than [STALE_MS] is not an answer any more, and a control that has never been read (readAt 0) never
     * had one. Both are the same fact to the owner — nobody currently knows — so both are null, which [StatusLight] draws
     * as Unknown rather than picking a colour. The tile's own fill deliberately does not age out: un-filling a tile because
     * a read grew old would look like the device turning off.
     */
    private fun reading(row: Reading?): Boolean? =
        if (row != null && row.readAt != 0L && SystemClock.elapsedRealtime() - row.readAt <= STALE_MS) row.state
        else null

    /**
     * Ask the device to change, then show what the device says. Every tile of this control is disabled for the duration
     * so a second tap — on either of them — cannot race the first, and the result is always a fresh read:
     * [DeviceControls.Verdict] decides only whether to explain, never what to display.
     */
    private fun write(decl: DeviceControls.Row, control: DeviceControls.Control, want: Boolean, scope: CoroutineScope) {
        val set = control.set ?: return
        val ctx = context ?: return
        writing[decl.id] = true
        scope.launch(ioDispatcher) {
            val verdict = runCatching { set(ctx, want) }.getOrElse {
                DeviceControls.Verdict(false, it.message ?: it::class.java.simpleName)
            }
            val state = runCatching { control.read(ctx) }.getOrNull()
            val blocked = runCatching { control.blocked(ctx) }.getOrDefault("")
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                writing.remove(decl.id)
                if (!isAdded) return@withContext
                landed(decl.id, state, blocked)
                if (!verdict.ok) {
                    view?.snack(getString(R.string.control_write_refused, decl.label, verdict.detail),
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    // ───────────────────────────── Chrome ───────────────────────────────

    /**
     * A group's wording, from the string table when the declaration names a key for it. The key is DATA — build.json says
     * which resource a group's heading is — so adding a group is still a declaration edit, while the words it shows are
     * translated like everything else on the page. A declaration that names no key, or names one this build does not
     * have, falls back to the English text sitting beside it rather than drawing an empty heading.
     */
    private fun groupText(ctx: Context, resName: String, fallback: String): String {
        if (resName.isBlank()) return fallback
        val id = ctx.resources.getIdentifier(resName, "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else fallback
    }

    @Composable
    private fun GroupHeading(label: String, subtitle: String) {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
            Text(label.uppercase(), color = p.accent, fontSize = 12.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = p.textSecondary, fontSize = 11.sp)
        }
    }

    /**
     * The hand-off under a group heading, or nothing for a group that declares none — see [DeviceControls.Group.details].
     *
     * WHY THIS IS A LINK AND NOT MORE TILES. Battery Hungers gathers the controls a human judged expensive. That is a
     * useful thing to have in one row and it is NOT an answer to "which apps are eating my battery": these are controls,
     * not apps. The measured answer already exists — `libs:battery`'s [EnergyUsageDialog] — so this opens THAT, rather than
     * growing a second per-app list here that would drift from it. It is not a tile (no light: there is nothing about it to
     * be on or off) and it FAILS CLOSED, twice: an unknown surface, or a label missing from the string table, draws nothing.
     */
    @Composable
    private fun DetailsLink(group: DeviceControls.Group) {
        val ctx = LocalContext.current
        val view = LocalView.current
        val p = LocalKitPalette.current
        if (group.details.isBlank()) return
        val labelId = ctx.resources.getIdentifier(group.detailsRes, "string", ctx.packageName)
        if (labelId == 0) return
        if (group.details != DETAILS_ENERGY_USAGE) return
        Text(ctx.getString(labelId), color = p.accent, fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp).clickable(
                onClickLabel = ctx.getString(R.string.control_group_details_action),
            ) {
                Haptics.tap(view)
                EnergyUsageDialog().show(parentFragmentManager, EnergyUsageDialog.TAG)
            }.testTag("control:details:${group.id}"))
    }

    companion object {

        /** Poll cadence WHILE VISIBLE — the launcher status strip's, so the two live surfaces in this app do not tick at
         *  two different rates. */
        private const val REFRESH_MS = 10_000L

        /** How long a reading counts as an answer. Two missed ticks plus slack: long enough that an ordinary slow read does
         *  not blink the light, short enough that nothing on screen is ever a minute old. */
        private const val STALE_MS = 25_000L

        /** The one surface [DetailsLink] knows how to open, named as `build.json` names it. A declaration is data; the
         *  class it resolves to cannot be, so the mapping lives here. */
        private const val DETAILS_ENERGY_USAGE = "energy_usage"

        /** The filled round plate behind the icon: at most 4dp wider than the icon (was a 56dp circle, white when lit -
         *  the owner's "white box too big"). The 48dp touch target is the whole tile, [TOUCH_TARGET_DP]. */
        private const val BADGE_DP = 26
        private const val ICON_DP = 22

        /** The floor for a whole tile, badge and label together. Android's accessibility minimum is 48dp in both
         *  directions and a grid of tiles below it is a grid that gets mis-tapped. */
        private const val TOUCH_TARGET_DP = 48

        private const val ARG_EMBEDDED = "embedded"

        const val TAG_TITLE = "control:title"
        fun tileTag(id: String) = "control:tile:$id"

        /** [embedded]: drawn inside another page's scroll view instead of wearing its own. */
        fun newInstance(embedded: Boolean = false): ControlFragment =
            ControlFragment().apply { arguments = Bundle().apply { putBoolean(ARG_EMBEDDED, embedded) } }
    }
}
