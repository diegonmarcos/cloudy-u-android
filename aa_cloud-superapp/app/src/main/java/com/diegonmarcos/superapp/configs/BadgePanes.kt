package com.diegonmarcos.superapp.configs

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.notificationcenter.BadgeCustomization
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1603b — the SETTINGS pane of Configs ▸ Launcher ▸ Notify: the one place on/off control lives (the viewer, [com.diegonmarcos.superapp.launcher.BadgeCards], carries NO switches).
 *
 * ONE clean list, DERIVED from the same declaration that draws both surfaces (`build.json::ui.notification_center`, #580):
 * a single "turn all on/off" master at the top, then one row per DECLARED badge (in the owner's `card.rank` order), each
 * row offering the badge's two owner switches — `enabled` (show this badge) and `persistent` (keep it pinned). Both are
 * read and written through [BadgeCustomization] (`KEY_ENABLED` / `KEY_PERSISTENT`), which the shade and the restart path
 * already obey, so a flip here is the same flip everywhere. The row labels are the declared option labels, never a string
 * typed here — adding a badge is a build.json edit and no edit in this file.
 *
 * The old Apps Push / Apps Keep Alive toggle tables, the group switches and the live-badge boxes are gone: this flat list
 * replaces them (#1603b). Nothing here posts, cancels or keeps a store; the host redraws the pane on every return and on
 * every switch moved, via [redraw].
 */
object BadgePanes {

    /** The settings pane. */
    @Composable
    fun configs(redraw: () -> Unit) {
        val ctx = LocalContext.current
        val declared = BadgeServices.declared
        val badges = BadgeDeclaration.rankOrder(declared, BadgeDeclaration.badges(declared))
        Column(Modifier.fillMaxWidth()) {
            if (badges.isEmpty()) {
                caption(ctx.getString(R.string.push_none_badges))
                return@Column
            }
            caption(ctx.getString(R.string.notify_settings_sub))
            // The master: ON only while every badge's `enabled` switch is on, so it reads the list's true state. Flipping
            // it writes `enabled` for every badge at once (persistent is each badge's own choice and is left untouched).
            val allOn = badges.all { BadgeCustomization.bool(ctx, it, BadgeCustomization.KEY_ENABLED) }
            LabelSwitch(ctx.getString(R.string.notify_turn_all), allOn, 16, "notify-all") { v ->
                for (b in badges) BadgeCustomization.set(ctx, b, BadgeCustomization.KEY_ENABLED, v)
                apply(ctx, redraw)
            }
            for (b in badges) badgeRow(ctx, redraw, b)
        }
    }

    /** Back-compat entry for hosts/tests that named the old view: the pane is one list now. */
    @Composable
    fun badges(redraw: () -> Unit) = configs(redraw)

    /** One declared badge: its name, then its `enabled` and `persistent` switches, labelled from the declaration. */
    @Composable
    private fun badgeRow(ctx: Context, redraw: () -> Unit, b: BadgeDeclaration.Badge) {
        val p = LocalKitPalette.current
        val enabledOpt = b.customization.firstOrNull { it.key == BadgeCustomization.KEY_ENABLED }
        val persistentOpt = b.customization.firstOrNull { it.key == BadgeCustomization.KEY_PERSISTENT }
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp).testTag("badge-setting:${b.id}")) {
            Text(b.label, fontSize = 15.sp, color = p.textPrimary)
            if (enabledOpt != null) LabelSwitch(
                enabledOpt.label, BadgeCustomization.bool(ctx, b, BadgeCustomization.KEY_ENABLED), 13, "enabled:${b.id}",
            ) { v -> BadgeCustomization.set(ctx, b, BadgeCustomization.KEY_ENABLED, v); apply(ctx, redraw) }
            if (persistentOpt != null) LabelSwitch(
                persistentOpt.label, BadgeCustomization.bool(ctx, b, BadgeCustomization.KEY_PERSISTENT), 13, "persistent:${b.id}",
            ) { v -> BadgeCustomization.set(ctx, b, BadgeCustomization.KEY_PERSISTENT, v); apply(ctx, redraw) }
        }
    }

    /**
     * Take effect on the LIVE badge: re-ensure the services (a badge switched back on needs its owner started) and redraw
     * so the switches read the store that was just written.
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
}
