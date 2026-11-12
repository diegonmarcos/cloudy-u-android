package com.diegonmarcos.superapp.system

import android.content.Context
import android.content.res.Configuration
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs

/**
 * The display levers Samsung keeps outside any app's reach: system-wide
 * dark mode, and the Screen zoom / Font size pair.
 *
 * Dark mode is a genuine device-wide setting — `AppCompatDelegate
 * .setDefaultNightMode` would repaint THIS app and leave the rest of the
 * phone light, which is not what "dark mode" means on the settings screen
 * it is drawn on — so [setNight] goes through the same privileged shell
 * channel the Battery Hunger levers use, and reports honestly when there is
 * no channel rather than pretending it worked. Call off the main thread —
 * that write is a shell round-trip.
 *
 * Scale is NOT device-wide any more (#601): [wrap] applies it in-process, so
 * it needs no channel and never touches a system setting at all.
 */
object SystemDisplay {

    /**
     * In-process size override — #601. Until this, the scale slider was
     * pixels on paper unless the privileged shell channel could write
     * `wm density` + `settings put system font_scale`, both of which need
     * wireless debugging switched on. A fresh install has no channel, so it
     * rendered at the bare device size no matter what build.json shipped as
     * the "default" step — the slider promised 0.85 and nothing on screen
     * ever moved.
     *
     * [wrap] applies the SAME [factor] this class always used, but through
     * `createConfigurationContext` instead of a shell command: an
     * Activity-scoped Configuration override needs no permission, no ADB,
     * and no channel, because it never touches a device-wide setting — it
     * only tells THIS process what size to draw itself at. Call from
     * `attachBaseContext`; the shipped default (currently 3 → 0.85, see
     * build.json::ui.launcher_settings.scale) is what a fresh install now
     * looks like from the very first frame, with nothing left to grant.
     */
    fun wrap(base: Context): Context {
        val factor = factor(LauncherSettingsPrefs(base).scale).toFloat()
        val config = Configuration(base.resources.configuration)
        config.fontScale = factor
        // Quick Actions > UI > Cloud-superapp Scale: a chosen smallest width (dp) decides the density outright; none = the factor.
        val dm = base.resources.displayMetrics
        val sw = QuickDisplay.swOverride(base)
        config.densityDpi = if (sw > 0) QuickDisplay.densityForSw(minOf(dm.widthPixels, dm.heightPixels), sw)
                            else Math.round(dm.densityDpi * factor)
        return base.createConfigurationContext(config)
    }

    private fun exec(ctx: Context): ((String) -> String?)? {
        val channel = ShellChannels.active(ctx) ?: return null
        return { command -> channel.exec(ctx, command) }
    }

    /** Whether a privileged channel exists at all, for the caller's error text. */
    fun hasChannel(ctx: Context): Boolean = AdbReadiness.usableNow(ctx)

    // ── Dark mode ────────────────────────────────────────────────────────

    /**
     * The phone's real uiMode, not our own intention. A switch that reports
     * what we last tried to write is the failure the whole screen exists to
     * make impossible.
     */
    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun setNight(ctx: Context, on: Boolean): Boolean =
        exec(ctx)?.invoke("cmd uimode night ${if (on) "yes" else "no"}") != null

    // ── Scale (Screen zoom + Font size, as one knob) ─────────────────────

    /**
     * Samsung splits this in two on the SYSTEM equivalent — Screen zoom
     * (`wm density`) and Font size (`settings put system font_scale`) — and
     * the two drift apart the moment anything writes one and not the other.
     * [wrap] takes the SAME factor for both, in-process, so they can't drift
     * relative to each other even though (since #601) neither is a
     * device-wide setting any more.
     *
     * Each step is 0.05, anchored so scale 6 is exactly 1.00: the phone's own
     * size, changing nothing. That is why 6 is labelled "Normal" rather than
     * the shipped default — the shipped default is 3 (0.85), baked as the
     * app's own baseline since #601 so a fresh install renders it with no
     * channel and no grant.
     *
     * The band is CLAMPED TO THE DECLARED RANGE, never to a pair of literals.
     * It used to read coerceIn(1, 10), which was the declared range written a
     * second time — so when #384 widened the declaration to 0..10 the clamp
     * would have silently folded the new bottom step onto the old one and made
     * two different tick marks produce pixel-identical output. A slider whose
     * marks lie is worse than one with no marks.
     */
    fun factor(scale: Int): Double {
        val declared = LauncherSettingsPrefs.Config.scale
        return 0.70 + 0.05 * scale.coerceIn(declared.min, declared.max)
    }

    /**
     * Apply a new slider value — #601. This used to shell out `wm density`
     * + `settings put system font_scale`, a device-wide write that needed
     * the privileged channel (wireless debugging) to exist at all. Neither
     * command runs any more: the size is drawn by THIS process through
     * [wrap], so all a change needs is to be re-read — recreate() tears the
     * Activity down and rebuilds it, which calls attachBaseContext again
     * and picks up the value [LauncherSettingsPrefs.scale] was just set to.
     *
     * @return false when [ctx] is not an Activity — the caller must say so
     *         rather than leave a slider that moves and does nothing.
     */
    fun applyScale(ctx: Context): Boolean {
        // ctx may arrive wrapped (a Fragment's inflater context, a themed
        // wrapper) rather than the raw Activity, so unwrap ContextWrapper
        // the same way AppCompatActivity's own helpers do rather than
        // trusting a single `as?` to see through it.
        var c: Context? = ctx
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        val activity = c as? android.app.Activity ?: return false
        activity.recreate()
        return true
    }
}
