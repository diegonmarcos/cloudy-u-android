package com.diegonmarcos.cloudkeyboard

import android.app.Application
import androidx.work.Configuration as WorkManagerConfiguration
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.media.MediaRuntime
import com.diegonmarcos.superapp.translate.TranslateEngines
import com.diegonmarcos.superapp.voice.VoiceEngines
import helium314.keyboard.latin.suggestions.ToolbarStatus
import helium314.keyboard.latin.utils.prefs as heliboardPrefs

/**
 * Application entry point for Cloud Keyboard.
 *
 * HeliBoard (libs:keyboard) ships android:name="helium314.keyboard.latin.App" in its
 * manifest; our manifest wins via tools:replace="android:name". Since HeliBoard's own
 * App.onCreate never fires, we replicate its synchronous keyboard init here so
 * LatinIME / Settings don't crash with "parameter prefs is null".
 */
class App : Application(), WorkManagerConfiguration.Provider {

    override val workManagerConfiguration: WorkManagerConfiguration
        get() = WorkManagerConfiguration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // #1352 Configs ▸ General and ▸ About (libs:ui-nav-bars) at the top of the keyboard's Configs.
        helium314.keyboard.settings.KeyboardSettingsExtras.entries = listOf(
            com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPage.General to "Perms, update, battery, storage, notifications, language",
            com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPage.About to "What this keyboard does, its data, permissions and credits",
        ).map { (page, sub) ->
            helium314.keyboard.settings.KeyboardSettingsExtras.Entry(com.diegonmarcos.superapp.fleetconfigs.fleetConfigsEntryLabel(page), sub) { ctx ->
                com.diegonmarcos.superapp.fleetconfigs.FleetConfigsEntries.open(ctx, page)
            }
        }
        // #1651 the fleet default top + bottom nav bars around the settings pages, and the keyboard's own tab of the standard
        // Configs strip ({app tabs} | Agents, Perms, Logcat, X-Ray App, About; the tail tabs register from the linked fleetconfigs libs).
        helium314.keyboard.settings.KeyboardSettingsExtras.chrome = { route, open, content -> KeyboardChrome.Draw(route, open, content) }
        com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.register(
            com.diegonmarcos.superapp.fleetconfigs.FleetConfigsTab("keyboard", "Keyboard", "settings") { dark -> KeyboardConfigsTabBody(dark) }
        )
        // Crash takeout — on any uncaught exception, drop the stack into a
        // single, predictably-named SHARED file: Download/cloud-keyboard-log-error.log
        // (MediaStore, no permission on Android 10+). Readable from Termux
        // (~/storage/Download) or any file manager — no adb/logcat needed.
        // Mirrors the superapp's cloud-superapp-log-error.log.
        CrashTakeout.install(this, "cloud-keyboard-log-error.log")

        // #776 /api/keyboard/toolbar on the fleet debug server: expected vs drawn toolbar
        // icons and why the row was last rebuilt, so the empty-toolbar fault is checked on
        // the device. A debug facility must never take the keyboard down.
        runCatching {
            AppDebugServer.route(
                BuildConfig.DEBUG_API_GROUP,
                listOf(AppDebugServer.Op("toolbar", "", "toolbar row health: expected vs drawn icons, keys, last rebuild reason, self-heal count")),
            ) { op, _ -> if (op == "toolbar") ToolbarStatus.json() else null }
        }

        // Mirror helium314.keyboard.latin.App.onCreate's synchronous init.
        runCatching { helium314.keyboard.latin.define.DebugFlags.init(this) }
        runCatching { helium314.keyboard.latin.utils.FoldableUtils.init(this) }
        runCatching { helium314.keyboard.latin.settings.Settings.init(this) }
        runCatching { helium314.keyboard.latin.utils.SubtypeSettings.init(this) }
        runCatching { helium314.keyboard.latin.RichInputMethodManager.init(this) }
        runCatching { helium314.keyboard.latin.settings.Defaults.initDynamicDefaults(this) }
        runCatching { helium314.keyboard.latin.utils.upgradeToolbarPrefs(heliboardPrefs()) }
        runCatching { MediaRuntime.configure(BuildConfig.MEDIA_CONFIG_B64, null, BuildConfig.GIPHY_API_KEY) }
        // Register AIDL client so libs:translate's Translator / TranslateBarView
        // delegate to the companion cloud-keyboard-libs service (ML Kit not bundled here).
        runCatching { TranslateEngines.client = AidlTranslateEngineClient(this) }
        // Register AIDL voice client so libs:voice's VoiceBarView delegates to the
        // companion cloud-keyboard-libs service (Vosk not bundled here).
        runCatching { VoiceEngines.client = AidlVoiceEngineClient(this) }

