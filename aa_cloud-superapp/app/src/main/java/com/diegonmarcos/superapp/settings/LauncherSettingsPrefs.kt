package com.diegonmarcos.superapp.settings

import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.onehand.OneHandController
import com.diegonmarcos.superapp.onehand.OneHandPrefs
import com.diegonmarcos.superapp.system.SystemDisplay
import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import com.diegonmarcos.superapp.launcher.themes.cloud.Home3DFragment

/**
 * Configs → Launcher → Screensaver + Others. Single source of truth for the
 * user's launcher-settings choices: the picked screensaver, the per-feature
 * on/off toggles (pets / cube / stars / haptics / eye-protection), and the two
 * sliders (system brightness, eye-protection strength).
 *
 * Defaults come from build.json (baked into BuildConfig.UI_LAUNCHER_SETTINGS_B64
 * + UI_SCREENSAVERS_B64) so nothing is hardcoded here. Every subsystem reads its
 * own key — Haptics gates on "haptics", GalaxyBackdropView on "stars_anim",
 * Home3DFragment on "cube_anim", the status strip on "pets_anim".
 */
class LauncherSettingsPrefs(context: Context) {

    private val appCtx = context.applicationContext
    private val sp = appCtx
        .getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    var screensaver: String
        get() = sp.getString("screensaver", null) ?: Config.defaultScreensaverId
        set(v) { sp.edit().putString("screensaver", v).apply() }

    fun toggle(id: String): Boolean = sp.getBoolean("t_$id", Config.toggleDefault(id))
    fun setToggle(id: String, v: Boolean) { sp.edit().putBoolean("t_$id", v).apply() }

    /**
     * Store-aware read/write. Most switches live in this class's own
     * `launcher_settings` store, but a switch may declare `"store"` in
     * build.json when the state it shows is already owned by another subsystem.
     * `edge_menus` does: the One-Hand tab writes OneHandPrefs, and a second copy
     * of that boolean here would let the two tabs of the SAME page disagree
     * about whether edge menus are on. So the switch is a VIEW of the owning
     * store, never a duplicate of it.
     */
    fun toggle(item: Item): Boolean = when (item.store) {
        Config.STORE_ONEHAND  -> OneHandPrefs.isEnabled(appCtx)
        // The phone's real uiMode, never a boolean of our own: dark mode is
        // device-wide and anything else on the phone can change it.
        Config.STORE_NIGHTMODE -> SystemDisplay.isNight(appCtx)
        else          -> toggle(item.id)
    }

    fun setToggle(item: Item, v: Boolean) = when (item.store) {
        // OneHandController, not OneHandPrefs — enabling edge menus has to bring
        // the overlay up (and check the permissions it needs), which is exactly
        // what the One-Hand tab's own switch calls.
        Config.STORE_ONEHAND -> { if (v) OneHandController.enable(appCtx) else OneHandController.disable(appCtx) }
        // Shell round-trip. Call this off the main thread.
        Config.STORE_NIGHTMODE -> { SystemDisplay.setNight(appCtx, v); Unit }
        else          -> setToggle(item.id, v)
    }

    /** Master motion gate. Every animated surface asks through here, so one
     *  "all_anim" flip stops UI motion regardless of the per-feature *_anim
     *  toggles. Pass a feature id to AND it with that feature's own switch;
     *  omit it for surfaces that have no per-feature toggle of their own. */
    fun anim(id: String? = null): Boolean =
        toggle("all_anim") && (id == null || toggle(id))

    /** Whole-UI size over the DECLARED range (build.json::ui.launcher_settings
     *  .scale, 0..10 since #384). A phone that has never touched the row reads
     *  the SHIPPED preset — 3 since #408, factor 0.85 — and that number lives
     *  only in build.json. Stored here; applied IN-PROCESS by
     *  SystemDisplay.wrap (#601) — no device-wide write, no shell channel,
     *  so the shipped value is what a fresh install actually looks like. */
    var scale: Int
        get() = sp.getInt("scale", Config.scale.default)
        set(v) { sp.edit().putInt("scale", v).apply() }

    var brightness: Int
        get() = sp.getInt("brightness", Config.brightness.default)
        set(v) { sp.edit().putInt("brightness", v).apply() }

    var eyeIntensity: Int
        get() = sp.getInt("eye_intensity", Config.eyeIntensity.default)
        set(v) { sp.edit().putInt("eye_intensity", v).apply() }

    /** Idle seconds before the screensaver auto-starts (0 = never). */
    var screensaverTimeout: Int
        get() = sp.getInt("screensaver_timeout", Config.screensaverTimeout.default)
        set(v) { sp.edit().putInt("screensaver_timeout", v).apply() }

