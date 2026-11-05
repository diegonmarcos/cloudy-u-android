package com.diegonmarcos.superapp.settings

import android.content.Context
import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configs → Launcher → Presets ▸ Modes (#574): Power · Focus · Saving Energy · Super Saving Battery Mode (#1506, an `engine` mode).
 *
 * A mode is a named bundle of the launcher's own switches — no palette, no
 * chrome. It owns NO engine: applying one goes through
 * [LauncherThemes.applyToggles], the same call a theme's `toggles` go through,
 * and "modified" is [LauncherThemes.differs], the same derived comparison. What
 * lives here is only the parse of `build.json::ui.launcher_modes` and the one
 * fact a theme keeps in its own prefs and a mode needs too: which was picked.
 *
 * Names, order and toggles are DATA; there is deliberately no enum to add an
 * arm to. That is also why nothing here can be confused with the power-saving
 * THEME that was deleted in #435 — that one had a home pane, a design
 * vocabulary and a system-lever engine, none of which a mode has.
 */
object LauncherModes {
    data class Mode(
        val id: String,
        val label: String,
        val subtitle: String,
        val toggles: Map<String, Boolean>,
        /** #1506 Non-empty = the pick is handed to that engine (bgpolicy.SuperSavingRuntime.ENGINE) instead of writing [toggles]. */
        val engine: String = "",
    )

    private fun declaration(): JSONArray =
        JSONArray(String(Base64.decode(BuildConfig.UI_LAUNCHER_MODES_B64, Base64.NO_WRAP)))

    /** In declared order. Empty rather than throwing if the blob is unreadable. */
    fun load(): List<Mode> = runCatching {
        val arr = declaration()
        // The SAME parser a theme's toggles use, so a `user_owned` id (edge
        // menus, dark mode) can never survive into a mode either.
        val toggles = LauncherThemes.parseTogglesFrom(arr)
        (0 until arr.length()).map { i ->
            val o: JSONObject = arr.getJSONObject(i)
            val id = o.optString("id")
            Mode(id, o.optString("label"), o.optString("subtitle"), toggles[id] ?: emptyMap(), o.optString("engine"))
        }
    }.getOrDefault(emptyList())

    /** Record the pick, then set the switches it names. Unnamed switches keep
     *  their value. No Activity recreate: a mode names no style. */
    fun apply(ctx: Context, mode: Mode) {
        LauncherModePrefs(ctx).selected = mode.id
        LauncherThemes.applyToggles(ctx, mode.toggles)
    }

    /** Has a switch this mode names been moved since it was applied? Derived,
     *  never stored — see [LauncherThemes.isModified]. */
    fun isModified(ctx: Context, mode: Mode): Boolean =
        LauncherThemes.differs(ctx, mode.toggles)
}

/** The picked mode's id, or null before any pick. Fixed store name, never a
 *  page id, so renaming the tab cannot lose it. */
class LauncherModePrefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("launcher_mode_prefs", Context.MODE_PRIVATE)

    var selected: String?
        get() = sp.getString("mode", null)
        set(value) { sp.edit().putString("mode", value).apply() }
}

/** `build.json::ui.launcher_presets` — which groups the Presets tab shows, in
 *  what order, under what name, and which declaration feeds each. */
object LauncherPresets {
    data class Group(val id: String, val kind: String, val label: String, val subtitle: String)

    const val KIND_PROFILE = "profile"
    const val KIND_THEME = "theme"
    const val KIND_MODE = "mode"

    fun groups(): List<Group> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_LAUNCHER_PRESETS_B64, Base64.NO_WRAP))
        val arr = JSONObject(json).getJSONArray("groups")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Group(o.optString("id"), o.optString("kind"), o.optString("label"), o.optString("subtitle"))
        }
    }.getOrDefault(emptyList())
}