        // Extract keyboard dicts from companion cloud-keyboard-libs (which bundles
        // the .dict files since this APK omits them to save ~39 MB). Must run
        // before seedDefaultLocales/prewarmEmojiDict so the cache is populated
        // before HeliBoard's lazy-extraction path is hit. No-op if companion is
        // absent or dicts are already cached.
        runCatching { CompanionDictExtractor.extractIfNeeded(this) }

        // Seed the default enabled languages once (data-driven from
        // build.json::default_locales → BuildConfig.DEFAULT_LOCALES). Runs only on
        // a fresh install (no subtype yet + never seeded), so it never fights a
        // user who later curates their own languages. Uses the exact API the
        // Settings → Languages screen uses (getResourceSubtypesForLocale +
        // addEnabledSubtype), resolving each configured tag to the closest
        // DECLARED subtype so a seeded language always survives the next launch.
        runCatching { seedDefaultLocales() }
    }

    private fun seedDefaultLocales() {
        val prefs = heliboardPrefs()
        if (prefs.getBoolean(PREF_DEFAULT_LOCALES_SEEDED, false)) return
        if (helium314.keyboard.latin.utils.SubtypeSettings.getEnabledSubtypes(false).isNotEmpty()) {
            prefs.edit().putBoolean(PREF_DEFAULT_LOCALES_SEEDED, true).apply()
            return
        }
        BuildConfig.DEFAULT_LOCALES.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { tag ->
            runCatching {
                val locale = helium314.keyboard.latin.common.LocaleUtils.run { tag.constructLocale() }
                // Seed only locales the keyboard actually DECLARES a subtype for. Creating one
                // on the fly instead (the previous behaviour) enabled a subtype that no later
                // launch could resolve: addEnabledSubtype writes it to PREF_ENABLED_SUBTYPES,
                // but it was never registered in PREF_ADDITIONAL_SUBTYPES, so the next start's
                // loadResourceSubtypes/loadEnabledSubtypes pass found neither a resource nor an
                // additional subtype for it and deleted it again. The language vanished without
                // any error and the system-locale default silently took its place.
                // getBestMatch returns the exact locale when one is declared and otherwise the
                // closest declared relative, which is also the locale whose main dictionary
                // getBestMatch will find — so a phone reporting Mexico or Argentina lands on the
                // Spanish subtype that has a dictionary rather than on nothing at all.
                val declaredLocale = helium314.keyboard.latin.common.LocaleUtils.getBestMatch(
                    locale,
                    helium314.keyboard.latin.utils.SubtypeSettings.getAvailableSubtypeLocales()
                ) { it } ?: return@runCatching
                val subtype = helium314.keyboard.latin.utils.SubtypeSettings
                    .getResourceSubtypesForLocale(declaredLocale).firstOrNull() ?: return@runCatching
                helium314.keyboard.latin.utils.SubtypeSettings.addEnabledSubtype(prefs, subtype)
                prewarmEmojiDict(declaredLocale)
            }
        }
        prefs.edit().putBoolean(PREF_DEFAULT_LOCALES_SEEDED, true).apply()
    }

    /**
     * Extract the bundled emoji dictionary (assets/dicts/emoji_<locale>.dict, shipped
     * by libs:keyboard) into this locale's per-locale cache on first install.
     *
     * PREF_SUGGEST_EMOJIS already defaults true, but emoji suggestions and inline
     * emoji search gate on getLocalesWithEmojiDicts(), which reads the *cache* dir —
     * and normal extraction is lazy (only on first dictionary load). On a fresh
     * install that leaves the "An emoji dictionary is required for this feature"
     * dialog showing until the keyboard has been opened once. Pre-extracting here
     * (a cheap asset→file copy, no binary load) makes emoji suggestions work from
     * first boot. Emoji dicts only — main dicts stay lazy to keep first launch fast.
     */
    private fun prewarmEmojiDict(locale: java.util.Locale) {
        val (_, nonExtracted) = helium314.keyboard.latin.dictionary.DictionaryFactory
            .getAvailableDictsForLocale(locale, this, true)
        nonExtracted
            .filter { it.substringBefore("_") == helium314.keyboard.latin.dictionary.Dictionary.TYPE_EMOJI }
            .forEach { filename ->
                helium314.keyboard.latin.utils.DictionaryInfoUtils
                    .extractAssetsDictionary(filename, locale, this)
            }
    }

    private companion object {
        const val PREF_DEFAULT_LOCALES_SEEDED = "cloudkb_default_locales_seeded"
    }
}