    // ── #1621 Background (the launcher animated backdrop) ─────────────────
    /** Draw the rotating shape on the home pane at all. */
    var backgroundEnabled: Boolean
        get() = sp.getBoolean(Config.background.enableKey, Config.background.enableDefault)
        set(v) { sp.edit().putBoolean(Config.background.enableKey, v).apply() }

    /** The selected background shape id (one of PolytopeMath.SHAPE_IDS). */
    var backgroundShape: String
        get() = sp.getString(Config.background.shapeKey, null) ?: Config.background.defaultShape
        set(v) { sp.edit().putString(Config.background.shapeKey, v).apply() }

    /** Auto-cycle through the ticked shapes. */
    var backgroundCycleEnabled: Boolean
        get() = sp.getBoolean(Config.background.cycleEnableKey, Config.background.cycleDefaultEnabled)
        set(v) { sp.edit().putBoolean(Config.background.cycleEnableKey, v).apply() }

    /** Minutes between shape changes while auto-cycling. */
    var backgroundCycleMinutes: Int
        get() = sp.getInt(Config.background.cycleMinutesKey, Config.background.cycleMinutes.default)
        set(v) { sp.edit().putInt(Config.background.cycleMinutesKey, v).apply() }

    /** #1685 Auto-rotate for the home model (3D AND 4D), declared at
     *  build.json::ui.launcher_settings.background.auto_rotate (default on). */
    var backgroundAutoRotate: Boolean
        get() = sp.getBoolean(Config.background.autoRotateKey, Config.background.autoRotateDefault)
        set(v) { sp.edit().putBoolean(Config.background.autoRotateKey, v).apply() }

