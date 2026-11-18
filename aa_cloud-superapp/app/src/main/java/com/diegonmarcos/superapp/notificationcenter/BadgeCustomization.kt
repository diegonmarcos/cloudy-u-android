package com.diegonmarcos.superapp.notificationcenter

import android.content.Context
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration.Badge
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration.Option

/**
 * #515 — the WRITE half. The declaration says what a badge IS; this says what
 * the owner has since changed about it.
 *
 * Stored per (badge id, option key) so the store has no schema of its own:
 * every key it can hold is one the declaration named. An option dropped from
 * build.json stops being read the same build it stops being declared, and a
 * new one starts life at its declared default without a migration.
 *
 * Unset reads fall through to the DECLARED default rather than to a constant
 * in here — otherwise the default would live in two places and the JSON one
 * would be the lie.
 */
object BadgeCustomization {

    private const val PREFS = "badge_customization"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(badgeId: String, optionKey: String) = "$badgeId.$optionKey"

    /** Raw declared default, as text ("true"/"false" for toggles). */
    private fun declaredDefault(badge: Badge, optionKey: String): String =
        badge.customization.firstOrNull { it.key == optionKey }?.default ?: ""

    fun bool(ctx: Context, badge: Badge, option: Option): Boolean =
        bool(ctx, badge, option.key)

    fun bool(ctx: Context, badge: Badge, optionKey: String): Boolean {
        val fallback = declaredDefault(badge, optionKey).equals("true", ignoreCase = true)
        val stored = prefs(ctx).getString(key(badge.id, optionKey), null) ?: return fallback
        return stored.equals("true", ignoreCase = true)
    }

    fun text(ctx: Context, badge: Badge, optionKey: String): String =
        prefs(ctx).getString(key(badge.id, optionKey), null) ?: declaredDefault(badge, optionKey)

    fun set(ctx: Context, badge: Badge, optionKey: String, value: String) {
        prefs(ctx).edit().putString(key(badge.id, optionKey), value).apply()
    }

    fun set(ctx: Context, badge: Badge, optionKey: String, value: Boolean) =
        set(ctx, badge, optionKey, value.toString())

    // ── The two options every other surface has to agree on ──────────────
    // Named rather than string-literalled at each call site: `enabled` and
    // `persistent` are read by the restart path AND by the producers
    // themselves, so a typo in one of them is a badge that silently stops
    // coming back.

    /**
     * Owner switch ("Show this badge"). A badge switched off here is not posted and not re-ensured after an update.
     *
     * #1794 THE ONE SOURCE: the Configs switch draws this, the status line ([BadgeServices.status]) reads this and the
     * posting decision ([BadgeServices.wanted], [BadgeServices.ensureAll], the producers) reads this. It used to AND in a
     * per-group switch (`notify_groups:<group>.enabled`, #777) whose UI #1603b removed: a group switched off by an older
     * build stayed off with no control left to turn it back on, so every member read "OFF · Switched off below" under an
     * ON switch and nothing reached the shade. A group's on/off is now DERIVED from its members ([NotifyGroups.isEnabled])
     * and the stale stored key is cleared ([NotifyGroups.clearStaleGroupSwitches]).
     */
    fun isEnabled(ctx: Context, badge: Badge): Boolean =
        badge.enabled && bool(ctx, badge, KEY_ENABLED)

    /** Write the owner switch. The caller applies it ([NotifyGroups.apply]); this only stores. */
    fun setEnabled(ctx: Context, badge: Badge, on: Boolean) = set(ctx, badge, KEY_ENABLED, on)

    /**
     * Whether the badge holds its place in the shade when it has nothing to
     * report. This is the declared+customised answer to the question
     * `MediaProxy` used to answer with `setOngoing(playing)` — i.e. "only
     * while audio is playing", which is why the media badge evaporated the
     * moment the music stopped.
     */
    fun isPersistent(ctx: Context, badge: Badge): Boolean =
        badge.persistent && bool(ctx, badge, KEY_PERSISTENT)

    const val KEY_ENABLED = "enabled"
    const val KEY_PERSISTENT = "persistent"
}
