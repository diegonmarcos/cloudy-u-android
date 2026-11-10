package com.diegonmarcos.superapp.configs

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.notificationcenter.BadgeCustomization
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
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
 */
object BadgePanes {

    private const val REBUILD_DELAY_MS = 1200L

    /** The settings pane — the single badge-box drawer (#580, T9): the host binds the badge panel to this one entry. */
    @Composable
    fun badges(redraw: () -> Unit) {
        val ctx = LocalContext.current
        val view = LocalView.current
        val declared = BadgeServices.declared
        val badges = BadgeDeclaration.rankOrder(declared, BadgeDeclaration.badges(declared))
        Column(Modifier.fillMaxWidth()) {
            if (badges.isEmpty()) {
                caption(ctx.getString(R.string.push_none_badges))
                return@Column
            }
            caption(ctx.getString(R.string.notify_settings_sub))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PaneButton(ctx.getString(R.string.push_launch_all)) { launched(ctx, view, redraw, BadgeServices.launchAll(ctx)) }
            }
            // The master: ON only while every badge's `enabled` switch is on, so it reads the list's true state. Flipping
            // it writes `enabled` for every badge at once (persistent is each badge's own choice and is left untouched).
            val allOn = badges.all { BadgeCustomization.bool(ctx, it, BadgeCustomization.KEY_ENABLED) }
            LabelSwitch(ctx.getString(R.string.notify_turn_all), allOn, 16, "notify-all") { v ->
                for (b in badges) BadgeCustomization.set(ctx, b, BadgeCustomization.KEY_ENABLED, v)
                apply(ctx, redraw)
            }
            for (b in badges) badgeRow(ctx, view, redraw, b)
        }
    }

    /** Back-compat alias for the pane's former name; the pane is one list now. */
    @Composable
    fun configs(redraw: () -> Unit) = badges(redraw)

    /**
     * One declared badge: its OWN live view (never a copy drawn here), its state line, Open / Launch, then its `enabled`
     * and `persistent` switches labelled from the declaration. The view is the platform's template for the notification
     * actually posted for this badge ([BadgeServices.live]) inflated by [BadgeServices.view] — the same one the shade shows;
     * a swiped or dead badge has nothing to render, so it falls back to the badge's name and the state line says why, and
     * Launch ([BadgeServices.launch]) puts it back by re-starting its owner.
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
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp).testTag("badge-setting:${b.id}")) {
            // The platform's own template for the posted Notification, inflated once per composition (the host keys the pane
            // on the badge epoch), with no parent: AndroidView attaches it. Null (swiped/dead) → the badge's name instead.
            val rendered = remember(live) { live?.let { n -> BadgeServices.view(ctx, n, null) } }
            if (rendered != null) AndroidView(modifier = Modifier.fillMaxWidth(), factory = { rendered })
            else Text(b.label, fontSize = 15.sp, color = p.textPrimary)

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
            Text("$stateLabel · ${st.reason}", fontSize = 12.sp, color = stateColour,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))

            val tap = live?.contentIntent
            Row {
                if (tap != null) PaneButton(ctx.getString(R.string.push_open)) {
                    runCatching { tap.send() }.onFailure { android.util.Log.w("BadgePanes", "Open failed", it) }
                }
                PaneButton(ctx.getString(R.string.push_launch)) { launched(ctx, view, redraw, mapOf(b to BadgeServices.launch(ctx, b))) }
            }

            if (enabledOpt != null) LabelSwitch(
                enabledOpt.label, BadgeCustomization.bool(ctx, b, BadgeCustomization.KEY_ENABLED), 13, "enabled:${b.id}",
            ) { v -> BadgeCustomization.set(ctx, b, BadgeCustomization.KEY_ENABLED, v); apply(ctx, redraw) }
            if (persistentOpt != null) LabelSwitch(
                persistentOpt.label, BadgeCustomization.bool(ctx, b, BadgeCustomization.KEY_PERSISTENT), 13, "persistent:${b.id}",
            ) { v -> BadgeCustomization.set(ctx, b, BadgeCustomization.KEY_PERSISTENT, v); apply(ctx, redraw) }
        }
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
        view.postDelayed({ if (view.isAttachedToWindow) redraw() }, REBUILD_DELAY_MS)
    }

    /**
     * Take effect on the LIVE badge: re-ensure the services (a badge switched back on needs its owner started) and redraw
     * so the switches and the live view read the store that was just written.
     */
    private fun apply(ctx: Context, redraw: () -> Unit) {
        runCatching { BadgeServices.ensureAll(ctx) }
        redraw()
    }

    @Composable
    private fun caption(s: String) {
        Text(s, fontSize = 12.sp, color = LocalKitPalette.current.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
    }

    @Composable
    private fun LabelSwitch(label: String, checked: Boolean, sizeSp: Int, tag: String, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
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
