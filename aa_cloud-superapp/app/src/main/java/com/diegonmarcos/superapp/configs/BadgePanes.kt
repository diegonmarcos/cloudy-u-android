package com.diegonmarcos.superapp.configs

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.notificationcenter.BadgeCustomization
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.notificationcenter.AlertStore
import com.diegonmarcos.superapp.notificationcenter.AlertsNotifier
import com.diegonmarcos.superapp.notificationcenter.NotifyGroups
import com.diegonmarcos.superapp.notificationcenter.NotifySections
import com.diegonmarcos.superapp.core.FleetAlerts
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * The persistent-badge views of Configs ▸ Launcher ▸ Notify — #515 / #518,
 * merged into the ntfy page by #580 (it was the Push tab, a page of its own).
 *
 * TWO PANES, both DERIVED from `build.json::ui.notification_center`, drawn by
 * AggregatorStackFragment for the panel kinds that name them:
 *
 *   1. [badges] — the badge boxes themselves, one per badge, as it appears in
 *      the notification centre, at the TOP of the page;
 *   2. [configs] — the customization menus, one per badge, same order, under
 *      the page's Filters.
 *
 * Neither is a producer. The one notification centre is the platform's shade
 * and the ntfy cards below; these are windows onto the badges that live in it,
 * so nothing here posts, cancels or keeps a store (#497 / #515).
 *
 * What these views are FOR. The #497 version listed all eight producers,
 * which was a channel inventory and not a badge list, and it drew every one of
 * them from the declared `enabled` flag — a build-time constant. So on the day
 * three of Diego's badges were missing from his shade, this page said all eight
 * were "Declared on", which was true and useless.
 *
 * Hence [badges] shows LIVE STATE, from [BadgeServices.status]: is the owning
 * service running, is a declared grant missing, has the owner switched it off.
 * A dead badge reads dead HERE, with the reason, which is the one thing the
 * previous pane could not do.
 *
 * Nothing here is named in Kotlin. `badge=true` in the declaration is what
 * puts a producer in both views; the customization rows are whatever that
 * producer's `customization` array declares. Adding a badge is a build.json
 * edit and no edit here.
 *
 * Live state goes stale the moment a service dies, so the host redraws on
 * every return to the page; [redraw] is how a view asks the host to repaint
 * BOTH of them, because a switch moved in [configs] changes a state line in
 * [badges].
 */
object BadgePanes {

    private const val REBUILD_DELAY_MS = 1200L

    /** View 1 — Launch-all, then the four groups (#777) in the owner's order,
     *  each with its members' boxes; a badge in no group is drawn after them. */
    @Composable
    fun badges(redraw: () -> Unit) {
        val ctx = LocalContext.current
        val view = LocalView.current
        val badges = BadgeDeclaration.badges(BadgeServices.declared)
        Column(Modifier.fillMaxWidth()) {
            if (badges.isEmpty()) {
                caption(ctx.getString(R.string.push_none_badges))
                return@Column
            }
            caption(ctx.getString(R.string.push_section_badges_sub))
            PaneButton(ctx.getString(R.string.push_launch_all)) { launched(ctx, view, redraw, BadgeServices.launchAll(ctx)) }
            val groups = NotifyGroups.ordered(ctx)
            // Same taxonomy as the shade / Notify page: shade groups plus the Apps Push / Apps Keep Alive sections (Cloudy | Phone).
            val apps = NotifySections.appSections
            val layout = NotifySections.layout(NotifySections.pageOrder, groups.map { it.id }, apps.map { it.id })
            for (id in layout) {
                val gi = groups.indexOfFirst { it.id == id }
                if (gi >= 0) groupBox(redraw, groups[gi], gi, groups.size, badges)
                else apps.firstOrNull { it.id == id }?.let { com.diegonmarcos.superapp.launcher.AppSectionBlock(it) }
            }
            val grouped = groups.flatMap { it.members }.toSet()
            for (b in BadgeDeclaration.rankOrder(badges, badges.filter { it.id !in grouped })) badgeBox(redraw, b)
        }
    }

    // ───────────────────────── #777: the groups ─────────────────────────

    /**
     * One group: its switch, its order (up/down), then its members. A group
     * with more than one member gets a switch per member (G1, G2); the alerts
     * group gets its filters, Clear all and the alerts themselves. All of it
     * read from the declaration and the stores — no group or badge is named.
     */
    @Composable
    private fun groupBox(
        redraw: () -> Unit, g: BadgeDeclaration.Group,
        index: Int, count: Int, badges: List<BadgeDeclaration.Badge>,
    ) {
        val ctx = LocalContext.current
        val on = NotifyGroups.isEnabled(ctx, g)
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LabelSwitch(g.label, on, 17, "group:${g.id}", Modifier.weight(1f)) { v -> NotifyGroups.setEnabled(ctx, g, v); redraw() }
                if (index > 0) PaneButton("▲", ctx.getString(R.string.notify_group_up)) { NotifyGroups.move(ctx, g, -1); redraw() }
                if (index < count - 1) PaneButton("▼", ctx.getString(R.string.notify_group_down)) { NotifyGroups.move(ctx, g, +1); redraw() }
            }
            if (!on) return@Column
            val members = BadgeDeclaration.rankOrder(badges, g.members.mapNotNull { id -> badges.firstOrNull { it.id == id } })
            for (b in members) {
                if (members.size > 1) LabelSwitch(
                    ctx.getString(R.string.notify_member_show, b.label), BadgeCustomization.bool(ctx, b, BadgeCustomization.KEY_ENABLED),
                    13, "member:${b.id}",
                ) { v ->
                    BadgeCustomization.set(ctx, b, BadgeCustomization.KEY_ENABLED, v)
                    NotifyGroups.apply(ctx); redraw()
                }
                badgeBox(redraw, b)
            }
            if (g.alerts) alertsBox(redraw)
        }
    }

    /** The Alerts group's own controls: filters by severity and by app (an
     *  app appears once it has raised something), Clear all, and the list. */
    @Composable
    private fun alertsBox(redraw: () -> Unit) {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth()) {
            caption(ctx.getString(R.string.notify_alerts_filters))
            @Composable
            fun filter(label: String, muted: Boolean, tag: String, set: (Boolean) -> Unit) =
                LabelSwitch(label, !muted, 13, tag) { v -> set(!v); AlertsNotifier.refresh(ctx); redraw() }
            for (sev in FleetAlerts.SEVERITIES) filter(
                ctx.getString(R.string.notify_alerts_show_severity, sev), AlertStore.severityMuted(ctx, sev), "alerts:sev:$sev",
            ) { AlertStore.setSeverityMuted(ctx, sev, it) }
            for (app in AlertStore.apps(ctx)) filter(
                ctx.getString(R.string.notify_alerts_show_app, AlertsNotifier.appLabel(ctx, app)), AlertStore.appMuted(ctx, app), "alerts:app:$app",
            ) { AlertStore.setAppMuted(ctx, app, it) }
            PaneButton(ctx.getString(R.string.notify_alerts_clear_all)) {
                val n = AlertStore.clear(ctx)
                AlertsNotifier.refresh(ctx)
                Toast.makeText(ctx, ctx.getString(R.string.notify_alerts_cleared, n), Toast.LENGTH_SHORT).show()
                redraw()
            }
            val visible = AlertStore.visible(ctx)
            if (visible.isEmpty()) caption(ctx.getString(R.string.notify_alerts_none))
            for (a in visible) Text(
                "${AlertsNotifier.appLabel(ctx, a.app)} · ${a.severity} · ${a.title}" +
                    (if (a.text.isNotBlank()) "\n${a.text}" else ""),
                fontSize = 13.sp,
                color = if (a.severity == FleetAlerts.ERROR) Color(0xFFE05252) else p.textPrimary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }

    /** View 2 — one customization menu per declared badge, same order. */
    @Composable
    fun configs(redraw: () -> Unit) {
        val ctx = LocalContext.current
        val badges = BadgeDeclaration.badges(BadgeServices.declared)
        Column(Modifier.fillMaxWidth()) {
            if (badges.isEmpty()) {
                caption(ctx.getString(R.string.push_none_badges))
                return@Column
            }
            // #1068: the badge CONTROLS live here, at the very bottom of Notify — Launch, the group switches and their order, the
            // alerts filters and each badge's live state line. The page itself draws the badges as cards.
            this@BadgePanes.badges(redraw)
            caption(ctx.getString(R.string.push_section_custom_sub))
            for (b in badges) customMenu(redraw, b)
        }
    }

    // ───────────────────────── View 1: the badges ────────────────────────

    /**
     * One badge, rendered from the notification ACTUALLY POSTED for it
     * ([BadgeServices.live]) by [BadgeServices.view] — the platform's own
     * template for that very Notification, the one the shade inflates. There is
     * no renderer in this file: a badge added or restyled in its producer shows
     * up here the same way it shows in the shade. #535: this used to print the
     * declaration's `shows` sentence, then hand-drawn title/text lines.
     *
     * Under it: the state line (why a badge is NOT in the shade), Open (the
     * badge's own tap) and Launch (put it back — a swiped or cleared badge
     * has nothing to render, so a dead badge is just its name and this button).
     */
    @Composable
    private fun badgeBox(redraw: () -> Unit, b: BadgeDeclaration.Badge) {
        val ctx = LocalContext.current
        val view = LocalView.current
        val p = LocalKitPalette.current
        val live = BadgeServices.live(ctx, b)
        // A running owner with nothing in the shade is NOT live — that is
        // exactly the swiped-away badge the previous pane called green.
        val st = BadgeServices.status(ctx, b).let {
            // (An on-demand badge — no service — has nothing to post when it
            // has nothing to say; its status already says so.)
            if (it.state == BadgeServices.State.LIVE && live == null && b.service.isNotBlank())
                it.copy(state = BadgeServices.State.DEAD, reason = ctx.getString(R.string.push_not_posted, b.channel))
            else it
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("badge:${b.id}")) {
            // The platform's own template for the posted Notification: a View, because RemoteViews can only be applied to one.
            // Inflated once per composition of the pane (the host keys the pane on the badge epoch, so a redraw builds a fresh
            // one), with no parent: it is handed to AndroidView, which attaches it.
            val rendered = remember(live) { live?.let { n -> BadgeServices.view(ctx, n, null) } }
            if (rendered != null) {
                AndroidView(modifier = Modifier.fillMaxWidth(), factory = { rendered })
            } else {
                Text(b.label, fontSize = 13.sp, color = p.textPrimary)
            }

            val stateColour = when (st.state) {
                BadgeServices.State.LIVE -> p.accent
                // Nothing to say is not a fault: an idle on-demand badge.
                BadgeServices.State.IDLE -> p.textSecondary
                // Everything that is not LIVE is the same colour on purpose:
                // "blocked" and "dead" are equally not-in-the-shade, and giving
                // one of them a softer colour is how a missing badge reads as fine.
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
            Text("$stateLabel · ${st.reason}", fontSize = 12.sp, color = stateColour)

            val tap = live?.contentIntent
            Row {
                if (tap != null) PaneButton(ctx.getString(R.string.push_open)) { runCatching { tap.send() }.onFailure { android.util.Log.w("BadgePanes", "Open failed", it) } }
                PaneButton(ctx.getString(R.string.push_launch)) { launched(ctx, view, redraw, mapOf(b to BadgeServices.launch(ctx, b))) }
            }
        }
    }

    /** Launch result → one toast (a refusal is never silent), then redraw once
     *  the owner has had a moment to post. */
    private fun launched(ctx: Context, view: android.view.View, redraw: () -> Unit, results: Map<BadgeDeclaration.Badge, String?>) {
        val refused = results.filterValues { it != null }
        Toast.makeText(
            ctx,
            if (refused.isEmpty()) ctx.getString(R.string.push_launched, results.size)
            else refused.entries.joinToString("\n") { "${it.key.label}: ${it.value}" },
            Toast.LENGTH_LONG,
        ).show()
        view.postDelayed({ if (view.isAttachedToWindow) redraw() }, REBUILD_DELAY_MS)
    }

    // ──────────────────── View 2: the customization ──────────────────────

    @Composable
    private fun customMenu(redraw: () -> Unit, b: BadgeDeclaration.Badge) {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(b.label, fontSize = 15.sp, color = p.textPrimary)
            if (b.customization.isEmpty()) {
                Text(ctx.getString(R.string.push_producer_off), fontSize = 12.sp, color = p.textSecondary)
                return@Column
            }
            for (opt in b.customization) control(redraw, b, opt)
        }
    }

    /** The declared `type` picks the widget. This method knows about option
     *  TYPES, never about option keys — so a new option in build.json gets a
     *  control here without a Kotlin edit. */
    @Composable
    private fun control(redraw: () -> Unit, b: BadgeDeclaration.Badge, opt: BadgeDeclaration.Option) {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        when (opt.type) {
            "choice" -> {
                val values = opt.options
                var shown by remember { mutableIntStateOf(0) }   // bumped on a tap so the line re-reads the store
                Text("${opt.label}: ${shown.let { BadgeCustomization.text(ctx, b, opt.key) }}",
                    fontSize = 13.sp, color = p.textPrimary,
                    // Tap cycles. A declared choice is two or three values; a
                    // dialog to pick between three is more chrome than choice.
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (values.isEmpty()) return@clickable
                        val cur = BadgeCustomization.text(ctx, b, opt.key)
                        val next = values[(values.indexOf(cur).takeIf { it >= 0 }?.plus(1) ?: 0) % values.size]
                        BadgeCustomization.set(ctx, b, opt.key, next)
                        shown++
                        applyLive(ctx, redraw)
                    }.padding(vertical = 8.dp).testTag("badge-option:${b.id}:${opt.key}"))
            }
            else -> LabelSwitch(opt.label, BadgeCustomization.bool(ctx, b, opt), 13, "badge-option:${b.id}:${opt.key}") { on ->
                BadgeCustomization.set(ctx, b, opt.key, on)
                applyLive(ctx, redraw)
            }
        }
    }

    /**
     * Take effect on the LIVE badge, which is the half of "declaration AND
     * customization" a settings page usually forgets: re-ensure the services
     * (a badge just switched back on needs its owner started) and redraw the
     * boxes so their state line agrees with the switch that was just moved.
     */
    private fun applyLive(ctx: Context, redraw: () -> Unit) {
        runCatching { BadgeServices.ensureAll(ctx) }
        redraw()
    }

    // ────────────────────────────── Chrome ───────────────────────────────

    @Composable
    private fun caption(s: String) {
        Text(s, fontSize = 12.sp, color = LocalKitPalette.current.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
    }

    @Composable
    private fun LabelSwitch(
        label: String, checked: Boolean, sizeSp: Int, tag: String, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit,
    ) {
        Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = sizeSp.sp, color = LocalKitPalette.current.textPrimary, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag("switch:$tag"))
        }
    }

    @Composable
    private fun PaneButton(label: String, description: String? = null, onClick: () -> Unit) {
        com.diegonmarcos.superapp.uikit.KitButton(onClick = onClick, modifier = Modifier.padding(end = 6.dp).let { m ->
            if (description != null) m.semantics { contentDescription = description } else m
        }.testTag("pane-button:$label")) { Text(label) }
    }
}
