package com.diegonmarcos.superapp.fleetconfigs

import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/**
 * #1852 The LIVE state each Configs > General entry shows next to its name (in the index chip and in the card header): perms granted/total,
 * battery optimisation on/off, storage used, notifications enabled and channels, UI theme, language, update state and version, the app.
 * The words are pure functions (JVM tested: GeneralStateTest); [all] reads the cheap device getters, so it is safe to call on every tick.
 */
internal object GeneralState {
    fun perms(granted: Int, total: Int): String = if (total <= 0) "none declared" else "$granted/$total granted"

    /** Battery optimisation: ON means Android may stop background work, OFF means the app is exempt. */
    fun battery(unrestricted: Boolean): String = if (unrestricted) "optimisation off" else "optimisation on"

    fun storage(dataBytes: Long): String = if (dataBytes < 0) "…" else human(dataBytes) + " used"

    fun notifications(enabled: Boolean, channels: Int, enabledChannels: Int): String =
        if (!enabled) "blocked" else if (channels == 0) "on" else "on · $enabledChannels/$channels channels"

    fun theme(dark: Boolean): String = if (dark) "dark theme" else "light theme"

    /** "1.2.0 (7) · up to date" / "· newer build" / "· not checked". */
    fun update(version: String, st: ReleaseState?, sha: String): String {
        val word = when {
            st == null -> "not checked"
            st.latestSha.isBlank() -> "no published build"
            st.upToDate(sha) -> "up to date"
            else -> "newer build"
        }
        return "$version · $word"
    }

    /** Section id -> its live state text. A section with no live state of its own is simply absent. */
    fun all(ctx: Context, decl: FleetConfigsDecl, dark: Boolean, granted: Int, total: Int, dataBytes: Long): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["perms"] = perms(granted, total)
        out[FleetConfigs.UPDATE_SECTION] = update(installedVersion(ctx, decl), FleetReleases.cached(), decl.sha)
        out["battery"] = runCatching {
            battery((ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName))
        }.getOrDefault("")
        out["storage"] = storage(dataBytes)
        out["notifications"] = runCatching {
            val ch = (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notificationChannels
            notifications(NotificationManagerCompat.from(ctx).areNotificationsEnabled(), ch.size, ch.count { it.importance != NotificationManager.IMPORTANCE_NONE })
        }.getOrDefault("")
        out["ui"] = theme(dark)
        out["language"] = language(ctx)
        out[FleetConfigs.APP_SECTION] = decl.name.ifBlank { "app" }
        return out
    }

    /** The language tag the app runs in, or the system default's name. */
    fun language(ctx: Context): String {
        val app = if (android.os.Build.VERSION.SDK_INT >= 33) ctx.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty() else ""
        return if (app.isNotBlank()) app else ctx.resources.configuration.locales[0].toLanguageTag()
    }
}