    /** Follow the Auto-rotate switch live; returns the unsubscribe. The listener is
     *  held strongly here (SharedPreferences keeps only a weak reference). */
    fun observeAutoRotate(onChange: (Boolean) -> Unit): () -> Unit {
        val key = Config.background.autoRotateKey
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k == null || k == key) runCatching { onChange(backgroundAutoRotate) }
        }
        sp.registerOnSharedPreferenceChangeListener(l)
        return { runCatching { sp.unregisterOnSharedPreferenceChangeListener(l) } }
    }

    /** Is [shapeId] a member of the auto-cycle set? Defaults to true, so a fresh
     *  install cycles through every shape rather than through an empty set. */
    fun cycleShapeEnabled(shapeId: String): Boolean =
        sp.getBoolean("t_${Config.background.cycleMemberPrefix}$shapeId", true)
    fun setCycleShapeEnabled(shapeId: String, v: Boolean) {
        sp.edit().putBoolean("t_${Config.background.cycleMemberPrefix}$shapeId", v).apply()
    }

    /** The shapes to auto-cycle through, in declared order, filtered to the
     *  ticked members (falling back to the single selected shape if none are). */
    fun cycleShapeIds(): List<String> {
        val all = Config.background.shapes.map { it.id }.filter { cycleShapeEnabled(it) }
        return if (all.isEmpty()) listOf(backgroundShape) else all
    }

    // ── Data-driven metadata (for the picker UI + defaults) ──────────────
    /**
     * [group] is the id of the [Group] this switch is drawn under. It replaced a
     * `battery: Boolean`, which could only ever express TWO boxes — the fragment
     * split the list with `filter { it.battery }` / `filter { !it.battery }`, so
     * a third box was a Kotlin change rather than a data one.
     *
     * [userOwned] marks a switch NO THEME MAY SET. Applying a theme writes every
     * toggle the theme names; a user-owned id is dropped from that mapping when
     * it is parsed (see LauncherThemes.parseToggles), so the drop happens once,
     * at the source, rather than at each of the call sites that would each have
     * to remember.
     *
     * [store] names where the value actually lives. "" = this class's own
     * `launcher_settings` store. "onehand" = the launcher-onehand library's
     * `onehand_prefs`, which the One-Hand tab also writes — the edge-menu switch
     * has to read and write the same key as that tab or the two surfaces would
     * disagree about whether edge menus are on.
     */
    data class Item(
        val id: String,
        val label: String,
        val subtitle: String,
        val default: Boolean,
        val group: String = "",
        val userOwned: Boolean = false,
        val store: String = "",
        /** Declared drawable name, resolved through Sections.iconResFor. Empty
         *  degrades to the generic tile icon rather than failing the build — a
         *  switch whose icon has not been named yet must still be flippable. */
        val icon: String = "",
    )
    /** One box of switches on the Theme tab. [master] adds an all-on/all-off
     *  switch above the group's own rows. */
    data class Group(
        val id: String,
        val label: String,
        val subtitle: String,
        val master: Boolean = false,
        val masterLabel: String = "",
        val masterSubtitle: String = "",
        /** Which Quick Actions ▸ Panels tab draws this box: `battery` or `tools` (UI draws no switch box). */
        val tab: String = "tools",
    )
    /** A named step the user can jump the slider to in one tap (#408). The
     *  one flagged [shipped] is ALSO the value a fresh install starts at —
     *  [Slider.default] is derived from it rather than declared beside it, so
     *  the button called "Default" and the size the app installs with are the
     *  same number by construction. Two declarations of that number is the
     *  #384 defect: retune one and they part company in silence. */
    data class Preset(val label: String, val value: Int, val shipped: Boolean = false)

    /** [ticks] asks the row to draw one mark per step under the line, which only
     *  reads as a scale when the steps are few — so it is DECLARED per slider in
     *  build.json rather than guessed from the range here. Screen brightness is
     *  0..255 and would draw 256 marks into a smear.
     *
     *  [presets] is empty for a slider that offers no one-tap sizes; a slider
     *  that declares them takes its [default] from the shipped one. */
    data class Slider(val label: String, val subtitle: String, val min: Int, val max: Int,
                      val default: Int, val ticks: Boolean = false,
                      val presets: List<Preset> = emptyList())

    // ── #1621 Background config (parsed from launcher_settings.background) ──
    /** One selectable background shape. [dim] 3 = Platonic solid drawn directly,
     *  4 = regular 4D polytope drawn as a 4D→3D projection. [dualOf] names the 3D
     *  twin a 4D shape generalises (empty for the self-dual 24-cell). */
    data class Shape(val id: String, val label: String, val dim: Int,
                     val group: String, val dualOf: String, val isDefault: Boolean)
    /** The whole background declaration: the enable switch, the shape catalogue,
     *  the galaxy-stars toggle it REFERENCES (never a second copy of), and the
     *  auto-cycle settings. */
    data class Background(
        val label: String, val subtitle: String,
        val enableKey: String, val enableLabel: String, val enableSubtitle: String, val enableDefault: Boolean,
        val starsToggle: String,
        val shapeKey: String, val defaultShape: String, val shapes: List<Shape>,
        val cycleEnableKey: String, val cycleEnableLabel: String, val cycleEnableSubtitle: String,
        val cycleDefaultEnabled: Boolean, val cycleMemberPrefix: String,
        val cycleMinutesKey: String, val cycleMinutes: Slider,
        // #1685 the home model's Auto-rotate switch (3D + 4D).
        val autoRotateKey: String = "background_auto_rotate",
        val autoRotateLabel: String = "Auto-rotate",
        val autoRotateSubtitle: String = "",
        val autoRotateDefault: Boolean = AUTO_ROTATE_FALLBACK,
    )

    object Config {
        /** `store` value routing a switch at the launcher-onehand library. */
        const val STORE_ONEHAND = "onehand"

        /** `store` value routing a switch at the phone's system-wide uiMode,
         *  which only the privileged shell channel can write. */
        const val STORE_NIGHTMODE = "nightmode"

        private val settings: JSONObject by lazy {
            runCatching {
                JSONObject(String(Base64.decode(BuildConfig.UI_LAUNCHER_SETTINGS_B64, Base64.NO_WRAP)))
            }.getOrDefault(JSONObject())
        }

        val screensavers: List<Item> by lazy {
            runCatching {
                val arr = JSONArray(String(Base64.decode(BuildConfig.UI_SCREENSAVERS_B64, Base64.NO_WRAP)))
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Item(o.optString("id"), o.optString("label"), o.optString("subtitle"), o.optBoolean("default", false))
                }
            }.getOrDefault(emptyList())
        }
        val defaultScreensaverId: String
            get() = screensavers.firstOrNull { it.default }?.id ?: screensavers.firstOrNull()?.id ?: "black_clock"

        val toggles: List<Item> by lazy {
            val arr = settings.optJSONArray("toggles") ?: JSONArray()
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Item(
                    id        = o.optString("id"),
                    label     = o.optString("label"),
                    subtitle  = o.optString("subtitle"),
                    default   = o.optBoolean("default", false),
                    group     = o.optString("group", ""),
                    userOwned = o.optBoolean("user_owned", false),
                    store     = o.optString("store", ""),
                    icon      = o.optString("icon", ""),
                )
            }
        }
        fun toggleDefault(id: String): Boolean = toggles.firstOrNull { it.id == id }?.default ?: true

        /** The switch boxes, in declared order. */
        val groups: List<Group> by lazy {
            val arr = settings.optJSONArray("toggle_groups") ?: JSONArray()
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Group(
                    id             = o.optString("id"),
                    label          = o.optString("label"),
                    subtitle       = o.optString("subtitle"),
                    master         = o.optBoolean("master", false),
                    masterLabel    = o.optString("master_label", ""),
                    masterSubtitle = o.optString("master_subtitle", ""),
                    tab            = o.optString("tab", "tools"),
                )
            }
        }

        /** Ids no theme is allowed to write. Read by LauncherThemes when it
         *  parses the theme → toggle mapping, so a theme record that names one
         *  of these loses it before any caller can act on it. */
        val userOwnedIds: Set<String> by lazy {
            toggles.filter { it.userOwned }.map { it.id }.toSet()
        }

        /** [fallbackDefault] is for a slider whose value build.json does NOT
         *  declare — eye_intensity is the only one, and 35 is therefore its
         *  single declaration rather than a copy of one. Every other row omits
         *  it: repeating a number build.json already carries is how the two
         *  copies come to disagree (#170, #384). */
        private fun slider(key: String, fallbackDefault: Int? = null): Slider {
            val o = settings.optJSONObject(key) ?: JSONObject()
            val declared = o.optJSONArray("presets") ?: JSONArray()
            val presets = (0 until declared.length()).map {
                val p = declared.getJSONObject(it)
                Preset(p.optString("label"), p.optInt("value"), p.optBoolean("shipped", false))
            }
            // Same shape as defaultScreensaverId: the shipped record is FOUND,
            // never named a second time. A presets list with nothing flagged
            // falls through to `default`, so adding one-tap sizes to a row is
            // not obliged to move that row's first-run value in the same edit.
            val shipped = presets.firstOrNull { it.shipped }?.value
                ?: o.optInt("default", fallbackDefault ?: o.optInt("min", 0))
            return Slider(o.optString("label", key), o.optString("subtitle", ""),
                o.optInt("min", 0), o.optInt("max", 100), shipped,
                o.optBoolean("ticks", false), presets)
        }
        /** build.json::ui.launcher_settings.display_controls: Cloud-superapp Scale, Font Size, Zoom Size (see QuickDisplay). */
        val displayControls: JSONObject by lazy { settings.optJSONObject("display_controls") ?: JSONObject() }

        /** #1621 the launcher background declaration. Falls back to a cube-only,
         *  enabled, no-cycle default if the block is absent, so the renderer
         *  always has a shape to draw. */
        val background: Background by lazy {
            val o = settings.optJSONObject("background") ?: JSONObject()
            val en = o.optJSONObject("enable") ?: JSONObject()
            val cy = o.optJSONObject("cycle") ?: JSONObject()
            val ar = o.optJSONObject("auto_rotate") ?: JSONObject()
            val shapesArr = o.optJSONArray("shapes") ?: JSONArray()
            val shapes = (0 until shapesArr.length()).map {
                val s = shapesArr.getJSONObject(it)
                Shape(s.optString("id"), s.optString("label"), s.optInt("dim", 3),
                    s.optString("group", ""), s.optString("dual_of", ""), s.optBoolean("default", false))
            }
            val minO = cy.optJSONObject("minutes") ?: JSONObject()
            Background(
                label = o.optString("label", "Background"),
                subtitle = o.optString("subtitle", ""),
                enableKey = en.optString("key", "background_enabled"),
                enableLabel = en.optString("label", "Animated background"),
                enableSubtitle = en.optString("subtitle", ""),
                enableDefault = en.optBoolean("default", true),
                starsToggle = o.optString("stars_toggle", "stars_anim"),
                shapeKey = o.optString("shape_key", "background_shape"),
                defaultShape = o.optString("default_shape",
                    shapes.firstOrNull { it.isDefault }?.id ?: shapes.firstOrNull()?.id ?: "cube"),
                shapes = shapes,
                cycleEnableKey = cy.optString("enable_key", "background_cycle_enabled"),
                cycleEnableLabel = cy.optString("enable_label", "Auto-cycle shapes"),
                cycleEnableSubtitle = cy.optString("enable_subtitle", ""),
                cycleDefaultEnabled = cy.optBoolean("default_enabled", false),
                cycleMemberPrefix = cy.optString("member_key_prefix", "bgcycle_"),
                cycleMinutesKey = minO.optString("key", "background_cycle_minutes"),
                cycleMinutes = Slider(minO.optString("label", "Change every"), minO.optString("subtitle", ""),
                    minO.optInt("min", 1), minO.optInt("max", 60), minO.optInt("default", 5)),
                autoRotateKey = ar.optString("key", "background_auto_rotate").ifBlank { "background_auto_rotate" },
                autoRotateLabel = ar.optString("label", "Auto-rotate"),
                autoRotateSubtitle = ar.optString("subtitle", ""),
                autoRotateDefault = ar.optBoolean("default", AUTO_ROTATE_FALLBACK),
            )
        }
        val scale: Slider by lazy { slider("scale") }
        val brightness: Slider by lazy { slider("brightness") }
        val eyeIntensity: Slider by lazy { slider("eye_intensity", 35) }
        val screensaverTimeout: Slider by lazy { slider("screensaver_timeout") }
    }

    companion object {
        /** #1685 Auto-rotate when build.json says nothing (and on any read failure): on. */
        const val AUTO_ROTATE_FALLBACK = true
    }
}
