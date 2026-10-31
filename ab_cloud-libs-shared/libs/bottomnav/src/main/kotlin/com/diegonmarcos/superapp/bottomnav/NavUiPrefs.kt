package com.diegonmarcos.superapp.bottomnav

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The persisted [NavUiStore]: the user's nav mode (Classic / Clean) and scroll-hide choice for THIS app, in the `nav_ui`
 * SharedPreferences. It is declared in the fleet manifest (libs:fleetconfig-model, class config), so it migrates to a new phone and
 * rides the app's backup with the rest of the settings. Reads are Compose state, so a change redraws the chrome at once.
 */
public class NavUiPrefs internal constructor(context: Context) : NavUiStore {
    private val app = context.applicationContext
    // The SharedPreferences file and the global pin are read OFF the main thread (StrictMode caught a 1.4-2.9 s disk read here at
    // CG-Omniverse launch): the chrome draws the app's declared default for that moment, then redraws with the stored choice.
    private val prefs by lazy { app.getSharedPreferences(NAME, Context.MODE_PRIVATE) }
    private var chosenMode: NavUiMode? by mutableStateOf(null)
    private var chosenScrollHide: Boolean? by mutableStateOf(null)
    @Volatile private var touched = false

    // The runtime nav walk (cloud-android-nav-walk.py) drives the pages through the Classic title dropdown: it pins the mode with the global
    // setting `cloud_nav_ui_mode` (only adb or the system can write it). Unset on a phone: the user's choice stands.
    private var pinned: NavUiMode? by mutableStateOf(null)

    init {
        Thread({
            val p = prefs
            pinned = runCatching {
                Settings.Global.getString(app.contentResolver, GLOBAL_PIN)?.let { v -> NavUiMode.entries.firstOrNull { it.declared == v } }
            }.getOrNull()
            // a choice the user made meanwhile wins over the stored one
            if (!touched) {
                chosenMode = NavUiMode.entries.firstOrNull { it.declared == p.getString(KEY_MODE, null) }
                chosenScrollHide = if (p.contains(KEY_SCROLL_HIDE)) p.getBoolean(KEY_SCROLL_HIDE, true) else null
            }
        }, "nav-ui-prefs").apply { isDaemon = true }.start()
    }

    override fun mode(): NavUiMode? = pinned ?: chosenMode

    override fun setMode(mode: NavUiMode?) {
        touched = true
        chosenMode = mode
        prefs.edit().apply { if (mode == null) remove(KEY_MODE) else putString(KEY_MODE, mode.declared) }.apply()
    }

    override fun scrollHide(): Boolean? = chosenScrollHide

    override fun setScrollHide(on: Boolean?) {
        touched = true
        chosenScrollHide = on
        prefs.edit().apply { if (on == null) remove(KEY_SCROLL_HIDE) else putBoolean(KEY_SCROLL_HIDE, on) }.apply()
    }

    public companion object {
        public const val NAME: String = "nav_ui"
        public const val KEY_MODE: String = "nav_mode"
        public const val KEY_SCROLL_HIDE: String = "scroll_hide"
        public const val GLOBAL_PIN: String = "cloud_nav_ui_mode"

        @Volatile private var instance: NavUiPrefs? = null

        /** The process's one store: the chrome that reads it and the Configs card that writes it must be the same object. */
        public fun of(context: Context): NavUiPrefs =
            instance ?: synchronized(this) { instance ?: NavUiPrefs(context).also { instance = it } }
    }
}

/** The app's persisted store: the process's one [NavUiPrefs]. */
@Composable
public fun rememberNavUiPrefs(): NavUiPrefs {
    val ctx = LocalContext.current
    return remember(ctx) { NavUiPrefs.of(ctx) }
}

/** The settings in force: the user's choice (the enclosing [LocalNavUiStore], else the persisted prefs) over the app's [LocalNavUiDefault]. */
@Composable
public fun rememberNavUiSettings(): NavUiSettings = resolveNavUi(LocalNavUiDefault.current, LocalNavUiStore.current ?: rememberNavUiPrefs())

/** The settings of the enclosing shared scaffold ([ProvideNavUi]), else resolved here: a bar reads this and is never without. */
@Composable
public fun currentNavUi(): NavUiSettings = LocalNavUiSettings.current ?: rememberNavUiSettings()

/** The scroll policy in force: a host's override ([LocalChromeScrollPolicy]), else the settings' (scroll-hide on = [ChromeScrollPolicy.Hide]). */
@Composable
public fun currentScrollPolicy(): ChromeScrollPolicy = LocalChromeScrollPolicy.current ?: currentNavUi().scrollPolicy()

/** Resolves the settings once for everything in [content] (an outer scaffold's win), so every bar below agrees. */
@Composable
public fun ProvideNavUi(content: @Composable () -> Unit) {
    if (LocalNavUiSettings.current != null) { content(); return }
    val settings = rememberNavUiSettings()
    CompositionLocalProvider(LocalNavUiSettings provides settings, content = content)
}

/** The first row's fill: [NavTokens.topRowFill], transparent in every mode, so no bar paints a grey box behind the app. */
@Composable
public fun navTopRowFill(): Color = (LocalNavTokens.current ?: NavTokens()).topRowFill
