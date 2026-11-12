package com.diegonmarcos.superapp.notificationcenter

import android.content.Context
import com.diegonmarcos.superapp.R
import java.util.Locale

/**
 * What the Weather card says. Three honest states, never a vague wait:
 *  - [State.Forecast]  a forecast (fresh, or the last good one when the fetch failed);
 *  - [State.Loading]   a position is known, the forecast has not arrived yet;
 *  - [State.NoLocation] there is truly no position: the actual reason + an action.
 */
object WeatherFace {

    sealed interface State {
        data class Forecast(val today: WeatherBadgeService.WeatherToday, val fix: WeatherLocation.Fix?) : State
        data class Loading(val place: String, val fix: WeatherLocation.Fix) : State
        data class NoLocation(val reason: WeatherLocation.Reason) : State
    }

    enum class Action { APP_SETTINGS, LOCATION_SETTINGS, RETRY, OPEN_NAV }

    data class Face(val title: String, val text: String, val action: Action?, val actionLabel: String?)

    fun of(ctx: Context, s: State): Face = when (s) {
        is State.Forecast -> Face(
            ctx.getString(R.string.badge_weather_title_fmt, WeatherModel.headline(s.today.forecast), s.today.place),
            ctx.getString(R.string.badge_weather_range_fmt,
                String.format(Locale.US, "%.0f", s.today.forecast.maxC),
                String.format(Locale.US, "%.0f", s.today.forecast.minC)),
            null, null)
        is State.Loading -> Face(
            ctx.getString(R.string.badge_weather_title_fmt, ctx.getString(R.string.badge_weather_title), s.place),
            ctx.getString(R.string.badge_weather_loading), Action.RETRY, ctx.getString(R.string.badge_weather_act_retry))
        is State.NoLocation -> {
            val (msg, act, label) = when (s.reason) {
                WeatherLocation.Reason.PERMISSION -> Triple(R.string.badge_weather_why_permission,
                    Action.APP_SETTINGS, R.string.badge_weather_act_permission)
                WeatherLocation.Reason.LOCATION_OFF -> Triple(R.string.badge_weather_why_off,
                    Action.LOCATION_SETTINGS, R.string.badge_weather_act_location)
                WeatherLocation.Reason.BACKGROUND -> Triple(R.string.badge_weather_why_background,
                    Action.APP_SETTINGS, R.string.badge_weather_act_permission)
                WeatherLocation.Reason.NO_FIX -> Triple(R.string.badge_weather_why_nofix,
                    Action.RETRY, R.string.badge_weather_act_retry)
                WeatherLocation.Reason.SEARCHING -> Triple(R.string.badge_weather_searching,
                    Action.RETRY, R.string.badge_weather_act_retry)
                WeatherLocation.Reason.NAV_UNAVAILABLE -> Triple(R.string.badge_weather_why_nav,
                    Action.OPEN_NAV, R.string.badge_weather_act_nav)
            }
            Face(ctx.getString(R.string.badge_weather_title), ctx.getString(msg), act, ctx.getString(label))
        }
    }

    /** The screen each button opens. A runtime permission cannot be asked from a notification or a card, so the
     *  permission buttons open this app's settings page, where Location is one tap away. */
    fun intentFor(ctx: Context, a: Action): android.content.Intent = when (a) {
        // #1515 the location grants live in Configs > Perms: the button opens the Perms page on those rows.
        Action.APP_SETTINGS -> com.diegonmarcos.superapp.perms.PermsActivity.intent(ctx, "Weather badge",
            listOf("fine_location", "coarse_location", "background_location"))
        Action.LOCATION_SETTINGS -> android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        Action.RETRY -> android.content.Intent(ctx, WeatherBadgeService::class.java)
        // #1690 Cloud Nav owns the location: open it, or (not installed) start its fleet install from the service.
        Action.OPEN_NAV -> runCatching { ctx.packageManager.getLaunchIntentForPackage(com.diegonmarcos.superapp.location.NavLocation.PKG) }.getOrNull()
            ?: android.content.Intent(ctx, WeatherBadgeService::class.java).setAction(WeatherBadgeService.ACTION_INSTALL_NAV)
    }

    /** The thin-line icon of each button, on the notification and on the card. */
    fun iconFor(a: Action): Int = when (a) {
        Action.APP_SETTINGS -> R.drawable.ic_act_settings
        Action.LOCATION_SETTINGS -> R.drawable.ic_act_location
        Action.RETRY -> R.drawable.ic_act_refresh
        Action.OPEN_NAV -> R.drawable.ic_act_location
    }

    /** "network · 12 min ago" - where the position came from and how old it is. */
    fun position(ctx: Context, fix: WeatherLocation.Fix, nowMs: Long): String {
        val m = ((nowMs - fix.timeMs) / 60_000L).coerceAtLeast(0)
        val age = when { m < 1 -> "now"; m < 60 -> "$m min ago"; m < 1_440 -> "${m / 60} h ago"; else -> "${m / 1_440} d ago" }
        return ctx.getString(R.string.badge_weather_pos_fmt, fix.source, age)
    }
}
