package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.media.BudsApp
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.notificationcenter.BadgeCustomization
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.notificationcenter.NotifyGroups
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1603b — the SETTINGS pane of Configs ▸ Launcher ▸ Notify: the one place on/off control lives (the viewer, [com.diegonmarcos.superapp.launcher.BadgeCards], carries NO switches).
 *
 * ONE clean list, DERIVED from the same declaration that draws both surfaces (`build.json::ui.notification_center`, #580):
 * a "turn all on/off" master and a "Launch all" at the top, then one row per DECLARED badge (in the owner's `card.rank`
 * order). Each row shows the badge AS IT IS IN THE SHADE — the RemoteViews the platform builds from the badge's own posted
 * notification ([BadgeServices.live] → [BadgeServices.view]), the very template the shade inflates, so this file has NO
 * renderer of its own and cannot drift from the shade (a badge added or restyled in its producer appears here the same way,
 * #535) — then the state line (why a badge is not in the shade), Open / Launch (put a swiped or dead badge back in the
 * shade), and the badge's two owner switches: `enabled` (show this badge) and `persistent` (keep it pinned). The switches
 * are read and written through [BadgeCustomization] (`KEY_ENABLED` / `KEY_PERSISTENT`), which the shade and the restart
 * path already obey, so a flip here is the same flip everywhere. The switch labels are the declared option labels, never a
 * string typed here — adding a badge is a build.json edit and no edit in this file.
 *
 * This pane is a VIEW, not a producer (#497/#515 single centre): it draws through [BadgeServices], and never posts, cancels
 * or keeps a notification/store of its own (Launch only re-starts a badge's own owner — a button on an existing producer).
 * The old Apps Push / Apps Keep Alive toggle tables and the group switches are gone (#1603b) — a flat per-declared-badge
 * list replaces them. The host redraws the pane on every return and on every switch moved, via [redraw].
 *
 * #1794 reorganised (no field lost, [PREVIOUS_FIELDS] + BadgePanesParityTest): the badges are grouped under the SAME
 * declared categories the Notify sub-pages use (build.json `groups` in `page_order`, [com.diegonmarcos.superapp.launcher.badgeSections]);
 * a row's state (ON/OFF/LIVE...) sits on the right of its title, on the title's row, with the reason under it; the
 * per-badge Launch button is gone by owner order because "Show this badge" now launches the badge when switched on
 * ([setShown]); the persistent switch reads its declared label ("Make it Persistent"). The switch's checked state IS
 * [BadgeCustomization.isEnabled], the same call the status line and the posting decision make.
 */
object BadgePanes {

    private const val REBUILD_DELAY_MS = 1200L

    /**
     * #1794 field parity. Every field the pane drew BEFORE the reorg (pane-level and per badge row), mapped to what draws
     * it now; BadgePanesParityTest renders the pane and finds each one. A field may leave only through [OWNER_REMOVED].
     */
    val PREVIOUS_FIELDS: List<String> = listOf(
        "pane:caption", "pane:launch-all", "pane:turn-all",
        "row:title", "row:live-view", "row:state", "row:reason", "row:open", "row:launch", "row:enabled", "row:persistent",
    )

    /** Fields removed by OWNER ORDER, with the order. Nothing else may be missing. */
    val OWNER_REMOVED: Map<String, String> = mapOf(
        "row:launch" to "#1794 owner-ordered: \"Show this badge\" already launches the badge, so the separate Launch button goes",
    )

    /** The switch's checked state: the ONE answer the status line and the posting decision read too (#1794). */
    fun shown(ctx: Context, b: BadgeDeclaration.Badge): Boolean = BadgeCustomization.isEnabled(ctx, b)

    /**
     * "Show this badge" moved: store it, apply it to the shade in both directions ([NotifyGroups.apply]) and, when it
     * goes ON, launch the badge (the owner's #1794 order: the switch IS the launch). Returns why it did not launch, or null.
     */
    fun setShown(ctx: Context, b: BadgeDeclaration.Badge, on: Boolean): String? {
        BadgeCustomization.setEnabled(ctx, b, on)
        runCatching { NotifyGroups.apply(ctx) }
        return if (on) runCatching { BadgeServices.launch(ctx, b) }.getOrElse { it.message } else null
    }

    /** "Turn all badges on / off": the same path as each row's switch, for every declared badge. */
    fun setAllShown(ctx: Context, badges: List<BadgeDeclaration.Badge>, on: Boolean): Map<BadgeDeclaration.Badge, String?> {
        for (b in badges) BadgeCustomization.setEnabled(ctx, b, on)
        runCatching { NotifyGroups.apply(ctx) }
        return if (on) badges.associateWith { runCatching { BadgeServices.launch(ctx, it) }.getOrElse { e -> e.message } }
        else emptyMap()
    }

    /** The settings pane — the single badge-box drawer (#580, T9): the host binds the badge panel to this one entry. */
    @Composable
    fun badges(redraw: () -> Unit) {
        val ctx = LocalContext.current
        val view = LocalView.current
        val declared = BadgeServices.declared
        val badges = BadgeDeclaration.rankOrder(declared, BadgeDeclaration.badges(declared))
        // #1794 the declared categories of the Notify sub-pages (Media, Me Infos, Device Infos, Actions, then any
        // ungrouped badge), in page_order: one list decides the page, the shade bundles and this pane.
        val sections = remember { com.diegonmarcos.superapp.launcher.badgeSections(ctx) }
        Column(Modifier.fillMaxWidth()) {
            if (badges.isEmpty()) {
                caption(ctx.getString(R.string.push_none_badges))
                return@Column
            }
            Box(Modifier.testTag("pane-field:caption")) { caption(ctx.getString(R.string.notify_settings_sub)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PaneButton(ctx.getString(R.string.push_launch_all)) { launched(ctx, view, redraw, BadgeServices.launchAll(ctx)) }
            }
            // The master: ON only while every badge's switch is on, so it reads the list's true state. Flipping it
            // writes `enabled` for every badge at once (persistent is each badge's own choice and is left untouched).
            val allOn = badges.all { shown(ctx, it) }
            LabelSwitch(ctx.getString(R.string.notify_turn_all), allOn, 16, "notify-all") { v ->
                refusals(ctx, setAllShown(ctx, badges, v)); later(view, redraw); redraw()
            }
            for (s in sections) androidx.compose.runtime.key(s.id) {
                if (s.title.isNotBlank()) Text(s.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = LocalKitPalette.current.textSecondary,
                    modifier = Modifier.padding(top = 16.dp).testTag("badge-setting-section:${s.id}"))
                for (b in s.badges) androidx.compose.runtime.key(b.id) { badgeRow(ctx, view, redraw, b) }
            }
        }
    }

    /** Back-compat alias for the pane's former name; the pane is one list now. */
    @Composable
    fun configs(redraw: () -> Unit) = badges(redraw)

    /**
     * One declared badge: a title row (its name, its state on the right), the reason, its OWN live view (never a copy
     * drawn here), Open, then its `enabled` ("Show this badge", which also launches it) and `persistent` ("Make it
     * Persistent") switches labelled from the declaration. The view is the platform's template for the notification
     * actually posted for this badge ([BadgeServices.live]) inflated by [BadgeServices.view] — the same one the shade shows.
     */
    @Composable
    private fun badgeRow(ctx: Context, view: android.view.View, redraw: () -> Unit, b: BadgeDeclaration.Badge) {
        val p = LocalKitPalette.current
        val enabledOpt = b.customization.firstOrNull { it.key == BadgeCustomization.KEY_ENABLED }
        val persistentOpt = b.customization.firstOrNull { it.key == BadgeCustomization.KEY_PERSISTENT }
        val live = BadgeServices.live(ctx, b)
        // A running owner with nothing in the shade is NOT live — that is the swiped-away badge the old pane called green.
        val st = BadgeServices.status(ctx, b).let {
            if (it.state == BadgeServices.State.LIVE && live == null && b.service.isNotBlank())
                it.copy(state = BadgeServices.State.DEAD, reason = ctx.getString(R.string.push_not_posted, b.channel))
            else it
        }
        val stateColour = when (st.state) {
            BadgeServices.State.LIVE -> p.accent
            BadgeServices.State.IDLE -> p.textSecondary
            else -> Color(0xFFE05252)
        }
        val stateLabel = ctx.getString(
            when (st.state) {
                BadgeServices.State.LIVE -> R.string.push_state_live
                BadgeServices.State.DEAD -> R.string.push_state_dead
                BadgeServices.State.BLOCKED -> R.string.push_state_blocked
                BadgeServices.State.DISABLED -> R.string.push_state_disabled
                BadgeServices.State.NO_SERVICE -> R.string.push_state_no_service
                BadgeServices.State.IDLE -> R.string.push_state_idle
            },
        )
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp).testTag("badge-setting:${b.id}")) {
            // #1794 the title row: the badge's name on the left, its state on the right, one row.
            Row(Modifier.fillMaxWidth().testTag("badge-title-row:${b.id}"), verticalAlignment = Alignment.CenterVertically) {
                Text(b.label, fontSize = 15.sp, color = p.textPrimary, modifier = Modifier.weight(1f).testTag("badge-title:${b.id}"))
                Text(stateLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = stateColour,
                    modifier = Modifier.padding(start = 8.dp).testTag("badge-state:${b.id}"))
            }
            Text(st.reason, fontSize = 12.sp, color = stateColour,
                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp).testTag("badge-reason:${b.id}"))

            // The platform's own template for the posted Notification, inflated once per composition (the host keys the pane
            // on the badge epoch), with no parent: AndroidView attaches it. Nothing posted (swiped/dead) → no view; the
            // title row above names the badge and the reason says why.
            if (live != null) Box(Modifier.fillMaxWidth().testTag("badge-view:${b.id}")) {
                val rendered = remember(live) { BadgeServices.view(ctx, live, null) }
                if (rendered != null) AndroidView(modifier = Modifier.fillMaxWidth(), factory = { rendered })
            }

            val tap = live?.contentIntent
            if (tap != null) Row {
                PaneButton(ctx.getString(R.string.push_open), tag = "badge-open:${b.id}") {
                    runCatching { tap.send() }.onFailure { android.util.Log.w("BadgePanes", "Open failed", it) }
                }
            }

            if (enabledOpt != null) LabelSwitch(enabledOpt.label, shown(ctx, b), 13, "enabled:${b.id}") { v ->
                refusals(ctx, mapOf(b to setShown(ctx, b, v))); later(view, redraw); redraw()
            }
            if (persistentOpt != null) LabelSwitch(
                persistentOpt.label, BadgeCustomization.bool(ctx, b, BadgeCustomization.KEY_PERSISTENT), 13, "persistent:${b.id}",
            ) { v -> BadgeCustomization.set(ctx, b, BadgeCustomization.KEY_PERSISTENT, v); apply(ctx, redraw) }
            budsAppSetting(ctx, b, redraw)
        }
    }

    /** #1966 the Media card's buds-app setting: the chosen app (declared default), tap to cycle the declared choices, and, when
     *  that app is not installed, the reason the card shows no button for it. */
    @Composable
    private fun budsAppSetting(ctx: Context, b: BadgeDeclaration.Badge, redraw: () -> Unit) {
        val opt = b.customization.firstOrNull { it.key == BudsApp.KEY } ?: return
        val cur = BadgeCustomization.text(ctx, b, BudsApp.KEY)
        val pm = ctx.packageManager
        val reason = BudsApp.reason(cur, emptyList(), emptyList()) { p -> runCatching { pm.getLaunchIntentForPackage(p) != null }.getOrDefault(false) }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${opt.label}: $cur", fontSize = 13.sp, color = LocalKitPalette.current.textPrimary,
                modifier = Modifier.weight(1f).testTag("buds-app:${b.id}"))
            PaneButton("Change", tag = "buds-app-next:${b.id}") {
                val all = opt.options.ifEmpty { listOf(cur) }
                BadgeCustomization.set(ctx, b, BudsApp.KEY, all[(all.indexOf(cur) + 1) % all.size])
                apply(ctx, redraw)
            }
        }
        if (reason != null) Text(reason, fontSize = 12.sp, color = Color(0xFFE05252), modifier = Modifier.testTag("buds-app-reason:${b.id}"))
    }

    /** A switch that launched: a refusal is never silent (one toast naming each badge and why). */
    private fun refusals(ctx: Context, results: Map<BadgeDeclaration.Badge, String?>) {
        val refused = results.filterValues { it != null }
        if (refused.isNotEmpty()) Toast.makeText(ctx, refused.entries.joinToString("\n") { "${it.key.label}: ${it.value}" },
            Toast.LENGTH_LONG).show()
    }

    /** Redraw again once a launched owner has had a moment to post. */
    private fun later(view: android.view.View, redraw: () -> Unit) {
        view.postDelayed({ if (view.isAttachedToWindow) redraw() }, REBUILD_DELAY_MS)
    }

    /**
     * Launch result → one toast (a refusal is never silent), then redraw once the owner has had a moment to post. Launch
     * only re-starts a badge's OWN owning service (the same re-entry its delete intent uses); the pane posts nothing itself.
     */
    private fun launched(ctx: Context, view: android.view.View, redraw: () -> Unit, results: Map<BadgeDeclaration.Badge, String?>) {
        val refused = results.filterValues { it != null }
        Toast.makeText(
            ctx,
            if (refused.isEmpty()) ctx.getString(R.string.push_launched, results.size)
            else refused.entries.joinToString("\n") { "${it.key.label}: ${it.value}" },
            Toast.LENGTH_LONG,
        ).show()
        later(view, redraw)
    }

    /**
     * Take effect on the LIVE badge, in BOTH directions, and redraw so the switches and the live view read the store that
     * was just written. [NotifyGroups.apply] is the one place a switch is applied: it stops the owner of a badge that is
     * no longer wanted and takes a data widget's card (and its alarm) down, starts the owner of a badge switched back on
     * ([BadgeServices.ensureAll]) and re-posts what the running ones own. This used to call only ensureAll, so switching
     * a badge OFF did nothing: its card stayed in the shade and its service or alarm kept running until the next reboot.
     */
    private fun apply(ctx: Context, redraw: () -> Unit) {
        runCatching { NotifyGroups.apply(ctx) }
        redraw()
    }

    @Composable
    private fun caption(s: String) {
        Text(s, fontSize = 12.sp, color = LocalKitPalette.current.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
    }

    @Composable
    private fun LabelSwitch(label: String, checked: Boolean, sizeSp: Int, tag: String, onChange: (Boolean) -> Unit) {
        // #1794 the whole row toggles (label included), so a tap on "Show this badge" flips it like the switch does.
        Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp).testTag("switch-row:$tag"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = sizeSp.sp, color = LocalKitPalette.current.textPrimary, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag("switch:$tag"))
        }
    }

    @Composable
    private fun PaneButton(label: String, description: String? = null, tag: String = "pane-button:$label", onClick: () -> Unit) {
        com.diegonmarcos.superapp.uikit.KitButton(onClick = onClick, modifier = Modifier.padding(end = 6.dp).let { m ->
            if (description != null) m.semantics { contentDescription = description } else m
        }.testTag(tag)) { Text(label) }
    }
}
